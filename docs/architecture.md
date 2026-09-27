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
| `track` | `TrackSimplifier`, streaming Douglas–Peucker, and `TrackSmoother`, a constant-velocity Kalman filter with Doppler velocity (off by default, for replays); both part of `EngineState` |
| `journal` | `Journal`: the engine's decisions as stays and moves with their distance; kept 30 days on the device |
| `timeline` | `TimelineEntry` (stay or move with mode of travel), `mergeTimeline`: Dawarich's timeline of a day followed by what the phone recognised since; `TimelineCache` keeps every fetched day for offline use |
| `tracking` | `Tracker`: the engine with a memory. Restores the saved state and stores it with the uploads of a step in one transaction; steps without uploads save on mode changes or every 30 s |
| `geo` | Distance and accuracy-weighted median |
| `upload` | Mapping engine output to Dawarich items; `Outbox`, the persistent upload queue, which sets aside data the server refuses |
| `dawarich` | `DawarichClient` for the Overland batch API and the timeline API (Dawarich 1.3 and later); no visits, Dawarich detects them; `DawarichAuth` for sign-in (mobile auth API, API key check, QR code) |
| `replay` | `EventLog` (JSONL recording format) and `replay()` for tuning on recorded days; on the JVM, `ReplayTool` compares track pipeline variants as GeoJSON (`./gradlew :core:replay`) |

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
| `TrackingService` | Foreground service (type `location`). Feeds all events through one channel into a `Tracker`, records them, and schedules uploads. Fits the sensors to the mode: high accuracy every second while moving, balanced every 5 min with a geofence while staying. Stopping from the app or the notification closes an open stay |
| `ActivityTransitionReceiver` | Turns Play Services activity transitions into `ActivityChange` events |
| `StayGeofence`, `GeofenceReceiver` | Geofence of radius `R_exit` around the stay anchor; an exit becomes a `GeofenceExit` event |
| `WifiScanner` | Reads scan results as salted BSSID hashes, asks for a scan at most every 30 min while not moving |
| `TrackingWatchdog` | WorkManager job every 15 min while tracking is on: restarts a service that Android or the manufacturer stopped, or notifies once when it cannot (permission taken away, no "Allow all the time") |
| `BootReceiver` | Resumes tracking after a reboot or an app update through the watchdog, if it was on |
| `Recordings` | Raw events as `Android/data/de.lukasrunge.verweil/files/recordings/<date>.jsonl`; on in debug builds, a setting otherwise; pruned after 30 days; shared through a `FileProvider` |
| `UploadWorker` | WorkManager job: flushes the outbox when online, batched with a 2 min delay, exponential backoff; stores the outcome and notifies when Dawarich refuses the key |
| `Notifications` | The ongoing tracking notification (state and "since") and alerts: tracking interrupted, API key refused |
| `PlaceNames` | Names stays in the timeline with the phone's `Geocoder`, if the user allows it |
| `Settings` | DataStore: server URL, API key, account email, custom headers, device ID, raw recording, place names, tracking on/off, setup done, upload outcome, Wi-Fi hash salt |
| `ui` | Compose, one activity. `LoginScreen` (Cloud with email and 2FA, self-hosted with QR code or manual setup), `SetupScreen` (the permissions one by one, with reasons), `HomeScreen` (state, what needs fixing, any day's timeline from Dawarich with the phone's newest entries, upload), `SettingsScreen`, `DiagnosticsScreen` (engine, sensors, queue, recordings). Theme: moss for staying, ochre for moving, Bricolage Grotesque for headings; English and German |

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
- Recorded real days: pull `recordings/*.jsonl` from the phone
  (`adb shell cat /sdcard/Android/data/de.lukasrunge.verweil/files/recordings/<date>.jsonl > day.jsonl`),
  then compare the track pipeline on them with `./gradlew :core:replay --args="day.jsonl --from 13:30 --to 13:40"`.
  Recordings contain where you live; keep them out of the repository. Labelled days should become regression tests.

## Build

```sh
./gradlew :core:jvmTest              # engine, client and outbox tests
./gradlew :core:replay --args="day.jsonl [--from HH:mm] [--to HH:mm] [--truth route.geojson]"
./gradlew :androidApp:assembleDebug  # APK in androidApp/build/outputs/apk/debug/
./gradlew :androidApp:lintDebug
```

Requires JDK 21 and the Android SDK (`sdk.dir` in `local.properties` or `ANDROID_HOME`).
Apple targets compile only on macOS; linking an iOS framework additionally needs Xcode.
CI (`.github/workflows/ci.yml`) runs tests, the debug build and lint on every push and pull request.
