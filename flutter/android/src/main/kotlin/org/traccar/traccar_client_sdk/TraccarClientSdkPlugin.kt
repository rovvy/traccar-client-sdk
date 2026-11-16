package org.traccar.traccar_client_sdk

import android.content.Context
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.traccar.client.Accuracy
import org.traccar.client.Config
import org.traccar.client.LocationConfig
import org.traccar.client.NotificationConfig
import org.traccar.client.requestPosition
import org.traccar.client.BatteryProfile
import org.traccar.client.sharedTracker
import org.traccar.client.startTracking
import org.traccar.client.trackerStatus
import org.traccar.client.updateTrackerNotification

class TraccarClientSdkPlugin :
    FlutterPlugin,
    MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var statusChannel: EventChannel
    private var statusJob: Job? = null
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "traccar_client_sdk")
        channel.setMethodCallHandler(this)
        // the tracker's status as it changes (TrackerStatus); the first event is the current status
        statusChannel = EventChannel(binding.binaryMessenger, "traccar_client_sdk/status")
        statusChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                statusJob?.cancel()
                statusJob = scope.launch {
                    // a tracker kept from an earlier run starts writing its status; a failure here only means no
                    // tracker yet, and must not end the stream or crash the app
                    try { sharedTracker() } catch (e: CancellationException) { throw e } catch (e: Throwable) { }
                    trackerStatus.collect { s ->
                        events.success(
                            mapOf(
                                "enabled" to s.enabled,
                                "paused" to s.paused,
                                "lastPositionTime" to s.lastPositionTime,
                                "lastLatitude" to s.lastLatitude,
                                "lastLongitude" to s.lastLongitude,
                                "lastFixTime" to s.lastFixTime,
                                "lastUploadTime" to s.lastUploadTime,
                                "lastUploadCode" to s.lastUploadCode,
                                "lastSentTime" to s.lastSentTime,
                                "waitingForNetwork" to s.waitingForNetwork,
                                "noAnswerSince" to s.noAnswerSince,
                                "pending" to s.pending,
                                "profile" to s.profile,
                            ),
                        )
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                statusJob?.cancel()
                statusJob = null
            }
        })
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "init" -> scope.launchHandler(result) {
                sharedTracker(parseConfig(call.arguments as Map<*, *>))
                null
            }
            "setConfig" -> scope.launchHandler(result) {
                val tracker = sharedTracker()!!
                // the battery profile in use stays (the SDK chose it); the app's config does not carry it
                val given = parseConfig(call.arguments as Map<*, *>)
                tracker.updateConfig(given.copy(activeProfile = tracker.config.activeProfile?.takeIf { name -> given.batteryProfiles.any { it.name == name } }))
                null
            }
            "start" -> scope.launchHandler(result) {
                sharedTracker()!!.startTracking(context)
                null
            }
            "stop" -> scope.launchHandler(result) {
                sharedTracker()?.stop()
                null
            }
            "requestPosition" -> scope.launchHandler(result) {
                val alarm = (call.arguments as? Map<*, *>)?.get("alarm") as? String
                sharedTracker()?.requestPosition(context, alarm) ?: false
            }
            "isTracking" -> scope.launchHandler(result) {
                sharedTracker()?.state?.value?.enabled ?: false
            }
            "getLogs" -> scope.launchHandler(result) {
                sharedTracker()?.getLogs()?.map {
                    mapOf("time" to it.time, "message" to it.message)
                } ?: emptyList<Map<String, Any>>()
            }
            "updateNotification" -> {
                val args = call.arguments as? Map<*, *>
                result.success(updateTrackerNotification(context, args?.get("title") as? String, args?.get("text") as? String ?: ""))
            }
            "setNoticeTexts" -> {
                val a = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                val keys = listOf(
                    "okTitle", "okText", "waitingTitle", "waitingText", "noInternetTitle", "noInternetText",
                    "noInternetTextNone", "noGpsTitle", "noGpsText", "silentTitle", "silentText", "refusedTitle", "refusedText",
                )
                // a missing or empty text would show a blank line on the lock screen, and be kept: refuse the whole set
                val missing = keys.filter { (a[it] as? String).isNullOrBlank() }
                if (missing.isNotEmpty()) {
                    result.error("bad_texts", "missing: ${missing.joinToString()}", null)
                    return
                }
                fun s(key: String) = a[key] as String
                val texts = org.traccar.client.NoticeTexts(
                    okTitle = s("okTitle"), okText = s("okText"),
                    waitingTitle = s("waitingTitle"), waitingText = s("waitingText"),
                    noInternetTitle = s("noInternetTitle"), noInternetText = s("noInternetText"),
                    noInternetTextNone = s("noInternetTextNone"),
                    noGpsTitle = s("noGpsTitle"), noGpsText = s("noGpsText"),
                    silentTitle = s("silentTitle"), silentText = s("silentText"),
                    refusedTitle = s("refusedTitle"), refusedText = s("refusedText"),
                    limitMinutes = (a["limitMinutes"] as? Number)?.toInt() ?: 10,
                    noAnswerSeconds = (a["noAnswerSeconds"] as? Number)?.toInt() ?: 30,
                )
                result.success(org.traccar.client.setTrackerNoticeTexts(context, texts))
            }
            "clearNotification" -> result.success(org.traccar.client.clearTrackerNotification(context))
            "clearLogs" -> scope.launchHandler(result) {
                sharedTracker()?.clearLogs()
                null
            }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        statusChannel.setStreamHandler(null)
        scope.cancel()
    }

    private fun CoroutineScope.launchHandler(result: Result, block: suspend () -> Any?) {
        launch {
            try {
                result.success(block())
            } catch (e: Throwable) {
                result.error(e::class.simpleName ?: "error", e.message, null)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConfig(args: Map<*, *>): Config {
        val location = args["location"] as Map<*, *>
        val notification = args["notification"] as Map<*, *>
        return Config(
            serverUrl = args["serverUrl"] as String,
            headers = args["headers"] as? Map<String, String> ?: emptyMap(),
            deviceId = args["deviceId"] as String,
            location = LocationConfig(
                accuracy = Accuracy.valueOf(location["accuracy"] as String),
                distanceMeters = (location["distanceMeters"] as Number).toInt(),
                intervalSeconds = (location["intervalSeconds"] as Number).toInt(),
                angleDegrees = (location["angleDegrees"] as Number).toInt(),
                stopDetection = location["stopDetection"] as Boolean,
                stopTimeoutSeconds = (location["stopTimeoutSeconds"] as Number).toInt(),
                stationaryRadiusMeters = (location["stationaryRadiusMeters"] as Number).toInt(),
                heartbeatIntervalSeconds = (location["heartbeatIntervalSeconds"] as Number).toInt(),
            ),
            wakeLock = args["wakeLock"] as Boolean,
            buffer = args["buffer"] as Boolean,
            preferPlatformProviders = args["preferPlatformProviders"] as Boolean,
            notification = NotificationConfig(text = notification["text"] as String),
            // fork options; missing (an older Dart side) means the SDK's defaults
            batchSize = (args["batchSize"] as? Number)?.toInt() ?: 1,
            promptBatteryOptimization = args["promptBatteryOptimization"] as? Boolean ?: true,
            batteryProfiles = (args["batteryProfiles"] as? List<*>)?.mapNotNull { p ->
                (p as? Map<*, *>)?.let {
                    BatteryProfile(
                        name = it["name"] as String,
                        enterPercent = (it["enterPercent"] as Number).toInt(),
                        leavePercent = (it["leavePercent"] as Number).toInt(),
                        intervalSeconds = (it["intervalSeconds"] as Number).toInt(),
                        heartbeatIntervalSeconds = (it["heartbeatIntervalSeconds"] as Number).toInt(),
                        withBatterySaver = it["withBatterySaver"] as? Boolean ?: false,
                    )
                }
            } ?: emptyList(),
        )
    }
}
