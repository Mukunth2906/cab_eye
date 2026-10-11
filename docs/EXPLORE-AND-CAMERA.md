# The camera: ride camera, explore and object detection

[← Back to the README](../README.md)

Cab Eye uses the rider's back camera in two separate modules, which share one camera session
and one object detector:

| | **Ride camera** (ride module) | **Explore** (explore module) |
|---|---|---|
| When | During a ride, before pickup | No ride: the rider walking around their own place or street |
| Who starts it | The driver asks (**SEE PASSENGER'S VIEW**); the rider says yes | The rider: **double tap**, "explore" / "what's in front of me", or **📷 Explore** |
| Who sees the pictures | The driver, live; the rider hears the objects around them | Only the rider's phone (and Gemini, one picture at a time, if configured) |
| What is kept | Nothing. Pictures are relayed, never stored | Objects seen, named spots and the walk, in the **explore** part of the mind map |
| Ends | Boarding code confirmed, passenger seated, trip start/end, cancel, "stop camera", or 3 minutes | "Finish" / **Save & finish**, or "discard" / **Close without saving the walk** |

---

## Object detection: two detectors, one answer

`vision/SceneDetector.kt` is the single place both modules ask "what's in front of me".

```
                 camera picture (upright, ≤ 640 px)
                              │
         ┌────────────────────┴─────────────────────┐
         │ describe / find / read                   │ quick (many per minute)
         ▼                                          ▼
 POST /me/vision/describe  ──fails/404──▶   OnDeviceDetector (ML Kit, in the app)
 backend VisionService                       object boxes + a label per box
   └─ GeminiClient                           + text recognition for "read this"
      several keys × models,                          │
      first good answer wins                          │
         │                                            │
         ▼                                            ▼
 Scene { summary, objects[label, box, clock, distance], hazards, text, found, source }
                              │
                              ▼
     SceneSpeech: hazards first, "a door at 1 o'clock, about 3 metres", only what's new
```

**Gemini (through the backend).** Richer names, rough distances, hazards, reading text. The
phone sends one JPEG only when asked (and every sixth picture in "keep telling me"). The
backend's prompt asks for up to 8 objects that matter for walking, with boxes normalised
0–1000, hazards most urgent first, and readable text. The answer is **re-checked on the
server** before it reaches the rider:
- boxes are clamped, and clock positions are worked out from the boxes, not taken from the
  model;
- lists are capped;
- any sentence claiming a path is "clear", "safe" or has "no obstacles" is removed. Neither the
  app nor the model can know that.

Limits: 20 pictures a minute per rider, 2 MB per picture. `GET /me/vision` says whether it is
on; `POST /me/vision/describe` answers 404 with no key, 429 over the limit, 503 when Gemini
didn't answer.

**Keys and models** (`backend/cabeye-secrets.properties`):

```properties
cabeye.gemini.api-keys=key1,key2,key3
# cabeye.gemini.models=gemini-3.5-flash-lite,gemini-3.5-flash,gemini-2.5-flash
```

`GeminiClient` calls Gemini's OpenAI-compatible endpoint
(`…/v1beta/openai/chat/completions`, `Authorization: Bearer <key>`). For each model, fastest
first, it tries the keys in turn:
- **429**: that key rests for a minute.
- **401/403**: that key is skipped until restart.
- **400/404**: moves to the next model.

Failures are logged with Google's reason, never the key.

**On the phone (ML Kit).** Object detection, per-object labelling and text recognition, with the
models bundled in the app. No network, no key. Coarser names and no distances, so it is the
fallback for descriptions and the fast detector for "keep telling me" and the ride camera.
Falling back is silent and per picture: a rate limit or dropped connection costs one picture's
detail, never the answer.

---

## Explore in detail

`explore/ExploreController.kt`, `explore/ExploreCommands.kt`, `ui/ExploreScreen.kt`.

- **Opening** checks there is no ride (otherwise: "Explore is for when you're not on a ride…")
  and asks for the camera permission the first time, saying why first. Then: "Exploring. Hold
  the phone at chest height, pointing the way you face…"
- **Describe / read**: one picture through `describe` (Gemini, else ML Kit).
- **Keep telling me**: a loop over the camera pictures. Most go through the fast on-phone
  detector; every sixth gets a full Gemini look when available. Only things not mentioned in
  the last 12 seconds are spoken, and never over the app's own speech.
- **Find the door**: up to 90 seconds of looking, saying when and where it appears.
- **Name this place**: a named spot (`a:` node) at the phone's position, with the nearest thing
  the camera sees. It is bookable by voice afterwards.
- **Remembering**: at most every 4 seconds, objects worth remembering (confidence ≥ 0.5 and a
  word a walker can use) become `OBSERVED` nodes with position, side and sense. Repeats merge
  with earlier sightings (how many times, last seen). At most 600 are kept.
- **The path**: the walk is recorded like a recorded walk (steps, compass, GPS), with the things
  seen as its landmarks. On **Save & finish** it is saved as a walk ("the walk to Ravi's tea
  shop" after the last named spot, otherwise "explore walk, 11 Oct, 5:40 PM"), so "guide me"
  can follow it later.
- **Holding the camera picture talks**: describe, find …, read this, this is …, keep telling me,
  stop, finish, discard.

**Storage**: the mind map keeps the two modules apart, in separate SharedPreferences on the
phone:
- `cabeye.mindmap.rides`: destinations, cues, areas, ride traces, places offered after a ride
  (`place:`, `cue:`, `area:`, `near:`).
- `cabeye.mindmap.explore`: named spots, notes, camera observations, anything tied to a walk
  (`a:`, `obs:`, `lm:`, `walk:`).

An edge belongs to explore when either end does. The mind map screen shows the two as **From
my rides** and **From my walks**.

---

## The ride camera in detail

Backend `camera/RideCameraService.java`, `controller/CameraController.java`; phone
`camera/CameraStreamer.kt`, `RiderViewModel` (camera section), `driver/DriverSurface.kt`.

```
driver taps SEE PASSENGER'S VIEW → POST /rides/{id}/camera/request → CAMERA_REQUESTED
rider says yes (or taps)          → POST /rides/{id}/camera/answer  → CAMERA_STARTED
rider's phone streams CAMERA_FRAME over the ride WebSocket (≈4 a second, ≤ 480 px, JPEG)
any stop condition                → CAMERA_STOPPED {reason}
```

All enforced on the server, not trusted to either phone:
- Only the ride's driver can ask, and only before pickup (assigned, on the way, arrived) and
  before the code is confirmed.
- Nothing streams until the rider says yes.
- Frames are relayed only while live, only from the ride's rider, only to the driver's
  connection. They are never stored and never in the event log.
- It stops by itself on code confirmed, seated, trip start or end, cancel, or after 3 minutes.

On the rider's side:
- The question is spoken, with **Share / Don't share** buttons.
- If the app is in the background it says "Open Cab Eye to answer" and asks properly when it
  is back.
- *Settings → Camera for your driver → always share* skips the question.
- While the camera is live, the fast detector runs on the same pictures, and the rider hears
  what's new around them ("a car on your left").
- The camera never touches the microphone, so the app can still hear the driver say the
  boarding code.

---

## Tests

- Android `VisionExploreTest`: commands, clock positions, what counts as new, merging
  observations, module storage. Also `CameraConsentTest`.
- Backend `VisionTest`: key/model fallback, rate limits, the "clear/safe" filter, with a local
  stub server, so tests never call Google. Also `CameraEndpointTest`.
