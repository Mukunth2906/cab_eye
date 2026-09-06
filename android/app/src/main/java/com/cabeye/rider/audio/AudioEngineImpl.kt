package com.cabeye.rider.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.cabeye.rider.telemetry.Telemetry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real [AudioEngine].
 *
 * ## Threading
 * Three separate lanes, on purpose:
 *
 *  - **Earcons** run on `earconExecutor`, a single thread. Serialising them means two
 *    earcons never overlap into an unrecognisable blur — the vocabulary is only learnable
 *    if each sound arrives intact.
 *  - **The heartbeat** runs on its own `heartbeatExecutor`, because it must keep pulsing
 *    *underneath* earcons. Sharing the earcon thread would make the system-alive pulse stop
 *    whenever anything else played, which inverts its entire meaning.
 *  - **Speech** is handled by the TTS engine's own threading.
 *
 * Every public method is safe to call from any thread and returns immediately.
 *
 * ## Audio focus
 * Requested as `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. The rider is very likely running a
 * screen reader or music; Cab Eye ducks them and restores them, never stops them.
 */
class AudioEngineImpl(
    context: Context,
    private val tts: TtsEngine = AndroidTtsEngine(context.applicationContext)
) : AudioEngine {

    companion object {
        private const val TAG = "CabEye.Audio"

        /** Heartbeat cadence and pitch, per the brief: ~58 Hz every 2.6 s, low volume. */
        private const val HEARTBEAT_FREQ_HZ = 58f
        private const val HEARTBEAT_PERIOD_MS = 2_600L
        private const val HEARTBEAT_DURATION_MS = 110
        private const val HEARTBEAT_VOLUME = 0.22f
    }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val synth = ToneSynth()

    private val earconExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "cabeye-earcon").apply { isDaemon = true } }

    private val heartbeatExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "cabeye-heartbeat").apply { isDaemon = true } }

    private val heartbeatRunning = AtomicBoolean(false)
    private var heartbeatTask: java.util.concurrent.Future<*>? = null

    private var focusRequest: AudioFocusRequest? = null
    private var released = false

    override val isSpeaking: Boolean get() = tts.isSpeaking

    // ---------------------------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------------------------

    override fun initialise(onReady: () -> Unit) {
        requestAudioFocus()
        tts.initialise { success ->
            if (!success) {
                Log.e(TAG, "TTS unavailable — the app will still earcon but cannot speak")
            }
            onReady()
        }
    }

    override fun release() {
        released = true
        heartbeat(false)
        tts.release()
        earconExecutor.shutdownNow()
        heartbeatExecutor.shutdownNow()
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // API 26+ only, which matches minSdk — no legacy branch needed.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                Log.d(TAG, "Audio focus changed: $change")
            }
            .build()

        focusRequest = request
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus not granted (result=$result); continuing anyway")
        }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    // ---------------------------------------------------------------------------------
    //  Tones and earcons
    // ---------------------------------------------------------------------------------

    override fun tone(
        frequencyHz: Float,
        durationMs: Int,
        pan: Float,
        pitchShift: Float,
        volume: Float
    ) {
        submitEarcon(listOf(ToneSpec(frequencyHz, durationMs, pan, pitchShift, volume)))
    }

    override fun earcon(earcon: Earcon, pan: Float, pitchShift: Float) {
        submitEarcon(specsFor(earcon, pan, pitchShift))
    }

    private fun submitEarcon(specs: List<ToneSpec>) {
        if (released || specs.isEmpty()) return
        runCatching {
            earconExecutor.submit { runCatching { synth.playBlocking(specs) } }
        }
    }

    /**
     * The earcon vocabulary, as actual tones.
     *
     * Each sound is shaped to be identifiable on a phone speaker on a noisy street, and
     * distinguishable from every other one in the set. Direction of pitch movement carries
     * most of the meaning — rising reads as opening or success, falling as closing or
     * failure — because absolute pitch is hard to judge without a reference but *contour*
     * is not.
     */
    private fun specsFor(earcon: Earcon, pan: Float, pitchShift: Float): List<ToneSpec> = when (earcon) {

        // Rising two-tone chirp: "the mic is open, speak now".
        Earcon.LISTENING_START -> listOf(
            ToneSpec(660f, 70, pan, pitchShift, 0.55f, gapAfterMs = 10),
            ToneSpec(990f, 90, pan, pitchShift, 0.55f)
        )

        // Falling single tone — the unmistakable bookend of the pair above.
        Earcon.LISTENING_END -> listOf(
            ToneSpec(760f, 90, pan, pitchShift, 0.45f)
        )

        // Soft, short, neutral. Fires often, so it must never become irritating.
        Earcon.UNDERSTOOD -> listOf(
            ToneSpec(880f, 55, pan, pitchShift, 0.38f)
        )

        // Low double thud, deliberately unlike every success sound in the set.
        Earcon.NOT_UNDERSTOOD -> listOf(
            ToneSpec(220f, 110, pan, pitchShift, 0.5f, gapAfterMs = 60),
            ToneSpec(185f, 140, pan, pitchShift, 0.5f)
        )

        // Ascending major triad. The most pleasant sound here, and the rarest — it should
        // feel like a small reward.
        Earcon.BOOKING_CONFIRMED -> listOf(
            ToneSpec(523f, 90, pan, pitchShift, 0.5f, gapAfterMs = 15),
            ToneSpec(659f, 90, pan, pitchShift, 0.5f, gapAfterMs = 15),
            ToneSpec(784f, 150, pan, pitchShift, 0.55f)
        )

        // Descending pair — the mirror of BOOKING_CONFIRMED, so the pairing is learnable.
        Earcon.CANCELLED -> listOf(
            ToneSpec(659f, 90, pan, pitchShift, 0.45f, gapAfterMs = 15),
            ToneSpec(440f, 160, pan, pitchShift, 0.45f)
        )

        // Pitch encodes distance, pan encodes bearing. Both supplied by the caller, which is
        // why this is a single short tone and nothing more: it fires every ~1.5 s for the
        // whole approach and anything longer would crowd the street noise the rider needs
        // to hear.
        Earcon.DRIVER_APPROACH -> listOf(
            ToneSpec(440f, 120, pan, pitchShift, 0.5f)
        )

        // Deliberately unmusical two-tone alternation so it stands out against traffic
        // instead of blending into it. This is the sound the rider physically turns toward.
        Earcon.BEACON -> listOf(
            ToneSpec(1180f, 130, pan, pitchShift, 0.75f, gapAfterMs = 45),
            ToneSpec(1560f, 130, pan, pitchShift, 0.75f, gapAfterMs = 45),
            ToneSpec(1180f, 130, pan, pitchShift, 0.75f, gapAfterMs = 45),
            ToneSpec(1560f, 200, pan, pitchShift, 0.8f)
        )

        // Bright, three-step, unmissable. Paired with the distinct arrival haptic.
        Earcon.ARRIVED -> listOf(
            ToneSpec(784f, 110, pan, pitchShift, 0.65f, gapAfterMs = 20),
            ToneSpec(988f, 110, pan, pitchShift, 0.65f, gapAfterMs = 20),
            ToneSpec(1175f, 200, pan, pitchShift, 0.7f)
        )

        // Low and quiet by design: present enough to prove the system is alive, quiet
        // enough to ignore for minutes at a time.
        Earcon.HEARTBEAT -> listOf(
            ToneSpec(HEARTBEAT_FREQ_HZ, HEARTBEAT_DURATION_MS, 0f, 1f, HEARTBEAT_VOLUME)
        )

        // Urgent, hard, and unlike anything else here.
        Earcon.SOS -> listOf(
            ToneSpec(1000f, 180, 0f, 1f, 0.9f, gapAfterMs = 70),
            ToneSpec(1000f, 180, 0f, 1f, 0.9f, gapAfterMs = 70),
            ToneSpec(1000f, 320, 0f, 1f, 0.9f)
        )

        // Buzzy descending pair. Silence must never be the only signal of a fault.
        Earcon.ERROR -> listOf(
            ToneSpec(330f, 100, pan, pitchShift, 0.5f, gapAfterMs = 40),
            ToneSpec(247f, 180, pan, pitchShift, 0.5f)
        )
    }

    // ---------------------------------------------------------------------------------
    //  Heartbeat
    // ---------------------------------------------------------------------------------

    override fun heartbeat(on: Boolean) {
        if (on) {
            // Idempotent: compareAndSet means a second heartbeat(true) is a no-op rather
            // than a second pulsing thread.
            if (!heartbeatRunning.compareAndSet(false, true)) return
            if (released) return

            heartbeatTask = runCatching {
                heartbeatExecutor.submit {
                    val pulse = specsFor(Earcon.HEARTBEAT, 0f, 1f)
                    while (heartbeatRunning.get() && !Thread.currentThread().isInterrupted) {
                        runCatching { synth.playBlocking(pulse) }
                        try {
                            Thread.sleep(HEARTBEAT_PERIOD_MS - HEARTBEAT_DURATION_MS)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }.getOrNull()
        } else {
            heartbeatRunning.set(false)
            heartbeatTask?.cancel(true)
            heartbeatTask = null
        }
    }

    // ---------------------------------------------------------------------------------
    //  Speech
    // ---------------------------------------------------------------------------------

    override fun speak(
        text: String,
        tier: NarrationTier,
        earconInstead: Earcon?,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?
    ) {
        if (released) { onDone?.invoke(); return }

        // Tier 2 speaks nothing at all. Principle 3: a high-frequency, low-information
        // event is worth a tone, never a sentence.
        if (tier == NarrationTier.EARCON_ONLY) {
            earconInstead?.let { earcon(it) }
            onDone?.invoke()
            return
        }

        val startedAt = System.currentTimeMillis()

        tts.speak(
            text = text,
            flush = tier == NarrationTier.INTERRUPT,
            onStart = { onStart?.invoke() },
            onDone = {
                Telemetry.logSpeech(text, tier.name, System.currentTimeMillis() - startedAt)
                onDone?.invoke()
            }
        )
    }

    override fun stopSpeaking() {
        tts.stop()
    }
}
