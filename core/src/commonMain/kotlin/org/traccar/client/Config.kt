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
    /**
     * Fewer fixes while the battery is low (Android): the SDK picks a profile by itself from the battery level, charging
     * and battery saver ([chooseBatteryProfile]) and runs with its interval and heartbeat ([applied]). Empty: off.
     */
    val batteryProfiles: List<BatteryProfile> = emptyList(),
    /** The profile in use (its name), set by the SDK when it switches; null: the config's own interval and heartbeat. */
    val activeProfile: String? = null,
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
