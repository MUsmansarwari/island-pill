package com.nahope.island

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class IslandApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "island_running"
    }
}
