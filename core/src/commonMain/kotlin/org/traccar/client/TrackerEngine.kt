package org.traccar.client

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

class TrackerEngine internal constructor(
    private val stateStore: StateStore,
    private val queue: PositionQueue,
    private val network: NetworkMonitor,
    private val locationSource: LocationSource,
    signalSources: List<SignalSource>,
    private val processors: List<PositionProcessor>,
    private val uploader: Uploader,
    private val buffer: Boolean,
    scope: CoroutineScope,
    /** Positions sent in one request when several wait (Config.batchSize). */
    private val batchSize: Int = 1,
    private val initialBackoff: Duration = 5.seconds,
    private val maxBackoff: Duration = 5.minutes,
    /**
     * While moving (not paused), a heartbeat tick sends a position when nothing went out for this long. Covers the
     * phone wrongly leaving the stationary state while it stands still (upstream issue #26): the distance rule then
     * keeps every fix back and the stationary heartbeat is off. Zero: off. The platform's heartbeat trigger must
     * tick while not paused too (Android: AlarmHeartbeatTrigger).
     */
    private val activeHeartbeat: Duration = Duration.ZERO,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val pipelineWakeUp = Channel<Unit>(Channel.CONFLATED)
    private val heartbeatPositions = MutableSharedFlow<Position>(extraBufferCapacity = 4)
    private val lastOutput = MutableStateFlow(now())
    /**
     * The newest fix from the location source, kept or not, and when it arrived (the engine's clock, not the fix's
     * own time, which may come from another clock): an active heartbeat sends it when it arrived recently.
     */
    private val lastIncoming = MutableStateFlow<Pair<Position, Long>?>(null)
    private val pendingMutex = Mutex()

    init {
        scope.launch { signalSources.map { it.signals }.merge().collect { handle(it) } }
        scope.launch { pipelineLoop() }
        scope.launch { syncLoop() }
        scope.launch {
            stateStore.state.collect { state ->
                updateStatus {
                    // a new start does not inherit the run before's fix or failed sends (the status is one per process)
                    if (state.enabled && !it.enabled) {
                        it.copy(enabled = true, paused = state.paused, startedAt = now(), lastFixTime = null, noAnswerSince = null)
                    } else {
                        it.copy(enabled = state.enabled, paused = state.paused)
                    }
                }
            }
        }
        scope.launch { refreshPending() }
    }

    // one at a time: two counts published out of order would show an old number
    private suspend fun refreshPending() = pendingMutex.withLock {
        val pending = queue.count()
        updateStatus { it.copy(pending = pending) }
    }

    suspend fun handle(signal: Signal) = mutex.withLock {
        when (signal) {
            Signal.StationaryEnter -> applyStationaryEnter()
            Signal.StationaryExit -> applyStationaryExit()
            Signal.HeartbeatTick -> applyHeartbeatTick()
        }
    }

    private suspend fun applyStationaryEnter() {
        val state = stateStore.state.value
        if (!state.enabled || state.paused) return
        Log.log("StationaryEnter: pausing")
        stateStore.update { it.copy(paused = true) }
    }

    private suspend fun applyStationaryExit() {
        val state = stateStore.state.value
        if (!state.enabled || !state.paused) return
        Log.log("StationaryExit: resuming")
        stateStore.update { it.copy(paused = false) }
    }

    private suspend fun applyHeartbeatTick() {
        val state = stateStore.state.value
        if (!state.enabled) return
        if (state.paused) {
            Log.log("HeartbeatTick")
            emitHeartbeat(locationSource.fetchOnce())
            return
        }
        if (activeHeartbeat <= Duration.ZERO) return
        val silent = now() - lastOutput.value
        // a little early still counts (alarm timing); a clock set back counts as due
        if (silent in 0 until activeHeartbeat.inWholeMilliseconds * 9 / 10) return
        Log.log("ActiveHeartbeat: nothing sent for ${silent / 1000}s")
        // a fix the filter dropped since the last output costs no GPS; otherwise ask for one, never waiting forever
        val recent = lastIncoming.value
            ?.takeIf { (_, at) -> at > lastOutput.value && now() - at <= RECENT_FIX.inWholeMilliseconds }?.first
        val position = recent ?: try {
            withTimeoutOrNull(LOCATION_FETCH_TIMEOUT + 5.seconds) { locationSource.fetchOnce() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.log("ActiveHeartbeat fix failed: $e")
            null
        }
        emitHeartbeat(position)
    }

    /**
     * The status's last fix time, from the fix's own time: a heartbeat's fix can be the phone's cached last location
     * (fetchOnce falls back to it), which must not count as a new fix. Written at most every [FIX_STEP], so a phone
     * that gives a fix every second does not send a status every second.
     */
    private fun noteFix(position: Position) {
        if (position.latitude == null || position.longitude == null) return
        updateStatus {
            val last = it.lastFixTime
            if (last != null && position.time < last + FIX_STEP.inWholeMilliseconds) it else it.copy(lastFixTime = position.time)
        }
    }

    private suspend fun emitHeartbeat(position: Position?) {
        val heartbeat = (position ?: Position(time = now())).copy(heartbeat = true)
        heartbeatPositions.emit(heartbeat)
    }

    private suspend fun pipelineLoop() {
        merge(locationSource.positions.onEach { lastIncoming.value = it to now() }, heartbeatPositions)
            .onEach(::noteFix)
            .collect { incoming ->
            if (!stateStore.state.value.enabled) return@collect
            var current: Position? = incoming
            for (processor in processors) {
                current = processor.process(current ?: break)
            }
            val final = current ?: return@collect
            lastOutput.value = now()
            updateStatus {
                it.copy(lastPositionTime = final.time, lastLatitude = final.latitude ?: it.lastLatitude, lastLongitude = final.longitude ?: it.lastLongitude)
            }
            if (buffer) {
                queue.enqueue(final)
                refreshPending()
                pipelineWakeUp.trySend(Unit)
            } else {
                uploader.upload(final)
            }
        }
    }

    private suspend fun syncLoop() {
        var backoff = initialBackoff
        while (currentCoroutineContext().isActive) {
            val pending = queue.peek(batchSize.coerceIn(1, MAX_BATCH))
            when {
                pending.isEmpty() -> pipelineWakeUp.receive()
                !network.isOnline.value -> {
                    Log.log("Offline, waiting for network")
                    updateStatus { it.copy(waitingForNetwork = true) }
                    try {
                        network.isOnline.first { it }
                    } finally {
                        updateStatus { it.copy(waitingForNetwork = false) }
                    }
                    Log.log("Network restored")
                }
                else -> {
                    // never more than was given: removing rows that were not sent would lose them
                    val sent = uploader.upload(pending).coerceIn(0, pending.size)
                    if (sent > 0) {
                        queue.remove(sent)
                        refreshPending()
                        backoff = initialBackoff
                    } else {
                        Log.log("Upload failed, retrying in $backoff")
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(maxBackoff)
                    }
                }
            }
        }
    }

    private companion object {
        /** A dropped fix older than this is not sent as a heartbeat: a new fix is asked for instead. */
        val RECENT_FIX = 1.minutes

        /** At most this many in one request, whatever the config says (a server may keep fewer and still answer 2xx). */
        const val MAX_BATCH = 500

        /** The status's last fix time moves in steps of at least this much. */
        val FIX_STEP = 30.seconds
    }
}
