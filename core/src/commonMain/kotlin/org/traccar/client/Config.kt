package org.traccar.client

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val serverUrl: String,
    val deviceId: String,
    val location: LocationConfig = LocationConfig(),
    val wakeLock: Boolean = false,
    val buffer: Boolean = true,
    val preferPlatformProviders: Boolean = false,
    val notification: NotificationConfig = NotificationConfig(),
    val headers: Map<String, String> = emptyMap(),
    /**
     * Positions sent in one request when several wait in the buffer (after a time without network). 1: one request
     * per position, as form fields (the OsmAnd protocol). More: a JSON request `{"device_id", "location": [...]}`
     * (the Traccar Client JSON format) with up to this many (at most 500), oldest first; the server must take that
     * format and keep the whole request when it answers 2xx. Saved with the config: an SDK without this field (the
     * official one before it) cannot read that config back and starts no tracker until the app's next init.
     */
    val batchSize: Int = 1,
    /** Android: on the first start, open the battery optimization list when the app is not exempt. */
    val promptBatteryOptimization: Boolean = true,
) {
    constructor(serverUrl: String, deviceId: String) : this(serverUrl, deviceId, LocationConfig())
}

@Serializable
data class NotificationConfig(
    val text: String = "Location tracking",
)

@Serializable
data class LocationConfig(
    val accuracy: Accuracy = Accuracy.MEDIUM,
    val distanceMeters: Int = 75,
    val intervalSeconds: Int = 300,
    val angleDegrees: Int = 0,
    val stopDetection: Boolean = true,
    val stopTimeoutSeconds: Int = 60,
    val stationaryRadiusMeters: Int = 100,
    val heartbeatIntervalSeconds: Int = 0,
)

@Serializable
enum class Accuracy {
    HIGHEST,
    HIGH,
    MEDIUM,
    LOW,
}

val LocationConfig.effective: LocationConfig
    get() = when {
        accuracy == Accuracy.HIGHEST -> copy(distanceMeters = 0, intervalSeconds = 0)
        distanceMeters > 0 -> copy(intervalSeconds = 0)
        else -> this
    }

/**
 * One log line with the settings that decide how often the phone takes and sends a position, for support: `Config:
 * HIGH, interval 60 s, distance 0 m, stop detection 180 s, heartbeat 300 s, batch 50`. The server address and the
 * device ID are left out (the start's own line has them).
 */
fun Config.describe(): String {
    val l = location
    val stop = if (l.stopDetection) "stop detection ${l.stopTimeoutSeconds} s" else "stop detection off"
    return "Config: ${l.accuracy}, interval ${l.intervalSeconds} s, distance ${l.distanceMeters} m, " +
        "$stop, heartbeat ${l.heartbeatIntervalSeconds} s, batch $batchSize" + if (buffer) "" else ", no buffer"
}
