package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.traccar.client.db.Database
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The status an app reads instead of the log: on/paused, the newest kept position and how many wait to be sent. */
class TrackerStatusTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    // the status is one per process: other tests leave theirs behind
    @BeforeTest
    fun reset() = resetStatus()

    /** A kept position shows as the newest one and waits in the queue until the upload goes through. */
    @Test
    fun keptPositionAndQueue() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        Database.Schema.create(driver)
        val store = StateStore.create(driver)
        store.update { State(enabled = true) }
        val queue = DatabaseQueue(driver)
        val positions = MutableSharedFlow<Position>(extraBufferCapacity = 4)
        val release = CompletableDeferred<Unit>()
        TrackerEngine(
            stateStore = store,
            queue = queue,
            network = object : NetworkMonitor {
                override val isOnline = MutableStateFlow(true)
            },
            locationSource = object : LocationSource {
                override val positions = positions
                override suspend fun fetchOnce(): Position? = null
            },
            signalSources = listOf(object : SignalSource {
                override val signals = emptyFlow<Signal>()
            }),
            processors = emptyList(),
            uploader = object : Uploader {
                override suspend fun upload(position: Position): Boolean {
                    release.await() // the server answers only when the test says so
                    return true
                }
            },
            buffer = true,
            scope = backgroundScope,
            now = { testScheduler.currentTime },
        )
        waitFor { trackerStatus.value.pending == 0 }
        assertTrue(trackerStatus.value.enabled, "on")
        positions.emit(Position(latitude = 46.1, longitude = 24.15, accuracy = 5.0, time = 1_000))
        waitFor { trackerStatus.value.pending == 1 }
        assertEquals(1_000, trackerStatus.value.lastPositionTime)
        assertEquals(46.1, trackerStatus.value.lastLatitude)
        release.complete(Unit)
        waitFor { trackerStatus.value.pending == 0 }
    }

    /** A fix sets the time of the last fix, also one a heartbeat fetched; a heartbeat without a fix leaves it (no GPS). */
    @Test
    fun lastFixTime() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        Database.Schema.create(driver)
        val store = StateStore.create(driver)
        store.update { State(enabled = true, paused = true) } // standing still: a tick sends a heartbeat
        val positions = MutableSharedFlow<Position>(extraBufferCapacity = 4)
        val signals = MutableSharedFlow<Signal>(extraBufferCapacity = 4)
        var fix: Position? = null
        TrackerEngine(
            stateStore = store,
            queue = DatabaseQueue(driver),
            network = object : NetworkMonitor {
                override val isOnline = MutableStateFlow(true)
            },
            locationSource = object : LocationSource {
                override val positions = positions
                override suspend fun fetchOnce(): Position? = fix
            },
            signalSources = listOf(object : SignalSource {
                override val signals = signals
            }),
            processors = emptyList(),
            uploader = object : Uploader {
                override suspend fun upload(position: Position) = true
            },
            buffer = true,
            scope = backgroundScope,
            now = { testScheduler.currentTime },
        )
        waitFor { trackerStatus.value.pending == 0 }
        assertEquals(null, trackerStatus.value.lastFixTime)

        advanceTimeBy(5_000)
        positions.emit(Position(latitude = 46.1, longitude = 24.15, accuracy = 5.0, time = 1_000))
        waitFor { trackerStatus.value.lastFixTime != null }
        val first = trackerStatus.value.lastFixTime!!

        // a heartbeat without a fix is kept and sent, but the last fix stays where it was
        advanceTimeBy(60_000)
        signals.emit(Signal.HeartbeatTick)
        waitFor { (trackerStatus.value.lastPositionTime ?: 0) > 1_000 }
        assertEquals(first, trackerStatus.value.lastFixTime)

        // a heartbeat that fetched a fix moves it
        advanceTimeBy(60_000)
        fix = Position(latitude = 46.2, longitude = 24.2, accuracy = 5.0, time = 2_000)
        signals.emit(Signal.HeartbeatTick)
        waitFor { (trackerStatus.value.lastFixTime ?: 0) > first }
        assertTrue(trackerStatus.value.lastFixTime!! >= first + 120_000, "the heartbeat's own time")
    }

    /** The queue and state store use Dispatchers.IO (real threads): wait a little for them, never forever. */
    private suspend fun kotlinx.coroutines.test.TestScope.waitFor(condition: () -> Boolean) {
        repeat(200) {
            runCurrent()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition not met within 2 s")
    }
}
