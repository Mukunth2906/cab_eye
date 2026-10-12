# Architecture

[← Back to the README](../README.md)

Cab Eye, drawn at increasing levels of detail. Each level zooms into one box of the level
above it: the system in its world, then its deployable parts, then the components inside the
phone app and the server, then how a ride moves, the main flows step by step, how input is
routed, and where every piece of data lives.

The diagrams are [Mermaid](https://mermaid.js.org/). GitHub and most Markdown viewers draw
them; anything else shows their source, which is written to be readable too.

| Level | Diagram | Answers |
|---|---|---|
| 1 | [System context](#1-system-context) | Who uses Cab Eye, and what it talks to |
| 2 | [Containers and deployment](#2-containers-and-deployment) | What runs where |
| 3 | [Phone app components](#3-phone-app-components) | How the app is put together |
| 3 | [Backend components](#4-backend-components) | How the server is put together |
| 4 | [Ride lifecycle](#5-ride-lifecycle) | Every state a ride passes through, on both sides |
| 4 | [Flows](#6-flows) | Booking to pickup, reconnecting, explore, ride camera, SOS |
| 4 | [Input routing](#7-input-routing) | What each touch and key press does |
| 4 | [Data and privacy](#8-data-and-privacy) | What is stored where, and what leaves the phone |

---

## 1. System context

```mermaid
flowchart TB
    rider(["👤 Blind or low-vision rider<br/>voice, touch, volume keys"])
    driver(["👤 Driver<br/>touch, silent screen"])
    admin(["👤 Support / admin<br/>web browser, Telegram"])

    subgraph cabeye["Cab Eye"]
        app["📱 Cab Eye app<br/>rider mode and driver mode"]
        server["🖥️ Cab Eye backend"]
    end

    rider <--> app
    driver <--> app
    admin <--> server
    app <--> server

    gemini["Google Gemini<br/>scene descriptions, sentence reader"]
    grok["xAI Grok<br/>sentence reader, optional"]
    osm["OpenStreetMap<br/>Nominatim, Overpass"]
    places["Google Places and Maps<br/>optional"]
    pay["Razorpay<br/>optional, mock otherwise"]
    sms["Twilio or Fast2SMS<br/>optional"]
    alerts["Telegram and Gmail<br/>SOS and urgent alerts"]

    server --> gemini
    server --> grok
    server --> osm
    server --> pay
    server --> sms
    server --> alerts
    app --> places
```

Every external service is optional. With no keys at all, the app books rides, describes the
camera with its on-device detector, finds places in its offline Coimbatore atlas, takes mock
payments and shows sign-in codes on screen.

---

## 2. Containers and deployment

```mermaid
flowchart LR
    subgraph phoneR["Rider's phone"]
        rapp["Cab Eye app<br/>rider mode"]
        rstore[("On-phone storage<br/>mind map, walks,<br/>explore finds")]
        rapp --- rstore
    end

    subgraph phoneD["Driver's phone"]
        dapp["Cab Eye app<br/>driver mode"]
    end

    subgraph host["PC, or a cloud host"]
        direction TB
        ingress["ngrok tunnel, adb reverse,<br/>or Nginx load balancer"]
        b1["Spring Boot backend<br/>instance 1"]
        b2["Spring Boot backend<br/>instance 2, Docker only"]
        db[("H2 file database,<br/>or PostgreSQL")]
        redis[("Redis<br/>Docker only")]
        web["Admin website<br/>served by the backend"]
        ingress --> b1
        ingress -.-> b2
        b1 --- db
        b2 -.- db
        b1 -.- redis
        b2 -.- redis
        b1 --- web
    end

    rapp -- "HTTPS REST and<br/>WSS /ws/ride" --> ingress
    dapp -- "HTTPS REST and<br/>WSS /ws/ride" --> ingress
    browser(["Admin's browser"]) --> web
```

| Setup | How phones reach the server | Data |
|---|---|---|
| **USB** (`install-app.ps1`) | `adb reverse` maps the phone's `localhost:8080` to the PC | H2 file in `backend/data/` |
| **Any phone** (`share-with-friends.ps1`) | ngrok gives the PC a public `https://` address, baked into the APK | H2 file |
| **Docker cluster** (`docker-compose.yml`) | Nginx in front of two backend instances | PostgreSQL; Redis shares ride events between instances |
| **AWS** (`aws/`) | Load balancer in front of containers | PostgreSQL, Redis |

---

## 3. Phone app components

One APK, one Activity. The role (rider or driver) picks the root screen; there is no
navigation stack.

```mermaid
flowchart TB
    subgraph ui["UI: Jetpack Compose"]
        main["MainActivity<br/>role switch, permissions,<br/>volume keys"]
        surface["RiderSurface<br/>one screen, every ride state,<br/>hold and double tap"]
        explorescr["ExploreScreen<br/>camera and boxes"]
        mapscr["MindMapScreen<br/>rides, walks, radar"]
        signin["RiderSignInSurface"]
        drv["DriverSurface"]
        main --> surface & signin & drv
        surface --> explorescr & mapscr
    end

    vm["RiderViewModel<br/>the dialogue: every question,<br/>every module's flow"]
    surface <--> vm
    main --> vm

    subgraph modules["Modules"]
        ride["Ride<br/>booking, multi-stop, trip,<br/>pay, feedback, schedule"]
        cam["Ride camera<br/>consent, stream to driver"]
        explore["Explore<br/>ExploreController"]
        mind["Mind map<br/>MindMapAgent, RideGuide,<br/>catalog, store"]
        walk["Walking routes<br/>record, narrate, guide"]
        memory["Preference memory<br/>habits, repair, aliases"]
    end
    vm --> ride & cam & explore & mind & walk & memory

    subgraph engines["Engines"]
        stt["On-device speech<br/>recognition"]
        tts["TTS, earcons,<br/>panned beacon"]
        camera["CameraStreamer<br/>one camera, three uses"]
        vision["SceneDetector<br/>Gemini via backend,<br/>ML Kit fallback"]
        placesE["Place resolution<br/>memory, Google Places,<br/>offline atlas"]
    end
    vm --> stt & tts & placesE
    cam & explore --> camera --> vision

    subgraph net["Network"]
        api["RideApi<br/>REST"]
        sock["RideSocket<br/>WebSocket, replay, de-dup"]
    end
    vm --> api & sock
    vision --> api
```

The surface is deliberately thin: it shows `RiderUiState` and reports touches. Every
decision (what a press means now, what to say, whether the mic may open) is made in
`RiderViewModel`, which is why the dialogue can be unit-tested on the JVM.

---

## 4. Backend components

```mermaid
flowchart TB
    subgraph httpLayer["HTTP and WebSocket"]
        direction LR
        auth["AuthInterceptor<br/>/me/** needs a token,<br/>the rest is optional"] --> rest["Controllers<br/>rides, stops, camera, vision,<br/>memory, places, mind map,<br/>payments, feedback, SOS, admin"]
        ws["RideWebSocketHandler<br/>/ws/ride?rideId=…"]
    end

    subgraph ridesG["Rides"]
        direction TB
        rides["RideService<br/>state machine, events, multi-stop"]
        fare["TripDistanceTracker<br/>FareCalculator"]
        camsvc["RideCameraService<br/>consent and relay"]
        rides --> fare
    end

    subgraph aiG["Vision and language"]
        direction TB
        visionsvc["VisionService<br/>clear and safe filter"]
        interp["InterpretService<br/>Grok, else Gemini"]
        gem["GeminiClient<br/>keys × models fallback"]
        visionsvc --> gem
        interp --> gem
    end

    subgraph peopleG["People and places"]
        direction TB
        otp["OtpService, SmsSender"]
        mem["MemoryService<br/>trips, places, habits"]
        osmsvc["OsmService<br/>cached, rate-limited"]
    end

    subgraph supportG["Money and support"]
        direction TB
        pay["Payments<br/>mock or Razorpay"]
        fb["FeedbackService"]
        cases["CaseService<br/>feedback, driver reports,<br/>stop overdue, SOS"]
        alert["AlertService<br/>Telegram, email"]
        fb --> cases --> alert
    end

    subgraph store["Storage"]
        direction LR
        tables[("JSON tables and H2,<br/>or PostgreSQL")]
        bridge["RedisBridge<br/>multi-instance events"]
    end

    rest --> ridesG & aiG & peopleG & supportG
    ws <--> rides
    camsvc --> ws
    rides --> cases
    ridesG & peopleG & supportG --> tables
    rides <--> bridge
```

---

## 5. Ride lifecycle

The server owns the ride (`RidePhase`). The rider's phone shows its own, finer states
(`RiderState`), several of which exist only on the phone: listening, clarifying, the
five-second cancel window, and the post-ride questions.

```mermaid
stateDiagram-v2
    direction TB
    [*] --> REQUESTED: rider books (after the 5 s cancel window)
    REQUESTED --> ASSIGNED: driver accepts
    ASSIGNED --> ENROUTE: driver sets off
    ENROUTE --> ARRIVED: I HAVE ARRIVED
    ASSIGNED --> ARRIVED
    ARRIVED --> SEATED: code confirmed, passenger seated
    SEATED --> IN_TRIP: START TRIP (refused before seated)
    IN_TRIP --> IN_TRIP: stops arrived, done, skipped
    IN_TRIP --> COMPLETED: COMPLETE TRIP, fare computed on the server
    REQUESTED --> CANCELLED
    ASSIGNED --> CANCELLED
    ENROUTE --> CANCELLED
    ARRIVED --> CANCELLED
    COMPLETED --> [*]
    CANCELLED --> [*]
```

```mermaid
stateDiagram-v2
    direction LR
    state "Rider's screen" as phone {
        Idle --> Listening: hold
        Listening --> Resolving: let go
        Resolving --> Clarify: two far-apart places
        Clarify --> Confirming: choice
        Resolving --> Confirming: one clear place
        Resolving --> Planning: stops mentioned
        Planning --> Confirming: yes
        Confirming --> Finding: window passes
        Confirming --> Idle: cancel
        Finding --> Assigned: RIDE_ASSIGNED
        Assigned --> Approaching: DRIVER_ENROUTE, DRIVER_LOCATION
        Approaching --> Arrived: DRIVER_ARRIVED
        Arrived --> InTrip: TRIP_STARTED
        InTrip --> Done: TRIP_COMPLETED
        Done --> Feedback: paid
        Feedback --> NextJourney
        NextJourney --> Idle: done
        Idle --> Walking: guide me
        Walking --> Idle: I'm there
    }
```

SOS, the ride camera, explore and the mind map are **overlays**, not states: raising one never
loses the ride state underneath, and closing it returns the rider exactly where they were. The
mind map closes by itself if the ride moves on underneath it.

---

## 6. Flows

### Booking to pickup, with the boarding code running backwards

```mermaid
sequenceDiagram
    autonumber
    actor R as Rider
    participant RA as Rider app
    participant S as Backend
    participant DA as Driver app
    actor D as Driver

    R->>RA: hold, "take me to PSG Tech", let go
    RA->>RA: on-device speech → intent → place, confidence gate
    RA-->>R: "Booking auto to PSG College… say cancel to stop"
    Note over RA: 5-second cancel window, honoured
    RA->>S: POST /rides
    S-->>DA: RIDE_CREATED, request shown with PASSENGER IS VISUALLY IMPAIRED
    D->>DA: ACCEPT
    DA->>S: POST /rides/{id}/accept
    S-->>RA: RIDE_ASSIGNED
    RA-->>R: "Karthik is coming"
    loop approach
        DA->>S: location, preset, beacon
        S-->>RA: DRIVER_LOCATION, POSITION_PRESET, BEACON
        RA-->>R: rising tone panned to the car's side, presets spoken
    end
    D->>DA: I HAVE ARRIVED
    S-->>DA: three-digit code on the driver's screen
    S-->>RA: DRIVER_ARRIVED
    RA-->>R: "Your car has arrived. Ask the driver to say the code."
    D-->>R: says the code out loud
    R->>RA: hold while the driver speaks
    RA->>S: POST /rides/{id}/code
    S-->>RA: CODE_CONFIRMED
    RA-->>R: "That's the right code. This is your car."
```

The rider's phone never announces the code: a blind person on a street can't tell who is
listening. With headphones in, it whispers the expected code through the earpiece first.

### Losing and regaining the connection

```mermaid
sequenceDiagram
    participant RA as Rider app
    participant S as Backend
    RA-xS: socket drops
    RA-->>RA: "Connection lost. Reconnecting." — said once
    loop backoff with jitter, capped at 30 s
        RA->>S: reconnect /ws/ride?lastSeq=n
    end
    S-->>RA: events after n, each with a stable eventId
    RA->>RA: drop events already seen
    RA->>S: GET /rides/{id}
    RA-->>RA: announce only what changed: "Your car has arrived."
```

### Explore: "what's in front of me"

```mermaid
sequenceDiagram
    actor R as Rider
    participant ES as Explore screen
    participant EC as ExploreController
    participant SD as SceneDetector
    participant S as Backend VisionService
    participant G as Gemini
    participant ML as ML Kit on the phone
    participant MM as Mind map, explore store

    R->>ES: double tap the main screen
    ES->>EC: open: camera on, walk recording starts
    R->>ES: Describe, or hold and say "what's in front of me"
    EC->>SD: describe(picture)
    SD->>S: POST /me/vision/describe
    alt Gemini configured and answering
        S->>G: picture and walking-focused prompt
        G-->>S: objects, boxes, hazards, text
        S->>S: clamp boxes, work out clock positions,<br/>remove any "clear" or "safe" claim
        S-->>SD: Scene
    else no key, rate limit or offline
        SD->>ML: detect on the phone
        ML-->>SD: Scene, coarser names
    end
    SD-->>EC: Scene
    EC-->>R: "Steps going down, ahead. A door at 1 o'clock, about 3 metres."
    EC->>MM: things worth remembering, with position and side
    R->>ES: Save & finish
    EC->>MM: the walk, with what was seen as its landmarks
```

### The ride camera

```mermaid
sequenceDiagram
    actor D as Driver
    participant DA as Driver app
    participant S as Backend RideCameraService
    participant RA as Rider app
    actor R as Rider

    D->>DA: SEE PASSENGER'S VIEW
    DA->>S: POST /rides/{id}/camera/request
    S-->>RA: CAMERA_REQUESTED
    RA-->>R: "Karthik can't see you and would like to see your camera."
    R->>RA: yes, or SHARE MY CAMERA
    RA->>S: POST /camera/answer
    S-->>DA: CAMERA_STARTED
    loop about 4 pictures a second, while live
        RA->>S: CAMERA_FRAME
        S-->>DA: relayed to the driver only, never stored
        RA-->>R: what's new around them, from the on-phone detector
    end
    Note over S: stops itself at code confirmed, seated,<br/>trip start or end, cancel, or 3 minutes
    S-->>RA: CAMERA_STOPPED
```

### SOS

```mermaid
sequenceDiagram
    actor R as Rider
    participant RA as Rider app
    participant S as Backend
    participant C as CaseService
    participant A as Telegram and email

    R->>RA: hold SOS for 2 seconds, from any screen
    RA-->>R: "S O S. Sending your location to Cab Eye support."
    RA->>RA: phone's position
    loop up to 4 tries, backing off
        RA->>S: POST /sos {rideId, lat, lng}
    end
    S->>C: urgent SOS case: rider, ride, driver, plate,<br/>emergency contact, position
    C->>A: alert, once per emergency, later presses update the position
    S-->>RA: caseId, alerted
    alt delivered
        RA-->>R: "Sent. Cab Eye support has your S O S and your location."
    else not delivered
        RA-->>R: "I couldn't reach Cab Eye support. If you are in danger, call 1 1 2."
    end
```

The app says only what actually happened. A guest, or a rider whose session expired, still
gets through.

---

## 7. Input routing

The microphone opens only while the rider presses. One flowchart for every input:

```mermaid
flowchart LR
    subgraph talk["Talk"]
        hold["Hold the screen"]
        vhold["Hold a volume key<br/>more than 350 ms"]
    end
    subgraph exp["Open Explore"]
        dtap["Double tap the screen<br/>ready screen only"]
        bexp["📷 Explore button<br/>or TalkBack action"]
    end
    subgraph mm["Open or close the mind map"]
        vdown3["Volume down ×3<br/>each within 600 ms"]
        bmap["🧭 My mind map button<br/>or TalkBack action"]
    end
    subgraph other["Everything else"]
        tap["Tap, in the cab"]
        vshort["Short volume press"]
        sos["Hold SOS 2 s, any screen"]
    end

    hold & vhold --> mic["Mic opens for whatever<br/>is being asked now"]
    dtap & bexp --> explore["Explore opens<br/>a double tap's first press<br/>is dropped silently"]
    vdown3 & bmap --> map["Mind map opens or closes<br/>only when no ride is being booked;<br/>the volume is put back"]
    tap --> where["Where are we"]
    vshort --> vol["Volume step"]
    sos --> sosflow["SOS sent to support"]

    mic --> purpose{"What is open?"}
    purpose -->|exploring| e1["describe, find, read,<br/>this is…, finish"]
    purpose -->|a question asked| e2["the answer"]
    purpose -->|in the cab| e3["ride words, cues,<br/>never a new booking"]
    purpose -->|at the kerb| e4["the driver's code"]
    purpose -->|ready screen| e5["a destination, or a command"]
```

---

## 8. Data and privacy

```mermaid
flowchart LR
    subgraph phone["Stays on the phone"]
        mm[("Mind map<br/>cabeye.mindmap.rides<br/>cabeye.mindmap.explore")]
        walks[("Walks<br/>cabeye.walks")]
        prefs[("Preferences<br/>theme, speech rate,<br/>camera always-share")]
        audio["Microphone audio<br/>recognised on the phone,<br/>never uploaded"]
    end

    subgraph server["On the server"]
        acct[("Accounts, sessions")]
        ridesT[("Rides, events, fares,<br/>payments")]
        memT[("Ride memory<br/>trips, places, habits")]
        fbT[("Feedback, admin cases")]
    end

    subgraph once["Sent only when asked, one at a time"]
        pic["One camera picture<br/>to Gemini"]
        sentence["One sentence,<br/>after consent,<br/>to Grok or Gemini"]
        frames["Ride camera frames<br/>to the driver, relayed, not stored"]
        pos["Rounded positions<br/>to OpenStreetMap, via the server"]
    end

    phone -. "describe, find, read" .-> pic
    phone -. "a note the rules<br/>can't read" .-> sentence
    phone -. "rider said yes" .-> frames
    phone == "bookings, completed rides,<br/>feedback, payments" ==> server
```

| Data | Where | Why there |
|---|---|---|
| Speech | The phone's recogniser | Seconds matter, and audio is private |
| Mind map, walks, camera finds | The phone (SharedPreferences) | The rider's own map of their life; nothing to gain by uploading it |
| Ride memory (habits, places) | Server, per account | Survives a new phone; powers "like most weekday mornings" |
| Rides, fares, payments, feedback | Server | Shared between rider, driver and support |
| Camera pictures | Nowhere | Described or relayed, then forgotten |
| OSM lookups | Server cache | Positions rounded to ~11 m, IPs not passed on, cached to respect OSM's limits |

---

## How it's checked

| Layer | Tests |
|---|---|
| Dialogue, agents, parsers | Android JVM tests (`gradlew testDebugUnitTest`): booking dialogue, memory agent, walks, mind map, ride guide, vision and explore, distance display |
| Every screen, every theme | `ScreenGalleryTest` renders all rider screens in all three themes to PNG, no phone needed (`-Pscreenshots`) |
| Server | Spring Boot endpoint tests (`gradlew test`): rides, multi-stop, fare, camera, vision, memory, OSM, payments, SMS, admin, SOS; external services stubbed |
| Real phones | [TESTING-NEXT-PHASE.md](../TESTING-NEXT-PHASE.md) |
