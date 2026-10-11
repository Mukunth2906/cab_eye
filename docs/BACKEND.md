# Backend: architecture and API

[← Back to the README](../README.md)

A Spring Boot 3 (Java 17) server in `backend/`. It matches riders with drivers, carries each
ride's events over a WebSocket, computes fares, keeps accounts and ride memory, relays the ride
camera, describes camera pictures with Gemini, serves OpenStreetMap data, takes payments, and
runs the admin website.

## Packages (`com.cabeye.backend`)

| Package | What it holds |
|---|---|
| `controller` | The REST endpoints below. |
| `service` | `RideService`: the ride state machine (requested → assigned → arrived → seated → in trip → completed / cancelled), multi-stop logic, event emission. |
| `websocket` | `/ws/ride?rideId=…`: one topic per ride, raw JSON (no STOMP). Every event has a sequence number and a stable `eventId`, so a reconnect replays from `lastSeq` and phones drop duplicates. |
| `model` | `Ride`, `RideStop`, `RideEvent`, `RideEventType` (the contract with both apps). |
| `auth`, `account` | Phone + one-time code sign-in (`OtpService`, `SmsSender`: Twilio / Fast2SMS / demo), sessions and tokens, rider and driver profiles. `/me/**` needs a token. |
| `memory` | Ride memory per account: completed trips, visited places with time-of-day habits, learned aliases, saved multi-stop routes, how the rider answered suggestions. A simulator (local only) seeds weeks of history. |
| `fare` | `TripDistanceTracker` (km from the driver's GPS, with jitter and glitch filtering) and `FareCalculator` (server-side fare). See [FARE-AND-FEEDBACK.md](FARE-AND-FEEDBACK.md). |
| `feedback` | Rider ratings and reports (safety words → urgent), drivers' reports on riders. |
| `payment` | Orders, the mock gateway, Razorpay checkout and verification. |
| `camera` | `RideCameraService`: the driver asking to see the rider's camera, consent, relaying frames. See [EXPLORE-AND-CAMERA.md](EXPLORE-AND-CAMERA.md). |
| `vision` | `GeminiClient` (keys × models fallback) and `VisionService` ("what's in front of me", with the server-side safety filter). |
| `mindmap` | `InterpretService`: reads a sentence the phone's rules couldn't (Grok, else Gemini), with consent. |
| `places` | `OsmService`: area names (Nominatim) and well-known places (Overpass), cached and rate-limited. |
| `admin` | The admin website's API, cases, SOS and urgent alerts to Telegram and email. |
| `store`, `persistence` | H2 file database by default (`backend/data/`), PostgreSQL with `CABEYE_DB_URL`; JSON tables. |
| `redis` | Optional: shares ride events between several backend instances (Docker cluster). |

## Endpoints

### Rides (`/rides`)

| | |
|---|---|
| `POST /rides` | Book (destination name, address, coordinates, place id, pickup, ride type, stops). |
| `GET /rides/{id}`, `GET /rides/{id}/events` | Snapshot (used to reconcile after a reconnect); event log. |
| `GET /rides/open` | Requests waiting for a driver. |
| `POST /rides/{id}/accept`, `/enroute`, `/location`, `/preset`, `/beacon`, `/arrived` | Driver steps before pickup. |
| `POST /rides/{id}/code` | Boarding-code confirmation. |
| `POST /rides/{id}/seated`, `/start` | Start is refused (409) before seated. |
| `POST /rides/{id}/trip-location` | Driver GPS during the trip (the km meter). |
| `POST /rides/{id}/complete`, `/cancel` | Completion computes the fare on the server. |
| `POST /rides/{id}/stops/{stopId}/arrived`, `/done`, `/skip`; `PUT /rides/{id}/stops` | Multi-stop. |
| `GET/POST /rides/{id}/camera`, `/camera/request`, `/camera/answer`, `/camera/stop` | Ride camera. |
| `POST /rides/{id}/feedback`, `/rider-feedback` | Rider's feedback; driver's rating of the rider. |
| `POST /rides/{id}/payment`, `/payment/order`, `/payment/confirm` | Payment. |

### Account and personal (`/auth`, `/me`; token required for `/me/**`)

| | |
|---|---|
| `POST /auth/otp`, `/auth/verify`, `/auth/logout` | Sign in by phone and code. |
| `GET /me`, `PATCH /me/profile`, `POST /me/logout-everywhere` | Profile. |
| `GET/DELETE /me/memory`, `POST /me/memory/outcome`, `/routes`, `/routes/used`, `DELETE /me/memory/places`, `POST /me/memory/simulate` | Ride memory. |
| `GET /me/feedback` | The rider's feedback. |
| `GET /me/vision`, `POST /me/vision/describe` | Camera descriptions (Gemini). 404 without a key. |
| `POST /me/mindmap/interpret` | Sentence reader. 404 without a Grok or Gemini key. |
| `GET /me/places/area`, `/me/places/nearby` | OpenStreetMap area names and well-known places. |

### Other

| | |
|---|---|
| `GET /health`, `/ready`, `/whoami` | Health checks. |
| `/pay/{orderId}`, `/payments/{orderId}…` | Checkout pages (mock and Razorpay). |
| `/admin`, `/admin/api/…` | Admin website: overview, cases (status, notes), rides, drivers (suspend), riders, Telegram link, test alert. |
| `POST /debug/broadcast` | Debug only: needs the dev profile and `cabeye.debug.token`; returns 404 otherwise (indistinguishable from absent), and does not exist under `prod`. |
| `/` | A browser test console for the WebSocket. |

## WebSocket events

`RIDE_CREATED, RIDE_ASSIGNED, DRIVER_ENROUTE, DRIVER_LOCATION, POSITION_PRESET, BEACON,
DRIVER_ARRIVED, CODE_CONFIRMED, PASSENGER_SEATED, TRIP_STARTED, ROUTE_DEVIATION, TRIP_PROGRESS,
TRIP_COMPLETED, RIDE_CANCELLED, PAYMENT_UPDATED, STOP_ARRIVED, RIDER_RETURNED, STOP_DONE,
STOP_SKIPPED, STOPS_CHANGED, WAIT_WARNING, WAIT_OVERDUE`, plus the camera's
`CAMERA_REQUESTED / STARTED / DECLINED / STOPPED / FRAME`.

The server stamps the sender from the session, never from the message body, so a client can't
speak as the driver. A reconnecting phone fetches the REST snapshot and announces only what
changed ("Your car has arrived"), not the backlog.

## Configuration

`src/main/resources/application.properties` holds the defaults. Secrets go in
`backend/cabeye-secrets.properties` (template: `cabeye-secrets.example.properties`), imported at
start-up. Every key can also be an environment variable. The full table is in
[SETUP.md](SETUP.md#keys-and-secrets).

`application-prod.properties` turns off the debug endpoint, code exposure and the memory
simulator.

## Tests

```powershell
cd backend
.\gradlew.bat test
```

There are endpoint tests for auth, rides, multi-stop, memory, fare and the trip meter, feedback,
payments, the camera, vision, the mind-map reader, OSM places, SMS and admin. External
services (Gemini, Grok, OSM, SMS, Razorpay) are stubbed, so the tests need no network or keys.
