# Verweil

An open-source location tracking client for [Dawarich](https://github.com/Freika/dawarich), for Android and later iOS.

Plain tracking apps send every raw fix to Dawarich. Indoors those fixes drift by up to several hundred metres,
and Dawarich adds the drift up as distance: you sit at your desk all day and still "walk" kilometres.
Verweil (German for *to linger*) uses the idea that makes Google Maps Timeline clean.
It decides on the phone whether you are **staying** or **moving**.
Movement becomes a track; a stay becomes one anchor point and a Dawarich visit.

- [Concept](docs/concept.md): the problem, what Google does, the state machine, and the Dawarich output
- [Architecture](docs/architecture.md): modules, tech stack, testing and build

## Status

Early prototype.

| Part | State |
|---|---|
| Stay/move state machine (`core/engine`) | First version with tests; thresholds not yet tuned on real days |
| Dawarich upload: Overland points and visits, persistent outbox | Done, tested against mocks |
| Sign-in: Dawarich Cloud (email, 2FA), self-hosted (QR code, manual setup with proxy headers) | Done, tested against mocks |
| Raw event recording (JSONL) and replay | Done |
| Android: foreground service, fused location, activity recognition, upload worker, settings UI | First version, not yet tried on a device |
| Kalman smoothing, anchor refinement, Wi-Fi place memory | Planned |
| iOS app | Planned; `core` already compiles for iOS |

## Build

Needs JDK 21 and the Android SDK.

```sh
./gradlew :core:jvmTest              # tests
./gradlew :androidApp:assembleDebug  # APK in androidApp/build/outputs/apk/debug/
```

## Setup on the phone

1. Install the debug APK.
2. Sign in, the same ways the official Dawarich app offers:
   - **Dawarich Cloud:** email and password; accounts with two-factor authentication get a code step.
   - **Self-hosted:** in Dawarich, open Account → API access. Scan the QR code there, or enter the server URL
     and API key by hand. Manual setup also takes custom headers for servers behind an authenticating
     reverse proxy (Cloudflare Access, Pangolin).
3. Grant location and activity access, then "Allow location all the time".
4. Start tracking. Disable other trackers that send to the same Dawarich account.
