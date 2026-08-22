package com.nahope.island.island.sources

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.nahope.island.island.IslandEvent
import com.nahope.island.island.IslandRepository

/**
 * The cheap wins: plug the charger in, flip the silent switch, connect your buds.
 * All of it is a broadcast away — no polling, no extra permissions beyond BLUETOOTH_CONNECT.
 */
class SystemSource(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> publishCharging(plugged = true)
                Intent.ACTION_POWER_DISCONNECTED -> publishCharging(plugged = false)
                AudioManager.RINGER_MODE_CHANGED_ACTION -> publishRinger(
                    intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                )

                BluetoothDevice.ACTION_ACL_CONNECTED -> publishBluetooth(intent, connected = true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> publishBluetooth(intent, connected = false)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun publishCharging(plugged: Boolean) {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugType = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        IslandRepository.publish(
            IslandEvent.Charging(
                levelPercent = if (level >= 0 && scale > 0) level * 100 / scale else 0,
                plugged = plugged,
                fast = plugType == BatteryManager.BATTERY_PLUGGED_AC,
            )
        )
    }

    private fun publishRinger(mode: Int) {
        val translated = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> IslandEvent.Ringer.Mode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> IslandEvent.Ringer.Mode.VIBRATE
            AudioManager.RINGER_MODE_NORMAL -> IslandEvent.Ringer.Mode.NORMAL
            else -> return
        }
        IslandRepository.publish(IslandEvent.Ringer(mode = translated))
    }

    private fun publishBluetooth(intent: Intent, connected: Boolean) {
        if (!hasBluetoothPermission()) return

        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

        val name = try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            return
        }

        IslandRepository.publish(
            IslandEvent.Bluetooth(
                deviceName = name,
                connected = connected,
                batteryPercent = if (connected) batteryLevelOf(device) else null,
            )
        )
    }

    /**
     * BluetoothDevice.getBatteryLevel() is @hide but present on every AOSP-derived build we care
     * about. Reflection is the only way to reach it; a null return just means "no badge".
     */
    private fun batteryLevelOf(device: BluetoothDevice): Int? = try {
        val level = BluetoothDevice::class.java
            .getMethod("getBatteryLevel")
            .invoke(device) as? Int
        level?.takeIf { it in 0..100 }
    } catch (_: Throwable) {
        null
    }

    private fun hasBluetoothPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
