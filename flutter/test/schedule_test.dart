import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('traccar_client_sdk');

  tearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null));

  test('setSchedule sends the start and stop as milliseconds since the epoch, and the notice; null clears them', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return true;
    });
    final start = DateTime.utc(2027, 6, 25, 2, 52);
    final stop = DateTime.utc(2027, 6, 30, 17);
    expect(await TraccarClientSdk().setSchedule(start: start, stop: stop, noticeTitle: 'T', noticeText: 'Tap to start'), isTrue);
    expect(calls.single.method, 'setSchedule');
    expect(calls.single.arguments, {
      'startAt': start.millisecondsSinceEpoch,
      'stopAt': stop.millisecondsSinceEpoch,
      'noticeTitle': 'T',
      'noticeText': 'Tap to start',
    });
    await TraccarClientSdk().setSchedule();
    expect(calls.last.arguments, {'startAt': null, 'stopAt': null, 'noticeTitle': '', 'noticeText': ''});
  });

  test('a platform without the schedule (iOS answers false) reads as false', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async => false);
    expect(await TraccarClientSdk().setSchedule(start: DateTime(2027)), isFalse);
    expect(await TraccarClientSdk().canStartInBackground(), isFalse);
    expect(await TraccarClientSdk().openExactAlarmSettings(), isFalse);
  });
}
