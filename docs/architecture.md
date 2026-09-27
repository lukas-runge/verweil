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
| `model` | `SensorEvent` (`Fix`, `ActivityChange`, `WifiScan`, `Tick`) and `EngineOutput` (`TrackPoint`, `StayStarted`, `StayEnded`) |
| `engine` | `StayEngine`, the stay/move state machine, and `EngineConfig` with its thresholds |
| `geo` | Distance and accuracy-weighted median |
| `upload` | Mapping engine output to Dawarich items; `Outbox`, the persistent upload queue |
| `dawarich` | `DawarichClient` for the Overland batch and visits APIs |
| `replay` | `EventLog` (JSONL recording format) and `replay()` for tuning on recorded days |

Targets: `android`, `jvm` (tests and desktop replays), `iosArm64`, `iosSimulatorArm64`.

| Concern | Library |
|---|---|
| Async and event streams | kotlinx.coroutines |
| Serialization | kotlinx.serialization |
| HTTP | Ktor client (OkHttp on Android, Darwin on iOS, Java on the JVM) |
| Local database | SQLDelight (schema in `core/src/commonMain/sqldelight`) |
| Time | `kotlin.time`, epoch milliseconds in all events |

## Android

| Piece | Role |
|---|---|
| `TrackingService` | Foreground service (type `location`). Requests fused locations: high accuracy every 5 s, balanced every 60 s while staying. Registers activity transitions, feeds all events through one channel into the engine, records them, and queues the output |
| `ActivityTransitionReceiver` | Turns Play Services activity transitions into `ActivityChange` events |
| `Recorder` | Writes raw events to `Android/data/de.lukasrunge.verweil/files/recordings/<date>.jsonl` |
| `UploadWorker` | WorkManager job: flushes the outbox when online, batched with a 2 min delay, exponential backoff |
| `Settings` | DataStore: server URL, API key, device ID, raw recording on/off |
| `MainActivity` | Compose screen: settings, permissions, start/stop, status |

Minimum Android 10 (API 29); compile and target SDK 37.
Play Services are required for fused location and activity recognition.

## iOS (planned)

- SwiftUI app. `core` is exported as an XCFramework; SKIE for Swift-friendly flows and suspend functions.
- Sensors: Core Location visit monitoring (`CLVisit`), `CLLocationUpdate.liveUpdates`, `CLBackgroundActivitySession`,
  Core Motion activity. No Wi-Fi scans (not available on iOS).
- `core` already compiles for iOS and provides `platformHttpClient()` and `createOutbox()` there.

## Testing

- `./gradlew :core:jvmTest` runs all `commonTest` and `jvmTest` tests on the JVM.
- Engine tests build synthetic days with `Scenario` (metre grid, activities, jitter) and assert on the result,
  including distance as Dawarich would compute it.
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
