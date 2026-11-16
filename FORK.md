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
| `TrackerEngine`: active heartbeat, while not paused a fix is taken and sent when nothing went out for `heartbeatIntervalSeconds` | upstream issue #26: a false "moving" left a standing phone silent (distance rule kept fixes back, stationary heartbeat off) | to offer (answers #26) |
