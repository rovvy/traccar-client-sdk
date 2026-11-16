package org.traccar.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Switches the battery profiles ([Config.batteryProfiles]) while tracking runs: reads the battery level, charging and
 * battery saver when one changes and every minute, and rebuilds the tracker with [chooseBatteryProfile]'s answer.
 *
 * One per process, with its own scope: a switch rebuilds the tracker, which ends the component scope and restarts the
 * tracking service, so the engine must live outside both. Started by the tracking service; it ends itself when
 * tracking stops.
 */
internal object BatteryProfileEngine {

    /** The shortest time between two switches (charging goes back to no profile at once). */
    private const val HOLD_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** When the profile last changed, in time since boot ([SystemClock.elapsedRealtime]). */
    private var sinceMs = -HOLD_MS

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        // a new run: the first choice applies at once (the profile the last run ended in is not held)
        sinceMs = -HOLD_MS
        job = scope.launch {
            val ticker = flow { while (true) { emit(Unit); delay(60_000) } }
            merge(ticker, changes(app), trackerStatus.map { it.enabled }.distinctUntilChanged()).conflate().collect {
                try {
                    if (!check(app)) throw StopEngine()
                } catch (e: StopEngine) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.log("Battery profile check failed: $e")
                }
            }
        }.also { j ->
            j.invokeOnCompletion {
                synchronized(this) { if (job === j) job = null }
                // tracking was started again while this run was ending (a quick Stop and Start): a new run, after a
                // pause so a status that is late to say "off" cannot loop
                scope.launch {
                    delay(5_000)
                    if (sharedTracker()?.state?.value?.enabled == true) start(app)
                }
            }
        }
    }

    private class StopEngine : CancellationException("tracking stopped")

    /** One look: false when tracking is off (the engine ends). */
    private suspend fun check(context: Context): Boolean {
        val tracker = sharedTracker() ?: return false
        if (!tracker.state.value.enabled) return false
        val config = tracker.config
        if (config.batteryProfiles.isEmpty() && config.activeProfile == null) return true
        // the time since boot, not the clock: a clock set back would hold the profile for hours
        val now = SystemClock.elapsedRealtime()
        val target = chooseBatteryProfile(config.batteryProfiles, config.activeProfile, read(context), sinceMs, now, HOLD_MS)
        if (target == config.activeProfile) return true
        sinceMs = now
        switchBatteryProfile(target)
        return true
    }

    private fun read(context: Context): BatteryReading {
        val battery = context.getSystemService<BatteryManager>()
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val saver = context.getSystemService<PowerManager>()?.isPowerSaveMode == true
        return BatteryReading(level, battery?.isCharging == true, saver)
    }

    /** Battery level or charging changed, a charger was plugged in or out, battery saver went on or off. */
    private fun changes(context: Context) = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}
