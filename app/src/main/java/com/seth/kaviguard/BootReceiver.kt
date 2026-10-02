package com.seth.kaviguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * On boot: reschedule the self-updater's daily check
 * (AlarmManager schedules don't survive a reboot).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            UpdateManager.scheduleDaily(context)
        }
    }
}
