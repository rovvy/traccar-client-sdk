import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('status events from Android become TrackerStatus values, missing fields as null or 0', () async {
    const channel = EventChannel('traccar_client_sdk/status');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockStreamHandler(
      channel,
      MockStreamHandler.inline(onListen: (args, sink) {
        sink.success({
          'enabled': true,
          'paused': false,
          'lastPositionTime': 1700000000000,
          'lastLatitude': 45.8,
          'lastLongitude': 24.15,
          'lastUploadTime': 1700000001000,
          'lastUploadCode': 200,
          'lastSentTime': 1700000001000,
          'pending': 2,
        });
        sink.success({'enabled': false});
      }),
    );
    final events = await TraccarClientSdk().status.take(2).toList();
    expect(events[0].enabled, isTrue);
    expect(events[0].lastLatitude, 45.8);
    expect(events[0].lastUploadCode, 200);
    expect(events[0].pending, 2);
    expect(events[1].enabled, isFalse);
    expect(events[1].lastSentTime, isNull);
    expect(events[1].pending, 0);
  });
}
