# Cab Eye — MVP (Step 4)

A voice-first ride-hailing app for blind riders. The interface is **audio**: speech,
non-speech tones (earcons), and vibration. The screen exists for low-vision users, sighted
helpers, and demo screenshots — never as the primary channel.

**Step 4 is complete**: the phone and the backend now talk to each other over a WebSocket,
there is a real driver app on the other end of the ride, and you can build an APK that
your friends can install and use against the backend running on your laptop.

The rule that governs the whole build: **the rider is blind, so silence must always mean
something.** If the app goes quiet, that must signal a fault, never ambiguity. Almost every
decision below follows from that one line.

Read [What actually works right now](#what-actually-works-right-now) before demoing anything.

---

## Table of contents

1. [What actually works right now](#what-actually-works-right-now)
2. [Share the app with your friends (ngrok)](#share-the-app-with-your-friends-ngrok)
3. [What's in this folder](#whats-in-this-folder)
4. [Before you start (one-time setup)](#before-you-start-one-time-setup)
5. [Part A — Run the backend](#part-a--run-the-backend)
6. [Part B — Prepare your Android phone](#part-b--prepare-your-android-phone)
7. [Part C — Run the app on your phone](#part-c--run-the-app-on-your-phone)
8. [Part D — Connect the phone to localhost](#part-d--connect-the-phone-to-localhost)
9. [Using the app](#using-the-app)
10. [Testing with TalkBack](#testing-with-talkback)
11. [Reading the instrumentation](#reading-the-instrumentation)
12. [Troubleshooting](#troubleshooting)
13. [Versions](#versions)
14. [Design decisions](#design-decisions)

---

## What actually works right now

Being precise about this, because it determines what you can demo today.

| Piece | Status |
|---|---|
| **App ↔ backend over WebSocket** | **Working** — one socket per ride, verified end to end |
| **Reconnect with backoff + jitter**, capped at 30 s | **Working** |
| **Duplicate events ignored** on replay | **Working** — verified: 12 frames in, 6 acted on |
| **"Connection lost" spoken once**, heartbeat stops | **Working** |
| **Reconnect reconciles over REST**, announces only the delta | **Working** — verified by test |
| **Configurable backend URL**, persisted, `wss://` derived from `https://` | **Working** — verified by test |
| **Debug settings screen** with a spoken "Test connection" | **Working** |
| **Rider / driver role toggle**, audio session genuinely torn down | **Working** |
| **Driver app — all seven screens** | **Working** |
| **Boarding code, reversed** — driver says it, rider's app verifies it | **Working** |
| **"PASSENGER IS VISUALLY IMPAIRED" badge**, non-dismissable | **Working** |
| **Position presets** — driver taps, rider's phone speaks | **Working** |
| **Audio beacon**, panned to the driver's bearing | **Working** |
| **Confirm-seated gate** before the trip can start | **Working** — server returns 409, verified |
| **`/debug/broadcast` locked down** — profile + token + scope | **Working** — all four cases verified |
| **`install-app.ps1` multi-device** | **Working** — verified on a real device |
| **`share-with-friends.ps1`** — ngrok URL baked into the APK | **Working** |
| Rider surface: all eleven states, earcons, TTS, on-device STT | **Working** (steps 1–3) |
| Classifier, four matching gates, recovery ladder, three themes | **Working** (steps 1–3) |
| LLM escalation | Interface only — still gated on your go-ahead |
| Cloud STT / Cloud TTS | Interfaces only — benchmark targets, not fallbacks |
| Driver GPS | Buttons stand in for it — the transport is real, the source is not |

**So:** you can book a ride by voice on one phone, accept it on a second phone, drive the
ride through all seven driver screens, and the rider's phone narrates the whole thing at the
right tier. Kill the wifi mid-ride and the rider hears "Connection lost. Reconnecting."
exactly once; restore it and they hear "Connected." followed by one sentence about what
changed — not a backlog.

### What step 4 added, and why each one matters

| Requirement | Why it is built the way it is |
|---|---|
| A dropped socket is announced **once**, not per retry | A rider hearing "Connection lost" every two seconds learns nothing after the first and cannot hear anything else meanwhile. `ConnectionState.Reconnecting` carries an `announced` flag so the retry loop and the narrator cannot disagree. |
| On reconnect: fetch REST, announce the **delta** | If the driver arrived while the socket was down, the rider hears *"Your car has arrived"* — not the three events that led there. Replaying the backlog would narrate the recent past to someone standing next to a waiting car. |
| Every event carries an `eventId` | Reconnecting **replays**. Without a stable id, replay and repetition are the same operation, and the rider is told twice that a car has arrived — with no way to look and check. |
| The boarding code runs **backwards** | The rider's phone never announces a secret over a loudspeaker to a street where a blind person cannot tell who is listening. The driver's screen shows it, the driver says it, the rider's app verifies it. Hearing the right code is exactly the check that cannot be done by looking at a number plate. |
| Driver mode **releases** the audio session | A driver is driving. Stray TTS is a distraction and an open mic is a live recording in a car. Switching role releases TTS, releases the recogniser, abandons audio focus, *and* swaps in a `SilentAudioEngine` — two independent defences, so a missed guard is a logged no-op rather than a hazard. |
| `/debug/broadcast` returns **404, not 401** | 401 confirms there is something there to authenticate against. 404 is indistinguishable from the release build's genuine absence, so probing the path teaches an attacker nothing either way. |

### Verified how

Everything in the "Working" rows above was run, not assumed:

| Check | Result |
|---|---|
| `gradlew compileJava` (backend) | passes |
| `gradlew testDebugUnitTest` (Android) | **75 tests, 0 failures** |
| `gradlew assembleRelease` | 11.1 MB APK, v2-signed, installs |
| Full ride lifecycle over REST | REQUESTED → ASSIGNED → ARRIVED → SEATED → IN_TRIP |
| Start trip **before** confirming seated | **HTTP 409** — refused by the server |
| Start trip **after** confirming seated | HTTP 200 |
| `/debug/broadcast` with no token | **404** |
| `/debug/broadcast` with a wrong token | **404** |
| `/debug/broadcast`, right token, ride that does not exist | **404** |
| `/debug/broadcast`, right token, type `CONNECTED` (not a lifecycle type) | **400** |
| `/debug/broadcast`, right token, real ride, `ROUTE_DEVIATION` | 200, delivered |
| `/debug/broadcast` under `--spring.profiles.active=prod` | **404 — the bean does not exist** |
| WebSocket reconnect with `lastSeq=0` | full replay, **event ids identical to the first connection** |
| WebSocket reconnect with `lastSeq=3` | only seq 4, 5, 6 — incremental replay correct |
| De-duplication simulation | 12 frames received → **6 acted on** |
| `install-app.ps1 -List` | lists the attached device with its model |
| `install-app.ps1 -Serial <bogus>` | clear error, **exit code 1** |
| Install + launch on a real phone (A142) | process alive, no crash, correct backend URL read |

**Not verified**, and I am flagging it rather than letting you find out during a demo: the
on-device voice loop was not exercised end to end, because the phone is password-locked and
I could not unlock it. The app launches, reads its backend URL and activates the rider audio
session cleanly — but *you* need to be the one who holds the screen and says "take me to
Adyar". That is the five-minute check in [Using the app](#using-the-app).

---

## Share the app with your friends (ngrok)

This is the part that lets someone else's phone talk to the backend on **your** laptop.

### One-time ngrok setup

1. Download ngrok: <https://ngrok.com/download>
2. Unzip `ngrok.exe` either onto your PATH or straight into `D:\pw\` next to the scripts
3. Sign up (free) and copy your authtoken from the dashboard
4. Run once, in any terminal:
   ```
   ngrok config add-authtoken YOUR_TOKEN_HERE
   ```

### Every time you want to share

**Right-click `share-with-friends.ps1` → Run with PowerShell.**

It does all of this for you:

1. starts the backend (in its own window) if it is not already running
2. starts an ngrok tunnel, giving your laptop a public `https://` address
3. reads that address out of ngrok's local API
4. **bakes it into the APK at build time**
5. copies the finished APK to your Desktop as `CabEye-MMDD-HHMM.apk`

Step 4 is the whole point. Your friend installs the APK and it **already knows where your
laptop is** — they never open settings, never paste a URL, and never have to be told one.
A rider who cannot see the screen could not do any of that anyway.

### What to tell whoever installs it

- Android will warn about installing from an unknown source. Allow it.
- Accept the microphone permission when the app opens.
- Press and **hold** anywhere on the screen, then say where you want to go.

For the **driver** side, on a second phone:

- Tap the small dot at the **top right** five times
- Settings → **DRIVER** → Done → **GO ONLINE**

### Two things that will bite you

> **Keep the windows open.** The tunnel dies with the PowerShell window that started it, and
> the app stops working for everyone holding that APK.

> **The free ngrok URL changes every restart.** When it does, either re-run the script and
> re-send the new APK, or have people paste the new address into Settings themselves — the
> app takes an `https://` address and works out the `wss://` socket address on its own.

### Changing the address by hand

Anyone can point their copy anywhere without a rebuild:

1. Tap the small dot at the top right **five times** (a deliberately awkward gesture — a
   blind rider who lands in a settings screen by accident has no way back out)
2. Paste the `https://` address. Trailing slashes and stray whitespace are stripped for you,
   because that is exactly how an ngrok URL arrives off a clipboard.
3. **Test connection** — the result is *spoken aloud*, which also proves the text-to-speech
   path works. A silent phone gets diagnosed here rather than mid-ride.
4. Save.

---

## What's in this folder

```
D:\pw\
├─ README.md                 ← you are here
├─ run-backend.ps1           ← start the server on its own
├─ install-app.ps1           ← build + install to EVERY connected phone
├─ share-with-friends.ps1    ← ngrok tunnel + shareable APK on your Desktop
├─ backend\                  ← Spring Boot server (Java)
└─ android\                  ← the phone app (Kotlin) — open THIS in Android Studio
```

Two separate projects. The backend runs on your PC; the app runs on your phone(s).

### `install-app.ps1`

```
.\install-app.ps1                  install to EVERY connected device
.\install-app.ps1 -Serial ABC123   install to one device
.\install-app.ps1 -List            list devices and exit
.\install-app.ps1 -Release         build the shareable release APK instead of debug
.\install-app.ps1 -NoLogs          skip the log stream at the end
```

Exit codes: `0` all good · `1` setup problem **or no devices attached** · `2` at least one
device failed.

It skips anything `unauthorized` or `offline` and **tells you which and why** rather than
silently installing to fewer phones than you thought. With zero devices attached it fails
loudly and returns non-zero — a script that prints nothing and returns 0 when it installed
nothing is how you end up demoing a build from three days ago.

Multi-device support is not a convenience here. The whole point of the boarding code is that
**two different people on two different phones** verify each other, so it cannot be
meaningfully tested on one device.

### Fastest path to a running app

Phone plugged in, USB debugging on (see [Part B](#part-b--prepare-your-android-phone)):

1. **Right-click `install-app.ps1` → Run with PowerShell**

That checks every attached phone, builds, installs to all of them, sets up the localhost
tunnel on each, launches the app, and reports a per-device summary. If anything is wrong it
tells you exactly what.

To share with someone not in the room, use `share-with-friends.ps1` instead.

---

## Before you start (one-time setup)

### Software you need

**Android Studio** — already installed on this machine at
`C:\Program Files\Android\Android Studio`.

<details>
<summary>If you ever need to install it from scratch (click to expand)</summary>

1. Go to <https://developer.android.com/studio>
2. Click **Download Android Studio**, accept the terms, run the `.exe`.
3. Accept every default. When the Setup Wizard asks, choose **Standard** installation.
4. It downloads ~2–4 GB of SDK components. This takes a while; let it finish.
5. On the welcome screen it is ready to use.

</details>

**Java** — you do **not** need to install it. Your system Java is version 11, which is too
old for this project, but Android Studio ships its own **Java 21** and both halves of this
project use that one automatically. Nothing on your system is changed.

### Already verified on this machine

| Thing | Status |
|---|---|
| Android Studio | Installed |
| Bundled Java (JBR) | 21.0.9 — good |
| Android SDK platforms | 33, 34, 35, 36, 36.1 installed — good |
| Build tools | 34.0.0, 36.1.0, 37.0.0 — good |
| `adb` | Present at `C:\Users\Prahalya\AppData\Local\Android\Sdk\platform-tools\adb.exe` |

You do not need to download any SDK components. Both projects already compile — the backend
and the APK were built successfully during setup.

### Things you also need

- Your Android phone
- **A USB cable that carries data.** Many cheap charging cables carry power only, and this
  is the single most common reason a phone "won't connect". If nothing appears in step B,
  try a different cable before anything else.

---

## Part A — Run the backend

The backend is the server. It has to be running before the app can talk to it (step 2 and
onward), and you can test it on its own today.

### Start it

1. Open **File Explorer**, go to `D:\pw`
2. **Right-click** `run-backend.ps1` → **Run with PowerShell**

A blue terminal window opens.

> **If Windows blocks it** with a red "running scripts is disabled" message, open
> PowerShell and run it explicitly instead:
> ```
> powershell -ExecutionPolicy Bypass -File D:\pw\run-backend.ps1
> ```

### What you'll see

**The first run takes 2–5 minutes** — it downloads Gradle and all dependencies. Later runs
take about 10 seconds. This is normal, not a hang.

You are waiting for this line:

```
Started CabEyeBackendApplication in 2.163 seconds
```

**Leave this window open.** Closing it stops the server. To stop it deliberately, click in
the window and press `Ctrl+C`.

### Check it works

Open a browser on your PC:

- <http://localhost:8080/health> → should show
  `{"service":"cabeye-backend","activeRides":0,"rides":{},"status":"UP"}`
- <http://localhost:8080/> → a dark **test console** page

### Prove the WebSocket works (optional but worth 60 seconds)

This is how you confirm the real-time layer independently of the phone.

1. Open <http://localhost:8080/> in **two browser tabs**.
2. Tab 1: leave the defaults (`rideId=demo`, `userId=rider-1`, `role=RIDER`) → click **Connect**
3. Tab 2: change `userId` to `driver-9` and `role` to `DRIVER` → click **Connect**
4. In tab 2, click **Send**.
5. **Tab 1 receives the message. Tab 2 does not see its own message.**

That last point is intentional: the server echoes to the *other* party only. The sender
already knows what it just said, and every unnecessary message is latency the rider
eventually pays for.

---

## Part B — Prepare your Android phone

You only ever do this once per phone.

### Step B1 — Unlock Developer Options

1. On the phone: **Settings** → scroll to the bottom → **About phone**
2. Find **Build number**. (On Samsung it's under **About phone → Software information**.)
3. **Tap "Build number" 7 times.** After a few taps it counts down: "You are now 3 steps
   away from being a developer."
4. It asks for your PIN/pattern. Enter it.
5. You'll see **"You are now a developer!"**

### Step B2 — Turn on USB debugging

1. **Settings** → **System** → **Developer options**
   (on some phones: Settings → Developer options, near the bottom)
2. Turn **Developer options** ON at the top.
3. Scroll down and turn **USB debugging** ON.
4. Confirm the warning dialog.

### Step B3 — Plug in and authorise

1. Connect the phone to your PC with the USB cable.
2. **A dialog appears on the phone:** *"Allow USB debugging?"* with a computer fingerprint.
3. **Tick "Always allow from this computer"**, then tap **Allow**.

   If no dialog appears, unplug and replug. If it still doesn't appear, see the
   troubleshooting table.

4. Swipe down the phone's notification shade, tap the USB notification, and set the mode to
   **File Transfer / MTP**. ("Charging only" mode blocks debugging on some phones.)

### Step B4 — Confirm the PC can see it

Open PowerShell on your PC and run:

```powershell
& "C:\Users\Prahalya\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
```

You want:

```
List of devices attached
R58N12ABCDE     device
```

- `device` → **correct, you're ready**
- `unauthorized` → you didn't tap Allow. Unplug, replug, tap Allow.
- nothing listed → bad cable, wrong USB mode, or USB debugging is off

---

## Part C — Run the app on your phone

### Step C1 — Open the project

1. Launch **Android Studio**.
2. Click **Open** (not "New Project").
3. Navigate to **`D:\pw\android`** and click **OK**.

   > **Important:** open `D:\pw\android`, **not** `D:\pw`. Opening the wrong folder is the
   > most common mistake here — Android Studio will fail to recognise it as a project.

4. If it asks **"Trust project?"** → click **Trust Project**.

### Step C2 — Wait for Gradle sync

A progress bar appears at the bottom: *"Gradle: Build model…"*.

**The first sync takes 3–10 minutes.** Let it finish. You want the bottom bar to read
**"Sync finished"** or **"BUILD SUCCESSFUL"** with no red errors.

> Both projects were already compiled successfully during setup, so most dependencies are
> cached and this should be quicker than a cold start.

### Step C3 — Check the Gradle JDK (only if sync fails)

If sync fails with a Java version complaint:

1. **File** → **Settings** → **Build, Execution, Deployment** → **Build Tools** → **Gradle**
2. Set **Gradle JDK** to the entry containing **`jbr-21`** (JetBrains Runtime 21)
3. Click **OK**, then **File → Sync Project with Gradle Files**

### Step C4 — Select your phone and run

1. At the top of Android Studio there's a dropdown showing available devices. Your phone
   should appear by model name.
2. If it says "No devices", re-check Part B step B4.
3. Click the green **▶ Run** button (or press `Shift+F10`).

The app builds, installs, and launches. **First install takes 1–3 minutes.**

You should see a **black screen** with large white text: **"Hold anywhere and speak"**.

That's it — the app is running on your phone.

---

## Part D — Connect the phone to localhost

> Not needed for step 1 (the app doesn't call the backend yet), but set it up now so it's
> ready and you understand it.

### The problem

Your phone and your PC are different computers. `localhost` on the phone means *the phone
itself*, not your PC. So the app asking for `http://localhost:8080` would find nothing.

### The fix: `adb reverse`

One command creates a tunnel through the USB cable:

```powershell
& "C:\Users\Prahalya\AppData\Local\Android\Sdk\platform-tools\adb.exe" reverse tcp:8080 tcp:8080
```

After this, when the **phone** asks for `localhost:8080`, the request travels down the USB
cable and reaches **your PC's** port 8080. The app can use the address `localhost:8080`
without knowing anything about IP addresses.

**Why this is the right approach for you:**

- No Wi-Fi needed — works over the cable alone
- No IP address to look up, and none to change when your network changes
- No Windows Firewall rule, and no firewall prompt to get wrong

### Important

**You must re-run this command every time you unplug and replug the phone**, and after
rebooting either device. The tunnel does not survive a disconnect.

To check it's active:

```powershell
& "C:\Users\Prahalya\AppData\Local\Android\Sdk\platform-tools\adb.exe" reverse --list
```

---

## Using the app

The whole screen is one button, and the app is now voice-driven.

**On first launch, accept the microphone permission prompt.** Without it the app will tell
you out loud that it can't hear you.

### The two-phone demo (this is the one worth doing)

You need two phones and the backend running. Both phones must point at the same backend —
either both on USB with `install-app.ps1`, or both holding an APK built by
`share-with-friends.ps1`.

**Phone B — set it up as the driver first**, so it is already waiting:

1. Tap the small dot at the **top right** five times → Settings
2. Tap **DRIVER**, then **Done**
3. Tap **GO ONLINE**

Notice what just happened on phone B: it went completely silent. That is not a UI change.
The text-to-speech engine was released, the microphone was released, and audio focus was
abandoned — because a driver is driving, and an open mic in a car carrying a passenger who
cannot see it is not a rough edge.

**Phone A — the rider:**

4. Press and hold, say *"take me to Adyar"*, release
5. It confirms out loud and gives you five seconds to say "cancel"
6. Let the window pass. It says it is finding a driver, and a quiet pulse starts —
   that pulse is the promise that the system is alive

**Phone B — the driver, again:**

7. The request appears, with **PASSENGER IS VISUALLY IMPAIRED** across the top. There is no
   way to dismiss that badge, deliberately.
8. **ACCEPT** → phone A says *"Karthik is coming. 4 minutes."*
9. Tap the distance buttons — **400m, 250m, 120m, 40m**. Phone A says *nothing* and plays a
   tone that rises in pitch each time. That is the design, not a bug: narrating a car moving
   forty metres would cost the rider their attention for something a tone conveys instantly.
10. Tap a **position preset** — *"20 m to your left, near the gate"*. Phone A speaks it.
    You never said a word and they never read one.
11. Tap an **audio beacon** direction. The tone arrives on phone A panned to that side.
12. **I HAVE ARRIVED** → phone B now shows a huge three-digit code, and phone A says
    *"Your car has arrived"* and starts listening.

**The boarding code — the bit that runs backwards:**

13. **Say the code out loud.** Do not show them the screen; say it.
14. Phone A hears it, checks it against what the server issued, and says *"That's the right
    code. This is your car."*

Say a *wrong* code deliberately once, to see the other branch: phone A interrupts with
*"That is not the right code. Do not get in."*

> If phone A has headphones plugged in, it will have quietly told the rider the expected code
> **through the earpiece** beforehand. With no headphones it stays silent about the code and
> just listens — and says so, so the silence is never mistaken for a fault. A blind person on
> a street cannot tell who is standing close enough to hear a loudspeaker.

15. **PASSENGER IS SEATED** → then **START TRIP**. Try tapping START TRIP before confirming
    seated and the server refuses it with a 409; the driver app tells you what has to happen
    first.
16. **COMPLETE TRIP** → phone A announces the fare.

### The connection test (30 seconds, and the most important one)

Mid-ride, **turn wifi and mobile data off on phone A**.

- The heartbeat pulse **stops**
- It says **"Connection lost. Reconnecting."** — once, and only once, however long the outage
  lasts

Now, while it is still offline, drive the ride forward on phone B: tap **I HAVE ARRIVED**.

Turn phone A's connection back on:

- It says **"Connected."**
- Then **one sentence**: *"Your car has arrived. Karthik, Bajaj auto, yellow."*

That is the whole point. It does not replay the events it missed. It asks the server where
the ride actually is and tells the rider only what changed — because a rider standing next to
a waiting car does not need a narration of the recent past.

### The basic loop

1. **Press and hold anywhere** on the screen. You'll hear a rising two-tone chirp and feel
   a tick — that means the mic is genuinely open.
2. **Say where you want to go** while still holding: *"take me to Adyar."*
3. **Release.** You'll hear a falling tone.
4. The app speaks: *"Booking auto to Adyar. Say cancel to stop."*
5. The mic reopens for **five real seconds**. Say **"cancel"** and it genuinely cancels.
6. Say nothing and the ride stands — you'll hear the quiet heartbeat pulse begin.

### Things to try

| Say this | What happens |
|---|---|
| "take me to Adyar" | Books straight through — unambiguous |
| "book a cab to T Nagar" | Same, but ride type is *cab* |
| **"anna"** | **Asks you to clarify** — Anna Nagar and Anna Salai are ~6 km apart |
| "anna nagar" | Books *without* asking — see the note below |
| "cancel" (during the window) | Cancels the ride |
| "help" | Lists the available commands |
| "repeat" | Repeats the last thing said |
| "status" | Describes the current ride state |
| "book again" | Rebooks your previous destination |

Other controls: **Volume Up/Down** works like press-and-hold. The **SOS** button
(bottom-right) raises the overlay; **Dismiss** returns you to the exact state underneath.

### A correction worth knowing about

The step-1 placeholder claimed Anna Nagar East and West were 2.1 km apart. **That number was
made up.** With real coordinates they are **1.29 km** apart — *below* the 1.5 km DIVERGE
threshold.

This means saying "anna nagar" correctly **does not** trigger a clarification: the two
candidates tie on score, but being wrong between them costs the rider about a kilometre, so
interrupting them isn't worth it. That is the gate's second branch working exactly as
specified:

```
gap < DELTA (0.16)  &&  distance > DIVERGE (1.5 km)  ->  ask
gap < DELTA         &&  distance <= DIVERGE          ->  don't ask, book it
```

To demo the *asking* branch, **Anna Salai** (Mount Road) was added to the gazetteer. Saying
just **"anna"** is genuinely ambiguous between Anna Nagar and Anna Salai, which are ~6 km
apart — there, being wrong really does cost you, so the app asks. Both numbers are logged on
every decision, including when the gate correctly stays silent.

### What to listen for

- **`confirming` asks you nothing.** The ride is already being booked. That's optimistic
  execution — a real, honoured cancellation window instead of a confirmation question that
  would add a conversational turn to every single booking.
- **The mic reopens the instant a question finishes**, never on a timer. If the app asks
  something out loud, it is already listening.
- **The mic is closed the whole time the app is speaking**, so it never transcribes its own
  voice.
- **The heartbeat** (~58 Hz every 2.6 s) starts when the ride enters `finding`. It's meant
  to be barely noticeable — its job is to make silence mean "broken" rather than "waiting".

---

## Testing with TalkBack

TalkBack is Android's screen reader. This is how a blind user actually experiences the app,
so it is worth turning on at least once.

**Turn it on:** Settings → Accessibility → TalkBack → toggle on.

**Essential gestures while TalkBack is running** (the phone behaves very differently):

| Gesture | Effect |
|---|---|
| Swipe right / left | Move to next / previous item |
| **Double-tap** | Activate (a single tap only selects) |
| Two-finger swipe | Scroll |
| Swipe down-then-left | Back |
| Swipe up-then-right | Local context menu (the custom actions live here) |

**Turn it off:** hold both volume keys for 3 seconds, or go back through Settings.

### What to verify

1. **One swipe selects the entire screen.** Not a list of separate labels — one target.
   That's `mergeDescendants = true`, and it's the point: traversing a list is the
   navigation this product exists to remove.
2. **State changes are announced without you touching anything.** Hold and release; the new
   state is spoken even though focus never moved. That's `liveRegion = Assertive`.
3. **Swipe up-then-right in `clarify`** opens the local context menu with the two
   destination options as custom actions. Merging descendants removes the child buttons from
   traversal, so those affordances are restored here instead.

---

## Reading the instrumentation

Everything measurable is logged under one tag. With the phone connected:

```powershell
& "C:\Users\Prahalya\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -s CABEYE_METRICS
```

Leave that running and use the app. Saying "take me to Adyar" produces roughly:

```
STATE Idle -> Listening
stage=MIC_RELEASED t=+2104ms
stage=SPEECH_FINAL t=+2530ms
stage=INTENT_PARSED t=+2532ms
CLARIFY_DECISION a="Adyar" b="Anna Salai" gap=0.410 divergenceKm=6.21 asked=false
stage=PLACE_RESOLVED t=+2535ms
STATE Resolving -> Confirming
SPEECH tier=QUEUED durationMs=2380 text="Booking auto to Adyar. Say cancel to stop."
RIDE_SUMMARY ride=local timeToBookingMs=... taps=1 clarifications=0 speechSeconds=... path=REGEX
  | asr=426ms intent=2ms resolve=3ms tts=...ms total=...ms
```

Note `CLARIFY_DECISION ... asked=false` — the gate logs its reasoning **even when it decides
not to interrupt**, which is the whole point of measuring it.

Budget breaches are logged at WARN so they surface in a filtered log without you hunting:

```
ASR ≤ 800 ms · intent ≤ 300 ms · resolve ≤ 400 ms · TTS first audio ≤ 300 ms
TOTAL ≤ 2000 ms
```

Press `Ctrl+C` to stop watching.

### Other useful log tags

```powershell
# everything Cab Eye, including speech and audio internals
adb logcat -s CABEYE_METRICS:V CabEye.Stt:V CabEye.Tts:V CabEye.Audio:V

# crashes only
adb logcat -s AndroidRuntime:E
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `adb devices` shows nothing | Charge-only USB cable | **Try a different cable first** — this is the most common cause |
| `adb devices` shows nothing | Wrong USB mode | Notification shade → USB notification → **File Transfer** |
| `adb devices` shows `unauthorized` | Didn't accept the RSA prompt | Unplug, replug, tap **Allow** + "Always allow" |
| No "Allow USB debugging" prompt | Stale key | Revoke: Developer options → **Revoke USB debugging authorizations**, then replug |
| Studio: "No devices available" | adb started before the phone | Run `adb kill-server` then `adb start-server`, replug |
| Backend: `Port 8080 was already in use` | Another program has 8080 | Find it: `Get-NetTCPConnection -LocalPort 8080 -State Listen`. Close it, or change `server.port` in `backend\src\main\resources\application.properties` |
| `run-backend.ps1` won't run | PowerShell execution policy | `powershell -ExecutionPolicy Bypass -File D:\pw\run-backend.ps1` |
| Backend: "No Java 17+ found" | Studio moved or uninstalled | Install Temurin JDK 17 from <https://adoptium.net/temurin/releases/?version=17> |
| Studio: "Unsupported Java" / sync fails on version | Wrong Gradle JDK | Settings → Build Tools → Gradle → **Gradle JDK = jbr-21** |
| Studio: "SDK location not found" | `local.properties` path wrong | Delete `android\local.properties` and re-sync — Studio regenerates it |
| Gradle sync hangs at "Downloading…" | Slow first-time download | It's genuinely large. Leave it. Only intervene after ~15 min |
| App installs but instantly closes | Usually a theme/manifest mismatch | Run `adb logcat -s AndroidRuntime` and read the top exception |
| "CLEARTEXT communication not permitted" | HTTP blocked | Already handled by `network_security_config.xml`; confirm the manifest still references it |
| Gradle wrapper missing (`gradle-wrapper.jar`) | File deleted | Android Studio regenerates it on open. For the backend, open `D:\pw\backend` as a second Studio window and run `bootRun` from the Gradle panel |
| No vibration on the phone | Device has no vibrator, or system haptics off | Check Settings → Sound → Vibration. The code degrades silently by design |
| App says "I need microphone permission" | Permission denied | Settings → Apps → Cab Eye → Permissions → Microphone → Allow |
| App says "Speech recognition isn't available" | No recogniser installed | Install/enable the **Google** app, or Settings → System → Languages & input → On-device recognition |
| Hear earcons but **no speech** | TTS engine or voice data missing | Settings → Accessibility → Text-to-speech output → install voice data. Check `adb logcat -s CabEye.Tts` |
| Speech recognition is slow | Offline model not downloaded, so it's using the network | Settings → Languages & input → On-device recognition → download **English (India)**. `EXTRA_PREFER_OFFLINE` is a preference, not a guarantee |
| It transcribes the app's own voice | Should be impossible — mic is closed during TTS | This is a bug; capture `adb logcat -s CabEye.Stt CabEye.Tts` and report it |
| "cancel" doesn't cancel | Said before the mic reopened | The window starts *after* the sentence finishes. Wait for the chirp, then speak |
| No sound at all | Audio focus lost, or media volume at zero | Turn media volume up. Check `adb logcat -s CabEye.Audio` for focus warnings |

---

## Versions

Pinned deliberately as a tested-together set. Changing one usually means changing another.

**Android**

| | |
|---|---|
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose compiler plugin | 2.0.21 (must always equal the Kotlin version) |
| Compose BOM | 2024.10.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| JVM target | 17 |
| OkHttp | 4.12.0 |
| Coroutines | 1.9.0 |
| Lifecycle | 2.8.7 |

**Backend**

| | |
|---|---|
| Gradle | 8.11.1 |
| Spring Boot | 3.3.5 |
| Spring dependency-management | 1.1.6 |
| Java source/target | 17 (compiled and run by JBR 21) |

Note: OkHttp's WebSocket client is in the **core** `okhttp` artifact — there is no separate
"okhttp-websocket" dependency to add.

---

## Design decisions

Recorded here because they are decisions, not defaults, and reversing one by accident would
break the product's premise.

### The four non-negotiable principles

1. **One surface, zero navigation.** One Activity, one Composable, a `when` over eleven
   states. No `NavHost`, no back stack. Enforced structurally so later work can't
   accidentally add one.
2. **If the app asks a question out loud, it listens for the answer out loud.** The mic
   reopens the instant a spoken prompt finishes. `AudioEngine.speak`'s `onDone` must fire
   even when the utterance is *cancelled* — otherwise a cancelled prompt leaves the mic shut
   and the rider talks to a phone that isn't listening.
3. **Speech time is task time.** High-frequency, low-information events get an earcon, never
   words. The whole driver-approach phase is wordless.
4. **Silence must mean something.** A quiet ~58 Hz pulse every 2.6 s during any wait, so
   silence signals a fault rather than ambiguity.

### The boarding PIN is reversed

The rider's phone does **not** announce a code for the rider to repeat. A blind person
standing on a street cannot tell who is within earshot, so announcing a secret over a
loudspeaker defeats it.

Instead the **driver's** screen shows the code, the driver says it aloud, and the rider's
app verifies it. The rider never speaks a secret, and hearing the correct code is exactly
the verification a blind rider cannot perform visually. The expected code is spoken through
the earpiece only when headphones are connected.

### Audio never round-trips to the server

Recognition uses on-device `SpeechRecognizer` with streaming partial results. Sending audio
to Spring, then to Cloud STT, then back would cost seconds — and this project's entire claim
is about time. Cloud STT sits behind an interface so it can be *measured* against on-device
later, but the default path never leaves the phone.

### Smaller choices worth knowing

- **No React Native.** The audio layer needs `AudioTrack` with runtime pitch and per-ear
  panning, audio focus handling, and a foreground service. None have a JS equivalent, so RN
  would mean writing Kotlin native modules *and* maintaining a bridge.
- **No dynamic colour (Material You).** It would let the wallpaper pick foreground colours,
  and a wallpaper-derived palette can't be guaranteed to hold 7:1. An accessibility
  requirement must not be delegated to a decorative source.
- **Pure black background, all text ≥ 7:1** (WCAG AAA). Most are far above it — body text is
  21:1. Note the danger colour is `#FF8A80`, not pure red: `#FF0000` on black measures only
  ~5.3:1 and would fail AAA outright.
- **88.dp minimum touch target**, well above Android's own 48.dp guidance, because a rider
  who can't see the control is aiming from memory rather than sight.
- **Reduced motion** is read from `Settings.Global.ANIMATOR_DURATION_SCALE`, Android's
  closest equivalent to `prefers-reduced-motion`.
- **Portrait locked, `configChanges` handled manually.** A rotation would destroy and
  recreate the Activity, restarting the audio engine and cutting the narrator off
  mid-sentence — and a blind user can't see that the screen rotated anyway.
- **Sealed interface, not an enum,** for `RiderState`. Illegal combinations become
  unrepresentable, and adding a twelfth state becomes a compile error at every place that
  must handle it rather than a blank screen at runtime.
- **SOS is an overlay, not a state.** Folding it into the hierarchy would mean either
  duplicating every state or losing the one underneath — and losing it is unacceptable,
  since dismissing SOS must return the rider exactly where they were.
- **The server re-stamps sender identity** from the session rather than trusting the message
  body. Even without a security layer, a client shouldn't be able to speak as the driver by
  setting a field — that would make the boarding-code flow meaningless.

### Assumptions made (flagged rather than silently chosen)

- Packages `com.cabeye.rider` and `com.cabeye.backend`.
- `minSdk 26` is a hard floor: `VibrationEffect` and the `AudioTrack.Builder` path both
  require it.
- **No launcher icon.** PNG/WebP icons are binary files that can't be authored as text, so
  the manifest omits `android:icon` and Android uses the system default. The app installs
  and launches normally; only the home-screen icon is generic. Fix in Studio via
  **File → New → Image Asset** whenever you like.
- **Telemetry ships now** even though it wasn't in step 1's numbered list. The brief calls
  instrumentation "the evaluation data, not a nice-to-have" — adding it later would
  contradict that.
- Driver mode is scaffolded as a concept only; the driver UI is a later step.
- Raw WebSocket, no STOMP (the brief permits this). One topic shape means one
  `WebSocketListener` on Android instead of a broker abstraction.

---

## Next step

Audio and STT are in. Still deliberately not built:

1. **App ↔ backend wiring** over the WebSocket — this is the natural next piece, and the
   `adb reverse` tunnel is already set up for it.
2. **The remaining 7 ride states**: `finding` → `assigned` → `approaching` → `arrived` →
   `intrip` → `done`, including the bearing-panned approach earcon (needs the magnetometer)
   and the reversed boarding-code verification.
3. **Driver mode** — position presets, audio beacon, the verification code, and the
   non-dismissable "passenger is visually impaired" badge.
4. **LLM escalation** on a regex miss — still gated on your go-ahead. The interface
   (`LlmIntentResolver`) is in place and unimplemented.

The `AudioEngine` already exposes `DRIVER_APPROACH` and `BEACON` with pan and pitch
parameters, so the approach phase needs a bearing source rather than new audio work.

---

# Google Maps + Places integration (Cab Eye dynamic destinations)

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

The backend remains the same Spring Boot + in-memory architecture from the original project;
no Firebase or new database technology has been introduced.

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
- The existing backend is still in-memory; rides disappear when the server restarts.
- Routes/ETA from Google Routes API are not part of this milestone.
- Driver GPS remains the existing demo transport path.  
