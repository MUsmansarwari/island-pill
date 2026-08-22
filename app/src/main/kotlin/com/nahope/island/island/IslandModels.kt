package com.nahope.island.island

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/** How much screen the island is currently claiming. */
enum class IslandMode { HIDDEN, IDLE, MINIMAL, COMPACT, EXPANDED }

/**
 * Priorities decide who owns the main pill when several things are live at once.
 * Higher wins. Keep the gaps wide so new sources can slot in between.
 */
object Priority {
    const val CALL = 100
    const val NAVIGATION = 90
    const val RECORDING = 80
    const val TIMER = 70
    const val MEDIA = 60
    const val SYSTEM_TRANSIENT = 40
    const val NOTIFICATION = 30
}

sealed interface IslandEvent {
    /** Stable per-source id; publishing the same id replaces the previous event. */
    val id: String
    val priority: Int

    /** null = sticky, stays until the source clears it. */
    val autoDismissMs: Long?

    /**
     * A phone call — cellular or VoIP. Sticky: it lives exactly as long as the dialer's own
     * notification does, so hanging up in any app clears the pill without us tracking state.
     */
    data class Call(
        override val id: String = ID,
        val packageName: String,
        val appName: String,
        val callerName: String,
        val callerAvatar: Bitmap?,
        val state: State,
        val isVideo: Boolean,
        /** Wall-clock start of a connected call, for the duration ticker. Null while ringing. */
        val startedAtMs: Long?,
        val answer: PendingIntent?,
        val decline: PendingIntent?,
        val hangUp: PendingIntent?,
        /** Tapping the card hands off to the dialer's own in-call screen. */
        val open: PendingIntent?,
    ) : IslandEvent {
        enum class State { RINGING, ACTIVE }

        override val priority = Priority.CALL
        override val autoDismissMs: Long? = null

        companion object { const val ID = "call" }
    }

    data class Media(
        override val id: String = ID,
        val packageName: String,
        val appName: String,
        val title: String,
        val artist: String?,
        val artwork: Bitmap?,
        val isPlaying: Boolean,
        val positionMs: Long,
        /** SystemClock.elapsedRealtime() when positionMs was sampled, so the UI can extrapolate. */
        val positionSampledAt: Long,
        val durationMs: Long,
        val accent: Int?,
    ) : IslandEvent {
        override val priority = Priority.MEDIA
        override val autoDismissMs: Long? = null

        companion object { const val ID = "media" }
    }

    data class Charging(
        override val id: String = ID,
        val levelPercent: Int,
        val plugged: Boolean,
        val fast: Boolean,
    ) : IslandEvent {
        override val priority = Priority.SYSTEM_TRANSIENT
        override val autoDismissMs: Long = 4_000

        companion object { const val ID = "charging" }
    }

    data class Ringer(
        override val id: String = ID,
        val mode: Mode,
    ) : IslandEvent {
        enum class Mode { SILENT, VIBRATE, NORMAL }

        override val priority = Priority.SYSTEM_TRANSIENT
        override val autoDismissMs: Long = 2_500

        companion object { const val ID = "ringer" }
    }

    data class Bluetooth(
        override val id: String = ID,
        val deviceName: String,
        val connected: Boolean,
        val batteryPercent: Int?,
    ) : IslandEvent {
        override val priority = Priority.SYSTEM_TRANSIENT
        override val autoDismissMs: Long = 4_000

        companion object { const val ID = "bluetooth" }
    }

    data class Notification(
        override val id: String,
        val packageName: String,
        val appName: String,
        val title: String?,
        val text: String?,
        val icon: Drawable?,
        val accent: Int?,
    ) : IslandEvent {
        override val priority = Priority.NOTIFICATION
        override val autoDismissMs: Long = 4_500
    }
}

data class IslandUiState(
    val mode: IslandMode = IslandMode.IDLE,
    val primary: IslandEvent? = null,
    val secondary: IslandEvent? = null,
)
