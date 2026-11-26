package org.traccar.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != EXACT_ALARM_CHANGED) return
        Log.log("Boot received: ${intent.action}")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                sharedTracker()
                // alarms are gone after a reboot and an app update, and an alarm set before "Alarms and reminders" was
                // allowed is not exact: the schedule sets its next one again
                handleScheduleAfterRestart(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Android 12 and up: "Alarms and reminders" was allowed or taken back for the app. */
private const val EXACT_ALARM_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
