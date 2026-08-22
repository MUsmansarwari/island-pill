package com.nahope.island.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nahope.island.IslandApp
import com.nahope.island.MainActivity
import com.nahope.island.R
import com.nahope.island.data.IslandConfig
import com.nahope.island.data.IslandPrefs
import com.nahope.island.island.IslandEvent
import com.nahope.island.island.IslandRepository
import com.nahope.island.island.sources.SourceSet
import com.nahope.island.service.overlay.OverlayHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Runs the data sources, and hosts the overlay only as a fallback.
 *
 * When [IslandAccessibilityService] is connected it owns the window instead — its
 * TYPE_ACCESSIBILITY_OVERLAY renders above the status bar, which TYPE_APPLICATION_OVERLAY cannot do
 * on Android 12+. Without accessibility the pill still works, it just sits behind the status bar.
 */
class IslandService : LifecycleService() {

    private lateinit var prefs: IslandPrefs

    private var overlay: OverlayHost? = null
    private var sources: SourceSet? = null

    private val config = MutableStateFlow(IslandConfig())
    private val landscape = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        prefs = IslandPrefs(this)

        startForegroundNotification()

        landscape.value = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        overlay = OverlayHost(
            context = this,
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            config = config,
            landscape = landscape,
            scope = lifecycleScope,
            onOpenApp = ::openPrimary,
        )

        lifecycleScope.launch {
            combine(prefs.config, IslandAccessibilityService.connected, ::Pair)
                .collect { (cfg, accessibilityOwnsWindow) ->
                    config.value = cfg

                    if (!cfg.enabled) {
                        Log.i(TAG, "config says disabled -> stopping")
                        stopSelf()
                        return@collect
                    }

                    val host = overlay ?: return@collect
                    val feed = sources ?: return@collect
                    when {
                        // Accessibility owns both the window and the sources; stay out of the way.
                        accessibilityOwnsWindow -> {
                            host.detach()
                            feed.stop()
                        }

                        !Settings.canDrawOverlays(this@IslandService) ->
                            Log.w(TAG, "no overlay permission and no accessibility service")

                        else -> {
                            feed.start()
                            host.attach()
                        }
                    }
                }
        }

        lifecycleScope.launch {
            IslandNotificationListener.connected.collect { sources?.bindMedia(it) }
        }

        sources = SourceSet(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape.value = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onDestroy() {
        sources?.stop()
        sources = null
        IslandRepository.clearAll()
        overlay?.detach()
        overlay = null
        super.onDestroy()
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

    private fun startForegroundNotification() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, IslandApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "IslandService"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, IslandService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IslandService::class.java))
        }
    }
}
