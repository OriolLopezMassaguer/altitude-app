# Altitude App

An Android application that displays your current GPS altitude in real-time at the top of the screen.

## Features

- ✅ Real-time GPS altitude display in meters
- ✅ Automatic location permission handling
- ✅ Updates every 5 seconds
- ✅ Clean, simple interface
- ✅ Shows GPS status

## Requirements

- Android 7.0 (API level 24) or higher
- GPS/Location services enabled
- Location permission granted

## Setup

1. Clone this repository
2. Open the project in Android Studio
3. Sync Gradle files
4. Run on a physical device (emulator GPS may not provide accurate altitude)

## Permissions

The app requires the following permissions:
- `ACCESS_FINE_LOCATION` - for precise GPS altitude data
- `ACCESS_COARSE_LOCATION` - fallback location access

## Usage

1. Launch the app
2. Grant location permissions when prompted
3. Wait for GPS signal acquisition
4. Altitude will be displayed at the top of the screen in meters

## Building

```bash
./gradlew assembleDebug
```

## License

MIT License