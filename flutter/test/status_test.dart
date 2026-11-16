import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('status events from Android become TrackerStatus values, missing fields as null or false', () async {
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
          'lastFixTime': 1700000000500,
          'lastUploadTime': 1700000001000,
          'lastUploadCode': 200,
          'lastSentTime': 1700000001000,
          'waitingForNetwork': true,
          'noAnswerSince': 1700000002000,
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
    expect(events[0].lastFixTime, 1700000000500);
    expect(events[0].waitingForNetwork, isTrue);
    expect(events[0].noAnswerSince, 1700000002000);
    expect(events[1].enabled, isFalse);
    expect(events[1].lastSentTime, isNull);
    expect(events[1].pending, isNull);
    expect(events[1].lastFixTime, isNull);
    expect(events[1].waitingForNetwork, isNull);
  });
}
