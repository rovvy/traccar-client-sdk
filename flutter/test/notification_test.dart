import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('updateNotification sends title and text and returns what the platform answers (false when none)', () async {
    const channel = MethodChannel('traccar_client_sdk');
    final calls = <MethodCall>[];
    Object? answer = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return answer;
    });
    expect(await TraccarClientSdk().updateNotification(title: 'Tracking on', text: 'Last sent 10:42'), isTrue);
    expect(calls.single.method, 'updateNotification');
    expect(calls.single.arguments, {'title': 'Tracking on', 'text': 'Last sent 10:42'});
    answer = null;
    expect(await TraccarClientSdk().updateNotification(text: 'x'), isFalse);
  });
}
