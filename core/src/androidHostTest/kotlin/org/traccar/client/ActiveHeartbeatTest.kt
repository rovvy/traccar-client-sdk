package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.traccar.client.db.Database
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The active heartbeat (upstream issue #26): while not paused, a heartbeat tick (the Android alarm) sends a position
 * when nothing went out for the heartbeat interval, so a phone that wrongly left the stationary state is not silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveHeartbeatTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    private class Fakes {
        val positions = MutableSharedFlow<Position>(extraBufferCapacity = 16)
        val signals = MutableSharedFlow<Signal>(extraBufferCapacity = 16)
        val sent = mutableListOf<Position>()
        var fetches = 0
        var fetchHangs = false
    }

    private fun fix(time: Long) = Position(latitude = 45.8, longitude = 24.15, accuracy = 5.0, time = time)

    /** An engine with the real LocationFilter (distance 250 m, as in #26) and a heartbeat interval of [heartbeat]. */
    private suspend fun TestScope.engine(state: State, heartbeat: Duration): Fakes {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        Database.Schema.create(driver)
        val store = StateStore.create(driver)
        store.update { state }
        val fakes = Fakes()
        val config = Config(
            serverUrl = "https://example.org",
            deviceId = "test",
            location = LocationConfig(intervalSeconds = 0, distanceMeters = 250, heartbeatIntervalSeconds = heartbeat.inWholeSeconds.toInt()),
        )
        TrackerEngine(
            stateStore = store,
            queue = object : PositionQueue {
                override suspend fun enqueue(position: Position) {}
                override suspend fun peek(): Position? = null
                override suspend fun removeFirst() {}
            },
            network = object : NetworkMonitor {
                override val isOnline = MutableStateFlow(true)
            },
            locationSource = object : LocationSource {
                override val positions = fakes.positions
                override suspend fun fetchOnce(): Position {
                    fakes.fetches++
                    if (fakes.fetchHangs) awaitCancellation()
                    return fix(testScheduler.currentTime)
                }
            },
            signalSources = listOf(object : SignalSource {
                override val signals = fakes.signals
            }),
            processors = listOf(LocationFilter(config, store)),
            uploader = object : Uploader {
                override suspend fun upload(position: Position): Boolean {
                    fakes.sent += position
                    return true
                }
            },
            buffer = false,
            scope = backgroundScope,
            activeHeartbeat = heartbeat,
            now = { testScheduler.currentTime },
        )
        settle()
        return fakes
    }

    /**
     * Runs what is due now. StateStore writes on Dispatchers.IO (a real thread), so the work that follows a
     * write comes back to the test scheduler a moment later: wait a little real time and run again.
     */
    private fun TestScope.settle() {
        repeat(10) {
            runCurrent()
            Thread.sleep(20)
        }
        runCurrent()
    }

    private suspend fun TestScope.fixNow(f: Fakes) {
        f.positions.emit(fix(testScheduler.currentTime))
        settle()
    }

    private suspend fun TestScope.tick(f: Fakes) {
        f.signals.emit(Signal.HeartbeatTick)
        settle()
    }

    /** The #26 case: moving state, fixes keep coming but the filter drops them (standing still); a tick sends one. */
    @Test
    fun droppedFixesStillGetAHeartbeat() = runTest {
        val f = engine(State(enabled = true, paused = false), 5.minutes)
        fixNow(f)
        assertEquals(1, f.sent.size, "the first fix is kept")
        repeat(5) {
            advanceTimeBy(1.minutes)
            fixNow(f) // same place: dropped by the 250 m rule
        }
        assertEquals(1, f.sent.size, "standing still: the filter drops them")
        tick(f)
        assertEquals(2, f.sent.size, "the tick after 5 minutes sends a position")
        assertEquals(0, f.fetches, "the newest dropped fix is sent, no extra GPS fix")
    }

    /** No fix at all while moving: the tick asks for one. */
    @Test
    fun noFixAtAllAsksForOne() = runTest {
        val f = engine(State(enabled = true, paused = false), 5.minutes)
        advanceTimeBy(5.minutes)
        tick(f)
        assertEquals(1, f.fetches)
        assertEquals(1, f.sent.size)
        assertTrue(f.sent[0].latitude != null, "a real fix, with coordinates")
    }

    /** Something went out recently: the tick sends nothing. */
    @Test
    fun recentOutputSkipsTheTick() = runTest {
        val f = engine(State(enabled = true, paused = false), 5.minutes)
        advanceTimeBy(4.minutes)
        fixNow(f)
        advanceTimeBy(1.minutes)
        tick(f)
        assertEquals(1, f.sent.size, "only the fix itself")
        assertEquals(0, f.fetches)
    }

    /** A fix that never comes does not block the engine: the tick gives up and sends a heartbeat without one. */
    @Test
    fun aHangingFixDoesNotBlock() = runTest {
        val f = engine(State(enabled = true, paused = false), 5.minutes)
        f.fetchHangs = true
        advanceTimeBy(5.minutes)
        tick(f)
        advanceTimeBy(LOCATION_FETCH_TIMEOUT + 6.seconds)
        settle()
        assertEquals(1, f.sent.size, "a heartbeat without a fix")
        f.fetchHangs = false
        f.signals.emit(Signal.StationaryEnter)
        settle()
        tick(f)
        assertEquals(2, f.sent.size, "the engine still handles signals: paused heartbeat sent")
    }

    /** Heartbeat 0: the active heartbeat is off; tracking off: nothing. */
    @Test
    fun zeroOrOffDoesNothing() = runTest {
        val zero = engine(State(enabled = true, paused = false), Duration.ZERO)
        val off = engine(State(enabled = false), 5.minutes)
        advanceTimeBy(10.minutes)
        tick(zero)
        tick(off)
        assertEquals(0, zero.sent.size + off.sent.size + zero.fetches + off.fetches)
    }
}
