package com.cabeye.rider.audio

/**
 * The rider's entire user interface.
 *
 * Everything the rider perceives arrives through this one contract: speech, non-speech
 * earcons, and the system-alive heartbeat. The screen is a courtesy for low-vision users,
 * sighted helpers and demo screenshots — it is never the primary channel.
 *
 * **This file is an interface only.** No implementation exists yet, by design: the audio
 * layer is the next step and should be written against a settled contract rather than
 * grown ad hoc.
 *
 * ## Implementation contract
 * These are requirements on whoever implements this, not suggestions:
 *
 * 1. **`AudioTrack`, not `SoundPool`, for [tone].** The bearing cue needs per-ear volume
 *    set at runtime and the approach cue needs pitch that changes continuously with
 *    distance. `SoundPool` can only play fixed clips at fixed rates, so it is acceptable
 *    only for invariant one-shots. `AudioTrack` with `setStereoVolume` / `setPlaybackRate`
 *    is the required path.
 *
 * 2. **Request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.** The rider is very likely listening
 *    to music or a screen reader. Cab Eye must duck them, not stop them, and must restore
 *    them afterwards.
 *
 * 3. **Close the microphone while speaking.** Whenever [speak] is producing audio, the
 *    recogniser must be stopped. If it is not, the phone transcribes its own voice and the
 *    conversation collapses into a loop. Reopening the mic the instant a *question*
 *    finishes is principle 2 and is mandatory; a prompt the app does not listen for is a
 *    bug.
 *
 * 4. **Run in a foreground service.** Narration must survive backgrounding and a locked
 *    screen: the app has to be fully operable with the screen off. Service type
 *    `mediaPlayback`, already declared in the manifest.
 *
 * 5. **Every method is safe to call from any thread**, and must not block the caller.
 *    Audio work belongs on its own thread; callers are frequently on the main thread
 *    inside a composition or a WebSocket callback.
 */
interface AudioEngine {

    // ---------------------------------------------------------------------------------
    //  Raw tone generation
    // ---------------------------------------------------------------------------------

    /**
     * Plays a single synthesised tone. This is the primitive every earcon is built from.
     *
     * @param frequencyHz base pitch in hertz, before [pitchShift] is applied. Keep within
     *   roughly 200–4000 Hz: below that, phone speakers cannot reproduce it, and above it
     *   the tone becomes fatiguing at the repetition rates this app uses.
     * @param durationMs how long the tone sounds. Short is strongly preferred — an earcon
     *   exists precisely because it costs less time than a word.
     * @param pan stereo position from `-1f` (hard left) through `0f` (centre) to `+1f`
     *   (hard right). This is what carries the driver's real-world bearing, so it must map
     *   to actual per-channel gain and not merely a balance approximation. Values outside
     *   the range are clamped.
     * @param pitchShift multiplier applied to [frequencyHz]; `1f` is unshifted. Used to
     *   raise pitch as the driver's car nears. Implementations should ramp smoothly between
     *   successive values rather than stepping, since audible stepping reads as a fault.
     * @param volume linear gain `0f`..`1f` applied on top of the current stream volume.
     */
    fun tone(
        frequencyHz: Float,
        durationMs: Int,
        pan: Float = 0f,
        pitchShift: Float = 1f,
        volume: Float = 1f
    )

    /**
     * Plays a named earcon from the standard set.
     *
     * Prefer this over [tone] for anything the rider will hear repeatedly: a fixed
     * vocabulary of sounds becomes learnable, whereas ad-hoc tones never do.
     *
     * @param earcon which sound to play
     * @param pan stereo position, as in [tone]. Only meaningful for [Earcon.DRIVER_APPROACH]
     *   and [Earcon.BEACON]; ignored by the others.
     * @param pitchShift as in [tone]; used by [Earcon.DRIVER_APPROACH] to encode distance.
     */
    fun earcon(
        earcon: Earcon,
        pan: Float = 0f,
        pitchShift: Float = 1f
    )

    // ---------------------------------------------------------------------------------
    //  System-alive heartbeat
    // ---------------------------------------------------------------------------------

    /**
     * Turns the system-alive pulse on or off.
     *
     * A ~58 Hz pulse every 2.6 s at low volume, during any wait — principally `finding`
     * and `intrip`. Principle 4: silence must mean something. With the heartbeat running,
     * silence is a fault the rider can detect; without it, a crashed app and a working one
     * are indistinguishable to someone who cannot see the screen.
     *
     * Idempotent: calling `heartbeat(true)` while already pulsing does nothing.
     *
     * @param on true to start pulsing, false to stop
     */
    fun heartbeat(on: Boolean)

    // ---------------------------------------------------------------------------------
    //  Speech
    // ---------------------------------------------------------------------------------

