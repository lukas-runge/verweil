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
  None of them has a maintainer answer (checked 2026-09-27).

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
> Only movement becomes a track. A stay becomes points at a single anchor, which Dawarich turns into a visit.

While a stay is active, fixes are **never** forwarded, whatever position they report.
A single bad fix therefore cannot create distance.
Leaving a stay needs two independent signals: motion, and a *good* fix outside the stay radius.

## Division of labour with Dawarich

Dawarich documents no contract for its clients: the `CLAUDE.md` in its repository calls itself the authoritative
architecture reference and has no word on it, and there are no ADRs. What its code, its documentation and its own
apps show is this: clients send points, the server interprets them. The official Android app records raw points and
uploads them in batches, with a distance and a time filter at most. Dawarich then flags anomalies (by speed only),
cuts tracks at time gaps, classifies modes of travel, detects visits and names places.
Its visits API is for people creating and editing visits by hand.

Verweil keeps that contract and sits in front of it as a filter: it sends only points, but clean ones. A smoothed,
simplified track while moving; while staying, points at one spot instead of indoor zig-zag.
What a track and what a visit is, Dawarich decides.

## Inputs

All logic lives in a platform-independent engine that consumes a stream of events:

| Event | Source (Android) | Source (iOS, later) |
|---|---|---|
| `Fix(time, lat, lon, accuracy, speed?, altitude?)` | Fused Location Provider | Core Location |
| `Activity(time, type)` with `STILL`, `WALKING`, `RUNNING`, `CYCLING`, `VEHICLE`, `UNKNOWN` | Activity Recognition Transition API | Core Motion activity |
| `WifiScan(time, bssids)` | `WifiManager` scan results | not available (iOS blocks Wi-Fi scanning) |
| `CarConnection(time, connected)` | Android Auto (`CarConnection`), or a Bluetooth device the user marked as their car | CarPlay, Bluetooth audio route |

While connected to a car, every activity counts as `VEHICLE`: in town traffic the phone's own guess is often cycling,
and at a red light still. Connecting starts a departure like a moving activity. Disconnecting changes nothing until
the phone's next activity. Points taken while connected say that their `driving` is certain (`motion_confidence: 1.0`
in the Overland properties): Dawarich weighs a plain motion against the speed, and in town traffic the speed of a car
looks like cycling. A Dawarich that knows the field takes a certain motion as the mode; others ignore it.

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
  when a good fix lands outside the exit radius (see LEAVING),
  when a Wi-Fi scan's similarity to the stay's fingerprint drops below `S_leave`,
  or when the platform reports leaving the geofence around the anchor.

**LEAVING** (a candidate departure)
- The departure is confirmed when a movement activity is active **and** a good fix lies outside the exit radius,
  or when `N_exit` consecutive good fixes lie outside `R_exit`.
  While moving, the exit radius shrinks with the fix's accuracy to `min(R_exit, R_stay + F_exit × accuracy)`:
  a walk around the block with 5 m fixes is a trip, while imprecise fixes still need `R_exit` against indoor jitter.
  Emit `StayEnded(anchor, since, until, center)` with `until` = the last evidence of presence
  and `center` = the accuracy-weighted median of all fixes of the stay, or the known place it belongs to.
  Then go to MOVING; the new track starts at the anchor.
- Without confirmation within `T_leave`, drop the evidence and go back to STAYING (it was jitter).
  Wi-Fi and geofence exits only start LEAVING; confirming still takes fixes.

**Tracking stops:** when stopping, nobody knows when tracking starts again, so an open stay is paused, not ended:
nothing is sent, and the phone's timeline shows it ending at its last evidence of presence for now.
The first event after the next start decides. Within `T_resume` (1 h) of that evidence, the stay goes on and the
event is judged as ever, so a fix elsewhere starts the departure, backdated to the last presence.
Later, the stay ends at that evidence and its departure point goes out with that old time; the event then starts
afresh in MOVING. Stopping on the way ends like before: buffered track points are released, the next start
begins in MOVING. Signing out ends an open or paused stay right away, so nothing of it reaches the next account.

