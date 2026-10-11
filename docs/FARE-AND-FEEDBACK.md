
# Real-time km, server-side fare and feedback

[← Back to the README](../README.md)

Branch: `feature/fare-km-feedback`. Covers two items from the team's task list: *"payment amount
and km of distance to be updated to rider — dynamic, not a single value"* and *"feedback
enhancement"* (agreed scope: show km + fare with every complaint, and let the driver give
feedback on the rider).

## What changed for each person

| Who | Before | Now |
|---|---|---|
| **Rider** | Every trip cost ₹148 and 12 min, whatever happened. Arrival said "You've arrived. 148 rupees." | Hears the real figures at the end: *"You've arrived. 5.6 kilometres, 124 rupees."* During the trip, saying **"status"** or **"how far"** answers *"On the way to Gandhipuram. 3.2 kilometres so far, about 98 rupees."* Nothing is spoken on its own while driving — silence still means all is fine. |
| **Driver** | Tapped COMPLETE and the app sent a hard-coded fare. | The phone sends its GPS during the trip; the In-trip screen shows **km and fare so far**. The Complete screen shows the final km, and a **Rate your passenger** panel (1–5, a reason, an optional note). |
| **Admin** | A complaint showed the rider's words only. | Every feedback case shows **Trip: 5.6 km · ₹124 · 14 min**, so "overcharged" or "long route" can be checked at a glance. Drivers' reports appear as **Driver's report on rider**. The Rides table has a Distance column; the Riders table shows each rider's rating from drivers and how many reports they have. |

## How the fare is worked out

```
driver phone ──GPS fix every ~4 s──▶ POST /rides/{id}/trip-location {lat,lng,at}
                                         │
                                         ▼
                         TripDistanceTracker (on the server, per ride)
                         · ignores jitter under 10 m (a parked car does not "drive")
                         · ignores jumps faster than 55 m/s (~200 km/h GPS glitches)
                         · after 3 glitches in a row, accepts the car really moved
                                         │ every 100 m
                                         ▼
                   TRIP_PROGRESS event {distanceMeters, fareRupees, minutes}
                   → driver screen updates, rider phone stores it silently
                                         │
driver taps COMPLETE ──▶ POST /rides/{id}/complete   (any fare in the body is IGNORED)
                                         │
                                         ▼
               distance = GPS meter, or — if no GPS ever arrived — the straight line
                          pickup → destination, marked "ESTIMATE" (a lower bound)
               minutes  = trip start → now, rounded up (at least 1)
               fare     = max(minimum, base + per-km × km + per-minute × minutes)
```

The fare is computed **on the server** so a phone can never name its own price. The payment order
uses that same figure.

### Rates — PLACEHOLDERS, the team must set real ones

| | base | per km | per minute | minimum |
|---|---|---|---|---|
| AUTO | ₹40 | ₹15 | ₹1 | ₹50 |
| CAB | ₹60 | ₹18 | ₹1.5 | ₹80 |

These are **not real tariffs**. Change them without touching code, in
`backend/src/main/resources/application.properties` (`cabeye.fare.auto.per-km` …) or with
environment variables, e.g.:

```powershell
$env:CABEYE_FARE_AUTO_BASE = "50"
$env:CABEYE_FARE_AUTO_PER_KM = "18"
.\run-backend.ps1
```

The backend logs the rates it is using at start-up (`FARES auto=… cab=…`) and every fare it
computes (`FARE ride=… distance=…m (GPS) minutes=… fare=Rs…`).

## API changes

