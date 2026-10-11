# Personal mind map: memory for walks and rides

A sighted person holds a mental map of the places they know, built from everything they see.
A blind traveller builds one from what they feel, hear and smell, and from what places mean to
them: "left after Ravi's tea shop", "the temple bell means Amma's house is close". The walking
agent ([WALKING-ROUTE-AGENT.md](WALKING-ROUTE-AGENT.md)) remembers single walks, and the ride
memory ([../AGENT-ARCHITECTURE.md](../AGENT-ARCHITECTURE.md)) remembers cab destinations. The
mind map joins them into one connected memory and adds the rider's own words. It works in two
ways:

- **On foot:** "what's around the clinic?" gives a spoken mental map of the place. Personal notes
  are read back during walk guidance.
- **In the cab:** the ride guide says the cues the rider marked on earlier rides as they come up,
  names the areas the cab passes through, and says once, calmly, if the cab is not on the usual
  way.

Like the other agents it is rule-based, explainable and tested on the JVM. Everything stays on
the phone. The one optional exception is a Grok reader for sentences the rules can't follow:
the rider has to agree to it, and only that one sentence is sent.

## The graph

| Node | From | Example |
|---|---|---|
| PLACE | cab destinations (ride memory), walk ends, places the rider kept | KG Clinic, clinic door |
| LANDMARK | walk landmarks | bakery smell on your left |
| ANCHOR | the rider's own notes | Ravi's tea shop: you stop on Fridays |
| AREA | the phone's geocoder, on rides | Gandhipuram |
| RIDE_CUE | marked by the rider in the cab | the flyover, speed bump |

| Edge | Meaning |
|---|---|
| LEADS_TO | drop-off → walk end (the ride, then the walk, then the door) |
| ON_WALK / ON_RIDE | landmark → walk end; cue or area → destination |
| SAME_AS | the same landmark on two walks |
| MEANS | anchor → the landmark or place it gives meaning to |
| NEAR | two things the rider knows are close together |
| IN_AREA | place → area |

Places, walks and landmarks are **derived** again each time from the walk store and ride
memory, so they never go out of date. Anchors, cues, areas and kept places are **authored** and
stored in `MindMapStore` (SharedPreferences `cabeye.mindmap`, on the phone only).

**Same landmark.** Two walk landmarks are joined only if all of these hold:
- they are the same kind;
- they share words that name *this* one, not just its kind ("bakery smell" yes, "kerb" never);
- those words agree;
- when both walks are located, they start within 400 m of each other.

A wrong join would tell the rider a kerb is somewhere it is not.

## Voice commands

| Say | What happens |
|---|---|
| "Ravi's tea shop, where I stop on Fridays" / "the temple bell means I'm near Amma's house" / "remember this as the place I meet Priya" | A personal note, read back in the second person and joined to the landmark or place it is about. When a name matches more than one landmark, the one on the walk where the rider is wins. If it is still unclear, the note is kept unjoined rather than guessed. |
| "Remember this as Ravi's tea shop" / "remember this as the place I meet Priya", said on the spot | The spot is kept at the phone's position. Next time, "take me to Ravi's tea shop" or "take me to where I meet Priya" books a ride straight there, with the usual announcement and cancel window. Small shops and meeting points are often on no map. Only "this place" notes take a position: "Ravi's tea shop, where I stop on Fridays" said at home does not put the shop at home. |
| "What's around the clinic?" / "what's around here" | The walk from the drop-off and its landmarks with the rider's notes, places the rider knows within 1 km (distance and direction), ride cues on the way there, and the area. Old notes are said with "check it still holds". Away from any known place, "around here" uses the phone's position: the area, and the rider's places nearby. Where the rider knows nothing, it offers well-known places instead. |
| "What do I know about Ravi's shop?" | Everything joined to that one thing. |
| "What's famous around here?" | Well-known places nearby (see below). |
| "Read my mind map" / "forget the note about …" / "forget my mind map" | Summary; forget one note or ride cue; forget all authored content. Walks and ride history are kept. "Forget my history" now also forgets the ride traces kept on the phone. |
| In the cab: a tap on the screen | "Where are we", answered at once, as before. It now adds the area and, on a ride taken before, the usual time left. |
| In the cab: hold the screen and say "speed bump", "that was the flyover", "I can hear the market" | Marks a ride cue at this point of the ride. Saying it again on a later ride confirms it. "Undo" takes back the last one. |
| In the cab: "where am I" / "how much longer" | Area, distance into the ride, minutes left by the usual way (or straight-line distance on a first ride), and the next cue. |
| In the cab: "quiet ride" / "talk to me" / "what's on the way" | Cues off or on, and a preview of the usual way. |

