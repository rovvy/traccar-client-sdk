package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals

/** The log line with the settings in force, which support reads to know how the phone was told to track. */
class ConfigDescribeTest {

    /**
     * Each setting that decides how often a position is taken and sent, in one line, as the SDK uses them; no address
     * and no device ID. Expects the interval and distance after `effective` (a distance turns the interval off,
     * HIGHEST turns both off), the batch within 1 to 500, and an angle, the platform provider and a wake lock only
     * when set.
     */
    @Test
    fun describe() {
        val c = Config(
            serverUrl = "https://track.example.com/", deviceId = "tt27-1-2",
            location = LocationConfig(Accuracy.HIGH, distanceMeters = 0, intervalSeconds = 60, stopTimeoutSeconds = 180, heartbeatIntervalSeconds = 300),
            batchSize = 50,
        )
        assertEquals(
            "Config: HIGH, interval 60 s, distance 0 m, stop detection 180 s, radius 100 m, heartbeat 300 s, batch 50",
            c.describe(),
        )
        assertEquals(
            "Config: HIGH, interval 60 s, distance 0 m, stop detection off, heartbeat 300 s, batch 50, no buffer",
            c.copy(location = c.location.copy(stopDetection = false), buffer = false).describe(),
        )
        assertEquals(
            "Config: HIGH, interval 0 s, distance 250 m, stop detection 180 s, radius 100 m, heartbeat 300 s, batch 500, " +
                "angle 30°, platform provider, wake lock",
            c.copy(location = c.location.copy(distanceMeters = 250, angleDegrees = 30), batchSize = 1000,
                preferPlatformProviders = true, wakeLock = true).describe(),
        )
        assertEquals(
            "Config: HIGHEST, interval 0 s, distance 0 m, stop detection 180 s, radius 100 m, heartbeat 300 s, batch 1",
            c.copy(location = c.location.copy(accuracy = Accuracy.HIGHEST), batchSize = 0).describe(),
        )
    }
}
