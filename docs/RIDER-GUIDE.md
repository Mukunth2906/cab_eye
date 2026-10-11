# Rider guide: every input, every module

[← Back to the README](../README.md)

How a blind rider uses Cab Eye: the touch and button inputs, then what can be said in each
module. Everything is spoken; the screen repeats it in large type for low-vision riders and
sighted helpers, and every voice feature also has a button.

---

## 1. The inputs

The microphone **never opens by itself**. It listens only while the rider holds the screen or a
volume key. When the app asks a question, it says it, the screen shows **"Hold to answer"**,
and the rider presses to answer. (An earlier version reopened the mic after every question; it
heard the app's own voice saying "say cancel to stop" and cancelled rides.)

| Input | Where | What it does |
|---|---|---|
| **Hold anywhere** on the screen | Main screen, explore camera | Talk. The mic opens at once with a rising chirp and a tick; letting go ends it (falling tone). Pauses while holding are fine. |
| **Hold a volume key** (either, longer than about ⅓ s) | Anywhere in rider mode | Talk, exactly like holding the screen. Easy to find without looking. |
| **Short press** of a volume key | Anywhere | Changes the volume, with the system click so the level can be heard. |
| **Double tap** the screen | Main screen, no ride under way | **Opens Explore** (the camera, no ride needed). |
| **Volume down three times quickly** (each press within 0.6 s) | Main screen, no ride under way | **Opens the Mind map.** The same again closes it. The volume returns to where it was before the three presses. |
| **Tap** the screen | In the cab | "Where are we": area, distance, time left, next cue. |
| **Hold SOS** for 2 seconds | Bottom right, always | Emergency: shares location and alerts support. |
| Top bar buttons: **⚙ Settings**, **📷 Explore**, **🧭 My mind map** | Main screen | Same as the gestures, for touch and TalkBack users. Outside the hold-to-talk area, so touching them never opens the mic. |

Why these choices:

- **Double tap for Explore.** It's the quickest gesture on the screen, and opening the camera
  should be quick. The first tap of a double tap is still an ordinary press (holding must open
  the mic instantly, never after a wait), so the app drops that press silently when the second
  tap lands. Only from the ready screen: during a ride or a question, a second tap is just
  another press.
- **Volume down ×3 for the Mind map.** A physical key works with the phone in a pocket or
  held up for the camera. Only *volume down* counts, so turning the narrator **up** in a
  hurry never opens anything. The presses still change the volume as they land (no waiting to
  see if a third is coming), and the third press puts the volume back.
- **With TalkBack on**, double taps belong to TalkBack. Use the top-bar buttons, or swipe
  up-then-right on the main screen for its actions menu: **Open explore**, **Open my mind map**,
  **Help and spoken instructions**. Volume keys still work.

Saying **"help"** lists the commands, including these inputs.

---

## 2. Signing in

| Do | What happens |
|---|---|
| First launch | "Welcome to Cab Eye… Say your ten digit number." Hold and say it any way ("98765 … 43210"). Part of a number is kept and only the rest is asked for. |
| The code | Read from the incoming SMS by itself (tap **Allow** once), or said, or typed. With no SMS provider configured, it arrives as a pretend message in the app. |
| "My name is Harshini" | Your name, confirmed by yes. |
| Reopen the app later | "Welcome back… touch the fingerprint sensor." |
| "skip" | Guest mode: nothing is remembered. |
| "sign out", or Settings → Sign out | Back to sign-in. |

A helper panel lets someone type the number, code or name instead.

---

## 3. Ride module: booking and riding a cab

### Booking

| Say (while holding) | What happens |
|---|---|
| "Take me to PSG Tech" | Finds the place (your memory first, then Google Places if configured, then the offline Coimbatore atlas). "Booking auto to PSG College of Technology. Say cancel to stop." Five seconds to cancel, then it books. |
| "Book a cab to Gandhipuram" | The same, as a cab instead of an auto. |
| A name that could be two places far apart | "Which place did you mean, A or B?" Buttons too. "Neither" / "go back" asks again. |
| A near miss | "Did you mean X?" Yes / no. |
| "cancel" / "go back" in the cancel window | Cancels / returns to the question. |
| "Take me to PSG College via Apollo Pharmacy" | A multi-stop ride (up to 3 stops). Each stop is a wait, a drop-off or a pick-up; the plan is read back for a yes. "Save this as Monday errands" keeps it; "book Monday errands" uses it. |
| "my places", "book again", "status", "repeat", "call driver" | Usual places; rebook the last destination; where the ride is; say the last thing again; phone the driver. |
| "Schedule a ride tomorrow at 8 30 to college" | A scheduled ride: a notification at the time, then the normal booking with its cancel window. "My scheduled rides", "cancel my scheduled ride". |

The **memory agent** learns habits from completed rides. At the usual time it offers ("Going to
PSG College, like most weekday mornings?"), repairs misheard names ("piece g" → PSG), and learns
your own words for places. It always asks, never books on its own. See
[../AGENT-ARCHITECTURE.md](../AGENT-ARCHITECTURE.md).

### Waiting for the car

- A quiet heartbeat (about 58 Hz every 2.6 s) means "still searching"; silence means a fault.
  "Still looking…" after 30 seconds, then every 90.
- "Karthik is coming. 4 minutes." The driver's approach is **tones**, rising in pitch as the car
  gets closer, never words.
- The driver can send **position presets** ("20 m to your left, near the gate"), spoken on the
  rider's phone, and an **audio beacon** panned to the side the car is on.
- **The ride camera.** If the driver can't find the rider, the driver can ask to see the rider's
  camera. The rider hears "Karthik can't see you and would like to see your camera", and
  answers by voice or with the on-screen buttons. Say yes and the back camera streams small
  pictures to the driver's screen, and the rider hears the objects around them. "Stop camera"
  ends it. It stops by itself at the boarding code, the start of the trip, or after 3 minutes.
  Settings → *Camera for your driver* → always share skips the question. See
  [EXPLORE-AND-CAMERA.md](EXPLORE-AND-CAMERA.md).

### At the kerb: the boarding code, reversed

The driver's screen shows a three-digit code; **the driver says it**; the rider's phone checks
it: "That's the right code. This is your car." or "That is not the right code. Do not get in."
The rider never says a secret out loud on a street. With headphones in, the expected code is
whispered through the earpiece first.

### In the cab

| Do | What happens |
|---|---|
| Tap | "Where are we": area, km so far, minutes left by the usual way, next cue. |
| Hold and say "speed bump", "that was the flyover", "I can hear the market" | A ride cue, said next time ~150 m before that point. "Undo" takes the last one back. |
| "where am I", "how much longer", "what's on the way", "quiet ride", "talk to me" | The ride guide. |
| "status" / "how far" | km and fare so far. |
| "skip the next stop", "add a stop at the ATM", "what are my stops" | Multi-stop changes. Nothing said in the cab ever books a new ride. |

The ride guide names areas as the cab passes through them, says "almost there" about 400 m
out, and says once, calmly, if the cab leaves the usual way. See
[MIND-MAP-AGENT.md](MIND-MAP-AGENT.md#ride-mode).

### After the ride

"You've arrived. 5.6 kilometres, 124 rupees." Then **pay** (UPI, card or net banking, confirmed
with a fingerprint; Razorpay or the mock gateway), **rate** the ride 1–5 or report a problem
(safety words are flagged urgent), and **next journey**: book now, schedule, or done. After a
ride somewhere new: "Well-known places close by…". Say "take me there", "remember them" or "no".

---

## 4. Explore module: the camera, no ride needed

**Open it:** double tap the main screen, or say "explore", "what's in front of me", "be my
eyes" or "find the door", or tap **📷 Explore**.

Hold the phone at chest height, pointing the way you face. The screen shows the live camera
with boxes around what is found, and large buttons.

| Say (hold the camera picture) / button | What happens |
|---|---|
| "describe", "what's in front of me" / **Describe** | One picture, described: hazards first, then objects with clock positions and rough distances ("steps going down, ahead; a door at 1 o'clock, about 3 metres"). |
| "keep telling me" / **Keep telling me** | While walking slowly, only what's **new** is said. "Stop" ends it. |
| "find the door" / **Find…** | Turn slowly; it says when and where the door appears. Up to 90 s. **Stop finding** ends it. |
| "read this" / **Read text** | Reads printed text: signs, labels, boards. |
| "this is Ravi's tea shop" / **Name this place** | Keeps a named spot at this position, with what the camera sees around it. Later, "take me to Ravi's tea shop" books a cab straight there. |
| "finish" / **Save & finish** | Saves the walk (path, steps, the things seen as landmarks) and what was found to the mind map. "Discard" / **Close without saving the walk** keeps only what the camera saw. |

Explore is never available during a ride; the ride camera is used instead. The app never says
a path is "clear" or "safe": it can't know that.

---

## 5. Mind map

**Open it:** press **volume down three times quickly**, or tap **🧭 My mind map**. Close it the
same way, or with **Close**.

Everything the rider knows, kept separately by where it came from:

| Tab | Holds | Buttons |
|---|---|---|
| **From my rides** | Cab destinations (visits, your names for them, distance and direction from here, area, cues on the way), saved routes, ride cues, areas. | **Book a ride**, **Book this route**, **What's around**, **Hear**, **Forget**, **Famous places nearby** |
| **From my walks** | Spots named while exploring, recorded and explored walks, personal notes, what the camera saw nearby. | **Guide me**, **Read route**, **Book a ride**, **Hear**, **Forget** |
| **Map** | A radar with the rider at the centre. Tap a dot for its details. | **What's around**, **Book a ride** |

Every answer is spoken as well as shown. By voice, from the main screen:

| Say | What happens |
|---|---|
| "Remember this as Ravi's tea shop" | A spot at your position, bookable later. |
| "Ravi's tea shop, where I stop on Fridays" / "the temple bell means I'm near Amma's house" | A personal note, joined to the place it's about. |
| "What's around here?" / "What's around the clinic?" | Your places, walks, notes, cues and camera finds nearby. |
| "What do I know about Ravi's shop?", "What's famous around here?" | One thing in detail; well-known places nearby. |
| "Read my mind map", "forget the note about …", "forget my mind map" | Summary; forget one; forget all notes (walks and ride history are kept). |

Details: [MIND-MAP-AGENT.md](MIND-MAP-AGENT.md).

---

## 6. Walking routes: the last hundred metres

| Say | What happens |
|---|---|
| "Record my walk to the clinic door" | Records steps, compass and GPS (on the phone only). While walking, hold and say "kerb here", "bakery smell on my left", "turning left", then "I'm there". |
| "How do I get to the clinic" | The whole route, in orientation-and-mobility style. Never books a cab. |
| "Guide me to the clinic" | One part at a time: next, repeat, previous, more/less detail, I'm there, stop. |
| "My walking routes", "forget the route to …", "export my walking log" | List, delete, share a CSV. |

Visual-only cues ("the red sign") are refused: a blind traveller can't use them. Details:
[WALKING-ROUTE-AGENT.md](WALKING-ROUTE-AGENT.md).

---

## 7. Settings, voice and accessibility

| Say / do | What happens |
|---|---|
| "speak slower" / "speak faster" / "louder" / "quieter" | Changes the narrator, then asks "Is this better?" |
| "yellow theme" (or deep dark, high contrast light) | Three themes, every text at least 7:1 contrast. |
| "switch city" | Coimbatore (default) or Chennai. |
| **⚙ Settings** | Rider / driver mode, backend address with a spoken **Test connection**, demo data (debug builds), sign out, *Camera for your driver: always share*. |

**TalkBack:** one swipe selects the whole screen (merged, so there is nothing to traverse);
state changes are announced without moving focus; swipe up-then-right for the actions menu
(the options in a question, pay, call driver, open explore, open mind map). Turn TalkBack on and
off with both volume keys held for 3 seconds.

---

## 8. The driver app

Same app, **Settings → DRIVER**. The phone goes silent: text-to-speech, the microphone and
audio focus are released, because the driver is driving.

1. Sign in by phone and SMS code; fill the profile (name, vehicle model, colour, plate).
2. **GO ONLINE.** A request shows **PASSENGER IS VISUALLY IMPAIRED** (it can't be dismissed).
3. **ACCEPT**, then the distance buttons (tones on the rider's phone), position presets, the audio
   beacon, and **SEE PASSENGER'S VIEW** to ask for the rider's camera (with the objects it finds
   labelled).
4. **I HAVE ARRIVED** shows the code to say. **PASSENGER IS SEATED** → **START TRIP** (the server
   refuses to start before seated). The phone's GPS measures the km.
5. Multi-stop: **ARRIVED AT STOP**, then **DONE** once the rider's code is heard (wait stops) or
   **DROPPED — CONTINUE**.
6. **COMPLETE TRIP**: the server computes the fare. Rate the passenger.

---

## 9. The admin website

<http://localhost:8080/admin> (login from `cabeye-secrets.properties`): feedback and safety
cases with each trip's km, fare and minutes; rides; drivers (suspend); riders (their rating from
drivers); SOS and urgent alerts to Telegram and email; notes and status on each case.