**At the main question and after a ride**, a sentence like "the mall where I bought shoes" stays
a destination. Only "remember …" turns it into a note there. Saved-route naming ("save this as
Monday errands") and "remember my walk" keep their old meanings. Every utterance in the
existing test suites was run through the new parsers, and none is taken.

**Discovering it.** "Help" now mentions teaching places and the cab commands. The ride guide
also introduces itself once, on the first ride.

**Visual-only cues** ("the red sign means turn left") are refused, as on walks.

## Ride mode

- **Tap or hold.** On an ordinary ride a tap still answers "where are we" at once. Holding the
  screen talks to the ride guide, and letting go ends it, as at the main question. A multi-stop
  ride keeps its existing press behaviour. Nothing said in the cab ever books a ride.
- **The rider's own GPS** feeds the ride guide (`LocationProvider.updates`, every 5 s, fixes worse
  than 50 m dropped). `NarrationService` adds the `location` service type when location is
  allowed, so this keeps working with the screen off. Without GPS, the server's trip meter is
  used to place cues.
- **Cues:** said about 150 m before the place, with the minutes left from the usual ride.
  - Cues within 350 m are said in one sentence ("the speed bump, then the market noise coming up").
  - Otherwise at most one announcement a minute.
- **Areas:** "Now in Gandhipuram" when the area changes, at most every 2 minutes.
  - Neighbourhood names only, never the city.
  - A change must hold for two readings, so a boundary road doesn't flip-flop.
  - The first area is not said.
- **Familiar rides say less.** From the third ride the same way:
  - the preview is one line ("About 20 minutes by your usual way");
  - area names stop, unless the cab leaves the usual way, when knowing the area matters again.
- **Almost there:** about 400 m from the destination, said once even on a quiet ride: "Almost
  there: KG Clinic is about 350 metres away." If a walk is saved from that drop-off, its cue line
  follows, so the rider can get ready to step out.
- **While the rider is talking,** cues and area names are not said, so the microphone doesn't hear
  them. Safety and arrival are said anyway.
- **Unusual way:** checked once 400 m into the ride, against earlier rides to the same place
  whose road passes within 500 m of where this ride starts. This also works after the app
  restarts mid-ride.
  - If the cab is more than 300 m off all of them for two fixes in a row, it is said once: "This
    isn't your usual way to home. It may just be a detour for traffic. If anything feels wrong,
    press and hold the S O S button for two seconds."
  - Coming back is said too.
  - This is said even on a quiet ride.
- **Traces:** the last 5 rides to each place are kept (points about every 50 m, plus the areas
  passed through).

## Well-known places nearby

- **When it's offered:** after a ride to a place the rider has never been to (no earlier visit,
  no saved walk). The offer is fetched straight away and said once paying and feedback are over:
  "This is new for you. Well-known places close by: Sri Ranganathar Temple, about 200 metres
  north; …"
- **Where the list comes from**, in order (at most three places):
  1. **Google Places Nearby Search (New)**, 600 m, ranked by popularity, when the app has a key.
     Only places rated 4.0 or higher with at least 100 ratings are kept. The key must allow
     "Places API (New)".
  2. **OpenStreetMap, no key**, through the backend (see below). It has no ratings, so places
     are ranked by what the map says about them.
  3. **Offline:** the app's own gazetteer.
