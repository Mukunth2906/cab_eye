# Code walkthrough

Thirteen files. Five pages, one stylesheet, seven scripts. No frameworks, no build step.

The split exists so four people can work at once without fighting over one file in git,
and so the rider and driver are genuinely separate apps rather than two halves of one page.

---

## css/theme.css — the design system

Everything visual comes from tokens in one `:root` block. Change a colour there and every
page follows.

**Text is deliberately bright.** `--ink #F5F9F8` is 17:1 on the ground, `--ink-dim
#C6D1CE` is 11:1, and `--ink-faint #A2AFAC` is 7.4:1. Nothing dimmer than that exists in
the design — if text isn't worth 7:1 it gets deleted rather than greyed out. That's WCAG
AAA, which is the standard to claim in an accessibility paper.

**Five accent colours, each meaning a speaker:**

| Token | Who |
|---|---|
| `--amber` | the rider speaking |
| `--teal` | the system speaking |
| `--violet` | earcons — non-speech audio |
| `--red` | alerts, SOS, deviation |
| `--green` | driver events |

Every log line is coloured by *who made the sound*, which is what the project is about.

Base size is 19px. Buttons are at least 64px tall. `prefers-reduced-motion` kills all
animation. Focus rings are 3px teal and visible everywhere.

---

## js/bus.js — how the two apps talk

`BroadcastChannel`: real message passing between two browser windows on the same origin,
no server. `publish(type, payload)` and `on(type, fn)`.

Every message name lives in `CE.bus.T` so the two apps can't drift apart. There's a
`localStorage` fallback for browsers without BroadcastChannel.

**Why this shape matters:** it's publish/subscribe, exactly like the WebSocket the Ktor
backend will use. Swapping the prototype for the real thing means rewriting this one file
and nothing else. The event bus isn't a fake — it's the real architecture at small scale.

Needs a real origin, which is why you serve the folder instead of double-clicking.

---

## js/audio.js — the actual interface

One primitive builds every sound:

```js
tone(freq, dur, {type, gain, pan, delay, glide})
```

Oscillator → gain envelope → `StereoPannerNode` → speakers. `pan` runs −1 (left) to +1
(right), which is what makes bearing audio possible.

**Nine earcons** built from it: `listen`, `captured`, `thinking`, `booked`, `assigned`,
`arrived`, `error`, `alarm`, `beacon(pan)`, `approach(pan, near)`.

**`heartbeat(on)`** — a 58 Hz pulse every 2.6 s at very low volume. This is the
system-alive floor. While it pulses, silence is not ambiguity; when it stops, something
is wrong. It answers the exact failure mode the problem statement names: users can't tell
frozen from working.

**`speak(text, tier)`** — the Ambient Status Narrator.

- **tier 0** cancels whatever is speaking and interrupts (arrival, route deviation)
- **tier 1** queues normally (assignment, confirmation)
- **tier 2** never reaches here — those events fire an earcon and nothing else

`onstart` stamps the T4 latency mark and starts the dwell clock; `onend` adds the elapsed
seconds. That's how `Audio dwell` is measured, and why the approach phase shows it
staying flat.

---

## js/voice.js — the ear

The rule this file enforces: **if the app asks a question out loud, it must listen for the
answer.** Without it the app talks like an assistant and listens like a form.

One microphone, three ways of using it:

| Mode | When |
|---|---|
| `dictate` | the opening sentence — "take me to Anna Nagar" |
| `answer` | a one-shot reply to a question the narrator just asked |
| `command` | a standing loop, so "status" or "cancel" work at any moment |

`open(kind, handler, opts)` starts a fresh recogniser each time — Chrome allows only one
at a time, and reusing an instance across modes is where flaky voice apps come from.

**The mic is closed while the narrator speaks.** Otherwise the recogniser hears the
phone's own voice and answers itself. If a call to `open()` arrives mid-sentence it waits
and retries rather than failing.

**Barge-in:** the first word the user speaks calls `CE.audio.shutUp()`, so the narrator
stops mid-sentence. Nobody has to sit through a long announcement.

**Returning `false` from an `answer` handler re-opens the mic** — that's how "sorry,
which one?" works without extra plumbing.

`G` is the grammar: `cancel`, `yes`, `status`, `call`, `again`, `repeat`, `help`, `first`,
`second`. `pickCandidate(text, candidates)` decides which of two places the rider meant —
by name, by "first"/"second", or by the distinguishing word alone ("east").

One honest caveat for the paper: a standing mic drains battery and mis-fires, which is why
real assistants use a hardware key. `Hands-free: off` exists so you can compare both.

---

## js/nlu.js — the research bit

**`PLACES`** — 15 Chennai locations with coordinates. Anna Nagar East and West sit 2.0 km
apart on purpose, so the ambiguity rule fires on demand during a demo.

**`PATTERNS`** — five regexes: *take me to X*, *go to X*, *book an auto to X*, *…to X*,
and the Tamil-English *X-ku poganum*.

**`parseIntent(text)`** returns `{q, rideType, fast}`. **`fast` is the finding you
report** — true means a rule matched in microseconds, false means it fell back.

**`resolve(text)`** is the whole pipeline, and the rule lives at the end of it:

```js
const DELTA   = 0.16;   // scores this close count as tied
const DIVERGE = 1.5;    // km — closer than this, being wrong is cheap

tied && far apart   →  {ask:true,  candidates:[a,b]}
tied && close       →  {ask:false, place}    // don't spend a turn
clear winner        →  {ask:false, place}
```

