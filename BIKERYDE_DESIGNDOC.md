# BikeRyde — Design & Spec Document (Native Android / Kotlin)

A personal motorbike ride tracker: single & multi-day ride tracking, Strava-style graphics,
animated multi-day trip videos, fuel logging, and AI-assisted ride planning. Local-first,
no backend, no hosting cost. Native Android app built in Kotlin.

---

## 1. Goals & non-goals

**Goals**
- Track rides (single day and multi day) with manual, user-controlled start/pause/end actions
- Generate a static image or animated video for any ride, single or multi day
- Multi-day videos show route, distance, time on road, and named stops (city/place)
- Manual fuel log (odo, liters, cost) with mileage/cost trends
- AI-assisted planning (weather, food/sightseeing/lodging suggestions placed along the actual
  route) using local or cloud model
- Zero backend, zero hosting cost — local storage + optional user-owned Google Drive sync
- User brings their own API keys; app never holds or proxies shared credentials

**Non-goals (v1)**
- No multi-user backend, no shared/default API keys, no app-level login/SSO
- No in-app navigation of any kind — no route preview map, no turn-by-turn, no handoff to another
  navigation app; riders use whatever navigation app they already prefer, entirely outside BikeRyde
- No iOS or web build — Android only
- No social feed / follower features — exports go to Instagram manually

---

## 2. High-level architecture

```
┌───────────────────────────────────────────────────────────┐
│  BikeRyde — native Android app (Kotlin, Views/Fragments)  │
│                                                           │
│  ┌────────────────┐   ┌────────────────┐                  │
│  │ GPS tracking   │   │ Render engine  │                  │
│  │ FusedLocation- │   │ MediaCodec /   │                  │
│  │ ProviderClient │   │ Media3         │                  │
│  │ + ForegroundSvc│   │ Transformer    │                  │
│  └────────┬───────┘   └────────┬───────┘                  │
│           v                    v                          │
│  ┌─────────────────────────────────────────┐              │
│  │ Local storage                           │              │
│  │ SQLite — structured data                │              │
│  │ App-scoped storage — photos, video      │              │
│  └───────────────────┬─────────────────────┘              │
│                      │                                    │
│  ┌─────────────────────────────────────────┐              │
│  │ Ride planner                            │              │
│  │ MediaPipe LLM Inference (Gemma, local)  │              │
│  │ or cloud AI API (toggle)                │              │
│  └───────────────────┬─────────────────────┘              │
└──────────────────────┼────────────────────────────────────┘
                       │
     ┌─────────────────┼──────────────────┬──────────────────┐
     v                 v                  v                  v
Google Drive API   Weather/Places API   Routing API       Cloud AI API
(optional sync,     (BYO key or free    (OSRM/OpenRoute-  (BYO key,
 user's account)     tier)               Service, free)    optional)
```

No backend server anywhere — every external call is made directly from the device using the
user's own credentials, and native Android APIs are used directly with no cross-platform bridge.

---

