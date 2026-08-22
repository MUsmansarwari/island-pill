package com.nahope.island.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the island back after a reboot. The service itself re-checks the enabled flag. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> IslandService.start(context)
        }
    }
}
