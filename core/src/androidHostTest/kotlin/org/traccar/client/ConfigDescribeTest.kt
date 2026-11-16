package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals

/** The log line with the settings in force, which support reads to know how the phone was told to track. */
class ConfigDescribeTest {

    /** Each setting that decides how often a position is taken and sent, in one line; no address and no device ID. */
    @Test
    fun describe() {
        val c = Config(
            serverUrl = "https://track.example.com/", deviceId = "tt27-1-2",
            location = LocationConfig(Accuracy.HIGH, distanceMeters = 0, intervalSeconds = 60, stopTimeoutSeconds = 180, heartbeatIntervalSeconds = 300),
            batchSize = 50,
        )
        assertEquals(
            "Config: HIGH, interval 60 s, distance 0 m, stop detection 180 s, heartbeat 300 s, batch 50",
            c.describe(),
        )
        assertEquals(
            "Config: HIGH, interval 60 s, distance 0 m, stop detection off, heartbeat 300 s, batch 50, no buffer",
            c.copy(location = c.location.copy(stopDetection = false), buffer = false).describe(),
        )
    }
}
