package com.nahope.island.island

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single place every source pushes into and the overlay reads from.
 *
 * Deliberately a singleton object: the NotificationListenerService, the foreground service and the
 * settings UI all live in the same process but have no shared owner to hang a graph off.
 */
object IslandRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val events = MutableStateFlow<Map<String, IslandEvent>>(emptyMap())
    private val expanded = MutableStateFlow(false)
    private val dismissJobs = mutableMapOf<String, Job>()

    val state: StateFlow<IslandUiState> =
        combine(events, expanded) { map, isExpanded ->
            val sorted = map.values.sortedByDescending { it.priority }
            val primary = sorted.firstOrNull()
            val secondary = sorted.getOrNull(1)
            val mode = when {
                primary == null -> IslandMode.IDLE
                isExpanded -> IslandMode.EXPANDED
                secondary != null -> IslandMode.COMPACT
                else -> IslandMode.MINIMAL
            }
            IslandUiState(mode = mode, primary = primary, secondary = secondary)
        }.stateIn(scope, SharingStarted.Eagerly, IslandUiState())

    fun publish(event: IslandEvent) {
        events.update { it + (event.id to event) }
        val ttl = event.autoDismissMs
        if (ttl != null && !expanded.value) arm(event.id, ttl) else dismissJobs.remove(event.id)?.cancel()
    }

    fun clear(id: String) {
        dismissJobs.remove(id)?.cancel()
        events.update { it - id }
        if (events.value.isEmpty()) expanded.value = false
    }

    fun clearAll() {
        dismissJobs.values.forEach(Job::cancel)
        dismissJobs.clear()
        events.value = emptyMap()
        expanded.value = false
    }

    fun setExpanded(value: Boolean) {
        if (value && events.value.isEmpty()) return
        if (expanded.value == value) return
        expanded.value = value
        if (value) {
            // While the card is open, nothing times out underneath the user's finger.
            dismissJobs.values.forEach(Job::cancel)
            dismissJobs.clear()
        } else {
            // Collapsing re-arms the transient timers that were frozen on expand.
            events.value.values.forEach { e -> e.autoDismissMs?.let { arm(e.id, it) } }
        }
    }

    fun toggleExpanded() = setExpanded(!expanded.value)

    private fun arm(id: String, ttl: Long) {
        dismissJobs.remove(id)?.cancel()
        dismissJobs[id] = scope.launch {
            delay(ttl)
            clear(id)
        }
    }
}
