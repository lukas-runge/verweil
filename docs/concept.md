# Verweil – Concept

Verweil is a location tracking client for [Dawarich](https://github.com/Freika/dawarich).
Its purpose is to make self-hosted location history as clean as Google Maps Timeline:
no phantom kilometres while you sit indoors, and still detailed tracks while you move.

## The problem

Dawarich clients (the official app, OwnTracks, GPSLogger, Overland) report raw location fixes.
Indoors, fixes wander 20–500 m: GPS multipath, Wi-Fi and cell-tower positions.

Dawarich turns every one of those fixes into movement:

- Distance is the plain sum of geodesic distances between consecutive points
  (`app/queries/stats/daily_distance_query.rb`, `app/models/concerns/distanceable.rb`).
  There is no smoothing and no stationary detection.
- The anomaly filter (`app/services/points/anomaly_filter.rb`) only removes absurd outliers:
  accuracy above 10 km or speeds above 1000 km/h. Drift at walking pace passes on purpose.
- The user-configurable accuracy threshold was removed in 1.11.0.
- Known and open upstream:
  [#3417](https://github.com/Freika/dawarich/issues/3417),
  [#3300](https://github.com/Freika/dawarich/issues/3300),
  [#3601](https://github.com/Freika/dawarich/issues/3601).
  Maintainers point to the client as the place to fix it.

The result: a day at the desk adds up to several kilometres of zig-zag lines.

## What Google Timeline does differently

Google's raw fixes are just as noisy. The difference is interpretation:

1. **The day is modelled as segments, not as a point stream.**
   Timeline stores *place visits* and *activity segments*, each with a confidence
   ([format docs](https://locationhistoryformat.com/guides/semantic-location/)).
   Distance only exists inside activity segments.
2. **Motion decides between stationary and moving.**
   Android's activity recognition knows when the phone is still. A jump while still is noise, not travel.
3. **Wi-Fi identifies the place, not GPS.**
   Patent [US9787557](https://patents.google.com/patent/US9787557B2/en) describes Wi-Fi scans that
   score visit confidence, and good visits merged into a per-place point cloud
   to overcome imprecise indoor fixes.
4. **Post-processing.**
   Visits are snapped to known places; routes are (most likely) map-matched.

Verweil copies 1–3 on the device. Point 4 is optional and later.

## Core idea

> Verweil decides on the device whether you are **staying** or **moving**.
> Only movement becomes a track. A stay becomes a single anchor point and a Dawarich visit.

While a stay is active, fixes are **never** forwarded, whatever position they report.
A single bad fix therefore cannot create distance.
Leaving a stay needs two independent signals: motion, and a *good* fix outside the stay radius.

## Inputs

All logic lives in a platform-independent engine that consumes a stream of events:

| Event | Source (Android) | Source (iOS, later) |
|---|---|---|
| `Fix(time, lat, lon, accuracy, speed?, altitude?)` | Fused Location Provider | Core Location |
| `Activity(time, type)` with `STILL`, `WALKING`, `RUNNING`, `CYCLING`, `VEHICLE`, `UNKNOWN` | Activity Recognition Transition API | Core Motion activity |
| `WifiScan(time, bssids)` | `WifiManager` scan results | not available (iOS blocks Wi-Fi scanning) |

The engine uses no wall clock. Time comes only from the events.
That makes it deterministic and replayable (see [Tuning by replay](#tuning-by-replay)).

## State machine

```
            still / fixes cluster            held for T_stay
  MOVING ─────────────────────────▶ SETTLING ───────────────▶ STAYING
    ▲                                 │                          │
    │      good fix outside R_stay    │                          │ motion or good fix
    ◀─────────────────────────────────┘                          │ outside R_exit
    │                                                            ▼
    │            departure confirmed                          LEAVING
    ◀────────────────────────────────────────────────────────────┤
                                                                 │ not confirmed
                                          STAYING ◀──────────────┘ within T_leave
```

**MOVING**
- Filter fixes (see below) and forward them as track points.
- Go to SETTLING when the activity becomes `STILL`,
  or when all good fixes of the last `T_settle` fit within `R_stay`.

**SETTLING** (a candidate stay)
- Buffer fixes and do not forward them yet.
- A good fix outside `R_stay` while not `STILL` means it was a traffic light, not a stay.
  Flush the buffer as track points and go back to MOVING.
- If the candidate holds for `T_stay`, go to STAYING.
  The anchor is the accuracy-weighted median of the buffered fixes,
  or a known place's anchor when the Wi-Fi seen so far matches one (see [Place memory](#place-memory-wi-fi-fingerprints)).
  The stay start is backdated to the start of SETTLING.

**STAYING**
- Emit `StayStarted(anchor, since)`. Ignore all fixes for output.
  Fixes inside `R_exit` are collected to refine the stay's centre for the visit;
  the anchor itself stays put, so all points of the stay share one position.
- Presence needs evidence: a fix inside `R_exit`, `STILL`, or a matching Wi-Fi scan.
  Time alone does not stretch a stay, e.g. across hours with the phone switched off.
- Every `T_heartbeat` of presence, emit `StayHeartbeat(anchor, time)`.
- Go to LEAVING when a movement activity starts (`WALKING`, `RUNNING`, `CYCLING`, `VEHICLE`),
  when a good fix lands outside `R_exit`,
  when a Wi-Fi scan's similarity to the stay's fingerprint drops below `S_leave`,
  or when the platform reports leaving the geofence around the anchor.

**LEAVING** (a candidate departure)
- The departure is confirmed when a movement activity is active **and** a good fix lies outside `R_exit`,
  or when `N_exit` consecutive good fixes lie outside `R_exit`.
  Emit `StayEnded(anchor, since, until, center)` with `until` = the last evidence of presence
  and `center` = the accuracy-weighted median of all fixes of the stay, or the known place it belongs to.
  Then go to MOVING; the new track starts at the anchor.
- Without confirmation within `T_leave`, drop the evidence and go back to STAYING (it was jitter).
  Wi-Fi and geofence exits only start LEAVING; confirming still takes fixes.

**Tracking stops:** an open stay ends at its last evidence of presence, buffered track points are released,
and the next start begins in MOVING.

**Restarts:** the engine's whole state is saved together with the uploads of each step, in one transaction.
When Android kills the app, it continues where it stopped: an ongoing stay is not lost, nothing is queued twice.

### Moving filters

- Drop fixes with accuracy worse than `A_good`.
- Drop fixes whose implied speed from the last accepted fix is implausible for the current activity.
- Forward a point when it is at least `D_min` from the last forwarded point.

### Initial parameters

These are starting values, to be tuned by replay:

| Parameter | Value | Meaning |
|---|---|---|
| `A_good` | 35 m | Maximum accuracy radius of a "good" fix |
| `R_stay` | 75 m | Radius of a stay candidate |
| `R_exit` | 150 m | Distance from the anchor that counts as outside |
| `T_settle` | 3 min | Clustering window to enter SETTLING without `STILL` |
| `T_stay` | 5 min | Minimum stay duration |
| `T_leave` | 3 min | Time to confirm a departure |
| `N_exit` | 2 | Consecutive good fixes outside `R_exit` without motion |
| `D_min` | 15 m | Minimum spacing of forwarded track points |
| `S_leave` | 0.3 | Wi-Fi similarity below which the place counts as changed |
| `T_heartbeat` | 60 min | Interval of anchor points during a stay |
| `N_wifi` | 2 | Scans a stay needs before its fingerprint counts |
| `S_place` | 0.5 | Wi-Fi similarity from which a stay belongs to a known place |
| `R_place` | 250 m | Maximum distance between a stay and a known place it matches |

## Place memory (Wi-Fi fingerprints)

This is Android only; iOS relies on its built-in visit monitoring (`CLVisit`) instead.

- During a stay, collect the BSSIDs seen across scans.
  When the stay ends, merge it into a `Place` with anchor, fingerprint (BSSID → seen ratio) and visit count:
  the anchor becomes the visit-weighted mean of the stays' centres (weight capped at 20, so a place can still move),
  the fingerprint the weighted mean of the ratios, without access points seen in fewer than 5 % of scans.
- When a new stay starts, compare its fingerprint with known places (weighted Jaccard similarity).
  From `S_place`, snap the anchor to the known place's anchor.
  Repeated visits then land on the same point, as in Timeline.
- A known place only matches within `R_place` of the measured position.
  Access points that travel, like train Wi-Fi or phone hotspots, would otherwise pull stays across the map.
- A falling similarity during a stay is an extra departure signal that is independent of GPS.
- BSSIDs are hashed with a random per-install salt and never leave the device.

Android throttles scans to 4 per 2 minutes in the foreground and 1 per 30 minutes in the background.
Verweil mostly reads the results of scans the system runs anyway.
While not moving it asks for one scan per 30 minutes at most, within the background limit.

## Output to Dawarich

Verweil speaks existing Dawarich APIs; no server changes are needed.
Authentication is the user's API key as `Authorization: Bearer <key>`.

- **Track points** go to `POST /api/v1/overland/batches` as GeoJSON features.
  Properties: `timestamp` (ISO 8601), `horizontal_accuracy`, `speed`, `altitude`,
  `motion` (activity), `device_id`.
- **Stays** become points at the anchor: one at arrival, one at departure and a heartbeat every 60 minutes,
  so the map shows you there and distance stays at 0.
- **Visits:** when a stay ends, Verweil also creates it at its refined centre through `POST /api/v1/visits`
  with `{ "visit": { "latitude", "longitude", "started_at", "ended_at", "name": "Suggested place", "status": "suggested" } }`.
  With status `suggested`, Dawarich reverse-geocodes a name for new places,
  reuses places within 100 m, deduplicates, and lets the user confirm the visit.
- All output goes through a persistent upload queue. It is sent in batches with retries and survives
  offline periods and app restarts.
  Data the server refuses (4xx other than 401, 403, 408 and 429) is set aside with its error instead of blocking
  the queue; the user can retry it. A refused API key keeps everything queued until the user signs in again.

## Tuning by replay

Thresholds are the hard part, and walking around for every change doesn't scale.

- **Record:** a debug recorder writes every raw event of a day to a JSONL log.
  BSSIDs are hashed before they are written.
- **Replay:** a JVM tool feeds logs through the engine and writes the result as GeoJSON with metrics:
  total distance, number of stays, and phantom distance on days labelled "stationary".
- **Regression:** labelled real days become tests. For example,
  "office 09:00–17:00, then walked home" must produce exactly one stay and about 2.1 km.

## Battery strategy

- **MOVING:** high-accuracy location every 5 s.
- **STAYING:** balanced location every 5 minutes, plus fixes other apps request, at most one per minute.
  Wake-ups come from activity transitions and a geofence around the anchor with radius `R_exit`.
  Without the geofence (no "Allow all the time", location off) it falls back to every 60 s.
- Wi-Fi: mostly passive results; see [Place memory](#place-memory-wi-fi-fingerprints).
- Tracking resumes after a reboot or an app update when it was on.
  The app asks to be exempt from battery optimization and links to [dontkillmyapp.com](https://dontkillmyapp.com/)
  for manufacturers that stop background apps anyway.

## Non-goals for v1

- Kalman smoothing of track points. The Fused Location Provider already fuses GNSS, Wi-Fi, cell and inertial sensors,
  and there is no evidence that Timeline smooths on top of that; it simplifies paths and snaps them to roads instead.
  Phantom distance comes from stationary jitter, which the state machine handles.
  Revisit only if recorded days show jagged movement tracks.
- Map matching. Planned later as optional server-side post-processing with a self-hosted Valhalla (Meili).
- Place naming, which Dawarich and its reverse geocoder already do.
- Any UI beyond status, settings and a debug view. Dawarich is the UI.

## Open questions

- Real-world latency of activity transitions, which decides how much of a departure is lost.
- Background killing by OEM Android builds such as Samsung and Xiaomi.
- Whether Dawarich visits created through the API interact badly with its own visit detection.