## 3. Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin | Java fully interoperable if ever needed |
| UI | XML layouts + Fragments, Jetpack Navigation component | Simple, mature, no extra build tooling |
| State | Kotlin `StateFlow` state holders | Tracking services publish state that Fragments observe |
| Dependency injection | Manual wiring | App is small enough that a DI framework isn't worth the overhead |
| Local structured data | SQLite via `SQLiteOpenHelper` (`RideDbHelper`, singleton) | Rides, GPS points, stops, fuel logs |
| Settings | `SharedPreferences` (`EncryptedSharedPreferences` for API keys) | Key-value settings |
| Local blobs | App-scoped internal storage (`filesDir`) | Photos, rendered videos, cached geocode results |
| Background GPS | `FusedLocationProviderClient` + `ForegroundService` | Persistent notification while tracking, per Android background-location requirements |
| Video rendering | `MediaCodec` / Media3 Transformer (hardware-accelerated) | Target 1080p60, fallback 1080p30 on devices without hardware support |
| Local AI | MediaPipe LLM Inference API (Gemma) | First-class Kotlin support, on-device |
| Cloud AI (optional) | Retrofit/OkHttp client to Claude API (or user's chosen provider) | BYO key, toggle in settings |
| Weather | Open-Meteo (free, no key) or BYO key provider | Retrofit client |
| Places / geocoding | OpenStreetMap Nominatim/Overpass (free) or BYO Google Places key | |
| Routing / directions | OSRM public server (free, no key) or BYO OpenRouteService key (free tier, higher limits) | Route geometry + cumulative distance between waypoints — lets planning place a suggestion "40km into the ride" rather than just "near the destination" |
| Cloud sync (optional) | Google Drive REST API via Google Identity Services | File-scope OAuth, not app login |
| Notifications | `NotificationCompat`, `WorkManager` for background render jobs | |
| Networking | Retrofit + OkHttp | |
| Async | Kotlin Coroutines + Flow | |
| Image loading | Coil | |
| Charts (fuel trends) | Custom `View` drawn on a `Canvas` (`TrendLineChartView`) | |

---

## 4. Data model (SQLite tables)

**Ride**
```
id, type (SINGLE_DAY | MULTI_DAY), title, createdAt,
startTime, endTime, status (PLANNED | TRACKING | COMPLETED),
totalDistanceKm, totalDurationS, coverPhotoId
```

**RideDay** (one row per day; a single-day ride has exactly one)
```
id, rideId, dayIndex, dayType (TRAVEL | NOT_TRAVEL), startTime, endTime,
startPlaceName, endPlaceName, distanceKm, durationS
```
A `NOT_TRAVEL` day marks a day within a multi-day trip spent at the current stop with no
riding — a rest day or a day set aside for sightseeing/tourism. It has no `Stop`s or `GpsPoint`s
of its own; `startPlaceName`/`endPlaceName` are the same place, and `distanceKm`/`durationS` are 0.

**Stop** (from explicit start/pause/end user actions)
```
id, rideDayId, action (START | PAUSE | END), timestamp, lat, lng, placeName
```

**GpsPoint**
```
id, rideDayId, timestamp, lat, lng, elevation, speed
```

**FuelLog**
```
id, timestamp, odoKm, litersFilled, cost, pricePerLiter (derived),
mileageSinceLast (derived), notes
```

**Photo**
```
id, rideId, filePath, timestamp, lat, lng (optional), usedAsBackground (Boolean)
```

**Render**
```
id, rideId, type (IMAGE | VIDEO), status (QUEUED | PROCESSING | DONE | FAILED),
resolution, fps, filePath, createdAt
```

**RidePlan**
```
id, title, destination, dateRangeStart, dateRangeEnd, weatherSnapshot (JSON),
suggestions (JSON: food/sightseeing/lodging), generatedBy (LOCAL | CLOUD)
```

**AppSettings** (`SharedPreferences`, not SQLite, for key-value settings)
```
apiKeys (weather, places, routing, aiCloud) — stored via EncryptedSharedPreferences,
aiMode (LOCAL | CLOUD), driveSyncEnabled (Boolean), units (KM | MI)
```

---

## 5. Feature specs

### 5.1 Ride tracking — single day
- **Start ride** → `ForegroundService` begins GPS logging via `FusedLocationProviderClient`, persistent "Ride in progress" notification shown
- **End ride** → logging stops, service ends, ride marked `COMPLETED`, summary screen (distance, time, avg speed, route map)
- Same code path whether the ride is `SINGLE_DAY` or day 1 of a `MULTI_DAY` trip

### 5.2 Ride tracking — multi day
- **Start trip** once, at the beginning of the whole journey
- Per day: **Start day** → tracking → **Pause day** (end of riding for that day) or **End day** if final
- Each start/pause/end action captures a `Stop` (timestamp + lat/lng), reverse-geocoded once to a place name, cached locally in SQLite so repeat renders don't re-query
- **End trip** closes out the whole multi-day ride and triggers the trip summary
- A day can also be logged as **not travelling** — a rest day or a day spent sightseeing/touring at
  the current stop, with no start/pause/end tracking. This creates a `NOT_TRAVEL` `RideDay` (same
  start/end place, zero distance/duration) so trip summaries and the multi-day video can label it
  distinctly from a travel day, instead of it just appearing as an untracked gap between two days
- A trip recorded one day at a time as **single-day rides** can be combined afterwards from Ride
  History: pick two or more completed single-day rides (ordered by date), optionally insert
  `NOT_TRAVEL` days after any of them, and a new completed multi-day ride is created with copies of
  their days, stops and GPS points. The originals are kept unless the rider opts to remove them

**Still-riding safety check:** while a day-segment is active, if no significant GPS movement is detected for 30+ minutes (checked via a periodic `WorkManager` task or timer inside the foreground service), fire a notification: "Still riding? Tap to keep tracking, or end your day."

### 5.3 Graphics & video rendering
- Available for both single-day and multi-day rides
- **Static image**: composited on-demand via `android.graphics.Canvas`/`Bitmap` drawing — route map + stats overlay, near-instant
- **Animated video**: queued as a `WorkManager` background job
  - `ForegroundService` + persistent notification: "Rendering ride video… 42%"
  - On completion: notification "Your ride video is ready" → tapping opens preview/export screen
  - Multi-day video animates the stitched route day by day, with place-name labels appearing at each recorded stop, plus running totals for distance and time on road; `NOT_TRAVEL` days are shown as a labeled pause at that stop (e.g. "Day 3 — resting in Manali") rather than being skipped
  - User-selected photos layered as backgrounds behind map/stat overlays during compositing
  - Rendering pipeline: `MediaCodec` (or Media3 Transformer as the higher-level wrapper) for hardware-accelerated encoding — target 1080p60, automatic fallback to 1080p30 on devices where the hardware encoder can't sustain 60fps at that resolution
- Export: `MediaStore` save to gallery + `Intent.ACTION_SEND` share sheet (Instagram, etc.)

### 5.4 Fuel tracking
- Form at each fill-up: odometer reading, liters filled, total cost
- App derives price/liter and mileage since last fill automatically (SQL query against the previous `FuelLog` entry)
- History screen with trend charts (cost over time, mileage over time)

### 5.5 AI-assisted ride planning
- User describes an origin/destination and date range; app fetches:
  - the **route** between them (geometry + cumulative distance) from the routing provider — this
    is what lets a suggestion be placed a specific distance *into* the ride (e.g. "a breakfast stop
    ~40km in") rather than only near the endpoints
  - **weather** for the date range, and **nearby places** (food, sightseeing, lodging) — searched
    along the route's geometry, not just around the destination, so a stop can be suggested
    partway through a long day's ride
- That structured payload (route + weather + candidate places) is handed to the AI model — a light RAG-style summarization/ranking task (picking and placing good stops), not open-ended reasoning
- **Local mode (default):** MediaPipe LLM Inference API running Gemma on-device, no network call for the AI step itself (routing/weather/places calls still happen over the network)
- **Cloud mode (toggle):** sends the same structured payload to the user's configured cloud AI provider for a stronger answer

### 5.6 Onboarding & key management
- First open: **Import from Google Drive** or **Start fresh**
  - Import: Google OAuth (file-scope only via Google Identity Services, not an app login) — pulls a previous export/sync file
  - Start fresh: settings screen walkthrough where the user optionally adds their own keys (weather, places, routing, cloud AI), each field linking to "how to get this key" instructions
- No default or shared keys anywhere. Missing a key disables only that specific feature
- Keys stored via `EncryptedSharedPreferences`, never logged, never included in crash reports

### 5.7 Sync (optional)
- Google Drive, user's own account, app-scoped file access (Drive `appDataFolder` or a user-visible dedicated folder)
- Manual "Sync now" button plus optional sync-on-app-open
- Structured data (rides, fuel logs, plans) synced as a JSON export from SQLite; photos/videos optionally included if the user allows larger uploads
- Last-write-wins; single-user multi-device use, not real-time collaboration

---

## 6. Permissions & notifications

**Permissions requested**
- `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` (trip tracking)
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` (Android 14+ requires typed foreground service permissions)
- `POST_NOTIFICATIONS` (Android 13+)
- Media/storage access for saving photos and exported videos (scoped storage, no broad storage permission needed on modern Android)
- Google account access only if Drive sync is enabled (file scope, not full account)

**Notifications**
- "Trip in progress" — persistent, foreground-service backed, while any day-segment is active
- "Still riding?" — after 30+ min of no movement during an active day-segment
- "Rendering ride video… X%" — persistent during video render
- "Your ride video is ready" — on render completion, tap to open

---

## 7. Build phases

1. **Core tracking + static graphics** — single-day start/end tracking, route map, stats image export, fuel log
2. **Multi-day tracking + video export** — start/pause/end day actions, trip stitching, WorkManager render job + notifications, stop-name labeling
3. **Photo backgrounds + animation polish** — background layering in render engine, 1080p60/30 tuning across device tiers
4. **AI ride planning** — weather/places integration, local Gemma pipeline via MediaPipe, cloud toggle
5. **Google Drive sync** — import/export flow, first-run onboarding

---

## 8. Open questions / risks

- **MediaCodec hardware encoder support varies by device** — need capability detection at render start (query supported profiles/levels) rather than failing mid-render; fallback path to 1080p30 or software encoding on lower-end hardware
- **MediaPipe/Gemma model size vs. app install size** — bundling a multi-GB model in the APK isn't practical; plan for on-first-use download with clear user consent, stored in app-scoped storage
- **Foreground service + battery drain** — full-day GPS tracking with a persistent notification will noticeably drain battery; worth surfacing an estimated battery-per-hour figure during long multi-day trips
- **Android 14+ foreground service type requirements** — location-type foreground services now require explicit manifest declarations and runtime checks; needs to be verified against the target SDK at build time since this tightens with each Android release
- **Reverse-geocoding rate limits on free tiers** (Nominatim) — fine at personal-use volume, but cache aggressively in SQLite regardless since place names for a given coordinate never change
- **EncryptedSharedPreferences / Keystore behavior across OEMs** — some manufacturers have historically had quirks with Android Keystore; worth testing on a couple of different device brands before relying on it for API key storage

---

## 9. API key FAQ (to build into Settings)

Each entry below becomes an inline help card next to its key field in-app.

- **Weather (Open-Meteo):** no key required for the free tier — nothing to set up
- **Places / geocoding (OpenStreetMap):** no key required; optional Google Places key for richer POI data (Google Cloud Console → enable Places API → create API key)
- **Routing (OSRM):** no key required — uses the public OSRM server; optional OpenRouteService key for higher rate limits (openrouteservice.org → sign up → Dashboard → Create key)
- **Cloud AI (Claude API, optional):** console.anthropic.com → API Keys → Create Key