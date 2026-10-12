# Design decisions

[← Back to the README](../README.md)

These are decisions, not defaults. Reversing one by accident would break what the product
promises, so each is written down with its reason.

## The principles

1. **The rider is blind, so silence must always mean something.** During any wait a quiet
   ~58 Hz pulse plays every 2.6 s; if the app goes quiet, that is a fault, never "still
   working". A dropped connection is announced **once** ("Connection lost. Reconnecting."), and
   on reconnect only what changed is said.
2. **The microphone opens only while the rider presses.** Hold the screen or a volume key; let go
   to finish. Questions are spoken, then the screen shows "Hold to answer". The app never
   reopens the mic by itself. It used to, and it heard its own "say cancel to stop" and
   cancelled the ride. The mic is also closed whenever the app speaks.
3. **Every feature has a touchable entry.** Voice first, but every voice feature also has a
   button (Settings, Explore, My mind map, the camera question's Share / Don't share, payment,
   clarification options). A sighted helper or a low-vision rider must never be stuck.
4. **Speech time is task time.** High-frequency, low-information events get an earcon, never
   words. The whole driver-approach phase is tones that rise in pitch.
5. **One surface, no navigation.** One Activity and one rider screen, a `when` over the ride
   states. No `NavHost`, no back stack. Explore and the mind map are overlays opened by one
   gesture and closed by one button (or the same gesture), never pages to get lost in.
6. **Modules stay separate.** The ride module (booking, riding, the ride camera, ride cues), the
   explore module (the camera on foot) and the mind map that both feed are separate in code and
   in storage (`cabeye.mindmap.rides` vs `cabeye.mindmap.explore`). Each new feature says which
   module it belongs to.

## Inputs

- **Hold to talk, anywhere below the top bar.** The whole screen is one 88 dp+ target. The top
  bar (Settings, Explore, Mind map) sits outside it, so touching a button never opens the mic.
- **Volume keys.** A short press is a volume step: the narrator's level must be adjustable
  while the app is open. Holding past 350 ms talks. A physical key is findable without looking.
- **Double tap → Explore.** The first tap still opens the mic immediately (a hold must never
  wait to find out whether it is a double tap). When the second tap lands within the system's
  double-tap time, the view model drops that first press silently: its stop is not due for
  900 ms, so it never hears anything. It works only from the ready screen, where a stray mic
  press costs nothing.
- **Volume down ×3 → Mind map.** Only volume down counts, so turning the narrator up never
  opens anything. Each press changes the volume at once; the third restores the volume from
  before the first. The same gesture closes the mind map, so a rider who opened it by accident
  has the way out under their thumb.
- **TalkBack** owns double taps, so the same actions exist as the top-bar buttons and as custom
  accessibility actions on the main screen.

## Safety and privacy

- **The boarding code runs backwards.** The driver's screen shows it, the driver says it, the
  rider's phone checks it. A blind person on a street can't tell who is listening, so the
  rider's phone never announces a secret. With headphones in, the expected code is whispered
  through the earpiece.
- **Driver mode releases the audio session**: text-to-speech, the recogniser and audio focus,
  and swaps in a silent engine. A driver is driving, and an open mic in a car is a recording.
- **Never "clear" or "safe".** The camera narration, the walk narration and the Gemini answers
  never say a path is clear: the app can't know. The backend strips such sentences from the
  model's answer, and a unit test enforces it for walks.
- **The ride camera needs consent every time** (unless the rider chose "always"), is relayed
  only to that ride's driver, is never stored, and switches itself off at pickup or after
  3 minutes. The camera never touches the microphone.
- **SOS says only what happened.** Holding SOS for two seconds sends an urgent case to support
  (position, ride, driver, plate, emergency contact) and retries. The app then says "sent", or
  "couldn't reach support, call 112". An earlier version said "sharing your location" and sent
  nothing; it also fired twice, at the system long press and again at two seconds. SOS is on every
  screen that covers the main one (Explore, the mind map), and works without a sign-in.
- **The fare is computed on the server**, from the driver's GPS, so no phone can name its price.
- **`/debug/broadcast` answers 404, not 401**, so probing it reveals nothing.
- **The server stamps message senders from the session**, never from the body.
- **Walks, the mind map and explore observations stay on the phone.** Only the one picture or
  one sentence the rider asked about goes to Gemini/Grok (the sentence reader asks consent first).

## Technology

- **Native Kotlin + Jetpack Compose**, not React Native. The audio layer needs `AudioTrack`
  with runtime pitch and per-ear panning, audio focus and a foreground service.
- **Speech recognition on the phone** (`SpeechRecognizer`, streaming partials). Sending audio
  to a server costs seconds, and this project is about time.
- **Object detection: Gemini when configured, ML Kit always.** The phone answers on its own
  with no network or key, and Gemini improves it when it can. Falling back is per picture.
- **Rule-based agents.** The memory, walking and mind-map agents are deterministic scoring and
  parsing: fast, explainable ("like most weekday mornings") and unit-tested. An LLM is used
  only to read a sentence the rules couldn't, with consent, and its answer is re-checked by the
  same rules.
- **Raw WebSocket, one topic per ride**, with sequence numbers and event ids for replay and
  de-duplication.
- **Offline Coimbatore atlas** (real OpenStreetMap data, ~1,330 places, 136 areas, bundled) so
  destination search, area names and nearby places need no paid service.

## Look and feel

- **Every colour comes from the active theme** (`LocalPalette`), never from fixed constants in a
  component. Components that used the dark theme's constants made the light theme unreadable
  (a white title on white, dark text on black buttons) until `ScreenGalleryTest` rendered every
  screen in every theme and showed it. The one exception is text over the live camera picture,
  which is always light on a dark strip.
- **Unknown is not a number.** A distance or ETA the app doesn't know yet is null, and the
  screen says "on the way" rather than "-1 m away" or "0 minutes".

- **Pure black background, all text ≥ 7:1 contrast** (WCAG AAA). The danger colour is `#FF8A80`;
  pure red on black is only ~5.3:1.
- **Three themes**: deep dark (default), high-contrast yellow, high-contrast light. There is no
  wallpaper-derived (Material You) colour, which couldn't guarantee contrast.
- **88 dp minimum touch targets**: a rider who can't see a control is aiming from memory.
- **Reduced motion** follows the system animator scale.
- **Portrait only, configuration changes handled in place**: a rotation would restart the
  audio engine mid-sentence.
- **SOS is an overlay, not a state**: dismissing it returns the rider exactly where they were.
- **`RiderState` is a sealed interface**: adding a state is a compile error everywhere it must be
  handled, not a blank screen at runtime.

## Matching places

The clarification gate asks "A or B?" only when it matters:

```
score gap < 0.16  and  distance between them > 1.5 km   →  ask
score gap < 0.16  and  distance ≤ 1.5 km                →  book the best one
```

Being wrong between two nearby candidates costs the rider a short walk; interrupting every
booking would cost more. Every decision is logged as `CLARIFY_DECISION`, including when the gate
stays silent.
