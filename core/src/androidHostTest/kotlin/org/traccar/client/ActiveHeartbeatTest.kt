package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
 * The active heartbeat (upstream issue #26): while not paused, when nothing went out for the heartbeat interval,
 * the engine takes a fix and sends it, so a phone that wrongly left the stationary state is not silent.
 */
class ActiveHeartbeatTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    private class Fakes {
        val positions = MutableSharedFlow<Position>(extraBufferCapacity = 16)
        val sent = mutableListOf<Position>()
        var fetches = 0
    }

    private suspend fun TestScope.engine(state: State, heartbeat: Duration): Fakes {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        Database.Schema.create(driver)
        val store = StateStore.create(driver)
        store.update { state }
        val fakes = Fakes()
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
                    return Position(latitude = 45.8, longitude = 24.15, accuracy = 5.0, time = testScheduler.currentTime)
                }
            },
            signalSources = listOf(object : SignalSource {
                override val signals = emptyFlow<Signal>()
            }),
            processors = emptyList(),
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
        runCurrent()
        return fakes
    }

    /** Moving state, no fix for 5 minutes (the #26 case): one fix is taken and sent at 5 min, the next at 10. */
    @Test
    fun silentWhileActiveSendsAFix() = runTest {
        val f = engine(State(enabled = true, paused = false), 5.minutes)
        advanceTimeBy(5.minutes - 1.seconds)
        runCurrent()
        assertEquals(0, f.sent.size, "before 5 minutes: nothing")
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, f.sent.size, "at 5 minutes: one fix")
        assertTrue(f.sent[0].latitude != null, "a real fix, with coordinates")
        advanceTimeBy(5.minutes)
        runCurrent()
        assertEquals(2, f.sent.size, "5 minutes later: the next one")
    }

    /** Positions every minute: the active heartbeat never fires. */
    @Test
    fun regularPositionsNeedNoHeartbeat() = runTest {
        val f = engine(State(enabled = true, paused = false), 5.minutes)
        repeat(12) {
            advanceTimeBy(1.minutes)
            f.positions.emit(Position(latitude = 45.8, longitude = 24.15, accuracy = 5.0, time = testScheduler.currentTime))
            runCurrent()
        }
        assertEquals(12, f.sent.size)
        assertEquals(0, f.fetches, "no extra fix taken")
    }

    /** Paused: the stationary heartbeat is in charge, the active one stays quiet. */
    @Test
    fun pausedLeavesItToTheStationaryHeartbeat() = runTest {
        val f = engine(State(enabled = true, paused = true), 5.minutes)
        advanceTimeBy(16.minutes)
        runCurrent()
        assertEquals(0, f.fetches)
    }

    /** Tracking off, or heartbeat 0: nothing. */
    @Test
    fun offOrZeroDoesNothing() = runTest {
        val off = engine(State(enabled = false), 5.minutes)
        val zero = engine(State(enabled = true), Duration.ZERO)
        advanceTimeBy(16.minutes)
        runCurrent()
        assertEquals(0, off.fetches + zero.fetches)
    }
}
