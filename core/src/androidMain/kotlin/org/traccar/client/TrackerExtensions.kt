package org.traccar.client

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.content.getSystemService

private const val PREFERENCES_NAME = "traccar-client-sdk"
private const val BATTERY_PROMPTED_KEY = "battery-prompted"

suspend fun Tracker.startTracking(context: Context) {
    TrackerService.ensureNotificationChannel(context)
    if (!ensurePermissions(context)) {
        Log.log("Permissions denied")
        throw IllegalStateException("Location permission denied")
    }
    if (config.promptBatteryOptimization) promptBatteryOptimization(context)
    start()
}

suspend fun Tracker.requestPosition(context: Context, alarm: String? = null): Boolean {
    if (!ensurePermissions(context)) {
        Log.log("Permissions denied")
        throw IllegalStateException("Location permission denied")
    }
    return requestPosition(alarm)
}

/**
 * Sets the tracking notification's [title] and [text] while tracking runs (a status the app keeps current, say the
 * time of the last position sent). The config's notification text is used again after a stop. True when shown.
 */
fun updateTrackerNotification(context: Context, title: String?, text: String): Boolean =
    TrackerService.update(context, title, text)

private fun promptBatteryOptimization(context: Context) {
    val powerManager = context.getSystemService<PowerManager>() ?: return
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
    val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    if (preferences.getBoolean(BATTERY_PROMPTED_KEY, false)) return
    context.startActivity(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    preferences.edit { putBoolean(BATTERY_PROMPTED_KEY, true) }
}
