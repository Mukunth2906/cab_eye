
# Google Maps + Places: dynamic destinations

[← Back to the README](../README.md)

Optional. Without a key the app uses its offline Coimbatore atlas and the built-in gazetteer
(see [MIND-MAP-AGENT.md](MIND-MAP-AGENT.md#no-paid-services-the-offline-coimbatore-atlas)).

This version adds Google Maps Platform without replacing the existing voice, intent, accessibility,
WebSocket, or Spring Boot ride-state architecture.

## What changed

The old destination path was:

```text
Voice -> IntentParser -> Gazetteer/Cities.kt -> MatchGate -> Booking
```

The new primary path is:

```text
Voice -> IntentParser -> Google Places (New) -> MatchGate -> Booking
                                      |
                                      +-> real name/address/lat/lng/Place ID
                                      +-> Google Maps marker
```

The existing Gazetteer is still kept as a fallback. If the Google key is missing, the device is
offline, Google returns no usable result, or a request fails, the original hardcoded matching path
still works. This means the new feature does not remove the original demo functionality.

## Google Cloud setup

Enable these APIs/SDKs in the same Google Cloud project:

1. **Places API (New)** — dynamic destination search.
2. **Maps SDK for Android** — display the selected destination on the map.

Routes API is not required by this implementation. It can be added later when Cab Eye starts
calculating real route distance/ETA.

Google currently documents Places SDK for Android 5.3.0 and Maps SDK for Android 20.0.0. The
implementation uses those fixed versions so the build is reproducible.

## API key setup

The app expects the key in `android/local.properties`:

```properties
GOOGLE_MAPS_API_KEY=YOUR_KEY_HERE
```

`local.properties` is already ignored by git. Do not commit the real key.

For this native Android app, restrict the key to:

- Application restriction: **Android apps**
- Package name: `com.cabeye.rider`
- SHA-1: the SHA-1 certificate for the debug build you use on your phone

Also apply API restrictions so the key is limited to the Maps/Places services used by this app.

## What the app does now

Example:

```text
User: "Take me to PSG Tech"
        |
        v
SpeechRecognizer
        |
        v
IntentParser
        |
        v
"PSG Tech"
        |
        v
Places API (New) / Text Search
        |
        v
PSG College of Technology
Peelamedu, Coimbatore
Latitude / Longitude / Place ID
        |
        v
MatchGate
        |
        v
Voice: "Booking auto to PSG College of Technology, Peelamedu, Coimbatore..."
        |
        v
Google Map shows the selected destination marker
        |
        v
Existing 5-second cancel window
        |
        v
Spring Boot booking backend receives the destination coordinates
```

## Location behavior

The app requests normal Android location permission and uses the current device location as a
**search bias**, not a hard restriction. Therefore a user can still explicitly say a destination
in another city. If location is unavailable, Places search continues without the location bias.

## Booking payload

The rider now sends the existing destination name plus optional real-world metadata:

```json
{
  "destination": "PSG College of Technology",
  "destinationAddress": "Peelamedu, Coimbatore, Tamil Nadu",
  "destinationLatitude": 11.0,
  "destinationLongitude": 77.0,
  "destinationPlaceId": "ChIJ...",
  "pickupLatitude": 11.0,
  "pickupLongitude": 77.0,
  "rideType": "AUTO"
}
```

The backend stores the coordinates with the ride (H2 on one machine, PostgreSQL in the Docker
setup).

## Important accessibility decision

The map is a **visual confirmation layer**, not the primary interface. The blind rider continues
to use the existing voice/TTS/haptic flow. The map preview is non-interactive so it does not
steal the existing whole-screen hold-to-talk gesture.

The app does not automatically book an uncertain Google result. Google results are still passed
through the existing MatchGate/clarification logic.

## Build and test

From `android/`:

```text
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

On Windows Command Prompt:

```bat
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

To use Google Places/Maps, create `android/local.properties` with the key shown above, then sync
and run the app on an Android device/emulator with Google Play services.

## Recommended demo

1. Enable Places API (New) and Maps SDK for Android.
2. Add the restricted key to `android/local.properties`.
3. Install the debug APK.
4. Grant microphone and location permissions.
5. Hold the Cab Eye surface.
6. Say a real place that is **not** in `Cities.kt`, for example a specific college, hospital,
   mall, airport, or railway station.
7. The app should resolve it through Google Places, speak the result, show its marker, and then
   continue through the existing booking flow.

## Limitations of this milestone

- Google Places/Maps require a valid Google Maps Platform billing-enabled project and API key.
- Routes/ETA from Google Routes API are not part of this milestone.
