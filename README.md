# Cab Eye

Voice-first cab booking for blind users. Two separate apps — rider and driver — that
talk to each other for real, plus a side-by-side test rig for demos and measurements.

## Run it

**Double-click `serve.bat`**, then open <http://localhost:8000/rig.html>.
Or run `python -m http.server 8000` in this folder yourself.

Use **Google Chrome**; speech recognition only works there. Allow the microphone once.

> **Do not double-click `rig.html`.** Chrome refuses microphone access to any page loaded
> from `file://`, so the app comes up looking completely healthy — indicator lit, buttons
> live — with a microphone that was never opened. That one fact accounted for most of
> "the mic doesn't work". `rig.html` now shows a red banner when it detects this, but
> serving the folder is the fix. `localhost` counts as a secure context; no certificate
> needed.

To see them as genuinely separate apps, open `rider.html` in one window and `driver.html`
in another — or on two phones on the same wifi (use the laptop's LAN address; note that
a plain `http://192.168.…` address is *not* a secure context, so on a phone you need
`ngrok`, a self-signed HTTPS server, or Chrome's
`chrome://flags/#unsafely-treat-insecure-origin-as-secure`).

| Page | What it is |
|---|---|
| `index.html` | Role picker — Rider or Driver |
| `rider.html` | The blind user's app. One surface, no navigation |
| `driver.html` | The driver's app. Conventional, sighted |
| `rig.html` | Both apps side by side + instrumentation. **This is the paper view** |

## There is no button. Say "Hey Cab"

Tap-to-speak asks a blind user to find a target on a flat sheet of glass — the one thing
this app exists to avoid. So the trigger is a wake word.

| You say | What happens |
|---|---|
| *"Hey Cab."* | it answers "Yes. Where to?" and listens |
| *"Hey Cab, take me to Adyar."* | **books it, one breath, no round trip** |
| *"Hey Cab, status"* · *"Hey Cab, cancel"* | any time, mid-ride |

The one-breath form is the number worth reporting: a complete booking from a single
utterance, zero taps and zero turns.

Everything else was considered and lost. Hardware keys are invisible to a browser. Shake
fires on every pothole. Long-press is still a touch, and TalkBack claims the gesture. A
wake word is the only trigger that costs nothing to reach — phone in a pocket, screen off,
both hands on a cane. `ANDROID_VOICE_SPEC.md` covers what changes on a real phone.

## The rule this app follows

**If the app asks a question out loud, it listens for the answer out loud.**

That sounds obvious, and almost no voice app does it. The earlier version said
*"East or West?"* and then expected a finger, and said *"say cancel to stop"* while
nothing was listening. A blind user cannot answer a spoken question by finding a button.

Which gives the wake word its one exception, and it matters:

> **The app asked a question → answer it bare. You are starting something → say "Hey Cab".**

Inside a turn the app itself opened — *"Did you mean East, or West?"* — you just say
"east". Demanding the wake word again would be absurd. Outside one, in a moving
auto-rickshaw, unprompted speech is probably aimed at the driver, and *"no, not that way"*
must never cancel a ride.

**A complete ride takes zero taps.** The `Rider taps` gauge in the rig shows `0`.

Speaking also interrupts the narrator mid-sentence — you never have to wait it out.
`Hands-free: on/off` in the rider's top bar turns the standing mic off if it misbehaves;
every button still works.

## Try this first

Run `serve.bat`, open <http://localhost:8000/rig.html>, put headphones on. On the driver
phone (right) press **Go online**, then set **Auto: on**.

Click once anywhere on the rider phone — browsers need one gesture before they'll make a
sound, and that same click is when the app asks for the microphone. Allow it. It says
*"Ready. Say Hey Cab, then tell me where to go"* and then sits there filtering.

Now **say** *"Hey Cab, take me to Anna Nagar"* and don't touch the screen again. It will
ask which Anna Nagar; **say "east"** — no wake word needed, it asked you. Then just listen.

Watch the log while you do it. Everything the recogniser hears that *isn't* the wake word
is logged as `ASLEEP … ignored`, so you can see the filter working rather than guessing.

Run it again with *"Hey Cab, take me to Anna Nagar East"* and watch the `AMBIGUITY` line
change from `→ ask` to `→ book it`.

**If nothing happens when you speak,** the log will say why — `MIC BLOCKED` with the
reason, and the app reads it out loud. It is almost always one of: opened from `file://`,
microphone permission denied, or Chrome not being the browser.

## Sign in

**Rider — once, ever.** Name and guardian's number, then the app opens straight into the
mic on every later launch. That is deliberate: a typed login form is the worst possible
screen for a blind user, and it would contradict the zero-navigation claim. In the real
app a helper does this at handover and the device keeps a token.

**Driver — a normal form**, because they can see.

```
ravi@cabeye.test      driver123
suresh@cabeye.test    driver123
```

## What to look at

**The rider taps once.** The `Rider taps` gauge proves it. Everything after the mic is
the system talking on its own.

**The ambiguity rule fires in the log:**

```
gap 0.00 < 0.16 and 2.0 km apart  →  ask
gap 0.00 < 0.16 but 0.4 km apart  →  don't ask, book it
```

Ask only when being wrong would cost the user something. Tune `DELTA` and `DIVERGE` in
`js/nlu.js`.

**Fast path vs fallback.** `FAST PATH` means a regex matched in microseconds. Count these
across 30 utterances and that fraction is a result for the paper.

**Earcons instead of speech.** While the driver approaches, the rider hears a rising tone,
not a sentence. `Audio dwell` stays flat through that stretch. Speech time is task time.

**Stereo bearing.** On the driver phone tap **Play audio beacon** — it pans to the
driver's real bearing. Use headphones. This is the last-50-metres idea.

