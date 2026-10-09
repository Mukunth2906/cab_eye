# Walking-route memory agent — "the last hundred metres"

The cab gets a blind rider to the kerb. This agent gets them from the kerb to the door, and
from the door back to the pickup point, by remembering walks they have made. It follows
Clew3D (capture → landmarks → O&M narration) and Guerreiro et al. (previewing a route
sequentially helps people build a memory of it). Like the ride memory agent, it is rule-based,
costs nothing to run, and keeps everything on the phone.

## Feasibility

| Research layer | In this build | How |
|---|---|---|
| Capture | Yes | GPS (outdoors), accelerometer step detection and compass heading (indoors too, by pedestrian dead reckoning). Runs as a location foreground service, so it keeps recording with the screen off. |
| Capture: LiDAR / BLE beacons | No | Most Android phones have no LiDAR (Clew3D needs an iPhone Pro), and beacons need hardware on site. Indoor distances come from steps instead, which is less precise. |
| Landmark extraction | Partly | Landmarks come from the rider's own words ("bakery smell on my left", "kerb here"), parsed by rules. Visual-only cues (signs, colours) are refused, and colours are stripped from tactile cues. |
| Landmark extraction: vision model | No (hook left open) | A camera-based vision model would cost money or be too heavy to run on the phone. The rider is the sensor, which also means the stored landmarks are ones a blind traveller actually uses. |
| Route builder | Yes | `RouteBuilder` splits the walk into stretches: distance (calibrated steps), landmarks with their side, and a turn at the end. Turns come from the rider ("turning left") first, then from the compass (≥50° held for 3 s while walking; spinning the phone in place doesn't count). |
| Narration | Yes (templates, no LLM) | `WalkNarrator` uses O&M phrasing: "Part 1. Walk straight about 20 metres, about 30 of your steps. Then turn right at the kerb." |
| Memory store | Yes, on the phone | Per-route trip data, the cleaned route graph (segments and landmarks), and a profile (preferred detail, consent). All in `WalkStore` (app-private SharedPreferences). Nothing goes to the server. |
| Agentic recall | Yes | "How do I get to the clinic" gives the whole route. "Guide me to the clinic" goes one part at a time (next / repeat / previous / more detail / less detail / I'm there). "Tell me the walk" in the cab previews the route before getting out. |
| Learning over time | Yes | Detail is FULL for the first 2 walks, BRIEF from walk 3, and LANDMARKS only from walk 6. It is held back a level after repeats or requests for more detail, and the rider can shift it with "more/less detail". Every guided walk is re-recorded silently. If it matches, the landmarks are confirmed; if it differs, the agent says where and asks before updating. |
| Checking and correcting | Yes | After a walk the agent asks about the stalest landmark ("was the bakery smell still there?"). Yes confirms it. One no flags it ("wasn't there last time, so don't rely on it"); a second no removes it. The rider can correct a cue by saying "the bakery smell is the cue for the left turn". |
| Safety | Yes | Every landmark has a confidence and a last-confirmed date. Stale ones are said as "check for it". The narration never says the way is clear; FULL detail ends with "I can't tell whether the way is clear." A unit test enforces this. |
| Study log (Stage 3) | Yes | A local log of recall, guide start, next, repeat, more/less detail, corrections, verify answers, detected changes and route updates. "Export my walking log" shares it as a CSV. |
| The 3-stage evaluation itself | No | That is the research work: ethics approval, O&M specialists, participants. The app produces the Stage 3 logs and the routes Stage 1 measures. |

## How it fits the ride

- After a ride ends at a place with a saved walk: "I have your walk from here to the clinic door. Say guide me when you're ready."
- A walk recorded within 30 minutes of a ride is linked to that drop-off, so "guide me" with no name picks it.
- In the cab, "tell me the walk" previews the walk (a rehearsal before getting out).
- "Take me to the clinic" still books a cab. "How do I get to the clinic" never does.

## Voice commands

| Say | What happens |
|---|---|
| "record my walk to the clinic door" | Asks for consent the first time (on-phone only), then records. Press the screen to say "kerb here", "bakery smell on my left", "turning left", or "I'm there". |
| "how do I get to the clinic" / "remind me of the route to work" | Reads the whole route at the current detail level. |
| "guide me to the clinic" / "guide me" | Guides one part at a time. Press and say next, repeat, previous, more detail, less detail, I'm there, or stop. |
| "the bakery smell is the cue for the left turn" (while guided, or when asked) | Moves that landmark to the left turn. |
| "my walking routes" / "forget the route to the clinic" / "forget my walking routes" | Lists or deletes routes. |
| "export my walking log" | Opens the share sheet with the study log as a CSV. |

## Code

- `walk/WalkModels.kt`: routes, segments, landmarks, events, profile.
- `walk/LandmarkParser.kt`: the rider's words become a landmark, a turn, or a refusal.
- `walk/RouteBuilder.kt`: recorded events become segments.
- `walk/WalkNarrator.kt`: O&M narration, detail levels, cautions.
- `walk/RouteMemory.kt`: find, verify, correct, compare, update, CSV.
- `walk/WalkCommands.kt`: what the rider says.
- `walk/StepDetector.kt`: steps from the accelerometer (no extra permission).
- `walk/WalkRecordingService.kt`: the sensors, as a foreground service.
- `walk/WalkStore.kt`: storage on the phone.
- `RiderViewModel.kt`: the "Walking routes" section, which handles the dialogue.
- Tests: `WalkRouteTest.kt` (synthetic walks outdoors and indoors, narration, safety, memory over time, commands, step detection).

## Known limits

- Step-based distances drift by about 5–10% over long indoor walks. Routes are meant to be short (the last hundred metres).
- Compass readings are disturbed near metal and inside vehicles. Spoken turns override the compass.
- Routes live only on the phone, so a lost phone means lost routes. An opt-in encrypted backup to the server is a possible next step.
- The vision-model landmark hook is not built.
