package com.hermes.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-start sync service on boot
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, SyncService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}