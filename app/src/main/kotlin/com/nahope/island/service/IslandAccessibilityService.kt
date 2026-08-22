package com.nahope.island.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.nahope.island.data.IslandConfig
import com.nahope.island.data.IslandPrefs
import com.nahope.island.island.IslandEvent
import com.nahope.island.island.IslandRepository
import com.nahope.island.island.sources.SourceSet
import com.nahope.island.service.overlay.OverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The only window type that renders above the status bar is TYPE_ACCESSIBILITY_OVERLAY, and only an
 * AccessibilityService is allowed to add one — so this service exists purely to own that window.
 *
 * It listens to no events and retrieves no window content; the accessibility binding is the
 * permission, not the feature.
 */
class IslandAccessibilityService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private var overlay: OverlayHost? = null
    private var sources: SourceSet? = null

    private val config = MutableStateFlow(IslandConfig())
    private val landscape = MutableStateFlow(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        _connected.value = true

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = serviceScope

        landscape.value = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val host = OverlayHost(
            context = this,
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            config = config,
            landscape = landscape,
            scope = serviceScope,
            onOpenApp = ::openPrimary,
        )
        overlay = host

        val feed = SourceSet(this)
        sources = feed

        val prefs = IslandPrefs(this)
        serviceScope.launch {
            prefs.config.collect { cfg ->
                config.value = cfg
                if (cfg.enabled) {
                    feed.start()
                    host.attach()
                } else {
                    host.detach()
                    feed.stop()
                }
            }
        }

        serviceScope.launch {
            // Notification access can be granted long after we connect; rebind when it lands.
            IslandNotificationListener.connected.collect { feed.bindMedia(it) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape.value = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        _connected.value = false
        sources?.stop()
        sources = null
        overlay?.detach()
        overlay = null
        scope?.cancel()
        scope = null
    }

    private fun openPrimary() {
        val event = IslandRepository.state.value.primary ?: return
        val pkg = when (event) {
            is IslandEvent.Media -> event.packageName
            is IslandEvent.Notification -> event.packageName
            else -> null
        } ?: return

        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
        IslandRepository.setExpanded(false)
    }

    companion object {
        private val _connected = MutableStateFlow(false)

        /** True while this service owns the overlay, so IslandService stays out of the way. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
