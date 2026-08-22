package com.nahope.island.island.sources

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import com.nahope.island.island.IslandEvent
import com.nahope.island.island.IslandRepository
import com.nahope.island.service.IslandNotificationListener

/**
 * Reads whatever is playing through the platform's own session list.
 *
 * Requires notification-listener access — [MediaSessionManager.getActiveSessions] is gated on the
 * caller owning an enabled NotificationListenerService, which is exactly what we pass in.
 */
class MediaSource(private val context: Context) {

    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, IslandNotificationListener::class.java)

    private var controller: MediaController? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> bind(controllers) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() {
            detach()
            IslandRepository.clear(IslandEvent.Media.ID)
        }
    }

    fun start() {
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            val active = sessionManager?.getActiveSessions(listenerComponent)
            Log.i(TAG, "started, ${active?.size ?: 0} active sessions: ${active?.map { it.packageName }}")
            bind(active)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access not granted yet; media source idle", e)
        }
    }

    fun stop() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        detach()
        IslandRepository.clear(IslandEvent.Media.ID)
    }

    private fun bind(controllers: List<MediaController>?) {
        // Prefer whatever is actually making sound; fall back to the most recent session.
        val next = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()

        if (next?.sessionToken == controller?.sessionToken) {
            push()
            return
        }

        detach()
        controller = next
        Log.d(TAG, "bound to ${next?.packageName}")
        if (next == null) {
            IslandRepository.clear(IslandEvent.Media.ID)
            return
        }
        next.registerCallback(callback)
        MediaCommands.attach(next)
        push()
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
        MediaCommands.attach(null)
    }

    private fun push() {
        val c = controller ?: return
        val playback = c.playbackState
        val state = playback?.state

        // NONE/STOPPED/ERROR means the app is holding a session but nothing is playing.
        if (state == null ||
            state == PlaybackState.STATE_NONE ||
            state == PlaybackState.STATE_STOPPED ||
            state == PlaybackState.STATE_ERROR
        ) {
            Log.d(TAG, "clear: ${c.packageName} state=$state")
            IslandRepository.clear(IslandEvent.Media.ID)
            return
        }

        val meta = c.metadata
        val appName = appLabel(c.packageName)

        // Video players (MX Player) and some podcast apps publish a session with no metadata at
        // all. Falling back to the app name beats showing an empty pill — the waveform still says
        // what is making sound, and tapping still opens the right app.
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
            ?: appName

        Log.d(TAG, "publish: ${c.packageName} \"$title\" state=$state art=${meta != null}")

        IslandRepository.publish(
            IslandEvent.Media(
                packageName = c.packageName,
                appName = appName,
                title = title,
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() },
                artwork = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                isPlaying = state == PlaybackState.STATE_PLAYING,
                positionMs = playback.position,
                // The app stamps lastPositionUpdateTime when it last moved the playhead. Using our
                // own clock instead would treat a stale position as if it were sampled just now,
                // and the progress bar would race ahead of the track.
                positionSampledAt = playback.lastPositionUpdateTime.takeIf { it > 0L }
                    ?: SystemClock.elapsedRealtime(),
                durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                accent = null,
            )
        )
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private companion object {
        const val TAG = "MediaSource"
    }
}

/** Transport controls the overlay can drive without holding a reference to the source. */
object MediaCommands {

    @Volatile
    private var controller: MediaController? = null

    fun attach(c: MediaController?) {
        controller = c
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }
}
