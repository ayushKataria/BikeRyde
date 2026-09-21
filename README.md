# BikeRyde

A personal motorbike ride tracker: single & multi-day ride tracking, Strava-style graphics,
animated multi-day trip videos, fuel logging, and (planned) AI-assisted ride planning.

Local-first, no backend, no hosting cost. Native Android app built in Kotlin.

See [`BIKERYDE_DESIGNDOC.md`](./BIKERYDE_DESIGNDOC.md) for the full design and spec doc.

## Status

Early development, built directly with Views/Fragments (not yet on the Compose/Room/Hilt stack
the original spec called for — see [Tech stack](#tech-stack)). Build order follows the phases in
the spec doc:

1. Core tracking + static graphics — **done**
2. Multi-day tracking + video export — **done**
3. Photo backgrounds + animation polish — in progress
4. AI ride planning — not started
5. Google Drive sync — not started

## Implemented vs planned features

### Implemented

- Single-day ride tracking with foreground-service GPS logging (`RideTrackingService`)
- Multi-day ride tracking, including a "still riding?" safety check-in notification
  (`MultiDayRideTrackingService`, `StillRidingWatchdog`, `StillRidingNotifier`)
- Ride history, ride detail, and stop grouping/renaming along a route
- Static route image rendering (`StaticImageRenderer`, `RouteFrameDrawer`)
- Animated multi-day trip video rendering via `MediaCodec`/`MediaMuxer`, run in the background
  through WorkManager (`VideoRenderWorker`), with per-day photos and captions, stop exclusion,
  and duration customization
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
- Photo backgrounds and animation polish for rendered videos
- Migration to Jetpack Compose, Room, and Hilt (current code uses XML layouts + Fragments +
  Navigation component, a hand-rolled `RideDbHelper` SQLite wrapper, and manual dependency
  wiring instead)
- `EncryptedSharedPreferences` for API key storage (design spec §5.7; currently plain
  `SharedPreferences`)

## Tech stack

Current implementation:

- Kotlin, XML layouts + Fragments, Jetpack Navigation component
- Plain `SharedPreferences`-backed repositories (no Room/Hilt yet)
- Raw SQLite via a hand-rolled `RideDbHelper` for ride/fuel data
- `FusedLocationProviderClient` + `ForegroundService` for background GPS tracking
- `MediaCodec` / `MediaMuxer` for hardware-accelerated video rendering, driven by WorkManager

Planned (per the design spec, not yet wired in):

- Jetpack Compose UI, MVVM (ViewModel + StateFlow), Hilt for DI
- Room for structured data
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
