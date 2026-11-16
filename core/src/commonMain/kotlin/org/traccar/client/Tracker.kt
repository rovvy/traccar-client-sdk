package org.traccar.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.koin.dsl.koinApplication

class Tracker internal constructor(
    val config: Config,
    private val stateStore: StateStore,
    private val locationSource: LocationSource,
    private val batteryProcessor: PositionProcessor,
    private val uploader: Uploader,
    private val componentScope: ComponentCoroutineScope,
) {
    val state: StateFlow<State> = stateStore.state

    // start and stop act on the tracker in use: a battery profile switch may have rebuilt it since the caller got this one
    suspend fun start() = sharedMutex.withLock {
        Log.log("Tracker start ${config.serverUrl} ${config.deviceId}")
        (sharedTrackerInstance ?: this).stateStore.update { it.copy(enabled = true) }
    }

    suspend fun stop() = sharedMutex.withLock {
        Log.log("Tracker stop")
        (sharedTrackerInstance ?: this).stateStore.update { it.copy(enabled = false, paused = false) }
    }

    suspend fun requestPosition(alarm: String? = null): Boolean {
        // a battery profile switch rebuilt the tracker since the caller got this one: the new one's location source
        sharedTrackerInstance?.takeIf { it !== this }?.let { return it.requestPosition(alarm) }
        Log.log("Position requested${alarm?.let { " alarm=$it" } ?: ""}")
        val raw = locationSource.fetchOnce() ?: run {
            Log.log("Position request: no fix")
            return false
        }
        Log.log("Position fetched ${raw.latitude},${raw.longitude}")
        val processed = batteryProcessor.process(raw)?.copy(alarm = alarm) ?: return false
        return uploader.upload(processed)
    }

    suspend fun getLogs(): List<LogEntry> = Log.store?.all() ?: emptyList()

    suspend fun clearLogs() = Log.store?.clear()

    /**
     * A new config for the tracker in use (this one, or the one a battery profile switch built since). The battery
     * profile in use stays when [newConfig] names none and still has that profile (the SDK chose it, not the app).
     */
    suspend fun updateConfig(newConfig: Config): Tracker = sharedMutex.withLock {
        val current = sharedTrackerInstance ?: this
        val kept = current.config.activeProfile?.takeIf { name -> newConfig.batteryProfiles.any { it.name == name } }
        rebuild(current, if (newConfig.activeProfile == null) newConfig.copy(activeProfile = kept) else newConfig)
    }

    internal suspend fun rebuild(current: Tracker, newConfig: Config): Tracker {
        // the same config: nothing to rebuild (a rebuild stops and restarts the whole pipeline)
        if (newConfig == current.config && sharedTrackerInstance === current) return current
        current.componentScope.coroutineContext.job.cancelAndJoin()
        if (current !== this) componentScope.coroutineContext.job.cancelAndJoin()
        sharedTrackerInstance = null
        return bootstrap(newConfig)
    }
}

/** The battery profile engine: [name] (null: none) for the tracker in use; nothing when there is no tracker. */
internal suspend fun switchBatteryProfile(name: String?): Unit = sharedMutex.withLock {
    val current = sharedTrackerInstance ?: return@withLock
    if (current.config.activeProfile == name) return@withLock
    Log.log("Battery profile ${current.config.activeProfile ?: "none"} -> ${name ?: "none"}")
    current.rebuild(current, current.config.copy(activeProfile = name))
}

private val sharedMutex = Mutex()
private var sharedTrackerInstance: Tracker? = null

suspend fun sharedTracker(): Tracker? = sharedMutex.withLock {
    sharedTrackerInstance?.let { return@withLock it }
    val koin = openKoin()
    val persisted = koin.get<ConfigStore>().load() ?: return@withLock null
    install(koin, persisted)
}

suspend fun sharedTracker(config: Config): Tracker = sharedMutex.withLock {
    sharedTrackerInstance?.let { return@withLock it }
    bootstrap(config)
}

private suspend fun bootstrap(config: Config): Tracker {
    val koin = openKoin()
    koin.get<ConfigStore>().save(config)
    return install(koin, config)
}

private suspend fun openKoin() = withContext(Dispatchers.IO) {
    val koin = koinApplication { modules(coreModule(), platformModule()) }.koin
    Log.store = LogStore(koin.get())
    koin
}

/** The config as the app gave it (with its battery profiles); the parts of the tracker get [Config.applied]. */
internal class GivenConfig(val config: Config)

private suspend fun install(koin: Koin, config: Config): Tracker {
    val stateStore = StateStore.create(koin.get())
    koin.declare(stateStore)
    // the parts run with the active battery profile's interval and heartbeat; the Tracker keeps the config as given
    koin.declare(config.applied())
    koin.declare(GivenConfig(config))
    updateStatus { it.copy(profile = config.activeProfile?.takeIf { name -> config.batteryProfiles.any { p -> p.name == name } }) }
    if (config.activeProfile != null) Log.log("Battery profile ${config.activeProfile}: interval ${config.applied().location.intervalSeconds} s")
    if (!config.location.stopDetection) {
        stateStore.update { it.copy(paused = false) }
    }
    koin.get<TrackerEngine>()
    return koin.get<Tracker>().also { sharedTrackerInstance = it }
}
