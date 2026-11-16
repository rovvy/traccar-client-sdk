package org.traccar.client

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Date

class ForegroundServiceHolder(
    scope: ComponentCoroutineScope,
    context: Context,
    private val state: StateFlow<State>,
) : SignalSource {

    override val signals: Flow<Signal> = emptyFlow()

    private val appContext = context.applicationContext

    init {
        scope.observeState(state, { it.enabled }, inactive = false) { enabled ->
            if (enabled) start() else stop()
        }
    }

    private fun start() {
        // a new config while tracking (a battery profile switch, the app's setConfig): the service kept running, and
        // starting it again from the background is refused on Android 12+ unless the app is exempt from battery
        // optimization
        if (TrackerService.isRunning()) return
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
        // a new config (the scope ends, tracking stays on): the service, its notification and the app's text stay for
        // the new tracker
        if (state.value.enabled) return
        TrackerService.clearText()
        appContext.stopService(Intent(appContext, TrackerService::class.java))
    }
}

class TrackerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Keeps the notification current; one per service (onStartCommand runs at every start). */
    private var notices: kotlinx.coroutines.Job? = null

    /** Logs the phone's own switches that stop or slow tracking, while the service runs (for support). */
    private val phoneState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                LocationManager.PROVIDERS_CHANGED_ACTION -> logChange("Location", locationOn())
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> logChange("Battery saver", powerSave())
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> logChange("Doze", idle())
            }
        }
    }
    private val lastPhoneState = mutableMapOf<String, Boolean?>()

    private fun locationOn(): Boolean? = getSystemService<LocationManager>()?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLocationEnabled
        else it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    private fun powerSave(): Boolean? = getSystemService<PowerManager>()?.isPowerSaveMode
    private fun idle(): Boolean? = getSystemService<PowerManager>()?.isDeviceIdleMode

    // PROVIDERS_CHANGED comes once per provider; only a change of the switch itself is logged
    private fun logChange(name: String, on: Boolean?) = synchronized(lastPhoneState) {
        if (lastPhoneState.containsKey(name) && lastPhoneState[name] == on) return@synchronized
        lastPhoneState[name] = on
        Log.log("$name ${when (on) { true -> "on"; false -> "off"; null -> "unknown" }}")
    }

    override fun onCreate() {
        super.onCreate()
        Log.log("Service created")
        logChange("Location", locationOn())
        logChange("Battery saver", powerSave())
        logChange("Doze", idle())
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        // system broadcasts only: they reach a receiver that is not exported
        ContextCompat.registerReceiver(this, phoneState, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
        // the battery profiles switch by themselves while tracking runs (the engine outlives this service: a switch
        // rebuilds the tracker, which restarts the service)
        BatteryProfileEngine.start(this)
        // the notification follows the tracker's status by itself (noticeFor), also while the app sleeps or is closed;
        // the ticker catches a limit passing with no status change (no GPS after 10 min, no answer after 30 s). It
        // stops in deep sleep, but the heartbeat alarm (at least every 5 min while tracking) changes the status, and
        // failed sends retry and change it, so the notification still moves on with the screen off
        if (notices?.isActive != true) notices = serviceScope.launch {
            val ticker = flow { while (true) { emit(Unit); delay(30_000) } }
            merge(trackerStatus, ticker).collect { refresh(this@TrackerService) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.log("Service destroyed")
        runCatching { unregisterReceiver(phoneState) }
        // after this no update can show a notification; one posted just before (Android removes the foreground
        // notification before onDestroy) is removed here, so none is left once the service is gone
        synchronized(lock) {
            if (running === this) running = null
            getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
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
        synchronized(lock) { shown(this) }?.let { build(this, it.first, it.second) }
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
        /** The app's title and text (updateTrackerNotification), kept while tracking is on, also across a new config. */
        private var text: Pair<String?, String>? = null
        /** The wording per state (setTrackerNoticeTexts), kept on the phone so a restart after a reboot has it too. */
        private var texts: NoticeTexts? = null
        private var textsLoaded = false
        /** What the notification shows now, so a status change that changes nothing posts nothing. */
        private var current: Pair<String?, String>? = null

        private const val PREFERENCES_NAME = "traccar-client-sdk"
        private const val NOTICE_TEXTS_KEY = "notice-texts"

        /** The app's own text first (a problem only the app knows), else the state's wording, else null (config). */
        private fun shown(context: Context): Pair<String?, String>? {
            text?.let { return it }
            val t = loadTexts(context) ?: return null
            val clock = android.text.format.DateFormat.getTimeFormat(context)
            return noticeFor(trackerStatus.value, t, System.currentTimeMillis()) { clock.format(Date(it)) }
        }

        private fun loadTexts(context: Context): NoticeTexts? {
            if (!textsLoaded) {
                textsLoaded = true
                texts = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .getString(NOTICE_TEXTS_KEY, null)
                    ?.let { runCatching { json.decodeFromString(NoticeTexts.serializer(), it) }.getOrNull() }
            }
            return texts
        }

        private val json = Json { ignoreUnknownKeys = true }

        private val digits = Regex("[0-9]")
        private fun kind(shown: Pair<String?, String>?) = shown?.let { it.first?.replace(digits, "") to it.second.replace(digits, "") }

        /** Posts the notification again when what it should show changed. */
        private fun refresh(context: Context) = synchronized(lock) {
            val service = running ?: return@synchronized
            val now = shown(context) ?: return@synchronized
            if (now == current) return@synchronized
            post(context, service, now)
        }

        private fun post(context: Context, service: TrackerService, shown: Pair<String?, String>): Boolean {
            if (!hasNotificationPermission(context)) return false
            val manager = context.getSystemService<NotificationManager>() ?: return false
            if (manager.activeNotifications.none { it.id == NOTIFICATION_ID }) return false
            manager.notify(NOTIFICATION_ID, build(service, shown.first, shown.second))
            // a change of state is logged, not each new clock time in the same state (the title moves every minute)
            if (kind(shown) != kind(current)) Log.log("Notification: ${shown.first?.let { "$it | " } ?: ""}${shown.second}")
            current = shown
            return true
        }

        /**
         * The wording for each state; from then on the SDK keeps the notification current by itself from its status
         * (no internet, no GPS, refused, nothing sent, last sent), unless the app set its own text ([update]). Kept on
         * the phone. True when the notification shows it now.
         */
        fun setTexts(context: Context, value: NoticeTexts): Boolean = synchronized(lock) {
            texts = value
            textsLoaded = true
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
                .putString(NOTICE_TEXTS_KEY, json.encodeToString(NoticeTexts.serializer(), value)).apply()
            val service = running ?: return@synchronized false
            val now = shown(context) ?: return@synchronized false
            post(context, service, now)
        }

        /** The app's own text goes; the state's wording shows again. */
        fun clearAppText(context: Context): Boolean = synchronized(lock) {
            text = null
            val service = running ?: return@synchronized false
            val now = shown(context) ?: return@synchronized false
            post(context, service, now)
        }

        /**
         * Shows [title] and [body] in the tracking notification instead of the config's fixed text, until tracking
         * stops; the service uses them too when it builds its notification again. The whole text is shown, also on
         * the lock screen; a tap opens the app. True when shown; false when the tracking service is not running
         * (nothing is kept, so an update that crosses a stop never shows later), notifications are not allowed, or
         * the rider swiped the notification away (Android 14 allows it; it stays away, the text is kept for later).
         */
        fun update(context: Context, title: String?, body: String): Boolean = synchronized(lock) {
            val service = running ?: return@synchronized false
            text = title to body
            post(context, service, title to body)
        }

        /** The service runs in the foreground now. */
        internal fun isRunning(): Boolean = synchronized(lock) { running != null }

        /** Tracking stops: the app's text goes, and no update shows from now on (the service is about to end). */
        internal fun clearText() = synchronized(lock) {
            text = null
            current = null
            running = null
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
