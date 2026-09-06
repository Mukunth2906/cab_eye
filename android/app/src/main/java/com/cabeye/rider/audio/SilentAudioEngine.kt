package com.cabeye.rider.audio

import android.util.Log

/**
 * An [AudioEngine] that is incapable of making a sound.
 *
 * ## Why this exists rather than a `if (role == RIDER)` check at each call site
 * In driver mode the app must not speak and must not play an earcon. The driver is driving:
 * unexpected speech is a distraction at a moment nobody chose, and an earcon panned to a
 * bearing is meaningless to someone looking through a windscreen.
 *
 * The obvious implementation — guard every narration call with a role check — fails the moment
 * one call site is added without the guard, and it fails silently, in a car, on the road. So
 * the guarantee is made structural instead: while the app is in driver mode, the engine handed
 * to everything downstream is *this*, and it has no code path that reaches an `AudioTrack` or
 * a `TextToSpeech`. A missed guard cannot produce sound because there is nothing to produce it
 * with.
 *
 * The real engine is released at the same time (see [AudioSession]), so this is the second of
 * two independent defences rather than the only one.
 *
 * ## `onDone` still fires
 * Every [speak] invokes its callbacks immediately. Anything waiting on `onDone` to continue —
 * in the rider code that is how the microphone reopens — would otherwise hang forever if it
 * ever ran against this engine. Failing quietly is the whole point; failing *stuck* is not.
 */
class SilentAudioEngine : AudioEngine {

    private companion object {
        const val TAG = "CabEye.Audio"
    }

    override val isSpeaking: Boolean get() = false

    override fun tone(frequencyHz: Float, durationMs: Int, pan: Float, pitchShift: Float, volume: Float) = Unit

    override fun earcon(earcon: Earcon, pan: Float, pitchShift: Float) = Unit

    override fun heartbeat(on: Boolean) = Unit

    override fun speak(
        text: String,
        tier: NarrationTier,
        earconInstead: Earcon?,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?
    ) {
        // Logged, not spoken. If driver-mode code is trying to narrate, that is a bug worth
        // seeing in logcat — but it is not a bug worth hearing from a moving car.
        Log.d(TAG, "SILENT (driver mode) suppressed tier=${tier.name}: \"$text\"")
        onStart?.invoke()
        onDone?.invoke()
    }

    override fun stopSpeaking() = Unit

    override fun initialise(onReady: () -> Unit) = onReady()

    override fun release() = Unit
}
