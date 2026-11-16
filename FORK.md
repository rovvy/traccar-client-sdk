# Transylvania Trail fork of the Traccar Client SDK

This is a fork of [traccar/traccar-client-sdk](https://github.com/traccar/traccar-client-sdk) (Apache 2.0)
for the TransyTrail rider app ([rovvy/TransylvaniaTrail-mobile-app](https://github.com/rovvy/TransylvaniaTrail-mobile-app)).
Android first: the app builds the Android code from this repository; the iPhone build still uses the official
release (the Flutter plugin's `ios/traccar_client_sdk/Package.swift` points at the official tag).

## How the app uses it

- `pubspec.yaml` in the app takes `traccar_client_sdk` from this repository (`path: flutter`, a fixed `ref`).
- The app's `android/settings.gradle.kts` sees the `core/` folder next to the plugin and builds
  `org.traccar:traccar-client-sdk` from `:core` here (Gradle `includeBuild`), instead of downloading it from Maven Central.
- Going back to the official SDK is one change in the app's `pubspec.yaml` (the pub.dev version).

## Rules

- `main` is the official code plus our changes. Each change is one pull request here, small, with Kotlin tests
  where it has rules, and is offered upstream as its own pull request from a branch off the official release tag.
- A change upstream accepts is dropped from our list below at the next merge.
- An official release: merge its tag into `main`, run the tests, bump the app's `ref`, check on a phone.

## Our changes (against the official 1.1.4)

| Change | Why | Upstream |
|---|---|---|
| `settings.gradle.kts`: the sample app only when built on its own | the app's build needs only `:core` | not offered (ours only) |
| Kotlin unit tests on the build PC (`withHostTest`, `core/src/androidHostTest`), workflow `Tests` | rules we change get tests | can be offered (upstream has no tests yet) |
| `LocationFilter`: a fix up to a tenth of the interval (at most 1 s) early passes the time rule | Android delivers fixes at the interval with jitter; an early one was dropped and the gap doubled (60 s became 120 s) | to offer |
| `TrackerEngine` + `AlarmHeartbeatTrigger`: active heartbeat. The heartbeat alarm now ticks while tracking, not only while paused; moving, a tick sends a position only when nothing went out for 90% of `heartbeatIntervalSeconds` (the newest fix the filter dropped, else one new fix, at most 35 s wait). Only with stop detection. Android only (iOS still ticks only while paused). A standing phone is silent at most about twice the interval | upstream issue #26: a false "moving" left a standing phone silent (distance rule kept fixes back, stationary heartbeat off); an alarm, not a timer, because a timer stops while the phone sleeps | to offer (answers #26) |
| `TrackerStatus` + `trackerStatus` (core), `TraccarClientSdk.status` (Flutter, Android): on/paused, newest kept position, newest upload and its HTTP code, newest accepted upload, positions waiting on the phone | the app read its health from SDK log lines; a stream says the same without parsing text | to offer (state listener) |
| `updateTrackerNotification` (core, Android), `TraccarClientSdk.updateNotification` (Flutter): the app sets the tracking notification's title and text while tracking runs (whole text, lock screen too, a tap opens the app); iOS answers false | the app wrote over the SDK's notification by its private id and channel, and lost the race when the service rebuilt it; the plugin exposes the core as `api`, so an app's Activity can call it too | to offer (notification API) |