**Restarts:** the engine's whole state is saved together with the uploads of a step, in one transaction.
Steps without uploads save it when the mode changes or at least every 30 s; with a fix every second,
saving each step would rewrite hundreds of buffered fixes per second.
When Android kills the app, it continues where it stopped: an ongoing stay is not lost, nothing is queued twice,
and at most the last 30 s of fixes are missing.

### Moving filters

- Drop fixes with accuracy worse than `A_good`.
- Drop fixes whose implied speed from the last accepted fix is implausible for the current activity.
- Optionally smooth with a constant-velocity Kalman filter per axis that uses the Doppler speed and bearing
  of each fix. **Off by default:** on a walk around a block with a fix every second (accuracy 4.5 m),
  measured against the route actually walked, every setting of it moved the track further from the path
  than the raw fixes (mean offset 3.0–3.9 m against 2.9 m); with the Doppler velocity it got worse, not better.
  The fused provider already filters its fixes, and what is left is an offset over many seconds,
  e.g. up to 15 m at one corner, which no filter can tell from walking. It stays available for replays.
  A gap longer than `T_gap` starts the filter afresh, and so does every departure.
- Simplify: keep the points that shape the track and drop those within `D_simplify` of the line between them
  (streaming Douglas–Peucker, "opening window"), with at most `T_point` between two points.
  On jittery fixes it would keep every spike as a corner; the fixes every second from the fused provider
  are smooth enough. Over three walks around a block (8–12 points each), the corners of the route were
  on average 3.0 m from the track, against 7.1 m (up to 16.8 m) with `D_min` on fixes every 5 s as before,
  which cut corners and made one walk 190 m instead of 246 m.
  The newest point is released when the track pauses (SETTLING) or tracking stops.
- The state machine still decides on the raw fixes; smoothing only shapes the track.

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
| `Q_accel` | 1 m²/s³ | How freely the smoother lets the velocity change (smoothing is off by default) |
| `T_gap` | 30 s | Silence after which the smoother starts afresh |
| `D_simplify` | 3 m | Track points closer than this to the line between their neighbours are dropped |
| `T_point` | 30 s | Longest time between two track points |
| `D_min` | 15 m | Spacing of track points when simplification is off (as before smoothing) |
| `S_leave` | 0.3 | Wi-Fi similarity below which the place counts as changed |
| `T_heartbeat` | 5 min | Interval of anchor points during a stay; Dawarich's visit detection needs them (see Output) |
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
- **Stays** become points at the anchor: one at arrival, one at departure and a heartbeat every 5 minutes,
  so the map shows you there and distance stays at 0.
- **Visits:** none. Dawarich's visit detection finds each stay in the points at the anchor, while it is still
  going on, and suggests it as a visit for the user to confirm. It needs 3 points per stay and ends a stay after an
  hour without points (defaults), hence the heartbeat every 5 minutes.
  Until September 2026 Verweil also created visits (`POST /api/v1/visits`, status `suggested`). The detection
  replaces every suggested visit in the window it looks at with what it finds itself
  (`Visits::Detection::Persister`), so they were duplicates at best; at a place Dawarich already knew they kept the
  placeholder name "Suggested place", and with hourly heartbeats a stay vanished altogether.
  The app's timeline fills holes in Dawarich's timeline with the stays and moves the phone recognised.
- All output goes through a persistent upload queue. It is sent in batches with retries and survives
  offline periods and app restarts.
  Data the server refuses (4xx other than 401, 403, 408 and 429) is set aside with its error instead of blocking
  the queue; the user can retry it. A refused API key keeps everything queued until the user signs in again.

## Dawarich settings Verweil relies on

- **Time gap between Tracks: 4 minutes** (default 30). Dawarich cuts tracks only at time gaps between points
  (and at jumps longer than "Distance gap between Tracks"). With a heartbeat every 5 minutes there is no
  30-minute gap, so a walk ran on into the stay after it. Below 5 minutes, every stay ends the track.
  The cost: a ride without any signal for more than 4 minutes (underground, tunnel) splits into two.