| Endpoint / event | Change |
|---|---|
| `POST /rides/{id}/trip-location` | **New.** Body `{"lat":11.02,"lng":76.96,"at":<epoch ms, optional>}`. Driver only, IN_TRIP only (409 otherwise; 403 for a signed-in account that is not this ride's driver; 400 without lat/lng). |
| `POST /rides/{id}/complete` | Body no longer needed. `fareRupees` / `durationMinutes` sent by older app builds are ignored and logged. |
| `POST /rides/{id}/rider-feedback` | **New.** The driver rates the passenger: `{"rating":1-5,"category":"SAFETY\|BEHAVIOUR\|PICKUP\|PAYMENT\|OTHER","text":"…"}`, any part optional, at least one required. Only after COMPLETED, only by that ride's driver, rating counted once per ride. Safety words in the text file it as SAFETY + urgent. |
| `TRIP_PROGRESS` (WebSocket) | **New event**, about every 100 m: `distanceMeters`, `fareRupees`, `minutes`. Older app builds ignore it (it parses as `UNKNOWN`). |
| `TRIP_COMPLETED` (WebSocket) | Payload now also has `distanceMeters` and `distanceSource` (`GPS` / `ESTIMATE`). |
| Ride snapshot (`GET /rides/{id}`) | New fields: `tripDistanceMeters`, `distanceSource`, `liveFareRupees`. |
| `POST /rides/{id}/feedback` (response) | Now includes `distanceMeters`, `distanceSource`, `fareRupees`, `durationMinutes`. |
| Admin `GET /admin/api/cases` | Cases carry the same four trip fields; new kind `DRIVER_REPORT` (only ratings of 1–2, written notes or safety reports become cases — a plain rating of 3–5 does not clutter the inbox). |
| Admin `GET /admin/api/riders` | New: `ratingAverage`, `ratingCount`, `driverReports`. |
| Admin `GET /admin/api/drivers` | `complaints` no longer counts a driver's *own* reports about passengers. |

## Data

All additive — nothing existing is renamed or removed, and rides/feedback saved before this
branch load with the new fields empty.

- Ride rows: trip start time, measured distance and its source, live fare, and the meter's own
  state — so a backend restart **mid-trip** keeps the km already measured.
- New table `rider_feedback` (drivers' feedback about passengers).
- Rider accounts: `ratingAverage`, `ratingCount` (server-maintained; the app cannot set them).

## Files

- Backend, new: `fare/FareCalculator.java`, `fare/TripDistanceTracker.java`, `fare/FareConfig.java`,
  `feedback/RiderFeedbackRecord.java`.
- Backend, changed: `model/Ride.java`, `model/RideRecord.java`, `model/RideEventType.java`,
  `service/RideService.java`, `controller/RideController.java`, `controller/FeedbackController.java`,
  `feedback/FeedbackRecord.java`, `feedback/FeedbackService.java`, `account/Account.java`,
  `account/AccountService.java`, `admin/AdminCase.java`, `admin/CaseService.java`,
  `admin/AlertService.java`, `admin/AdminController.java`, `static/admin/index.html`,
  `application.properties`.
- Android, changed: `net/RideModels.kt`, `net/RideApi.kt`, `places/LocationProvider.kt`,
  `driver/DriverState.kt`, `driver/DriverViewModel.kt`, `driver/DriverSurface.kt`,
  `MainActivity.kt` (driver location permission), `RiderViewModel.kt`, `state/RiderState.kt`.
- Tests, new: `fare/FareCalculatorTest.java`, `fare/TripDistanceTrackerTest.java`,
  `TripFareEndpointTest.java`, `RiderFeedbackEndpointTest.java`.

## How to test it

```powershell
cd backend
.\gradlew.bat test        # all backend tests, old and new
cd ..\android
.\gradlew.bat testDebugUnitTest assembleDebug
```

Two-phone check:

1. Driver phone: allow **location** when asked (the driver app now asks once).
2. Book, accept, confirm the code, seat, **start the trip**.
3. The driver's In-trip screen shows *"Waiting for GPS…"*, then km and ₹ so far once the phone
   moves. Phones lying on a desk barely move — walk around outside, or use Android Studio's
   emulator *Extended controls → Location → Routes* to play a route.
4. Rider says **"status"** — hears the km and fare so far.
5. Tap **COMPLETE TRIP** — the rider hears the km and fare; the payment amount is that fare.
   With no GPS at all you will see *"About 5.2 km (straight-line estimate — no GPS)"*.
6. Driver: rate the passenger on the Complete screen.
7. Admin (`http://localhost:8080/admin`): Rides shows the distance; a rider's complaint shows
   **Trip: km · ₹ · min**; a low driver rating or a note shows up as *Driver's report on rider*.

## Known limits

- **Rates are placeholders** (see above).
- The straight-line estimate is used only when no GPS reached the server; it is always shorter
  than the road distance, so it never over-charges.
- Distance comes only from the driver's phone. A signed-in account that is not the ride's driver
  is refused, but — like the rest of the ride API — requests without a sign-in token are still
  accepted for the browser test page. Closing that is part of the planned WebSocket/API
  authentication work.
- A driver's rating of a rider is never used to refuse rides automatically; it is for support to
  read.
