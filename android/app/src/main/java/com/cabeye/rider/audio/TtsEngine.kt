package com.cabeye.rider.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Text-to-speech, behind an interface so the engine is swappable.
 *
 * On-device [AndroidTtsEngine] is the default and always will be for the shipping path.
 * The interface exists so a cloud engine can be **measured against** it, not so it can
 * quietly replace it — a network round-trip inside the 300 ms first-audio budget is
 * exactly the cost this project exists to avoid.
 */
interface TtsEngine {

    /**
     * Prepares the engine.
     *
     * @param onReady invoked with true once speech is genuinely available. TTS
     *   initialisation is asynchronous and a `speak` issued before it completes is silently
     *   dropped on most devices — which presents as an app that has gone mute for no
     *   visible reason.
     */
    fun initialise(onReady: (Boolean) -> Unit)

    /**
     * Speaks one utterance.
     *
     * @param text what to say
     * @param flush true to cancel anything in progress and speak now (tier 0);
     *   false to queue behind it (tier 1)
     * @param onStart fired when audio actually begins — this is the T4 "first sound" mark
     * @param onDone fired when the utterance ends, **including when it is cancelled**.
     *   Non-negotiable: this is the hook the microphone reopens from, so a cancelled prompt
     *   that never fired it would leave the mic shut and the rider talking to a phone that
     *   is not listening.
     */
    fun speak(text: String, flush: Boolean, onStart: () -> Unit = {}, onDone: () -> Unit = {})

    /** Stops immediately and clears the queue. Pending `onDone` callbacks still fire. */
    fun stop()

    /** True while audio is being produced. The microphone must stay closed whenever this is true. */
    val isSpeaking: Boolean

    fun release()
}

/**
 * On-device TTS via Android's [TextToSpeech]. The default path — audio never leaves the phone.
 */
class AndroidTtsEngine(private val context: Context) : TtsEngine {

    companion object {
        private const val TAG = "CabEye.Tts"

        /**
         * Indian English. Chennai place names ("Adyar", "Velachery", "Guindy") are mangled
         * by en-US voices badly enough to be unrecognisable, and the whole point of speaking
         * a destination back is that the rider can confirm it.
         */
        private val PREFERRED_LOCALE = Locale("en", "IN")
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var utteranceCounter = 0L

    /** utteranceId -> callbacks. Concurrent because TTS callbacks arrive on a binder thread. */
    private val pending = ConcurrentHashMap<String, Pair<() -> Unit, () -> Unit>>()

    @Volatile
    private var speakingCount = 0

    override val isSpeaking: Boolean
        get() = speakingCount > 0 || (tts?.isSpeaking == true)

    override fun initialise(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed with status=$status")
                ready = false
                onReady(false)
                return@TextToSpeech
            }

            val engine = tts ?: run { onReady(false); return@TextToSpeech }

            // Fall back through en-IN -> en-GB -> default rather than failing outright.
            // A slightly wrong accent is vastly better than no speech at all.
            val localeResult = engine.setLanguage(PREFERRED_LOCALE)
            if (localeResult == TextToSpeech.LANG_MISSING_DATA ||
                localeResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(TAG, "en-IN unavailable, falling back to en-GB")
                if (engine.setLanguage(Locale.UK) == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.getDefault())
                }
            }

            // Slightly faster than default. Blind users are typically practised listeners
            // and this is a direct saving on every sentence — speech time is task time.
            engine.setSpeechRate(1.15f)
            engine.setPitch(1.0f)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speakingCount++
                    utteranceId?.let { pending[it]?.first?.invoke() }
                }

                override fun onDone(utteranceId: String?) {
                    speakingCount = (speakingCount - 1).coerceAtLeast(0)
                    utteranceId?.let { pending.remove(it)?.second?.invoke() }
                }

                /**
                 * Fired when `stop()` cancels an utterance. Firing the done-callback here is
                 * the whole reason this override exists: a tier-0 interrupt must still
                 * reopen the microphone.
                 */
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    speakingCount = (speakingCount - 1).coerceAtLeast(0)
                    utteranceId?.let { pending.remove(it)?.second?.invoke() }
                }

                @Deprecated("Required by the base class; the int overload below supersedes it.")
                override fun onError(utteranceId: String?) {
                    speakingCount = (speakingCount - 1).coerceAtLeast(0)
                    utteranceId?.let { pending.remove(it)?.second?.invoke() }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS error on $utteranceId code=$errorCode")
                    speakingCount = (speakingCount - 1).coerceAtLeast(0)
                    utteranceId?.let { pending.remove(it)?.second?.invoke() }
                }
            })

            ready = true
            onReady(true)
        }
    }

    override fun speak(text: String, flush: Boolean, onStart: () -> Unit, onDone: () -> Unit) {
        val engine = tts
        if (!ready || engine == null) {
            Log.w(TAG, "speak() before ready, dropping: \"$text\"")
            // Fire onDone anyway so callers waiting to reopen the mic are not stranded.
            onDone()
            return
        }

        val id = "cabeye-${utteranceCounter++}"
        pending[id] = onStart to onDone

        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(text, mode, null, id)

        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() rejected for \"$text\"")
            pending.remove(id)?.second?.invoke()
        }
    }

    override fun stop() {
        // Snapshot and clear first: TextToSpeech.stop() does not reliably deliver onStop for
        // utterances still queued, so those callbacks are fired here instead. Without this a
        // flush could strand the microphone closed.
        val stranded = pending.values.toList()
        pending.clear()
        speakingCount = 0

        runCatching { tts?.stop() }
        stranded.forEach { runCatching { it.second.invoke() } }
    }

    override fun release() {
        pending.clear()
        speakingCount = 0
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
    }
}

/**
 * Cloud TTS — **the swap point, deliberately not implemented.**
 *
 * Present so the architecture admits a cloud engine without restructuring, and so it can be
 * benchmarked against on-device later. Every method fails loudly rather than silently
 * degrading, because a TTS engine that quietly says nothing is indistinguishable from a
 * crashed app to someone who cannot see the screen.
 */
class CloudTtsEngine : TtsEngine {

    override fun initialise(onReady: (Boolean) -> Unit) {
        Log.w("CabEye.Tts", "CloudTtsEngine is not implemented; on-device remains the default path")
        onReady(false)
    }

    override fun speak(text: String, flush: Boolean, onStart: () -> Unit, onDone: () -> Unit) {
        onDone()
        error("CloudTtsEngine is not implemented")
    }

    override fun stop() = Unit
    override val isSpeaking: Boolean get() = false
    override fun release() = Unit
}
