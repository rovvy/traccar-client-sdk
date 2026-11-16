package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The battery profiles: which profile the SDK picks from the battery level, charging and battery saver, with the
 * levels to leave a profile above the levels to enter it, and at least 10 minutes between two switches; and the
 * interval and heartbeat the tracker then runs with.
 */
class BatteryProfilesTest {

    private val eco = BatteryProfile("eco", enterPercent = 30, leavePercent = 40, intervalSeconds = 120, heartbeatIntervalSeconds = 600, withBatterySaver = true)
    private val critical = BatteryProfile("critical", enterPercent = 15, leavePercent = 22, intervalSeconds = 300, heartbeatIntervalSeconds = 600)
    private val profiles = listOf(eco, critical)
    private val hold = 600_000L
    private val now = 10_000_000L

    private fun pick(current: String?, level: Int?, charging: Boolean = false, saver: Boolean = false, since: Long = 0L) =
        chooseBatteryProfile(profiles, current, BatteryReading(level, charging, saver), since, now, hold)

    /** Above 30%: no profile; 30% and below: eco; 15% and below: critical (the lower level wins). */
    @Test
    fun levelsEnterTheProfiles() {
        assertEquals(null, pick(null, 31))
        assertEquals("eco", pick(null, 30))
        assertEquals("eco", pick(null, 16))
        assertEquals("critical", pick(null, 15))
        assertEquals("critical", pick("eco", 5))
    }

    /** Eco stays until 40% and critical until 22%; at 22% critical goes back to eco, at 40% eco goes off. */
    @Test
    fun leavingNeedsAHigherLevel() {
        assertEquals("eco", pick("eco", 39))
        assertEquals(null, pick("eco", 40))
        assertEquals("critical", pick("critical", 21))
        assertEquals("eco", pick("critical", 22))
    }

    /** Charging means no profile at once, also within the 10 minutes after a switch. */
    @Test
    fun chargingEndsTheProfileAtOnce() {
        assertEquals(null, pick("critical", 10, charging = true, since = now - 1_000))
    }

    /** Battery saver turns eco on at any level (not critical), unless the phone charges. */
    @Test
    fun batterySaverTurnsEcoOn() {
        assertEquals("eco", pick(null, 80, saver = true))
        assertEquals(null, pick(null, 80, charging = true, saver = true))
        assertEquals("critical", pick(null, 12, saver = true))
    }

    /** A switch less than 10 minutes after the one before waits; after 10 minutes it goes ahead. */
    @Test
    fun tenMinutesBetweenSwitches() {
        assertEquals("eco", pick("eco", 10, since = now - hold + 1))
        assertEquals("critical", pick("eco", 10, since = now - hold))
        assertEquals(null, pick(null, 20, since = now - 60_000))
    }

    /** An unknown level changes nothing; no profiles in the config means none; a profile no longer in the config goes. */
    @Test
    fun unknownLevelAndRemovedProfiles() {
        assertEquals("eco", pick("eco", null))
        assertEquals(null, chooseBatteryProfile(emptyList(), "eco", BatteryReading(10, false, false), 0, now, hold))
        assertEquals("eco", chooseBatteryProfile(listOf(eco), "critical", BatteryReading(10, false, false), now, now, hold))
    }

    /** The tracker runs with the active profile's interval and heartbeat; everything else stays as the app gave it. */
    @Test
    fun appliedConfig() {
        val config = Config("https://example.com", "1", LocationConfig(intervalSeconds = 60, distanceMeters = 0, heartbeatIntervalSeconds = 300), batteryProfiles = profiles)
        assertEquals(config, config.applied())
        val critical = config.copy(activeProfile = "critical").applied()
        assertEquals(300, critical.location.intervalSeconds)
        assertEquals(600, critical.location.heartbeatIntervalSeconds)
        assertEquals(0, critical.location.distanceMeters)
        assertEquals(config, config.copy(activeProfile = "gone").applied().copy(activeProfile = null))
    }
}