- **What the rider can say:**
  - "tell me more": the type of place, its rating, and that distances are straight-line, not the
    walking route.
  - "take me to the temple", or "take me there" after hearing about one: books that exact place
    (its position, not a new search), with the normal announcement and cancel window.
  - "remember them": kept in the mind map, NEAR the drop-off.
  - "no": counts as a decline. After two declines the app stops offering by itself.
  - "stop suggesting places" / "suggest places again": turns the offer off or on.

## No paid services: the offline Coimbatore atlas

Nothing in this feature needs a paid service.
- **GPS is the phone's own.** The GPS chip, read through Android's location service, is free
  and needs no key.
- **Google Places is optional.** It's only used when a key is configured.
- **What is there comes from a map bundled in the app.** For Coimbatore that means area names,
  well-known places, and destination search. It works with no network at all.

**The data:**
- Source: real OpenStreetMap data, `android/app/src/main/assets/atlas/coimbatore.json`, about
  125 KB.
- Contents: 136 named areas, plus the app's own hand-checked ones such as Race Course and
  Sundarapuram, and about 1,330 places: stations, bus stands, temples, hospitals, colleges, malls
  and parks.
- Coverage: Thudiyalur and Saravanampatti in the north to Madukkarai in the south, and Vadavalli
  and Perur in the west to Sulur and the airport in the east.
- Built once by `python scripts/build_coimbatore_atlas.py`. Run it again for fresher data.

**Names people actually say** are added to the places they mean: "Marudhamalai temple", KMCH,
PSG Tech, GH, TNAU, "airport", "railway station". Each one must match a place really in the
data; nothing is placed on the map by hand. If you add one, run the script with
`--aliases-only` to apply it without downloading again.

**What the app asks it:**
- **Area names** for the ride guide and "where am I". The nearest named area wins, weighed by
  how far that kind of area reaches: a neighbourhood is small, a village large.
- **Well-known places nearby**, ranked by what the map says about them. A Wikipedia link counts
  most, then attractions, monuments, stations and malls. Schools, banks and clinics can be booked
  but are not offered as sights.
- **Destination search without Google.** "Take me to Marudhamalai temple" works offline.
  - It uses the same scoring as the built-in places, so the confidence gate (book it, ask, or
    say "I didn't find it") is unchanged.
  - The 16 built-in places behave exactly as before.
  - An area ("take me to Kalapatti") is treated like a road: an area is not an address, so the
    app asks who is meeting the rider.

**What's not in the data:** places OpenStreetMap doesn't have (in this build, Prozone Mall, Fun
Mall and CODISSIA, plus Saravanampatti as an area name). The app says it didn't find them rather
than guessing. Adding them to OpenStreetMap, then rebuilding, fixes that for everyone.

Outside the atlas the app falls back as before: the phone's geocoder, then OpenStreetMap through
the backend.

## Try a ride on a phone without a cab

In a debug build, say **"simulate a ride"**, or **"simulate a ride to Koniamman Temple"**.

**How it plays:**
- The destination is found in the offline atlas.
- The ride starts from where the phone is, or from Saibaba Colony when the phone isn't in
  Coimbatore.
- The usual driver steps play first, then the trip runs on made-up GPS through the real ride
  guide: one fix every 0.7 seconds, each standing for 15 seconds of driving.
- Only the movement is synthetic. The places, and the area names said along the way, are real.

**What you can try:**
- Tap for "where are we".
- Hold the screen and say "speed bump" or "undo".
- Ask "where am I".
- Hear "almost there".
- Hear the nearby offer after arriving.

Simulate the same ride a second time to hear a repeat ride: the preview, your cues coming up,
and the usual time left. No server ride is created and no fare is charged.