**The driver's taps become the rider's speech.** Tap a position phrase on the driver
phone and the rider hears it spoken. Each side uses the channel that suits their
situation; the system translates.

**Latency marks.** T0–T4: mic release → speech recognised → intent parsed → place
resolved → first sound. Same five measurement points the Android spike needs.

## One thing about the numbers

`Time to booking` includes the 5-second undo window, because the booking isn't confirmed
until it closes. If you'd rather report the moment intent was resolved, that's **T3**.
Change the window in `js/rider.js` — `UNDO_SECONDS`.

## Structure

```
index.html        role picker
login.html        driver login + one-time rider setup
rider.html        rider app
driver.html       driver app
rig.html          side-by-side + instrumentation
serve.bat         starts a local server on Windows  ← use this

css/theme.css     colours, type, components — shared by every page
js/bus.js         BroadcastChannel event bus between the two apps
js/audio.js       tone(), earcons, heartbeat, the narrator
js/voice.js       the ear — dictation, spoken answers, standing commands
js/wake.js        "Hey Cab" — the trigger that replaced tap-to-speak
js/api.js         thin client for the backend, with hard millisecond deadlines
js/nlu.js         places, patterns, the ambiguity rule (+ server fallback)
js/auth.js        demo accounts, per-role sessions
js/rider.js       the rider state machine
js/driver.js      the driver screens

backend/          Spring Boot — intent resolution + Google speech proxy
```

`CODE_WALKTHROUGH.md` explains every file in detail.
`ANDROID_VOICE_SPEC.md` covers what the wake word becomes in Kotlin.

## The backend

Optional. The app works without it — `js/nlu.js` has the same gazetteer and the same
ambiguity rule, so if the service is down the browser answers in about 40 ms and nothing
is lost. That is deliberate: **the backend may never be the reason a booking fails.**

```
cd backend
mvn spring-boot:run          # http://localhost:8080
```

| Endpoint | What it does |
|---|---|
| `POST /api/v1/voice/interpret` | sentence → destination, ride type, ask-or-book, and the gap/divergence that decided it |
| `POST /api/v1/voice/clarify` | "east" + the two candidates → which one |
| `GET /api/v1/places` | the gazetteer, so the browser copy can't drift |
| `POST /api/v1/speech/transcribe` | audio → text via Google, **including Tamil** |
| `POST /api/v1/speech/synthesize` | text → MP3 via Google |
| `GET /api/v1/speech/capabilities` | whether cloud speech is configured at all |
| `GET /actuator/health` | health |

The rig's log says which resolved each utterance — *resolved on the server* or
*resolved in the browser* — so you can see how often the network was load-bearing.

**Cloud speech is off until you give it a key**, and off is a fine place to leave it: the
browser's Web Speech API is free and instant. Turn it on for the one thing the browser
cannot do — Tamil, and code-mixed Tamil-English in one utterance:

```
set CABEYE_GOOGLE_API_KEY=...      &:: Windows
export CABEYE_GOOGLE_API_KEY=...   #   macOS / Linux
```

Enable *Cloud Speech-to-Text API* and *Cloud Text-to-Speech API* on the same Google Cloud
project, and restrict the key to those two — it travels in a query string. The
`alternativeLanguageCodes: [ta-IN]` setting in `application.yml` is what makes
*"Velachery-ku poganum"* transcribe correctly.

Google is reached over its REST endpoints with Spring's `RestClient`, not the
`google-cloud-speech` SDK — the SDK drags in gRPC, Netty, protobuf and a service-account
file to do what two HTTP calls do here.

Not in the backend yet, on purpose: Kafka, Redis, Keycloak, PostGIS, Docker. There is
nothing yet for them to carry. The seams are there — `PlaceRepository` for PostGIS,
`SpeechService` for the vendor — so each is one class when it earns its place.

## Tech stack

**This prototype** — no frontend dependencies, no build step:

| Piece | What it uses |
|---|---|
| Frontend | Plain HTML + CSS + JavaScript |
| Backend | Java 21 + Spring Boot 3.3 (optional) |
| Voice out | Web Speech API (`speechSynthesis`); Google TTS via the backend |
| Voice in | Web Speech API (`webkitSpeechRecognition`), Chrome only; Google STT via the backend for Tamil |
| Wake word | the same recogniser, filtering on an edit-distance match |
| Earcons, heartbeat, beacon | Web Audio API oscillators |
| Bearing audio | Web Audio `StereoPannerNode` |
| App-to-app | `BroadcastChannel` — same shape as the production WebSocket |
| Intent | Regex patterns + a 15-place Chennai gazetteer |
| Type | IBM Plex, from Google Fonts |

**The real product:**

| Piece | What it uses |
|---|---|
| Rider + driver apps | Kotlin + Jetpack Compose |
| Speech | Android `SpeechRecognizer` and `TextToSpeech` |
| Earcons | `SoundPool`; `AudioTrack` for panning |
| Bearing | Device magnetometer |
| Wake word | Porcupine or Vosk, on-device, in a foreground service |
| Backend | Java + Spring Boot |
| Database | PostgreSQL + PostGIS |
| Real-time | One WebSocket |

Cut for now: payment, Kafka, Redis, Keycloak, microservices, Docker, cloud deploy.

## What's fake

15 hardcoded places, no real geocoding, no routing, no map, no backend, no persistence
beyond `localStorage`, and the driver is a person clicking buttons rather than a moving
car. None of that matters — this exists so we can *hear* the design and time it before
writing Android code.

## Accessibility notes

Every text-on-background pair is 7:1 or better (WCAG AAA). Touch targets are at least
64px tall. `prefers-reduced-motion` is respected. Focus rings are visible everywhere.
The rider app is fully operable from the spacebar, which stands in for a hardware key —
so it works with the screen off.