- Tracks of the same device that are less than 30 minutes and 5 km apart are joined again anyway
  (`Tracks::BoundaryDetector`, a fixed floor). A stop shorter than 30 minutes therefore never splits a track in
  Dawarich, whatever the setting; its visit, if detected, lies across the track.
- Visit suggestions stay on. Confirm a visit only after the stay is over: a confirmed visit gets a fixed end, and
  the detection then makes the rest of the stay a separate visit.
- No other tracker sends to the same account: Dawarich sums its indoor jitter as distance.

## Tuning by replay

Thresholds are the hard part, and walking around for every change doesn't scale.

- **Record:** a recorder writes every raw event of a day to a JSONL log (on in debug builds, a setting otherwise,
  kept 30 days, shared from the diagnostics screen).
  BSSIDs are hashed before they are written.
- **Replay:** `./gradlew :core:replay --args="day.jsonl --from 13:30 --to 13:40 --truth route.geojson"`
  runs a log through variants of the track pipeline: raw fixes, spacing by `D_min` (at 1 s and thinned to
  one fix per 5 s, as before), simplified (at 1 s and 5 s), smoothed, and smoothed and simplified; `*` marks
  what the app uploads. It writes one GeoJSON per variant plus `all.geojson` into `replay-out/` and prints
  points and length and, given the route actually walked as a LineString (`--truth`, e.g. drawn on geojson.io),
  three measures against it: how far the track strays from the route, how well it covers the route
  (a cut corner lies close to the route but leaves the corner uncovered), and how far the route's corners are
  from the track. The first alone rewards cutting corners.
- **Regression:** labelled real days become tests. For example,
  "office 09:00–17:00, then walked home" must produce exactly one stay and about 2.1 km.

## Battery strategy

- **MOVING** (also SETTLING and LEAVING): high-accuracy location every second. GNSS runs continuously
  at this accuracy anyway; the shorter interval costs wake-ups. With simplification, the same walks thinned
  to one fix per 5 s hit the corners about as well (2.7 m against 3.0 m), so the rate is still open; it stays
  at 1 s while recordings of rides and drives decide, and because 1 s recordings can replay any slower rate.
- **STAYING:** balanced location every 5 minutes, plus fixes other apps request, at most one per minute.
  Wake-ups come from activity transitions and a geofence around the anchor with radius `R_exit`.
  Without the geofence (no "Allow all the time", location off) it falls back to every 60 s.
- Wi-Fi: mostly passive results; see [Place memory](#place-memory-wi-fi-fingerprints).
- Tracking resumes after a reboot or an app update when it was on.
  The app asks to be exempt from battery optimization and links to [dontkillmyapp.com](https://dontkillmyapp.com/)
  for manufacturers that stop background apps anyway.

## Non-goals for v1

- Map matching. Off roads and paths, e.g. hiking or climbing, there is nothing to snap to,
  so it could only ever be optional server-side post-processing, e.g. with a self-hosted Valhalla (Meili).
- Place naming, which Dawarich and its reverse geocoder already do.
- Deciding what a visit is, which Dawarich's detection does from the points.
- Any UI beyond status, settings, a debug view and a timeline of any day. The timeline reads Dawarich's own
  (`GET /api/v1/timeline`, Dawarich 1.3 and later) and adds what the phone recognised that Dawarich does not show
  yet, such as the ongoing stay. Editing history stays in Dawarich; the one exception is a track's mode of travel,
  which the app corrects through Dawarich's track segments API, so Dawarich holds the correction.

## Open questions

- Real-world latency of activity transitions, which decides how much of a departure is lost.
- Background killing by OEM Android builds such as Samsung and Xiaomi.
- Short stops, like a bakery: does Dawarich's detection get its 3 points (arrival, one heartbeat, departure)
  and keep them as visits?