Confidence-gated clarification: ask only when being wrong would actually cost the user
something. Two clinics 400 m apart aren't worth a spoken turn; two 12 km apart are. Every
decision prints its own numbers to the log, so it's visible in a demo and screenshot-able
for the paper.

---

## js/auth.js — two roles, two front doors

A typed login form is the worst possible screen for a blind user: a sequence of fields,
each needing traverse → focus → type → verify. It's where blind users abandon apps.

So the rider does it **once** — `setupRider()` writes a device token — and every later
launch goes straight to the mic. `riderReady()` is what the role picker checks.

The driver gets `loginDriver(email, pass)` against two hardcoded accounts.

Sessions are stored **per role** (`cabeye.session.rider`, `cabeye.session.driver`), so
one machine can run both apps at once — which is exactly what the rig does.
`ensureDemo()` signs both in so the rig's iframes boot without a login round-trip.

None of this is real security. It's a prototype.

---

## js/rider.js — the blind user's app

**One surface. Eleven states.** `render()` is a single function with one branch per
state. There is no router, no screen stack, no back button, because a blind user never
navigates. The screen changes because the ride changed.

```
idle → listening → resolving → [clarify] → confirming
     → finding → assigned → approaching → arrived → intrip → done
```

The user acts in exactly two of them: **idle** (speak) and **confirming** (optionally
cancel). That's the one-tap claim, expressed in code.

**Listening** uses `webkitSpeechRecognition` at `en-IN` with interim results, so partial
text appears while you talk. Every failure path — no API, blocked mic, error, silent end
— falls back to the sample phrases.

**`commit(place)` is optimistic execution.** It books immediately and opens a 5-second
undo window instead of asking "shall I confirm?". That removes a whole turn from the
critical path. `UNDO_SECONDS` is the knob.

**Driver events arrive as `B.on(...)` handlers** — accept, arrived, says, beacon,
onboard, deviation, complete — and each one decides whether to spend speech or just an
earcon. `startApproach()` is the interesting one: a tone every 1.5 s, pitch rising,
panned by bearing, and **zero words**.

The spacebar stands in for a hardware talk key, so the app works with the screen off.

---

## js/driver.js — the sighted driver's app

Conventional UI on purpose: this user can see, and is driving. No speech recognition, no
text-to-speech. Seven screens: offline → waiting → request → topickup → arrived →
intrip → complete.

Four things make it different from an ordinary driver app:

1. A **non-dismissable "passenger is visually impaired" badge**. Everything else fails
   without this.
2. **Preset position phrases** — the driver taps, the rider *hears*. This is the modality
   bridge, and it's the fix for the last-50-metres problem from the other side.
3. An **audio beacon**, panned to the driver's real bearing.
4. An explicit **"passenger is seated"** confirmation before the trip starts.

`Auto: on` in the top bar makes the driver side play itself, so one person can demo alone.

---

## The pages

**index.html** — role picker. Two enormous targets. If the rider is already set up, it
says so and skips login entirely.

**login.html** — one file, two completely different forms depending on `?role=`. The
rider version says out loud that it's the only form they'll ever see.

**rider.html / driver.html** — thin shells. A top bar, a container, and script tags. All
the behaviour is in the js files.

**rig.html** — the paper view. Two `<iframe>`s (the real rider and driver apps, not
copies) plus the instrumentation panel. It subscribes to the same bus the apps use, so
the log, the state ladder, the latency marks and the gauges are all reading real traffic.
Zero duplicated code.

---

## Where to change things

| You want to… | Go to |
|---|---|
| Add destinations | `PLACES` in `js/nlu.js` |
| Change demo phrases | `SAMPLES` in `js/nlu.js` |
| Tune the ambiguity rule | `DELTA` / `DIVERGE` in `js/nlu.js` |
| Add a speech pattern | `PATTERNS` in `js/nlu.js` |
| Change the undo window | `UNDO_SECONDS` in `js/rider.js` |
| Redesign an earcon | `earcon` in `js/audio.js` |
| Change heartbeat rate | `heartbeat()` in `js/audio.js` |
| Change what interrupts | the `tier` argument on each `say()` call |
| Add a voice command | `G` in `js/voice.js`, then `onCommand()` in `js/rider.js` |
| Colours, fonts, sizes | `:root` in `css/theme.css` |
| Driver position phrases | `POSITIONS` in `js/driver.js` |
| Add driver accounts | `DRIVERS` in `js/auth.js` |
| Add a message type | `CE.bus.T` in `js/bus.js` |

## Code → paper

| Claim | Where it lives |
|---|---|
| One tap per booking | the `taps` counter; `render()` has one branch and no navigation |
| Ask only when it matters | `resolve()` in `js/nlu.js` |
| Rules handle most speech | the `fast` flag in `parseIntent()` |
| Speech time is task time | dwell tracking in `speak()`; `startApproach()` spends none |
| Silence means fault | `heartbeat()` |
| Bearing audio for pickup | `tone()`'s `pan`, used by `earcon.beacon` |
| Driver taps, rider hears | `POSITIONS` → `T.SAYS` → `say()` |
| Login is an accessibility barrier | `js/auth.js` — rider sets up once, never again |
| Latency budget | `mark()` and the T0–T4 tiles in `rig.html` |
| Spoken prompts are spoken-answerable | `askWhich()` and `commit()` in `js/rider.js`, via `js/voice.js` |
| Zero taps per ride | the `taps` gauge reads 0 for a full voice ride |
