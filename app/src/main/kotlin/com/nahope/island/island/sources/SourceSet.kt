package com.nahope.island.island.sources

import android.content.Context

/**
 * Everything that feeds the island, bundled so whichever service is alive can own it.
 *
 * This matters more than it looks: the sources used to live in the foreground service, which
 * ColorOS does not restart after a reinstall or a low-memory kill. The island stayed on screen but
 * went blind. The AccessibilityService is bound by the system and always comes back, so it holds
 * these instead, and the foreground service only takes over when accessibility is off.
 */
class SourceSet(context: Context) {

    private val media = MediaSource(context)
    private val system = SystemSource(context)

    private var running = false

    /**
     * Remembered rather than acted on immediately: the notification-access flow emits its current
     * value the instant it is collected, which is usually *before* [start] has run. Storing it here
     * makes the two calls order-independent.
     */
    private var notificationAccess = false

    fun start() {
        if (running) return
        running = true
        system.start()
        if (notificationAccess) media.start()
    }

    /** Media sessions only become readable once the notification listener is actually bound. */
    fun bindMedia(notificationAccess: Boolean) {
        this.notificationAccess = notificationAccess
        if (!running) return
        media.stop()
        if (notificationAccess) media.start()
    }

    fun stop() {
        if (!running) return
        running = false
        media.stop()
        system.stop()
    }
}
