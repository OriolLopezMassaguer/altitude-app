# MotoPass

Android app for motorcyclists that tracks real-time GPS altitude and shows nearby mountain passes on an interactive map. Runs as a persistent foreground service so it keeps working while you ride.

## Features

- Real-time GPS position and altitude tracking (foreground service)
- Detects nearest mountain pass within 50 km
- Shows all passes within 500 km, sorted by distance
- Map view with pass markers (OSMDroid, no API key required)
- Double-tap a marker to open Google Maps navigation to that pass
- Toggle between map view and sorted list view
- Debug log for GPS diagnostics
- 3000+ passes across Europe and beyond in a bundled GPX database

## Pass Coverage

43 GPX files covering: Spain (all 17 autonomous communities), Andorra, France, Italy, Switzerland, Austria, Norway, Alps, Pyrenees, Balkans, Eastern Europe, Morocco, Armenia, Georgia, Turkey.

## Stack

- Kotlin, Android (min SDK 24 / Android 7.0, target SDK 34)
- FusedLocationProviderClient (60 s / 30 s intervals)
- OSMDroid for maps (no API key required)
- LocalBroadcastManager for service → activity communication
- ViewBinding, RecyclerView

## Architecture

| Component | Role |
|---|---|
| `AltitudeService.kt` | Foreground service: GPS tracking, GPX parsing, pass detection, broadcasts |
| `MainActivity.kt` | Map rendering, broadcast receiver, UI state, persists last position |
| `PassAdapter.kt` | RecyclerView adapter for the sorted pass list |
| `MountainPass` | Data class; deduplicates passes by name + 100 m proximity |

Pass data lives in `app/src/main/assets/passes/`.

## Localization

English, Spanish, Catalan (default: Catalan).

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew clean assembleDebug
./gradlew installDebug
```
