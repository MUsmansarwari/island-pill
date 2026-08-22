package com.nahope.island.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nahope.island.service.IslandAccessibilityService
import com.nahope.island.service.IslandNotificationListener

/**
 * Every gate the island has to pass before it can draw anything. Kept in one place because the
 * onboarding screen and the service both need to ask the same questions.
 */
object Permissions {

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlayIntent(context: Context) = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    /**
     * Reads the platform's own list rather than trusting a local flag — the user can revoke
     * notification access from Settings at any time and we would never be told.
     */
    fun hasNotificationAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false

        val target = ComponentName(context, IslandNotificationListener::class.java)
        return flat.split(":").any { entry ->
            val parsed = ComponentName.unflattenFromString(entry)
            parsed == target || parsed?.packageName == context.packageName
        }
    }

    fun notificationAccessIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /**
     * Without this the pill renders *behind* the status bar: TYPE_APPLICATION_OVERLAY sits at
     * layer 111000 while the status bar sits at 151000, and only an AccessibilityService may add
     * the higher TYPE_ACCESSIBILITY_OVERLAY window.
     */
    fun hasAccessibility(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val target = ComponentName(context, IslandAccessibilityService::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == target }
    }

    fun accessibilityIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun hasPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBluetooth(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * ColorOS / OxygenOS kill background services aggressively; without this the island silently
     * disappears after a few minutes on screen-off.
     */
    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @Suppress("BatteryLife")
    fun batteryIntent(context: Context) = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
}
