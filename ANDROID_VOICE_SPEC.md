# The trigger, on real Android

The web prototype answers one question: *what should replace tap-to-speak?*
The answer it settles on is a wake word, **"Hey Cab"**. This document is about
what that decision costs and gains once it moves to Kotlin, and what the
browser could not test.

## Why not a button

Tap-to-speak asks a blind user to find a target on a flat sheet of glass —
the one thing this app exists to avoid. Everything else we considered:

| Trigger | Works in browser | Works on Android | Why it lost |
|---|---|---|---|
| **Wake word** | yes | yes | **chosen** |
| Volume-key long-press | no | yes, via AccessibilityService | invisible to the web; keep as a secondary |
| Bluetooth headset button | no | yes | excellent, but needs a headset |
| Shake | partly | yes | fires constantly in a moving auto-rickshaw |
| Long-press screen | yes | conflicts with TalkBack | still a touch |
| Spacebar | yes | no phone has one | laptop-only |

A wake word is the only trigger that costs nothing to reach. Phone in a
pocket, screen off, both hands on a cane.

## What the browser prototype cannot tell you

Three things, and they are the three that matter most for the paper:

1. **Battery.** Chrome's `SpeechRecognition` streams audio to Google's servers
   continuously. On a phone that is unacceptable for an always-on listener.
   The Android build must do hotword detection *on device*, and the browser
   gives you no measurement of what that costs.
2. **False accepts in a noisy vehicle.** An auto-rickshaw at 40 km/h is a
   genuinely hostile acoustic environment and a laptop in a quiet room is not.
3. **Screen off.** The whole promise of the wake word is that the phone stays
   in a pocket. A web page cannot run with the screen off.

Everything below is aimed at those three.

## The recommended shape

```
┌─────────────────────────────────────────────────────┐
│ HotwordService : LifecycleService (foreground)      │
│                                                     │
│   Porcupine / Vosk  ──► on-device, no network       │
│         │                                           │
│         └─ "Hey Cab" ──► SpeechRecognizer (Google)  │
│                              │                      │
│                              └─► /api/v1/voice/     │
│                                     interpret       │
└─────────────────────────────────────────────────────┘
```

Two stages, and the split is the whole design. The always-on stage is a tiny
on-device model that only knows one phrase and never touches the network. The
expensive, network-backed recogniser wakes up only after that phrase fires.

### Stage 1 — on-device hotword

```kotlin
class HotwordService : LifecycleService() {

    private lateinit var porcupine: PorcupineManager

    override fun onCreate() {
        super.onCreate()
        // A foreground service with a persistent notification is the only
        // way Android will let you hold a microphone indefinitely. Declare
        // it microphone-typed or Android 14 kills it on start.
        startForeground(ID, listeningNotification())

        porcupine = PorcupineManager.Builder()
            .setKeywordPath("hey_cab.ppn")   // trained once, ships in assets
            .setSensitivity(0.6f)            // see "tuning" below
            .build(applicationContext) { _ -> onWake() }
            .apply { start() }
    }

    private fun onWake() {
        // Earcon first, always. The user must know they were heard before
        // they start speaking, or they will repeat themselves into a gap.
        earcons.play(Earcon.LISTEN)
        recognizer.startListening(utteranceIntent)
    }
}
```

Manifest:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>

<service
    android:name=".voice.HotwordService"
    android:foregroundServiceType="microphone"
    android:exported="false"/>
