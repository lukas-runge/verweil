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
  The anchor is the accuracy-weighted median of the buffered fixes.
  The stay start is backdated to the start of SETTLING.

**STAYING**
- Emit `StayStarted(anchor, since)`. Ignore all fixes for output;
  good fixes may still refine the anchor.
- Go to LEAVING when a movement activity starts (`WALKING`, `RUNNING`, `CYCLING`, `VEHICLE`),
  when a good fix lands outside the exit radius (see LEAVING),
  or when the Wi-Fi fingerprint drops below `S_leave`.

**LEAVING** (a candidate departure)
- The departure is confirmed when a movement activity is active **and** a good fix lies outside the exit radius,
  or when `N_exit` consecutive good fixes lie outside `R_exit`.
  While moving, the exit radius shrinks with the fix's accuracy to `min(R_exit, R_stay + F_exit × accuracy)`:
  a walk around the block with 5 m fixes is a trip, while imprecise fixes still need `R_exit` against indoor jitter.
  Emit `StayEnded(anchor, since, until)` with `until` = the last evidence of presence.
  Then go to MOVING; the new track starts at the anchor.
- Without confirmation within `T_leave`, drop the evidence and go back to STAYING (it was jitter).

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
| `F_exit` | 2 | While moving, a fix is outside beyond `R_stay` plus this many times its accuracy |
| `T_settle` | 3 min | Clustering window to enter SETTLING without `STILL` |
| `T_stay` | 5 min | Minimum stay duration |
| `T_leave` | 3 min | Time to confirm a departure |
| `N_exit` | 2 | Consecutive good fixes outside `R_exit` without motion |
| `D_min` | 15 m | Minimum spacing of forwarded track points |
| `S_leave` | 0.3 | Wi-Fi similarity below which the place counts as changed |

## Place memory (Wi-Fi fingerprints)

This is Android only; iOS relies on its built-in visit monitoring (`CLVisit`) instead.

- During a stay, collect the BSSIDs seen across scans.
  Store a `Place` with its anchor, radius, fingerprint (BSSID → seen ratio) and visit count.
- When a new stay starts, compare its fingerprint with known places (weighted Jaccard similarity).
  Above a threshold, snap the anchor to the known place's anchor.
  Repeated visits then land on exactly the same point, as in Timeline.
- A falling similarity during a stay is an extra departure signal that is independent of GPS.
- BSSIDs never leave the device.

Android throttles scans to 4 per 2 minutes in the foreground and 1 per 30 minutes in the background.
Verweil mostly reads the results of scans the system runs anyway, which is enough during a stay.

## Output to Dawarich

Verweil speaks existing Dawarich APIs; no server changes are needed.
Authentication is the user's API key as `Authorization: Bearer <key>`.

- **Track points** go to `POST /api/v1/overland/batches` as GeoJSON features.
  Properties: `timestamp` (ISO 8601), `horizontal_accuracy`, `speed`, `altitude`,
  `motion` (activity), `device_id`.
- **Stays** become two points at the anchor, one at arrival and one at departure,
  so the map shows you there and distance stays at 0.
  An optional heartbeat point at the anchor every 60 minutes keeps the map populated during long stays.
- **Visits:** when a stay ends, Verweil also creates it through `POST /api/v1/visits`
  with `{ "visit": { "latitude", "longitude", "started_at", "ended_at", "name": "Suggested place", "status": "suggested" } }`.
  With status `suggested`, Dawarich reverse-geocodes a name for new places,
  reuses places within 100 m, deduplicates, and lets the user confirm the visit.
- All output goes through a persistent upload queue. It is sent in batches with retries and survives
  offline periods and app restarts.

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
- **STAYING:** balanced or passive location. Wake-ups come from activity transitions
  and a geofence around the anchor with radius `R_exit`.
- Wi-Fi: passive results only, no forced scans in the background.

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
