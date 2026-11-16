package org.traccar.client

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

class ForegroundServiceHolder(
    scope: ComponentCoroutineScope,
    context: Context,
    state: StateFlow<State>,
) : SignalSource {

    override val signals: Flow<Signal> = emptyFlow()

    private val appContext = context.applicationContext

    init {
        scope.observeState(state, { it.enabled }, inactive = false) { enabled ->
            if (enabled) start() else stop()
        }
    }

    private fun start() {
        val intent = Intent(appContext, TrackerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && TrackerService.hasNotificationPermission(appContext)) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (e: IllegalStateException) {
            Log.log("Service start blocked: $e")
        }
    }

    private fun stop() {
        appContext.stopService(Intent(appContext, TrackerService::class.java))
    }
}

class TrackerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.log("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground(NotificationConfig())) {
            stopSelf()
            return START_NOT_STICKY
        }
        synchronized(lock) { running = this }

        serviceScope.launch {
            sharedTracker()?.let { startInForeground(it.config.notification) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.log("Service destroyed")
        // after this no update can show a notification: none is left once tracking stopped
        synchronized(lock) {
            if (running === this) running = null
            text = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(settings: NotificationConfig): Boolean {
        if (!hasNotificationPermission(this)) return true
        val notification = buildNotification(settings)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: IllegalStateException) {
            Log.log("Foreground start not allowed: $e")
            false
        } catch (e: SecurityException) {
            Log.log("Foreground start denied: $e")
            false
        }
    }

    private fun buildNotification(settings: NotificationConfig): Notification =
        synchronized(lock) { text }?.let { build(this, it.first, it.second) }
            ?: NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tracker_notification)
                .setContentText(settings.text)
                .setOngoing(true)
                .build()

    companion object {
        private const val CHANNEL_ID = "tracker"
        private const val NOTIFICATION_ID = 0x7AC0

        private val lock = Any()
        /** The service while it runs in the foreground; an update shows only then. */
        private var running: TrackerService? = null
        /** The app's title and text (updateTrackerNotification), kept while the service runs. */
        private var text: Pair<String?, String>? = null

        /**
         * Shows [title] and [body] in the tracking notification instead of the config's fixed text, until tracking
         * stops; the service uses them too when it builds its notification again. The whole text is shown, also on
         * the lock screen; a tap opens the app. True when shown; false when the tracking service is not running
         * (nothing is kept, so an update that crosses a stop never shows later) or notifications are not allowed.
         */
        fun update(context: Context, title: String?, body: String): Boolean = synchronized(lock) {
            val service = running ?: return@synchronized false
            text = title to body
            if (!hasNotificationPermission(context)) return@synchronized false
            val manager = context.getSystemService<NotificationManager>() ?: return@synchronized false
            manager.notify(NOTIFICATION_ID, build(service, title, body))
            true
        }

        private fun build(context: Context, title: String?, body: String): Notification {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val open = launch?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tracker_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }

        internal fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)

        internal fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService<NotificationManager>() ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Location tracking", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
