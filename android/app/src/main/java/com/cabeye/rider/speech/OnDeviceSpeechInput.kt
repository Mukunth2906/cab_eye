package com.cabeye.rider.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * The default speech path: Android's on-device [SpeechRecognizer] with streaming partials.
 *
 * ## Online first, on-device as the fallback
 * Android's recogniser service is asked for online recognition by default: in the demo the
 * on-device model misheard phone numbers, codes and ratings far too often for Indian English.
 * Audio goes to the phone's own recognition service (Google), never to the Cab Eye backend.
 * If the network fails, the session is retried silently with `EXTRA_PREFER_OFFLINE`, and the
 * rest of the process stays on-device.
 *
 * API 31+ also offers `createOnDeviceSpeechRecognizer`, which never goes online. It is
 * available via [strictOnDevice] for measurement, and is not the default.
 *
 * ## Threading
 * [SpeechRecognizer] must be created and driven from the main thread. Every entry point
 * here hops to the main looper rather than trusting the caller.
 */
class OnDeviceSpeechInput(
    context: Context,
    private val strictOnDevice: Boolean = false
) : SpeechInput {

    companion object {
        private const val TAG = "CabEye.Stt"

        /**
         * Indian English is *preferred* — the gazetteer is Chennai place names, and en-IN
         * recognises them far better than other English variants.
         *
         * It is only a preference. Verified on a real device whose locale is en-GB: asking
         * for en-IN there fails with ERROR_LANGUAGE_UNAVAILABLE no matter whether offline is
         * requested, because the phone has no en-IN model at all. Hence the fallback ladder
         * in [tryFallback].
         */
        private const val PREFERRED_LANGUAGE = "en-IN"

        /** Pause before a fallback retry; restarting instantly tends to yield error 11. */
        private const val FALLBACK_DELAY_MS = 250L

        /**
         * RMS values from Android are roughly -2..10 dB. Mapped to 0..1 for the visual ring
         * only; nothing functional depends on this number.
         */
        private const val RMS_MIN = -2f
        private const val RMS_MAX = 10f

        // Added in API 31. Referenced as literals rather than via SpeechRecognizer.* so the
        // code still compiles against minSdk 26; they are plain compile-time int constants,
        // and older devices simply never report them.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /** API 30+. Transient: the recognition service dropped the connection. */
        private const val ERROR_SERVER_DISCONNECTED = 11
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechInputListener? = null

    @Volatile
    private var listening = false

    /** Guards against delivering both a final result and an error for one session. */
    private var sessionSettled = false

    /**
     * Set once the device proves it has no offline model for [LANGUAGE].
     *
     * Verified on a real device: asking for `EXTRA_PREFER_OFFLINE` on a phone that has never
     * downloaded the en-IN pack does not degrade to the network — it fails outright with
     * `ERROR_LANGUAGE_UNAVAILABLE`. So the preference is dropped for the rest of the process
     * after the first such failure and the session is retried transparently. The rider hears
     * nothing about it, because a missing language pack is not something they can act on.
     */
    private var offlinePreferenceFailed = false

    /**
     * Online recognition first. Google's server recogniser is markedly better than the
     * on-device model at Indian-English digits and place names — found in the demo, where
     * phone numbers and ratings were misheard offline. The on-device model is the fallback
     * when the network fails, not the default.
     */
    private var preferOffline = false

    /** One silent switch to the on-device model per session when the network fails. */
    private var networkFallbackUsed = false

    /** Language currently being asked for. Degrades from en-IN to the device default. */
    private var activeLanguage: String = PREFERRED_LANGUAGE

    /** Set once the device default locale has been tried, so the ladder cannot cycle. */
    private var triedDefaultLocale = false

    /**
     * One transient retry per session.
     *
     * `ERROR_SERVER_DISCONNECTED` shows up on a real device when a session starts soon after
     * the previous one was torn down — the recognition service has not finished releasing.
     * It clears on its own, so it is retried silently once rather than being reported as a
     * fault the rider has to hear about.
     */
    private var transientRetryUsed = false

    override val isListening: Boolean get() = listening

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun start(listener: SpeechInputListener) {
        // Reset here rather than in startOnMain, which the internal retries also call —
        // otherwise a retry would refresh its own budget and could loop.
        transientRetryUsed = false
        networkFallbackUsed = false
        main.post { startOnMain(listener) }
    }

    private fun startOnMain(newListener: SpeechInputListener) {
        if (!isAvailable) {
            Log.e(TAG, "No recognition service on this device")
            newListener.onError(SpeechError.UNAVAILABLE)
            return
        }

        // A recogniser left over from a previous session will reject a fresh start, so the
        // old one is always torn down first. Reusing instances across sessions is a known
        // source of ERROR_CLIENT on several vendors' builds.
        destroyRecognizer()

        this.listener = newListener
        sessionSettled = false

        val engine = try {
            if (strictOnDevice && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not create recogniser: ${e.message}")
            newListener.onError(SpeechError.UNAVAILABLE)
            return
        }

        recognizer = engine
        engine.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeLanguage)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)

            // Streaming partials are what make the interaction feel immediate rather than
            // batch — the surface updates while the rider is still talking.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Online first; on-device only after a network failure. See [preferOffline].
            if (preferOffline && !offlinePreferenceFailed) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            // Five guesses, not one: the listener picks the first that fits its question.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)

            // Press-and-hold means the rider decides when they have finished, so the
            // recogniser's own silence detection is pushed out of the way rather than being
            // allowed to cut them off mid-sentence.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }

        listening = true
        try {
            engine.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening threw: ${e.message}")
            listening = false
            settle { newListener.onError(SpeechError.UNKNOWN) }
        }
    }

    override fun stop() {
        main.post {
            runCatching { recognizer?.stopListening() }
            // `listening` stays true: stopListening() only closes the mic, and the final
            // result is still on its way.
        }
    }

    override fun cancel() {
        // Flags are cleared synchronously, before the posted teardown, so that a caller can
        // cancel and immediately reopen without the `isListening` guard seeing a stale true
        // and silently dropping the new session.
        listening = false
        sessionSettled = true
        main.post {
            runCatching { recognizer?.cancel() }
        }
    }

    override fun release() {
        main.post {
            listening = false
            destroyRecognizer()
            listener = null
        }
    }

    private fun destroyRecognizer() {
        runCatching {
            recognizer?.setRecognitionListener(null)
            recognizer?.destroy()
        }
        recognizer = null
    }

    /**
     * Steps down the language/offline ladder and restarts the session.
     *
     * Order, each step tried at most once per process:
     *  1. en-IN, offline preferred        (best recognition of Chennai place names)
     *  2. device default locale, offline  (en-GB on the test device — usually installed)
     *  3. device default locale, online   (last resort; costs a network round trip)
     *
     * @return true if a retry was scheduled, false if the ladder is exhausted and the error
     *   should be reported to the listener
     */
    private fun tryFallback(): Boolean {
        val current = listener ?: return false

        val deviceLanguage = Locale.getDefault().toLanguageTag()

        when {
            !triedDefaultLocale && !deviceLanguage.equals(activeLanguage, ignoreCase = true) -> {
                triedDefaultLocale = true
                Log.w(TAG, "No model for $activeLanguage; falling back to device locale $deviceLanguage")
                activeLanguage = deviceLanguage
            }
            preferOffline && !offlinePreferenceFailed -> {
                offlinePreferenceFailed = true
                Log.w(TAG, "No offline model for $activeLanguage; retrying over the network")
            }
            else -> {
                Log.e(TAG, "Language ladder exhausted; no usable recognition model")
                return false
            }
        }

        // Posted with a small delay rather than called inline: restarting tears down the
        // very recogniser whose callback we are inside, and doing so instantly reliably
        // produces ERROR_SERVER_DISCONNECTED.
        main.postDelayed({
            sessionSettled = false
            startOnMain(current)
        }, FALLBACK_DELAY_MS)

        return true
    }

    /** Ensures exactly one terminal callback per session. */
    private inline fun settle(block: () -> Unit) {
        if (sessionSettled) return
        sessionSettled = true
        block()
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            listener?.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            val normalised = ((rmsdB - RMS_MIN) / (RMS_MAX - RMS_MIN)).coerceIn(0f, 1f)
            listener?.onLevel(normalised)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return
            listener?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val alternatives = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            settle {
                if (alternatives.isEmpty()) {
                    listener?.onError(SpeechError.NO_MATCH)
                } else {
                    Log.i(TAG, "final=${alternatives.joinToString(" | ") { "\"$it\"" }}")
                    listener?.onFinalAlternatives(alternatives)
                }
            }
        }

        override fun onError(error: Int) {
            listening = false

            // Language faults are recoverable by asking for something else. Handled
            // transparently — the rider hears nothing, because a missing language pack is
            // not a fact they can act on mid-sentence.
            if (error == ERROR_LANGUAGE_UNAVAILABLE || error == ERROR_LANGUAGE_NOT_SUPPORTED) {
                if (tryFallback()) return
            }

            // No network: switch to the on-device model and retry once, silently. From then on
            // this process prefers on-device, until the app restarts.
            if ((error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) &&
                !preferOffline && !networkFallbackUsed
            ) {
                val current = listener
                if (current != null) {
                    networkFallbackUsed = true
                    preferOffline = true
                    Log.w(TAG, "Network recognition failed; retrying on-device")
                    main.postDelayed({
                        sessionSettled = false
                        startOnMain(current)
                    }, FALLBACK_DELAY_MS)
                    return
                }
            }

            // Transient service disconnect — retry once, silently.
            if (error == ERROR_SERVER_DISCONNECTED && !transientRetryUsed) {
                val current = listener
                if (current != null) {
                    transientRetryUsed = true
                    Log.w(TAG, "Recognition service disconnected; retrying once")
                    main.postDelayed({
                        sessionSettled = false
                        startOnMain(current)
                    }, FALLBACK_DELAY_MS)
                    return
                }
            }

            val mapped = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NO_MATCH
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechError.NETWORK
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.BUSY
                SpeechRecognizer.ERROR_CLIENT -> SpeechError.BUSY
                // 11 = ERROR_SERVER_DISCONNECTED. Seen on a real device when sessions are
                // started and torn down in quick succession; it is transient, so it maps to
                // BUSY ("One moment.") rather than to the alarming generic message.
                ERROR_SERVER_DISCONNECTED -> SpeechError.BUSY
                ERROR_LANGUAGE_UNAVAILABLE,
                ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechError.LANGUAGE
                else -> SpeechError.UNKNOWN
            }
            Log.w(TAG, "recognition error code=$error -> $mapped")
            settle { listener?.onError(mapped) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/**
 * Cloud STT — **the swap point, deliberately not implemented.**
 *
 * Present so on-device can be benchmarked against a cloud engine later, per the brief. The
 * default path never leaves the phone and this class is not wired into it.
 */
class CloudSpeechInput : SpeechInput {

    override val isAvailable: Boolean get() = false
    override val isListening: Boolean get() = false

    override fun start(listener: SpeechInputListener) {
        Log.w("CabEye.Stt", "CloudSpeechInput is not implemented; on-device remains the default")
        listener.onError(SpeechError.UNAVAILABLE)
    }

    override fun stop() = Unit
    override fun cancel() = Unit
    override fun release() = Unit
}
