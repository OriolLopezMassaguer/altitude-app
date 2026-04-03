# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug

# Install on connected device
./gradlew installDebug
```

There are no automated tests in this project.

## Architecture Overview

**MotoPass** is an Android app (package `com.example.altitudeapp`, app name "MotoPass") that tracks GPS altitude and nearby mountain passes for motorcyclists. Despite the repo/folder name being "altitude-app", the product name is MotoPass.

### Core Components

**`AltitudeService.kt`** — A foreground `Service` that:
- Uses `FusedLocationProviderClient` for GPS updates (every 60s, min 30s interval)
- Parses GPX waypoint files from `assets/passes/` to build an in-memory `Set<MountainPass>`
- Computes altitude gain over a rolling 1-minute window
- Detects the nearest pass within 50 km (`PROXIMITY_THRESHOLD_METERS`)
- Returns all passes within 500 km radius (`SEARCH_RADIUS_KM`)
- Broadcasts results via local `Intent` with action `ACTION_LOCATION_UPDATE`
- Also broadcasts log messages with action `ACTION_LOG_UPDATE`
- Holds a `WakeLock` to stay alive in background

**`MainActivity.kt`** — Single activity that:
- Registers a `BroadcastReceiver` for service broadcasts in `onResume`/`onPause`
- Renders an OSMDroid map with a user position marker and nearby-pass markers
- Double-tap on a pass marker opens Google Maps navigation
- Toggles between map and RecyclerView list (mutually exclusive display)
- Double-tap anywhere on screen shows/hides the debug log view
- Persists last known position to `SharedPreferences` for cold-start display
- Supports EN/ES/CA locale switching (default: Catalan) via `attachBaseContext`

**`PassAdapter.kt`** — `RecyclerView.Adapter` that renders `MountainPass` items sorted by distance from user.

**`MountainPass`** data class (defined in `AltitudeService.kt`) — `Serializable`, with custom `equals` that considers two passes equal if their names match and they are within 100 m of each other (deduplication).

### Data Flow

```
GPS (FusedLocationProviderClient)
  → AltitudeService (processes location, looks up passes)
    → LocalBroadcast (ACTION_LOCATION_UPDATE)
      → MainActivity.receiver
        → updateUI() + updateMapLocation() + updateNearbyMarkers() + PassAdapter
```

### Mountain Pass Data

GPX files are stored in `app/src/main/assets/passes/`. Each file covers a geographic region (e.g., Spanish autonomous communities, European countries). Only `<wpt>` elements with `lat`/`lon` attributes and a `<name>` child are loaded. Files are parsed at service startup.

### Key Configuration

- `minSdk = 24` (Android 7.0), `targetSdk = 34`, `compileSdk = 34`
- Map tiles: OSMDroid (MAPNIK source) — Google Maps SDK removed, no API key needed for maps
- `AndroidManifest.xml` still has a placeholder `YOUR_API_KEY` meta-data entry (legacy, unused)
- ViewBinding and DataBinding are both enabled
- Kotlin + kapt (no Compose)

### Localization

String resources exist in `values/` (English), `values-es/` (Spanish), and `values-ca/` (Catalan). The default language stored in `SharedPreferences` key `"language"` is `"ca"`. Language changes call `recreate()`.
