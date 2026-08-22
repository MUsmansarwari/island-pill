package com.nahope.island.service

import android.app.NotificationManager
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nahope.island.island.IslandEvent
import com.nahope.island.island.IslandRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Feeds ordinary notifications into the island, and doubles as the credential that lets
 * [android.media.session.MediaSessionManager] hand us the active media sessions.
 */
class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i("IslandListener", "notification listener connected")
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _connected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (!shouldShow(sbn, rankingMap)) return

        val n = sbn.notification
        val extras = n.extras
        IslandRepository.publish(
            IslandEvent.Notification(
                id = idOf(sbn),
                packageName = sbn.packageName,
                appName = appLabel(sbn.packageName),
                title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
                text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
                icon = appIcon(sbn.packageName),
                accent = n.color.takeIf { it != 0 },
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        IslandRepository.clear(idOf(sbn))
    }

    private fun shouldShow(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        if (sbn.packageName == packageName) return false

        val n = sbn.notification
        // Ongoing / foreground-service chrome is noise; media is handled by MediaSource instead.
        if (n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return false
        if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)) return false

        val ranking = Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
        }

        val extras = n.extras
        val hasContent = !extras.getCharSequence(android.app.Notification.EXTRA_TITLE).isNullOrBlank() ||
            !extras.getCharSequence(android.app.Notification.EXTRA_TEXT).isNullOrBlank()
        return hasContent
    }

    private fun idOf(sbn: StatusBarNotification) = "notif:${sbn.key}"

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun appIcon(pkg: String) = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        null
    }

    companion object {
        private val _connected = MutableStateFlow(false)

        /** True while the OS has us bound — i.e. notification access is actually granted. */
        val connected: StateFlow<Boolean> = _connected
    }
}
