import 'package:flutter/services.dart';

/// Location-accuracy preset. Maps to the SDK's `Accuracy` enum.
enum Accuracy { highest, high, medium, low }

/// Tuning parameters for the location pipeline.
class LocationConfig {
  const LocationConfig({
    this.accuracy = Accuracy.medium,
    this.distanceMeters = 75,
    this.intervalSeconds = 300,
    this.angleDegrees = 0,
    this.stopDetection = true,
    this.stopTimeoutSeconds = 60,
    this.stationaryRadiusMeters = 100,
    this.heartbeatIntervalSeconds = 0,
  });

  final Accuracy accuracy;
  final int distanceMeters;
  final int intervalSeconds;
  final int angleDegrees;
  final bool stopDetection;
  final int stopTimeoutSeconds;
  final int stationaryRadiusMeters;
  final int heartbeatIntervalSeconds;

  Map<String, Object?> _toMap() => {
        'accuracy': accuracy.name.toUpperCase(),
        'distanceMeters': distanceMeters,
        'intervalSeconds': intervalSeconds,
        'angleDegrees': angleDegrees,
        'stopDetection': stopDetection,
        'stopTimeoutSeconds': stopTimeoutSeconds,
        'stationaryRadiusMeters': stationaryRadiusMeters,
        'heartbeatIntervalSeconds': heartbeatIntervalSeconds,
      };
}

/// Foreground-service notification settings (Android only).
class NotificationConfig {
  const NotificationConfig({this.text = 'Location tracking'});

  final String text;

  Map<String, Object?> _toMap() => {'text': text};
}

/// Tracker configuration. Pass to [TraccarClientSdk.setConfig] to install or
/// update.
class Config {
  const Config({
    required this.serverUrl,
    this.headers = const {},
    required this.deviceId,
    this.location = const LocationConfig(),
    this.wakeLock = false,
    this.buffer = true,
    this.preferPlatformProviders = false,
    this.notification = const NotificationConfig(),
    this.batchSize = 1,
    this.promptBatteryOptimization = true,
  });

  final String serverUrl;

  /// Additional HTTP headers, including Authorization. Persisted with the config.
  final Map<String, String> headers;

  final String deviceId;
  final LocationConfig location;

  /// Hold a wakelock while tracking (Android only).
  final bool wakeLock;

  /// When true, persist positions to a local queue and retry on network
  /// failure. When false, attempt a direct upload for each position and
  /// drop it on failure (real-time only).
  final bool buffer;

  /// When true, the Android SDK uses the platform `LocationManager` directly
  /// even when Google Play Services is available. Default `false` picks the
  /// Fused Location Provider when Play Services is present. Ignored on iOS.
  final bool preferPlatformProviders;

  final NotificationConfig notification;

  /// Android: positions sent in one request when several wait in the buffer.
  /// 1 sends each as form fields (OsmAnd); more sends a JSON request with
  /// up to this many (Traccar Client JSON format), which the server must take.
  final int batchSize;

  /// Android: on the first start, open the battery optimization list when the
  /// app is not exempt. False when the app asks for it itself.
  final bool promptBatteryOptimization;

  Map<String, Object?> _toMap() => {
        'serverUrl': serverUrl,
        'headers': headers,
        'deviceId': deviceId,
        'location': location._toMap(),
        'wakeLock': wakeLock,
        'buffer': buffer,
        'preferPlatformProviders': preferPlatformProviders,
        'notification': notification._toMap(),
        'batchSize': batchSize,
        'promptBatteryOptimization': promptBatteryOptimization,
      };
}

/// A single diagnostic log entry.
class LogEntry {
  const LogEntry({required this.time, required this.message});

  /// Epoch milliseconds at which the entry was recorded.
  final int time;
  final String message;
}

/// What the tracker is doing (Android). Times are epoch milliseconds.
class TrackerStatus {
  const TrackerStatus({
    required this.enabled,
    required this.paused,
    this.lastPositionTime,
    this.lastLatitude,
    this.lastLongitude,
    this.lastUploadTime,
    this.lastUploadCode,
    this.lastSentTime,
    this.pending,
  });

  factory TrackerStatus.fromMap(Map<Object?, Object?> m) => TrackerStatus(
        enabled: m['enabled'] as bool? ?? false,
        paused: m['paused'] as bool? ?? false,
        lastPositionTime: (m['lastPositionTime'] as num?)?.toInt(),
        lastLatitude: (m['lastLatitude'] as num?)?.toDouble(),
        lastLongitude: (m['lastLongitude'] as num?)?.toDouble(),
        lastUploadTime: (m['lastUploadTime'] as num?)?.toInt(),
        lastUploadCode: (m['lastUploadCode'] as num?)?.toInt(),
        lastSentTime: (m['lastSentTime'] as num?)?.toInt(),
        pending: (m['pending'] as num?)?.toInt(),
      );

