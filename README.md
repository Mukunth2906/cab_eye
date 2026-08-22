# Eyes-Free Booking Rig

Interactive prototype of the voice-first cab booking system. Rider phone on the left,
driver phone on the right, instrumentation in the middle. It actually speaks.

## Repository

GitHub: https://github.com/Mukunth2906/cab_eye

## Run it

Open `index.html` in **Google Chrome**. Nothing to install, no server, no build step.

Chrome matters — speech recognition only works there. Firefox will run everything else
but you'll have to use the sample phrases instead of your voice.

## Try this first

1. Click **Auto driver** in the top right so the driver side plays itself.
2. Hold the mic (or press **Spacebar**) and say *"take me to Anna Nagar."*
3. Listen. Don't touch anything else. The whole ride runs on its own.

Then run it again with *"take me to Anna Nagar East"* and watch the difference in the
log — the first one asks you which Anna Nagar, the second one doesn't.

## What to look at

**The rider taps once.** After the mic, everything you hear is the system talking on its
own. That's the claim. The Taps counter at the top proves it.

**The ambiguity rule fires in the log.** Look for the `AMBIGUITY` line:

```
gap 0.00 < 0.16 and 2.0 km apart  →  ask
gap 0.00 < 0.16 but 0.4 km apart  →  don't ask, book it
```

That's the confidence-gated clarification policy. It only spends a turn when being
wrong would actually cost the user something. Tune `DELTA` and `DIVERGE` in the script.

**The fast path vs fallback line.** `FAST PATH` means a rule matched the sentence in
microseconds. `FALLBACK` means it didn't. Count these across 30 utterances and you have
a real result for the paper — the fraction of speech the cheap path handles.

**Earcons instead of speech.** While the driver approaches, the rider hears a tone that
speeds up, not a sentence. Watch the `Audio dwell` gauge stay flat during that stretch.
Speech time is task time, so every second not spent talking is a second saved.

**Stereo bearing.** On the driver phone, tap **Play audio beacon**. It pans left or right
to match the driver's real bearing. Use headphones. This is the last-50-metres idea.

**Latency marks.** T0–T4 across the middle. Mic release → speech recognised → intent
parsed → place resolved → first sound. This is exactly the instrumentation the Android
spike needs. Whatever numbers you see here are your browser's, not Android's, but the
five measurement points are the same.

## One thing to know about the numbers

`Time to booking` includes the 5-second undo window, because the booking genuinely
isn't confirmed until that window closes. If you'd rather report the moment intent was
resolved, that's the T3 mark. Change the window length in `commit()` — `S.undoLeft = 5`.

## Tech stack

**This prototype** — deliberately dependency-free so it runs anywhere:

| Piece | What it uses |
|---|---|
| Everything | Plain HTML + CSS + JavaScript, single file, no build |
| Voice out | Web Speech API (`speechSynthesis`) |
| Voice in | Web Speech API (`webkitSpeechRecognition`), Chrome only |
| Earcons, heartbeat, beacon | Web Audio API oscillators |
| Bearing audio | Web Audio `StereoPannerNode` |
| Intent | Regex patterns + a 15-place Chennai gazetteer |
| Type | IBM Plex, loaded from Google Fonts |

**The real product** — unchanged from what we agreed:

| Piece | What it uses |
|---|---|
| Rider + driver apps | Kotlin + Jetpack Compose |
| Speech | Android `SpeechRecognizer` and `TextToSpeech` |
| Earcons | `SoundPool`; `AudioTrack` for panning |
| Bearing | Device magnetometer |
| Backend | Ktor |
| Database | PostgreSQL + PostGIS |
| Real-time | One WebSocket |

Cut for now: payment, Kafka, Redis, Keycloak, microservices, Docker, cloud deploy.

## What's fake in here

The gazetteer is 15 hardcoded places, there's no real geocoding, no routing, no map, no
backend, no persistence, and the driver is a person clicking buttons rather than a moving
car. None of that matters for what this is for — it exists so we can *hear* the design
and time it before writing Android code.

## Files

```
index.html    the whole prototype
README.md     this file
```
