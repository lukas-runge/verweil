# Verweil – Architecture

How the [concept](concept.md) maps to code.

## Modules

```
verweil/
├── core/        Kotlin Multiplatform: all logic, shared by Android and iOS
├── androidApp/  Android app: sensors, foreground service, upload worker, minimal UI
└── iosApp/      (later) SwiftUI app with a thin Core Location / Core Motion layer
```

The rule: **decisions live in `core`, platform code only collects sensor events and executes I/O.**
That keeps the engine testable on a desktop JVM and identical on both platforms.

## core

| Package | Content |
|---|---|
| `model` | `SensorEvent` (`Fix`, `ActivityChange`, `WifiScan`, `GeofenceExit`, `Tick`) and `EngineOutput` (`TrackPoint`, `StayStarted`, `StayHeartbeat`, `StayEnded`) |
| `engine` | `StayEngine`, the stay/move state machine; `EngineState`, its serializable memory; `EngineConfig` with its thresholds |
| `place` | Wi-Fi fingerprints and `PlaceMemory`, which recognises known places and learns from every stay; stored in SQLite or in memory for replays |
| `tracking` | `Tracker`: the engine with a memory. Restores the saved state and stores state and uploads of each step in one transaction |
| `geo` | Distance and accuracy-weighted median |
| `upload` | Mapping engine output to Dawarich items; `Outbox`, the persistent upload queue, which sets aside data the server refuses |
| `dawarich` | `DawarichClient` for the Overland batch and visits APIs; `DawarichAuth` for sign-in (mobile auth API, API key check, QR code) |
| `replay` | `EventLog` (JSONL recording format) and `replay()` for tuning on recorded days |

Targets: `android`, `jvm` (tests and desktop replays), `iosArm64`, `iosSimulatorArm64`.

| Concern | Library |
|---|---|
| Async and event streams | kotlinx.coroutines |
| Serialization | kotlinx.serialization |
| HTTP | Ktor client (OkHttp on Android, Darwin on iOS, Java on the JVM) |
| Local database | SQLDelight (schema in `core/src/commonMain/sqldelight`, migrations in `migrations/`) |
| Time | `kotlin.time`, epoch milliseconds in all events |

## Android

| Piece | Role |
|---|---|
| `TrackingService` | Foreground service (type `location`). Feeds all events through one channel into a `Tracker`, records them, and schedules uploads. Fits the sensors to the mode: high accuracy every 5 s while moving, balanced every 5 min with a geofence while staying. Stopping from the app or the notification closes an open stay |
| `ActivityTransitionReceiver` | Turns Play Services activity transitions into `ActivityChange` events |
| `StayGeofence`, `GeofenceReceiver` | Geofence of radius `R_exit` around the stay anchor; an exit becomes a `GeofenceExit` event |
| `WifiScanner` | Reads scan results as salted BSSID hashes, asks for a scan at most every 30 min while not moving |
| `BootReceiver` | Resumes tracking after a reboot or an app update, if it was on |
| `Recorder` | Writes raw events to `Android/data/de.lukasrunge.verweil/files/recordings/<date>.jsonl` |
| `UploadWorker` | WorkManager job: flushes the outbox when online, batched with a 2 min delay, exponential backoff |
| `Settings` | DataStore: server URL, API key, account email, custom headers, device ID, raw recording on/off, tracking on/off, Wi-Fi hash salt |
| `LoginScreen` | Sign-in flow modelled on the official Dawarich app: Cloud with email and 2FA, self-hosted with QR code (Google code scanner) or manual setup |
| `MainActivity` | Compose screen: sign-in until connected, then account, permissions, battery optimization, start/stop, status, upload queue with errors and retry |

Minimum Android 10 (API 29); compile and target SDK 37.
Play Services are required for fused location and activity recognition.

## iOS (planned)

- SwiftUI app. `core` is exported as an XCFramework; SKIE for Swift-friendly flows and suspend functions.
- Sensors: Core Location visit monitoring (`CLVisit`), `CLLocationUpdate.liveUpdates`, `CLBackgroundActivitySession`,
  Core Motion activity. No Wi-Fi scans (not available on iOS).
- `core` already compiles for iOS and provides `platformHttpClient()` and `createDatabase()` there.

## Testing

- `./gradlew :core:jvmTest` runs all `commonTest` and `jvmTest` tests on the JVM.
- Engine tests build synthetic days with `Scenario` (metre grid, activities, Wi-Fi, jitter) and assert on the result,
  including distance as Dawarich would compute it.
- `TrackerTest` kills and restores the tracker after every event and migrates the first database schema.
- Recorded real days: pull `recordings/*.jsonl` from the phone, then run them through `replay()`.
  Labelled days should become regression tests.

## Build

```sh
./gradlew :core:jvmTest              # engine, client and outbox tests
./gradlew :androidApp:assembleDebug  # APK in androidApp/build/outputs/apk/debug/
./gradlew :androidApp:lintDebug
```

Requires JDK 21 and the Android SDK (`sdk.dir` in `local.properties` or `ANDROID_HOME`).
Apple targets compile only on macOS; linking an iOS framework additionally needs Xcode.
CI (`.github/workflows/ci.yml`) runs tests, the debug build and lint on every push and pull request.
