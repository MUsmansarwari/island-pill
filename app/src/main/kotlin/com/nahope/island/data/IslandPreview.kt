package com.nahope.island.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A live, uncommitted config used while a calibration slider is being dragged.
 *
 * Writing every intermediate value to DataStore would mean a disk write per animation frame, but
 * committing only on release means calibrating blind. This holds the in-flight value in memory so
 * the overlay redraws immediately, and the real write still happens once, on release.
 */
object IslandPreview {

    private val _override = MutableStateFlow<IslandConfig?>(null)

    /** Non-null only while a slider is under the user's finger. */
    val override: StateFlow<IslandConfig?> = _override.asStateFlow()

    fun show(config: IslandConfig) {
        _override.value = config
    }

    fun clear() {
        _override.value = null
    }
}
