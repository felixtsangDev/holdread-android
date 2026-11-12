# 撳住讀 HoldRead · Android

Read by holding a finger on a track and sliding it under each word. Kotlin + Jetpack Compose.

## Modules

- `engine/` – the hold-to-read engine in plain Kotlin (no Android): tokenizing, speed cap,
  skipping, line changes, sessions, streaks. Rules: [`spec/engine.md`](spec/engine.md).
- `app/` – the Compose app.
- `spec/cases/` – JSON finger traces with expected results, shared with iOS.

## Build and test

```sh
./gradlew :engine:test                 # engine + shared JSON cases
./gradlew :app:testDebugUnitTest       # Robolectric UI tests (hold / slide / release) + screenshots
./gradlew :app:assembleDebug           # APK
```

Without the Android SDK or Google's Maven repo, work on the engine with `-PengineOnly=true`.

CI (`.github/workflows/android.yml`) runs all of the above on every push. Each push to `main`
refreshes the **Latest debug build** pre-release with an installable APK; screenshots from the UI
tests are attached to the workflow run.