    /**
     * Speaks a sentence, subject to its [NarrationTier].
     *
     * Every spoken sentence costs the rider seconds they cannot get back, so the shortest
     * phrasing that is still unambiguous is always the right one.
     *
     * @param text what to say. Write it as it should sound: expand "20m" to "twenty metres",
     *   because TTS engines mispronounce abbreviations inconsistently across devices.
     * @param tier scheduling policy — see [NarrationTier]. Passing
     *   [NarrationTier.EARCON_ONLY] speaks nothing at all and fires [earconInstead].
     * @param earconInstead the sound to play when [tier] is [NarrationTier.EARCON_ONLY].
     *   Ignored for other tiers.
     * @param onStart invoked when the **first audio sample actually reaches the speaker**.
     *   This is the T4 mark, and it is deliberately separate from [onDone]: the budget is
     *   "TTS first audio ≤ 300 ms", which measures responsiveness. Timing to [onDone]
     *   instead measures how long the sentence is, which is a different thing entirely and
     *   makes every long utterance look like a latency failure.
     * @param onDone invoked once audio has actually finished, on an unspecified thread.
     *   **This is the hook the microphone reopens from.** It must also fire when the
     *   utterance is cancelled by a tier-0 interrupt, otherwise a cancelled prompt leaves
     *   the mic shut and the rider talking to a phone that is not listening.
     */
    fun speak(
        text: String,
        tier: NarrationTier = NarrationTier.QUEUED,
        earconInstead: Earcon? = null,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    )

    /**
     * Immediately stops whatever is being spoken and clears the queue.
     *
     * Called when the rider starts talking — a global voice command must be able to cut the
     * narrator off mid-sentence, since waiting politely for a sentence to finish is exactly
     * the delay this product exists to remove.
     */
    fun stopSpeaking()

    /**
     * Speech speed, 1.0 = normal. The rider sets it by voice ("speak slower") and it is kept
     * with their profile. Engines that do not speak ignore it.
     */
    fun setSpeechRate(rate: Float) {}

    /** True while TTS is producing audio. The microphone must stay closed whenever this is true. */
    val isSpeaking: Boolean

    // ---------------------------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------------------------

    /**
     * Prepares the TTS engine, the `AudioTrack` pool and the audio-focus request.
     *
     * @param onReady called once speech is genuinely available. TTS initialisation is
     *   asynchronous and a `speak` issued before it completes is silently dropped on most
     *   devices — which would present as an app that has gone mute for no visible reason.
     */
    fun initialise(onReady: () -> Unit)

    /** Releases audio focus, the tracks and the TTS engine. Safe to call twice. */
    fun release()
}

/**
 * How urgently a spoken message should reach the rider.
 *
 * The three tiers exist because interrupting is sometimes correct and usually rude, and
 * the difference has to be a deliberate decision at every call site.
 */
enum class NarrationTier {

    /**
     * Tier 0 — interrupts. Cancels whatever is currently being spoken and speaks now.
     *
     * Reserved for things whose value collapses if delayed: **arrival** and **route
     * deviation**. Anything that can wait a sentence is not tier 0.
     */
    INTERRUPT,

    /**
     * Tier 1 — queues. Waits for the current utterance to finish.
     *
     * Assignment, booking confirmation, clarification questions.
     */
    QUEUED,

    /**
     * Tier 2 — speaks nothing. Fires an earcon and returns.
     *
     * For high-frequency, low-information events. Principle 3: a driver moving forty metres
     * closer is worth a tone, never a sentence.
     */
    EARCON_ONLY
}

/**
 * The fixed earcon vocabulary.
 *
 * Deliberately small. Every sound here has to be distinguishable from every other one on a
 * phone speaker on a noisy street, and a rider has to be able to learn the whole set —
 * both of which get harder with each addition.
 */
enum class Earcon {

    /** Microphone just opened. Rising two-tone chirp. The cue to start speaking. */
    LISTENING_START,

    /** Microphone closed, audio captured. Short falling tone. */
    LISTENING_END,

    /** Utterance understood and being acted on. Single soft click. */
    UNDERSTOOD,

    /** Nothing was recognised. Low double thud — distinct from every success sound. */
    NOT_UNDERSTOOD,

    /** Ride booked. Warm ascending triad. Deliberately the most pleasant sound in the set. */
    BOOKING_CONFIRMED,

    /** Ride cancelled, by the rider or the system. Descending pair. */
    CANCELLED,

    /**
     * Driver approaching. **Pitch encodes distance, pan encodes real bearing.**
     *
     * Fires roughly every 1.5 s for the whole approach phase, and no words are spoken
     * during it. Pitch rises as the car closes; pan follows the driver's true bearing
     * derived from the device magnetometer, so the rider can turn toward the car.
     */
    DRIVER_APPROACH,

    /**
     * Audio beacon, triggered by the driver.
     *
     * A distinctive, deliberately unmusical tone panned to the real bearing, so it stands
     * out against traffic and street noise and gives the rider something to turn toward.
     */
    BEACON,

    /** Driver has arrived. Paired with the distinct arrival haptic and a tier-0 utterance. */
    ARRIVED,

    /** The low system-alive pulse. Emitted by [AudioEngine.heartbeat], not called directly. */
    HEARTBEAT,

    /** SOS raised. Urgent, unmistakable, and unlike anything else here. */
    SOS,

    /** Something failed — lost connection, backend unreachable. Silence must never be the only signal. */
    ERROR
}