```

**Engine choice.** Porcupine (Picovoice) gives you a custom wake word and
runs in about 1% CPU; it is free for personal and evaluation use and
paid for commercial. Vosk is fully open and offline but is a general
recogniser pressed into hotword duty, so it costs more battery. Android's
own `AlwaysOnHotwordDetector` uses the DSP and costs almost nothing, but
it is reserved for the enrolled assistant app — you cannot have it.

Start with Porcupine. If licensing blocks you, Vosk with a tight grammar
(`["hey cab", "[unk]"]`) is the fallback, and the grammar restriction is
what keeps it affordable.

### Stage 2 — the utterance

```kotlin
val utteranceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
             RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

    // Offline where available. A rider under a flyover with no signal
    // must still be able to book.
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
}
```

Send the transcript to `POST /api/v1/voice/interpret`. The backend in this
repo already returns everything the UI needs, including the exact sentence
to speak — so the Kotlin client holds no wording of its own.

For Tamil and code-mixed speech, `SpeechRecognizer` will not switch languages
mid-utterance. That is what `POST /api/v1/speech/transcribe` is for: it
forwards to Google Cloud with `alternativeLanguageCodes: ["ta-IN"]`, which
handles *"Velachery-ku poganum"* correctly. Use it as the retry path when
the on-device transcript resolves to nothing.

## Tuning, and the number to report

Wake-word sensitivity is a single dial between two failure modes, and the
right setting is not symmetric:

- **False reject** — the user says "Hey Cab" and nothing happens. They say it
  again. Cost: three seconds and mild indignity.
- **False accept** — the phone wakes during a conversation with the driver.
  Cost: potentially a cancelled ride.

So bias toward false rejects. Start at 0.6, and measure both rates in a
moving vehicle rather than at a desk — that difference is worth its own line
in the paper.

The prototype's `js/wake.js` already implements the same asymmetry in its own
way: the wake phrase is matched by edit distance (recognisers really do hear
"hey cap", "hey gab", "hey cub"), but only within the first four tokens of an
utterance, so "call me a cab" cannot trigger it. Reuse that constraint — a
wake word belongs at the front of a sentence.

## The same rule about turns

The prototype's rule holds on Android and is the part most easily lost in
translation:

> **The app asked a question → answer it bare. You are starting something →
> say "Hey Cab" first.**

Inside a turn the app itself opened — *"Did you mean East, or West?"* — the
recogniser is already listening and demanding the wake word again would be
absurd. Outside one, in a moving vehicle, unprompted speech is probably aimed
at the driver, and *"no, not that way"* must never cancel a ride.

## Secondary triggers worth adding

The wake word is the primary. These cost little and cover its failure modes.

**Bluetooth headset button** — the best trigger in the set for a blind user
who already wears an earpiece. A physical click, zero false accepts, works in
any noise.

```kotlin
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val event: KeyEvent? = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        if (event?.keyCode == KeyEvent.KEYCODE_HEADSETHOOK &&
            event.action == KeyEvent.ACTION_UP) {
            HotwordService.forceWake(ctx)
        }
    }
}
```

**Volume-key long-press** — reachable without looking, works with the screen
off. Requires an AccessibilityService, which means an intrusive permission
screen; make it opt-in rather than part of onboarding.

```kotlin
class KeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.isLongPress) {
            HotwordService.forceWake(this)
            return true   // swallow it, so volume does not also change
        }
        return super.onKeyEvent(event)
    }
}
```

**Assistant role** — if the user sets Cab Eye as the device assistant, the
system's own assist gesture launches it. One line in the manifest, and it
costs nothing:

```xml
<intent-filter>
    <action android:name="android.intent.action.ASSIST"/>
    <category android:name="android.intent.category.DEFAULT"/>
</intent-filter>
```

Do **not** ship shake-to-talk as a default. It fires on every pothole.

## TalkBack

The rider app should be usable *with* TalkBack, not instead of it. Two rules:

- Never announce anything through TalkBack that the narrator already says.
  Doubling the speech is the most common accessibility mistake in voice apps
  and it makes the interface twice as slow.
- Set `android:accessibilityLiveRegion="polite"` on the single status surface
  and let the narrator carry everything else.

## What to measure

The five latency marks the prototype already reports (T0–T4) carry over
unchanged. Add three that only exist on a phone:

| Mark | What it measures |
|---|---|
| **W0** | wake word spoken → hotword fires |
| **W1** | hotword fires → earcon audible |
| **FA/h** | false accepts per hour, in a moving vehicle |
| **FR%** | false rejects, as a fraction of wake attempts |
| **mAh/h** | battery cost of the always-on listener |

W1 is the one users feel. Keep it under 150 ms or people start repeating
themselves.
