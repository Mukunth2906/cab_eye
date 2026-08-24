# Running Cab Eye, and checking it actually works

## The one rule

**Do not double-click the HTML files.** Chrome will not remember a microphone
permission for a `file://` page, because `file://` has no origin to remember it
against. The grant becomes one-shot, so every time the listener restarts — about
twice a second — you get another permission dialog. That is the "asking every 3
seconds" bug, and no amount of JavaScript can fix it. `localhost` is a secure
context; serving the folder is the entire fix.

---

## 1. Start it (30 seconds)

Double-click **`serve.bat`**.

It finds whatever you have (`python`, `py`, `npx`, or `php`), serves the folder on
port 8000, and opens the self test. If none of those are installed it says so and
tells you what to install — it will not fail silently.

If you'd rather do it by hand:

```
cd C:\Users\ELCOT\Desktop\cab_booking
python -m http.server 8000
```

then open <http://localhost:8000/selftest.html> in **Google Chrome**.

---

## 2. Check it works — `selftest.html`

This is the page that answers "is it working". It runs against the real modules in
`js/`, not a copy, and it prints a verdict at the top.

**Sections 1, 2, 3 and 6 run on their own** the moment the page loads:

| Check | What a pass means |
|---|---|
| Page origin | You're on `localhost`, so the permission will stick |
| Speech recognition | Chrome's recogniser exists |
| App modules | All 7 `js/` files loaded |
| Ambiguity rule | "Anna Nagar" asks which one; "Anna Nagar East" books |
| Sample utterances | All 6 resolve, Tamil code-mix included |
| Spoken answers | "east", "the second one" etc. map to the right place |
| Wake-word matching | Wakes on mishearings, ignores "call me a cab" |
| Backend | Amber if not running — **that is fine**, see §4 |
| Rider ↔ driver channel | The two apps can see each other |

**Section 4 (speakers)** needs one click. You should hear two rising beeps, then a
spoken sentence. It also checks that the narrator's *completion callback* fires —
the app opens the microphone from that callback, so a dropped event means a
question nobody listens to.

**Section 5 (microphone)** is the important one. Click **Start 25-second
microphone test**.

- Chrome asks for the microphone **once**. Allow it.
- Say **"Hey Cab, take me to Anna Nagar"**, stay quiet a few seconds, say it again.
- Watch the four counters.

### What the counters should say

| Counter | Good | Bad |
|---|---|---|
| **permission prompts** | `1` — or `0` on a repeat visit | `2+` means the bug is back |
| **recogniser starts** | anything, usually 3–10 | — |
| **phrases heard** | at least 1 | `0` means the mic isn't reaching Chrome |
| **wake word hits** | at least 1 | `0` with phrases heard > 0 → see the transcript |

The counter that matters is **permission prompts**. It counts every `getUserMedia`
call from the page's first instruction. Over a 25-second window the broken build
made about eight; this one makes one. Recogniser restarts are normal and expected —
Chrome ends a session after silence and the app reopens it. The point is that a
restart no longer costs a prompt.

The transcript box shows everything the microphone heard, including phrases that
were discarded for not containing the wake word. That box is the first place to
look if the wake word "never fires": usually it did hear you, and spelled it oddly.

---

## 3. The actual demo — `rig.html`

<http://localhost:8000/rig.html> — rider and driver side by side, with the
instrumentation.

1. Put headphones on.
2. On the **driver** phone (right): press **Go online**, then **Auto: on**.
3. Click once anywhere on the **rider** phone. It says *"Ready. Say Hey Cab, then
   tell me where to go."*
4. Say **"Hey Cab, take me to Anna Nagar."** Don't touch the screen again.
5. It asks which Anna Nagar. Say **"east"**. Then just listen.

The `Rider taps` gauge should read **0**. That is the claim the whole prototype
exists to support.

Run it again with *"Hey Cab, take me to Anna Nagar East"* and watch the `AMBIGUITY`
line change from `→ ask` to `→ book it` — one utterance, zero turns, zero taps.

Rider and driver as genuinely separate windows:
<http://localhost:8000/rider.html> and <http://localhost:8000/driver.html>.

---

## 4. The backend (optional — really)

The rider app carries a complete gazetteer and a complete ambiguity rule in
`js/nlu.js`. The Spring Boot service has the same ones in Java. So the server is an
upgrade, never a dependency: `js/api.js` gives it a 900 ms deadline and falls back
to the browser if it misses. **The backend may never be the reason a booking
fails.**

You need **JDK 21** and Maven. Then:

```
cd backend
mvn spring-boot:run
```

Reload `selftest.html`. Section 3 turns green and adds a **parity check** — it
sends four utterances through both engines and compares. If they disagree it says
exactly which one, because a fallback that behaves differently from the real thing
is worse than no fallback.

To run just the parity unit tests without starting anything:

```
cd backend
mvn test
```

Don't have JDK 21? Skip it. Everything in §2 and §3 works without it.

---

## 5. When something is red

| Symptom | Cause | Fix |
|---|---|---|
| Asks for the mic over and over | You opened the file directly | Use `serve.bat`, then `localhost:8000` |
| "Microphone blocked" banner | Permission denied for this origin | Padlock in the address bar → Site settings → Microphone → **Allow** → reload |
| "This browser cannot hear you" | Firefox or Safari | Use Chrome or Edge |
| Phrases heard `0` | Wrong input device, or tab muted | Padlock → Site settings → Microphone → pick the right device |
| Wake word never fires | Recogniser is mangling the phrase | Read the transcript box; add the spelling to `PHRASES` in `js/wake.js` |
| Port 8000 in use | A server is already running | Just open `localhost:8000`, or `netstat -ano \| findstr :8000` |
| Nothing audible at all | Chrome needs a gesture first | Click once on the page, then retry |

---

## 6. Two phones over wifi — the catch

`http://192.168.x.x:8000` is **not** a secure context, so you are back to the
prompt-every-few-seconds problem. The page detects this and refuses to start the
microphone rather than hammering you with dialogs.

Two ways around it:

**Tell Chrome to trust your laptop's address.** On the phone, open
`chrome://flags/#unsafely-treat-insecure-origin-as-secure`, add
`http://192.168.1.9:8000` (your laptop's real address), set it to Enabled, relaunch
Chrome. Fine for a demo, obviously not for anything else.

**Or tunnel it over HTTPS**, e.g. `npx localtunnel --port 8000`, and open the
`https://` URL it gives you. Real certificate, no flags, works on any device.

---

## What's fake

15 hardcoded Chennai places, no real geocoding, no routing, no map, no persistence
beyond `localStorage`, and the driver is a person clicking buttons rather than a
moving car. None of that matters — this exists so the design can be *heard* and
timed before any Android code gets written. `ANDROID_VOICE_SPEC.md` is what carries
over.
