package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.traccar.client.db.Database
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The filter that decides which fixes are kept (time OR distance OR angle since the last kept one). */
class LocationFilterTest {

    private suspend fun filter(location: LocationConfig): LocationFilter {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val state = StateStore.create(driver)
        return LocationFilter(Config(serverUrl = "https://example.org", deviceId = "test", location = location), state)
    }

    private fun fix(seconds: Long, north: Double = 0.0) =
        Position(latitude = 45.8 + north, longitude = 24.15, accuracy = 5.0, time = seconds * 1000)

    /** Interval only (distance 0): a fix 60 s after the last kept one is kept, one at 30 s is dropped. */
    @Test
    fun intervalOnlyKeepsAFixPerInterval() = runTest {
        val f = filter(LocationConfig(intervalSeconds = 60, distanceMeters = 0))
        assertNotNull(f.process(fix(0)), "the first fix is always kept")
        assertNull(f.process(fix(30)), "30 s after the last kept fix: dropped")
        assertNotNull(f.process(fix(60)), "60 s after: kept")
    }

    /** Distance: a fix 300 m away is kept before the interval is up. */
    @Test
    fun distanceKeepsAFarFixEarly() = runTest {
        val f = filter(LocationConfig(intervalSeconds = 60, distanceMeters = 250))
        assertNotNull(f.process(fix(0)))
        assertNull(f.process(fix(10, north = 0.0009)), "about 100 m: dropped")
        assertNotNull(f.process(fix(20, north = 0.0027)), "about 300 m: kept")
    }

    /** A heartbeat (no coordinates) always passes. */
    @Test
    fun heartbeatAlwaysPasses() = runTest {
        val f = filter(LocationConfig(intervalSeconds = 60, distanceMeters = 0))
        assertNotNull(f.process(fix(0)))
        assertNotNull(f.process(Position(time = 1000, heartbeat = true)))
    }
}
