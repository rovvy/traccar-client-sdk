package org.traccar.client

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val PREFERENCES_NAME = "traccar-client-sdk"
private const val SCHEDULE_KEY = "tracking-schedule"
private const val CHANNEL_ID = "tracking-schedule"
private const val NOTICE_ID = 0x7AC1

/**
 * The schedule as kept on the phone, with the notice shown when tracking cannot start by itself at the start time: the
 * phone does not let the app start location tracking from the background (no "Alarms and reminders" permission, or
 * location not allowed all the time). A tap on the notice opens the app, which can start tracking in the foreground.
 */
@Serializable
private data class KeptSchedule(val schedule: TrackingSchedule, val noticeTitle: String = "", val noticeText: String = "")

/**
 * Starts tracking at [startAtMs] and stops it at [stopAtMs] (milliseconds since the epoch; null for none) by itself,
 * also while the app is closed, after an app update and after a reboot. The tracker must have been set up before
 * (`sharedTracker(config)`, as for a start). [noticeTitle] and [noticeText]: the notice shown at the start time when the
 * phone does not let the app start tracking from the background ([canStartTrackingInBackground]). A new call replaces
 * the schedule; both null clears it. Something due now (a start time already passed) happens at once.
 */
fun setTrackingSchedule(context: Context, startAtMs: Long?, stopAtMs: Long?, noticeTitle: String = "", noticeText: String = "") {
    val app = context.applicationContext
    val kept = KeptSchedule(TrackingSchedule(startAtMs, stopAtMs), noticeTitle, noticeText)
    // a start notice still up is old news once the app hands over its schedule (it does so when it opens)
    app.getSystemService<NotificationManager>()?.cancel(NOTICE_ID)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        scheduleLock.withLock {
            save(app, kept)
            Log.log("Schedule set: start ${startAtMs ?: "none"}, stop ${stopAtMs ?: "none"}")
            if (kept.schedule.isEmpty) {
                clear(app)
                alarmManager(app)?.cancel(alarmIntent(app))
            } else {
                handleDueLocked(app)
            }
        }
    }
}

/** The schedule kept on the phone (empty when none). */
fun trackingSchedule(context: Context): TrackingSchedule = load(context.applicationContext)?.schedule ?: TrackingSchedule()

/**
 * Whether tracking can start from the background at the start time: the alarm can wake the phone on time and lets the
 * app start its tracking service (Android 12 and up: "Alarms and reminders"), and location is allowed all the time.
 */
fun canStartTrackingInBackground(context: Context): Boolean = canScheduleExact(context) && hasBackgroundLocation(context)

/** Android 12 and up: whether "Alarms and reminders" is allowed for the app (always true before). */
fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return alarmManager(context.applicationContext)?.canScheduleExactAlarms() ?: false
}

private fun hasBackgroundLocation(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))

/**
 * After a reboot, an app update, or a change of the "Alarms and reminders" permission: what was due meanwhile happens
 * now, and the next alarm is set again (exact once the permission allows it).
 */
internal suspend fun handleScheduleAfterRestart(app: Context) {
    if (load(app) != null) handleDue(app)
}

/** One at a time: an alarm, a boot and a new schedule from the app never overwrite each other's schedule. */
private val scheduleLock = Mutex()

internal suspend fun handleDue(app: Context) = scheduleLock.withLock { handleDueLocked(app) }

private suspend fun handleDueLocked(app: Context) {
    val tracker = sharedTracker()
    // read after the tracker, which can take a moment: the schedule as it is now
    val kept = load(app) ?: return
    val now = System.currentTimeMillis()
    if (tracker == null) {
        // nothing can start without a tracker yet: the alarm stays set, so the next one tries again
        Log.log("Schedule: no tracker set up, nothing done")
        arm(app, kept.schedule.nextAt(now))
        return
    }
    when (kept.schedule.stepAt(now, tracker.state.value.enabled)) {
        TrackingSchedule.Step.START -> if (canStartTrackingInBackground(app)) {
            Log.log("Schedule: tracking starts")
            tracker.start()
        } else {
            Log.log("Schedule: start time, tracking can't start from the background, notice shown")
            showNotice(app, kept)
        }
        TrackingSchedule.Step.STOP -> {
            Log.log("Schedule: tracking stops")
            tracker.stop()
        }
        TrackingSchedule.Step.NONE -> Unit
    }
    val left = kept.schedule.after(now)
    if (left.isEmpty) clear(app) else if (left != kept.schedule) save(app, kept.copy(schedule = left))
    arm(app, left.nextAt(now))
}

/** One alarm for the next time: exact when allowed (an exact alarm also lets the app start its service then). */
private fun arm(app: Context, at: Long?) {
    val manager = alarmManager(app) ?: return
    val intent = alarmIntent(app)
    if (at == null) {
        manager.cancel(intent)
        return
    }
    if (canScheduleExact(app)) {
        AlarmManagerCompat.setExactAndAllowWhileIdle(manager, AlarmManager.RTC_WAKEUP, at, intent)
    } else {
        AlarmManagerCompat.setAndAllowWhileIdle(manager, AlarmManager.RTC_WAKEUP, at, intent)
    }
}

private fun showNotice(app: Context, kept: KeptSchedule) {
    if (kept.noticeText.isBlank() || !TrackerService.hasNotificationPermission(app)) return
    val manager = app.getSystemService<NotificationManager>() ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, kept.noticeTitle.ifBlank { "Tracking start" }, NotificationManager.IMPORTANCE_HIGH))
    }
    val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
        PendingIntent.getActivity(app, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    manager.notify(
        NOTICE_ID,
        NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracker_notification)
            .setContentTitle(kept.noticeTitle.ifBlank { null })
            .setContentText(kept.noticeText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(kept.noticeText))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build(),
    )
}

private fun alarmManager(app: Context): AlarmManager? = app.getSystemService()

private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
    app,
    1,
    Intent(app, ScheduleReceiver::class.java),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private val json = Json { ignoreUnknownKeys = true }

private fun preferences(app: Context) = app.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

private fun load(app: Context): KeptSchedule? = try {
    preferences(app).getString(SCHEDULE_KEY, null)?.let { json.decodeFromString(KeptSchedule.serializer(), it) }
} catch (e: Exception) {
    Log.log("Schedule unreadable: $e")
    null
}

private fun save(app: Context, kept: KeptSchedule) = preferences(app).edit { putString(SCHEDULE_KEY, json.encodeToString(KeptSchedule.serializer(), kept)) }

private fun clear(app: Context) = preferences(app).edit { remove(SCHEDULE_KEY) }

/** The schedule's alarm: what is due now happens, and the next alarm is set. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.log("Schedule alarm fired")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleDue(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