## Real map data without keys (OpenStreetMap)

The backend serves real OpenStreetMap data to the app, with no API key. The code is
`backend/.../places/OsmService.java` and `PlacesController.java`.

| Endpoint | Source | Used for |
|---|---|---|
| `GET /me/places/area?lat&lng` | Nominatim reverse geocoding | Area names in the cab and for "what's around here", when the phone's own geocoder can't name the area |
| `GET /me/places/nearby?lat&lng&radius` | Overpass | Well-known places, when there is no Google key or Google has nothing |

**Area names are the names people say.**
- The order tried is neighbourhood, then quarter, then suburb.
- "Ward 85" and "Central Zone" are skipped.
- If the map only has a ward number at that point, the nearest named neighbourhood within 1 km
  is used instead.
- Checked live along a ride in Coimbatore: Saibaba Colony, Vadakovai, RS Puram, Town Hall,
  Five Corner.

**"Well known" without ratings.** Places are ranked by what the map says about them:
- a Wikipedia or Wikidata link counts most;
- then attractions and monuments, railway stations, malls, parks and colleges;
- ordinary temples and clinics only fill the list when nothing better is near.

At the clinic in the scenario, the live answer was: Coimbatore Junction, Koniamman Temple, the
Medical College Hospital, the Police Museum, Clock Tower and Ashok Pillar.

**Why the calls go through the backend, not straight from the phone.** The OpenStreetMap
services' usage policies ask for things only the server can guarantee:
- an identifying User-Agent;
- caching (areas for 7 days, places for a day, on a ~110 m grid);
- for Nominatim, at most one request a second across all users. Callers wait up to 2.5 s for a
  turn, otherwise the answer is "not known", which is not cached.

It also keeps riders' IP addresses away from third parties. Positions are passed on rounded to
about 11 m, and the backend stores none of them.

**Busy servers are expected.** Public Overpass servers often answer 504, or 200 with an HTML
error page. In live testing, six separate spatial searches timed out, while one search filtered
afterwards answered in about 5–8 s, so that is the query used. Each configured server is
tried in turn, and failures are never cached. The phone gives this one call a longer timeout
(35 s), says "Looking for well-known places nearby", and keeps the heartbeat going while it
waits. Repeat answers come from the cache in milliseconds.

**Settings** (in `application.properties` or `cabeye-secrets.properties`):

| Setting | Default |
|---|---|
| `cabeye.osm.enabled` | `true` (`false` in tests, which use recorded answers) |
| `cabeye.osm.user-agent` | identifies Cab Eye |
| `cabeye.osm.nominatim-url` | `https://nominatim.openstreetmap.org` |
| `cabeye.osm.overpass-url` | `https://overpass-api.de/api/interpreter,https://z.overpass-api.de/api/interpreter` |

For heavy use, point these at your own Nominatim and Overpass instances or a commercial
provider. The public ones are for light use.

**Credit:** "Place information from OpenStreetMap" is said at the end of "tell me more", and
both endpoints return the ODbL attribution.

## The optional Grok reader

- **When it is used:** when the rules don't understand a sentence in one of these places:
  - during a walk;
  - in the cab;
  - at the main question, after "remember …".
- **Consent:** the first time, the app asks whether it may send just that sentence to the
  server. A "no" is final. Silence or an unclear answer is not consent; the app asks again on
  another day.
- **What is sent:** the sentence and up to 20 names of places the rider already knows. No
  position, route or history.
- **The backend side** (`backend/.../mindmap/InterpretService.java`, `POST /me/mindmap/interpret`):
  - It asks **Grok** (xAI, `grok-4.7` by default, set with `cabeye.grok.model`) through the
    chat completions API for a structured answer (a strict JSON schema derived from
    `Interpretation`): intent, subject, person, habit, meaning, sense, side and confidence.
  - A 4-second timeout and no retries, because the rider is waiting to hear a reply; if it fails,
    the rules take over instead. A sound plays while the app waits.
  - At most 30 sentences per rider per hour.
  - Without `cabeye.grok.api-key` in `cabeye-secrets.properties` the endpoint answers 404 and
    the app stays rules-only for the session.