  /// Tracking is on.
  final bool enabled;

  /// Stop detection paused the location updates (the phone stands still).
  final bool paused;

  /// The newest position the tracker kept (a fix that passed the filter, or a heartbeat).
  final int? lastPositionTime;
  final double? lastLatitude;
  final double? lastLongitude;

  /// The newest upload attempt and its HTTP status; 0 when there was no answer.
  final int? lastUploadTime;
  final int? lastUploadCode;

  /// The newest upload the server took (2xx).
  final int? lastSentTime;

  /// Positions kept on the phone that the server has not taken yet; null
  /// until the SDK counted its queue.
  final int? pending;
}

/// Entry point for the Traccar Client SDK Flutter plugin.
class TraccarClientSdk {
  static const MethodChannel _channel = MethodChannel('traccar_client_sdk');
  static const EventChannel _statusChannel = EventChannel('traccar_client_sdk/status');

  /// The tracker's status as it changes, the current one first. Android only for now.
  Stream<TrackerStatus> get status => _statusChannel
      .receiveBroadcastStream()
      .map((e) => TrackerStatus.fromMap(e as Map<Object?, Object?>));

  /// Initializes the SDK with [config] if it isn't already initialized.
  /// Idempotent — subsequent calls return the existing tracker without
  /// touching its config. Call once at app startup to seed defaults; use
  /// [setConfig] to change settings on a running tracker.
  Future<void> init(Config config) =>
      _channel.invokeMethod<void>('init', config._toMap());

  /// Replaces the running tracker's configuration with [config]. Requires
  /// that [init] (or a prior session) has installed a tracker. Throws a
  /// [PlatformException] otherwise.
  Future<void> setConfig(Config config) =>
      _channel.invokeMethod<void>('setConfig', config._toMap());

  /// Starts background location tracking. Requires that the SDK has been
  /// initialized (via [init] in this session, or via persisted config from
  /// a previous one). Throws a [PlatformException] if required permissions
  /// were denied or no config has ever been provided.
  Future<void> start() => _channel.invokeMethod<void>('start');

  /// Stops tracking.
  Future<void> stop() => _channel.invokeMethod<void>('stop');

  /// Requests a single position fix and uploads it to the server. Returns
  /// whether the upload succeeded. Works independently of [start] / [stop].
  /// Requires that [setConfig] has been called.
  ///
  /// Prompts for location permission if not already granted and throws a
  /// [PlatformException] when permission is denied (matching [start]). A
  /// `false` return means a fix or upload failure, not a permission problem.
  ///
  /// Pass [alarm] (e.g. `"sos"`) to tag the upload with the Traccar `alarm`
  /// protocol field. The one-off path does not buffer — a failed upload is
  /// not retried and the alarm is lost.
  Future<bool> requestPosition({String? alarm}) async {
    final result = await _channel.invokeMethod<bool>(
      'requestPosition',
      {'alarm': alarm},
    );
    return result ?? false;
  }

  /// Android: shows [title] and [text] in the tracking notification while
  /// tracking runs (the whole text, also on the lock screen; a tap opens the
  /// app). After a stop the config's notification text is used again. Returns
  /// false when tracking is not running or notifications are not allowed, and
  /// always on iOS, which has no tracking notification.
  Future<bool> updateNotification({String? title, required String text}) async {
    final result = await _channel.invokeMethod<bool>(
      'updateNotification',
      {'title': title, 'text': text},
    );
    return result ?? false;
  }

  /// Returns whether tracking is currently active.
  Future<bool> isTracking() async {
    final result = await _channel.invokeMethod<bool>('isTracking');
    return result ?? false;
  }

  /// Returns recent diagnostic entries, oldest first.
  Future<List<LogEntry>> getLogs() async {
    final raw =
        await _channel.invokeListMethod<Map<dynamic, dynamic>>('getLogs');
    if (raw == null) return const [];
    return raw
        .map((m) => LogEntry(
              time: m['time'] as int,
              message: m['message'] as String,
            ))
        .toList(growable: false);
  }

  /// Clears all stored diagnostic entries.
  Future<void> clearLogs() => _channel.invokeMethod<void>('clearLogs');
}
