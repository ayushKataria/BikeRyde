# BikeRyde

A personal motorbike ride tracker: single & multi-day ride tracking, Strava-style graphics,
animated multi-day trip videos, fuel logging, and (planned) AI-assisted ride planning.

Local-first, no backend, no hosting cost. Native Android app built in Kotlin.

See [`BIKERYDE_DESIGNDOC.md`](./BIKERYDE_DESIGNDOC.md) for the full design and spec doc.

## Status

Early development. Build order follows the phases in the spec doc:

1. Core tracking + static graphics — **done**
2. Multi-day tracking + video export — **done**
3. Photo backgrounds + animation polish — **done** (see [Known gaps](#known-gaps))
4. AI ride planning — not started
5. Google Drive sync — not started

## Implemented vs planned features

### Implemented

- Single-day ride tracking with foreground-service GPS logging (`RideTrackingService`)
- Multi-day ride tracking, including a "still riding?" safety check-in notification
  (`MultiDayRideTrackingService`, `StillRidingWatchdog`, `StillRidingNotifier`)
- Ride history, ride detail, and stop grouping/renaming along a route
- Static route image rendering (`StaticImageRenderer`, `RouteFrameDrawer`)
- Animated trip video rendering via `MediaCodec`/`MediaMuxer`, run in the background through
  WorkManager (`VideoRenderWorker`), with stop exclusion and duration customization; targets
  1080p60 and falls back to 30fps when the device's H.264 encoder can't sustain 60
- Photo backgrounds on rendered output: a cover photo for a static image, per-stop photos
  (crossfaded) for a single-day video, and per-day photos plus optional day captions for a
  multi-day video (`RenderEditFragment`, `RouteFrameDrawer`)
- Fuel log tracking with fill-up entries and a mileage trend chart (`FuelLogFragment`,
  `TrendLineChartView`)
- Local settings for units, AI mode, and API key fields (stored in plain
  `SharedPreferences` today, not yet encrypted)
- Onboarding flow

### Planned / not yet implemented

- AI-assisted ride planning (currently a "Coming soon" placeholder screen)
- On-device AI planning via MediaPipe LLM Inference (Gemma), with a cloud AI toggle
- Weather and places lookups via Open-Meteo and OpenStreetMap Nominatim/Overpass
- Google Drive sync for multi-device backup/restore
- `EncryptedSharedPreferences` for API key storage (design spec §5.7; currently plain
  `SharedPreferences`)

### Known gaps

Small hardening items left over from the finished phases, not missing features:

- **Video encoder fallback.** The frame rate falls back from 60 to 30fps, but the resolution is
  fixed at 1080x1920: a device with no H.264 encoder that supports it fails the render, with no
  lower-resolution or software-encoder fallback (design spec §8). The 60→30 fallback has not been
  exercised on a low-end device.
- **Render photo cleanup.** Photos picked for a render are copied into app storage
  (`render_backgrounds`) and are not deleted when replaced, removed, or when the customize screen is
  left without generating. The design spec's `Photo` entity linking photos to rides is not
  implemented.

## Tech stack

Current implementation:

- Kotlin, XML layouts + Fragments, Jetpack Navigation component
- Kotlin Coroutines + `StateFlow` for tracking state
- Raw SQLite via a `SQLiteOpenHelper` (`RideDbHelper`) for ride/fuel data
- Plain `SharedPreferences`-backed repositories for settings
- Manual dependency wiring (no DI framework)
- `FusedLocationProviderClient` + `ForegroundService` for background GPS tracking
- `MediaCodec` / `MediaMuxer` for hardware-accelerated video rendering, driven by WorkManager

Planned (per the design spec, not yet wired in):

- MediaPipe LLM Inference API (Gemma) for on-device AI planning, with a cloud AI toggle
- Open-Meteo and OpenStreetMap Nominatim/Overpass for weather and places (free, no key required)
- Google Drive REST API for optional multi-device sync

## Requirements

- Android Studio (latest stable)
- JDK 11+ (project is currently configured for Java 11 source/target compatibility)
- Android SDK, compileSdk 37 / minSdk 24 / targetSdk 37 (see `app/build.gradle.kts`)
- A physical device or emulator running Android 10 (API 29) or later recommended, given the
  background location and foreground service work involved

## Getting started

```bash
# clone and open in Android Studio, or build from the CLI:

# build a debug APK
./gradlew assembleDebug

# install on a connected device/emulator
./gradlew installDebug

# run unit tests
./gradlew test

# run instrumented tests on a connected device
./gradlew connectedAndroidTest
```

## API keys

This app does not ship with any default or shared API keys. Settings has fields for a places
API key and a cloud AI key for features that aren't wired up yet — weather/places lookups and
cloud AI planning are both still on the roadmap. Keys are currently stored in plain
`SharedPreferences`; migrating to `EncryptedSharedPreferences` is planned but not yet done.

## Project structure

```
app/
  src/main/java/com/ayushkataria/bikeryde/
    fuel/            # Fuel log model + repository
    media/            # Route/video rendering: MediaCodec encoding, stop grouping, image/file storage
    planning/          # Ride plan model (AI planning UI not yet built)
    ride/               # Ride tracking services, state, SQLite repository/helper
    settings/            # App settings model + SharedPreferences-backed repository
    ui/
      fuel/              # Fuel log screens
      history/            # Ride history screen
      home/                # Home screen
      onboarding/           # Onboarding flow
      placeholder/           # "Coming soon" placeholder screen (used by unbuilt features)
      render/                  # Render customization + preview screens
      ride/                     # Single-day/multi-day ride tracking + detail screens
      settings/                  # Settings screen
  src/main/res/          # Resources (layouts, navigation graph, icons, strings)
  src/test/               # Unit tests
  src/androidTest/         # Instrumented tests
```

## License

Personal project — license TBD.
