package org.traccar.client

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val initialBackoff: Duration = 5.seconds,
    private val maxBackoff: Duration = 5.minutes,
    /**
     * While moving (not paused): when nothing went out for this long, take a fix and send it like a heartbeat.
     * Covers the phone wrongly leaving the stationary state while it stands still, when the distance rule then
     * keeps every fix back and the stationary heartbeat is off. Zero: off.
     */
    private val activeHeartbeat: Duration = Duration.ZERO,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val pipelineWakeUp = Channel<Unit>(Channel.CONFLATED)
    private val heartbeatPositions = MutableSharedFlow<Position>(extraBufferCapacity = 4)
    private val lastOutput = MutableStateFlow(now())

    init {
        scope.launch { signalSources.map { it.signals }.merge().collect { handle(it) } }
        scope.launch { pipelineLoop() }
        scope.launch { syncLoop() }
        if (activeHeartbeat > Duration.ZERO) scope.launch { activeHeartbeatLoop() }
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
        if (!state.enabled || !state.paused) return
        Log.log("HeartbeatTick")
        val position = locationSource.fetchOnce()
            ?: Position(time = Clock.System.now().toEpochMilliseconds())
        heartbeatPositions.emit(position.copy(heartbeat = true))
    }

    private suspend fun pipelineLoop() {
        merge(locationSource.positions, heartbeatPositions).collect { incoming ->
            if (!stateStore.state.value.enabled) return@collect
            var current: Position? = incoming
            for (processor in processors) {
                current = processor.process(current ?: break)
            }
            val final = current ?: return@collect
            lastOutput.value = now()
            if (buffer) {
                queue.enqueue(final)
                pipelineWakeUp.trySend(Unit)
            } else {
                uploader.upload(final)
            }
        }
    }

    private suspend fun activeHeartbeatLoop() {
        var wait = activeHeartbeat
        while (currentCoroutineContext().isActive) {
            delay(wait)
            val due = lastOutput.value + activeHeartbeat.inWholeMilliseconds - now()
            if (due > 0) {
                wait = due.milliseconds
                continue
            }
            wait = activeHeartbeat
            mutex.withLock {
                val state = stateStore.state.value
                // paused: the stationary heartbeat is in charge
                if (!state.enabled || state.paused) return@withLock
                Log.log("ActiveHeartbeat: nothing sent for $activeHeartbeat")
                val position = locationSource.fetchOnce() ?: Position(time = now())
                heartbeatPositions.emit(position.copy(heartbeat = true))
            }
            lastOutput.value = now()
        }
    }

    private suspend fun syncLoop() {
        var backoff = initialBackoff
        while (currentCoroutineContext().isActive) {
            val pending = queue.peek()
            when {
                pending == null -> pipelineWakeUp.receive()
                !network.isOnline.value -> {
                    Log.log("Offline, waiting for network")
                    network.isOnline.first { it }
                    Log.log("Network restored")
                }
                uploader.upload(pending) -> {
                    queue.removeFirst()
                    backoff = initialBackoff
                }
                else -> {
                    Log.log("Upload failed, retrying in $backoff")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(maxBackoff)
                }
            }
        }
    }
}