- **Checks on the answer:** the phone checks it again with the same rules as the rider's own
  words.
  - Visual-only subjects are refused.
  - "Clear" or "safe" claims are dropped.
  - A reading below 0.7 confidence is asked about ("Did you mean …? Say yes to save it") rather
    than saved.

## Try it without keys or a backend

`MindMapScenarioTest` runs five days of one rider in Coimbatore through the real agents. It
covers rides, cues, undo, a detour, "almost there", the nearby offer from the app's own place
list, notes, a walk, and booking a spot that is on no map. It then checks the key moments and
writes what the rider would hear to `android/app/build/mind-map-simulation.txt`.

```
cd android
.\gradlew.bat :app:testDebugUnitTest --tests "com.cabeye.rider.MindMapScenarioTest"
type app\build\mind-map-simulation.txt
```

**With live data.** Start the backend, sign in a rider to get a token, then run:

```
set CABEYE_LIVE_URL=http://localhost:8080
set CABEYE_LIVE_TOKEN=<the rider's token>
.\gradlew.bat :app:testDebugUnitTest --tests "com.cabeye.rider.MindMapScenarioTest" --rerun
type app\build\mind-map-live.txt
```

Area names and well-known places then come from OpenStreetMap through the backend: the same
calls the app makes.

**On a phone without keys:**
- well-known places come from OpenStreetMap, or the built-in list when offline;
- area names come from the phone's geocoder, then OpenStreetMap;
- only the Grok reader is off.

## Code

- **`mindmap/`** (pure Kotlin unless noted):
  - `MindMapModels.kt`: graph and geometry.
  - `MindMapBuilder.kt`: derived graph and `LandmarkLinker`.
  - `AnchorParser.kt`.
  - `MindMapAgent.kt`: find, around, about, remember, notes for walk narration.
  - `MindMapCommands.kt`.
  - `RideGuide.kt`: `RideGuideAgent`, `RideCueParser`, `RideCommands`, traces.
  - `NearbySuggestionAgent.kt`.
  - `MindMapInterpreter.kt`.
  - `MindMapStore.kt`: Android storage, plus the pure `MindMapJson`.
- **`places/`:**
  - `AreaNamer.kt`: the on-device geocoder.
  - `GooglePlaceResolver.searchNearby`.
- **`RiderViewModel.kt`:** the "personal mind map" section, plus the hooks at trip start,
  progress and end, ride words, post-ride and walk narration.
- **`walk/`:**
  - `WalkNarrator` takes the rider's notes.
  - `LandmarkParser` exposes its kind and colour patterns for reuse.
- **Tests:**
  - Android: `MindMapTest`, `RideGuideTest`, `NearbySuggestionTest`, `MindMapInterpreterTest`.
  - Backend: `MindMapInterpretTest`, with a stubbed model, so tests never call Grok.

## Known limits

- **Landmark positions:** walk landmarks are placed at the walk's drop-off, because walks are
  short and keep no per-landmark GPS. "Near" between landmarks is therefore coarse.
- **Area names** come from the phone's geocoder. On most phones that needs a network, and the
  names vary by phone.
- **Ride GPS and Android 14:** if the narration service is restarted from the background,
  Android may refuse the location type. The service then starts without it, so narration
  keeps working, and the ride guide falls back to the server's trip meter.
- **Positions:** "here" uses a fix up to a minute old, or a fresh one, waiting up to 6 seconds.
  It never uses one older than 5 minutes, so a spot is never saved where the rider was hours ago.
- **Place keys:** the backend keys places without a Google ID as `name:<name>`, while walks use
  the bare name. The mind map matches both, but the two keys still differ.
