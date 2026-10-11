package com.cabeye.rider.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * The default speech path: Android's on-device [SpeechRecognizer] with streaming partials.
 *
 * ## Patient listening
 * Android's recogniser ends a session at the first short pause. Found in use: a rider saying
 * their number in two breaths was cut off after the first five digits and asked again, every
 * time. So one *answer* here spans as many recogniser *segments* as it takes. When a segment
 * ends, the next one starts immediately and the words are joined ([Utterance]). The answer is
 * over only when:
 *  - the rider has been quiet for [ListenOptions.endSilenceMs] after speaking (longer —
 *    [ListenOptions.incompleteSilenceMs] — while a number is still short of its digits),
 *  - the words already make a complete answer ([ListenOptions.isComplete]),
 *  - a held press is released ([stop]), or
 *  - [ListenOptions.maxDurationMs] passes.
 * Before the rider starts speaking, early "no match" results are ignored and the microphone
 * stays open for [ListenOptions.startTimeoutMs].
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
         * Indian English is *preferred* — the gazetteer is Indian place names, and en-IN
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

        /** Pause before the next segment of the same answer. Short: the rider is mid-sentence. */
        private const val SEGMENT_GAP_MS = 40L

        /**
         * Extra time allowed after the last partial for the recogniser to finalise a segment,
         * on top of the pause window, before the partial is taken as it stands.
         */
        private const val FINALISE_GRACE_MS = 2_500L

        /** After a release, how long to wait for the recogniser's last words before giving up. */
        private const val STOP_GRACE_MS = 3_000L

        /** Upper bound on segments in one answer, so a broken recogniser cannot loop forever. */
        private const val MAX_SEGMENTS = 40

        /** Upper bound on rebuilding a busy or disconnected recogniser within one answer. */
        private const val MAX_REBUILDS = 4

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

    /** True for the whole answer — across segments — until a final result, error or cancel. */
    @Volatile
    private var listening = false

    /** Guards against delivering both a final result and an error for one session. */
    @Volatile
    private var sessionSettled = true

    /** Bumped on every new session, so delayed work from an old one cannot touch the new one. */
    private var sessionId = 0

    private var options = ListenOptions()
    private val utterance = Utterance()
    private var sessionStartedAt = 0L
    private var lastSpeechAt = 0L
    private var heardSpeech = false
    private var stopRequested = false
    private var readyAnnounced = false
    private var held = false
    private var segmentOpen = false
    private var segmentsStarted = 0

    /**
     * Set once the device proves it has no offline model for [activeLanguage].
     *
     * Verified on a real device: asking for `EXTRA_PREFER_OFFLINE` on a phone that has never
     * downloaded the en-IN pack does not degrade to the network — it fails outright with
     * `ERROR_LANGUAGE_UNAVAILABLE`. So the preference is dropped for the rest of the process
     * after the first such failure and the session is retried transparently.
     */
    private var offlinePreferenceFailed = false

    /** Online recognition first; on-device after a network failure. */
    private var preferOffline = false

    /** One silent switch to the on-device model per session when the network fails. */
    private var networkFallbackUsed = false

    /** Language currently being asked for. Degrades from en-IN to the device default. */
    private var activeLanguage: String = PREFERRED_LANGUAGE

    /** Set once the device default locale has been tried, so the ladder cannot cycle. */
    private var triedDefaultLocale = false

    /** Recogniser rebuilds after a busy/disconnected service, this session. */
    private var rebuilds = 0

    /** Bumped by every start and cancel, so a cancel always beats a start still in the queue. */
    @Volatile
    private var startToken = 0

    private val pauseWatch = Runnable { onPauseElapsed() }
    private val startWatch = Runnable { onStartTimeout() }
    private val maxWatch = Runnable { onMaxDuration() }
    private val stopWatch = Runnable { if (!sessionSettled) finish() }

    override val isListening: Boolean get() = listening

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun start(listener: SpeechInputListener, options: ListenOptions) {
        // Flags set synchronously so a caller checking isListening right after start() sees
        // the session, and a cancel() racing the posted start cannot be lost.
        listening = true
        val token = ++startToken
        main.post { if (token == startToken) beginSession(listener, options) }
    }

    private fun beginSession(newListener: SpeechInputListener, newOptions: ListenOptions) {
        clearWatches()
        sessionId++
        listener = newListener
        options = newOptions
        utterance.clear()
        sessionSettled = false
        listening = true
        heardSpeech = false
        stopRequested = false
        readyAnnounced = false
        held = false
        segmentOpen = false
        segmentsStarted = 0
        rebuilds = 0
        networkFallbackUsed = false
        sessionStartedAt = now()
        lastSpeechAt = sessionStartedAt

        main.postDelayed(startWatch, newOptions.startTimeoutMs)
        main.postDelayed(maxWatch, newOptions.maxDurationMs)
        startRecognizer(newListener)
    }

    /** Creates a fresh recogniser and starts a segment on it. */
    private fun startRecognizer(current: SpeechInputListener) {
        if (!isAvailable) {
            Log.e(TAG, "No recognition service on this device")
            fail(SpeechError.UNAVAILABLE)
            return
        }

        // A recogniser left over from a previous session will reject a fresh start, so the
        // old one is always torn down first.
        destroyRecognizer()

        val engine = try {
            if (strictOnDevice && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not create recogniser: ${e.message}")
            fail(SpeechError.UNAVAILABLE)
            return
        }

        listener = current
        recognizer = engine
        engine.setRecognitionListener(recognitionListener)
        startSegment()
    }

    /** Starts listening for the next stretch of speech on the current recogniser. */
    private fun startSegment() {
        val engine = recognizer ?: run { listener?.let(::startRecognizer); return }
        segmentsStarted++
        segmentOpen = true
        try {
            engine.startListening(buildIntent())
        } catch (e: Exception) {
            Log.e(TAG, "startListening threw: ${e.message}")
            segmentOpen = false
            if (utterance.isEmpty) fail(SpeechError.UNKNOWN) else finish()
        }
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguage)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeLanguage)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (preferOffline && !offlinePreferenceFailed) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        // Five guesses, not one: the listener picks the first that fits its question.
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)

        // Asked for, though many recogniser builds ignore them — which is why the pause
        // handling above does not rely on them.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
    }

    override fun stop() {
        main.post {
            if (sessionSettled) return@post
            stopRequested = true
            if (!segmentOpen) {
                // Between segments: everything heard is already in hand.
                finish()
                return@post
            }
            runCatching { recognizer?.stopListening() }
            // The last segment's result is still on its way; never wait on it forever.
            main.removeCallbacks(stopWatch)
            main.postDelayed(stopWatch, STOP_GRACE_MS)
        }
    }

    override fun setHeld(held: Boolean) {
        main.post { this.held = held }
    }

    override fun cancel() {
        // Flags are cleared synchronously, before the posted teardown, so that a caller can
        // cancel and immediately reopen without the `isListening` guard seeing a stale true
        // and silently dropping the new session.
        listening = false
        sessionSettled = true
        startToken++
        clearWatches()
        main.post {
            segmentOpen = false
            runCatching { recognizer?.cancel() }
        }
    }

    override fun release() {
        main.post {
            listening = false
            sessionSettled = true
            clearWatches()
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
        segmentOpen = false
    }

    private fun clearWatches() {
        main.removeCallbacks(pauseWatch)
        main.removeCallbacks(startWatch)
        main.removeCallbacks(maxWatch)
        main.removeCallbacks(stopWatch)
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** How long a pause ends the answer, given what has been heard so far. */
    private fun pauseWindow(): Long {
        val complete = options.isComplete ?: return options.endSilenceMs
        return if (utterance.anyComplete(complete)) options.endSilenceMs else options.incompleteSilenceMs
    }

    // ---------------------------------------------------------------------------------
    //  Deciding when the answer is over
    // ---------------------------------------------------------------------------------

    /** A segment ended, with words or without. Decides whether to keep listening. */
    private fun afterSegment(noMatch: Boolean) {
        if (sessionSettled) return
        if (stopRequested) { finish(); return }

        val complete = options.isComplete
        if (complete != null && !utterance.isEmpty && utterance.anyComplete(complete)) {
            finish()
            return
        }

        val t = now()
        if (t - sessionStartedAt >= options.maxDurationMs || segmentsStarted >= MAX_SEGMENTS) {
            if (utterance.isEmpty) fail(SpeechError.NO_MATCH) else finish()
            return
        }

        if (!heardSpeech) {
            // The recogniser gave up before the rider began. Keep the microphone open until
            // the start timeout: a rider who takes a breath first must not be told "I didn't
            // hear anything" two seconds in.
            if (!held && t - sessionStartedAt >= options.startTimeoutMs) fail(SpeechError.NO_SPEECH)
            else nextSegment()
            return
        }

        if (noMatch && !held && t - lastSpeechAt >= pauseWindow()) {
            finish()
            return
        }
        nextSegment()
    }

    private fun nextSegment() {
        val id = sessionId
        main.postDelayed({
            if (id == sessionId && !sessionSettled) startSegment()
        }, SEGMENT_GAP_MS)
    }

    /** The rider has been quiet for the pause window since they last spoke. */
    private fun onPauseElapsed() {
        if (sessionSettled || !heardSpeech) return
        // Still pressing: the rider decides when they have finished, by letting go.
        if (held) return
        Log.i(TAG, "pause of ${now() - lastSpeechAt} ms after speech; answer complete")
        finish()
    }

    private fun onStartTimeout() {
        if (sessionSettled || heardSpeech) return
        if (held) {
            // Pressing but not yet speaking (often: waiting for the driver to say the code).
            main.postDelayed(startWatch, 2_000L)
            return
        }
        Log.i(TAG, "no speech within ${options.startTimeoutMs} ms")
        fail(SpeechError.NO_SPEECH)
    }

    private fun onMaxDuration() {
        if (sessionSettled) return
        Log.i(TAG, "answer reached ${options.maxDurationMs} ms; closing")
        if (utterance.isEmpty) fail(SpeechError.NO_MATCH) else finish()
    }

    /** Delivers everything heard, as whole-answer guesses. */
    private fun finish() {
        if (sessionSettled) return
        clearWatches()
        if (segmentOpen) {
            segmentOpen = false
            runCatching { recognizer?.cancel() }
        }
        utterance.commitPartial()
        val alternatives = utterance.alternatives()
        listening = false
        settle {
            if (alternatives.isEmpty()) {
                listener?.onError(SpeechError.NO_MATCH)
            } else {
                Log.i(TAG, "final(${utterance.segmentCount} segments)=${alternatives.joinToString(" | ") { "\"$it\"" }}")
                listener?.onFinalAlternatives(alternatives)
            }
        }
    }

    private fun fail(error: SpeechError) {
        if (sessionSettled) return
        clearWatches()
        if (segmentOpen) {
            segmentOpen = false
            runCatching { recognizer?.cancel() }
        }
        listening = false
        Log.w(TAG, "session failed -> $error")
        settle { listener?.onError(error) }
    }

    /**
     * Steps down the language/offline ladder and restarts the segment.
     *
     * Order, each step tried at most once per process:
     *  1. en-IN                          (best recognition of Indian place names)
     *  2. device default locale          (en-GB on the test device — usually installed)
     *  3. device default locale, online  (when the offline preference was the problem)
     *
     * @return true if a retry was scheduled, false if the ladder is exhausted
     */
    private fun tryFallback(): Boolean {
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
        restartRecognizerLater()
        return true
    }

    /**
     * Rebuilds the recogniser and continues the same answer. Posted with a small delay rather
     * than run inline: restarting tears down the very recogniser whose callback we are inside,
     * and doing so instantly reliably produces ERROR_SERVER_DISCONNECTED.
     */
    private fun restartRecognizerLater() {
        val id = sessionId
        main.postDelayed({
            val current = listener
            if (id == sessionId && !sessionSettled && current != null) startRecognizer(current)
        }, FALLBACK_DELAY_MS)
    }

    /** Ensures exactly one terminal callback per session. */
    private inline fun settle(block: () -> Unit) {
        if (sessionSettled) return
        sessionSettled = true
        block()
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionSettled) return
            if (!readyAnnounced) {
                readyAnnounced = true
                listener?.onReadyForSpeech()
            }
            if (heardSpeech) {
                // A continuation segment: if the rider stays quiet for the rest of the pause
                // window, the answer is over.
                val remaining = (pauseWindow() - (now() - lastSpeechAt)).coerceAtLeast(300L)
                main.removeCallbacks(pauseWatch)
                main.postDelayed(pauseWatch, remaining)
            }
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (sessionSettled) return
            val normalised = ((rmsdB - RMS_MIN) / (RMS_MAX - RMS_MIN)).coerceIn(0f, 1f)
            listener?.onLevel(normalised)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (sessionSettled) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return
            utterance.onPartial(text)
            heardSpeech = true
            lastSpeechAt = now()
            main.removeCallbacks(startWatch)
            // Still talking. If the recogniser then never finalises this segment, the partial
            // is taken as it stands once the pause window and a grace period have passed.
            main.removeCallbacks(pauseWatch)
            main.postDelayed(pauseWatch, pauseWindow() + FINALISE_GRACE_MS)
            listener?.onPartial(utterance.bestText())
        }

        override fun onResults(results: Bundle?) {
            if (sessionSettled) return
            segmentOpen = false
            val alternatives = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            val hadPartial = utterance.pendingPartial.isNotBlank()
            val gotWords = alternatives.any { it.isNotBlank() }
            if (gotWords) {
                utterance.addSegment(alternatives)
                heardSpeech = true
                lastSpeechAt = now()
                main.removeCallbacks(startWatch)
                main.removeCallbacks(pauseWatch)
                Log.i(TAG, "segment ${utterance.segmentCount}: ${alternatives.joinToString(" | ") { "\"$it\"" }}")
                listener?.onPartial(utterance.bestText())
            } else {
                // The recogniser showed words and then finalised nothing: keep the words.
                utterance.commitPartial()
            }
            afterSegment(noMatch = !gotWords && !hadPartial)
        }

        override fun onError(error: Int) {
            if (sessionSettled) return
            segmentOpen = false

            // Language faults are recoverable by asking for something else. Handled
            // transparently — a missing language pack is not a fact a rider can act on.
            if (error == ERROR_LANGUAGE_UNAVAILABLE || error == ERROR_LANGUAGE_NOT_SUPPORTED) {
                if (tryFallback()) return
            }

            // No network: switch to the on-device model and retry once, silently. From then on
            // this process prefers on-device, until the app restarts.
            if ((error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) &&
                !preferOffline && !networkFallbackUsed
            ) {
                networkFallbackUsed = true
                preferOffline = true
                Log.w(TAG, "Network recognition failed; retrying on-device")
                restartRecognizerLater()
                return
            }

            // Silence, or nothing intelligible, in this segment. Whether that ends the answer
            // depends on what came before — see afterSegment.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (utterance.pendingPartial.isNotBlank()) {
                    utterance.commitPartial()
                    afterSegment(noMatch = false)
                } else {
                    afterSegment(noMatch = true)
                }
                return
            }

            // A busy or disconnected service mid-answer: rebuild it and carry on, keeping the
            // words already heard. Bounded by MAX_SEGMENTS.
            val transient = error == ERROR_SERVER_DISCONNECTED ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            if (transient && !stopRequested && segmentsStarted < MAX_SEGMENTS && rebuilds < MAX_REBUILDS) {
                rebuilds++
                Log.w(TAG, "recogniser error $error; rebuilding and continuing (${rebuilds}/$MAX_REBUILDS)")
                utterance.commitPartial()
                restartRecognizerLater()
                return
            }

            // Anything else ends the answer — but never throws away words already heard.
            utterance.commitPartial()
            if (!utterance.isEmpty) {
                Log.w(TAG, "recognition error code=$error after speech; using what was heard")
                finish()
                return
            }

            val mapped = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechError.NETWORK
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                ERROR_SERVER_DISCONNECTED -> SpeechError.BUSY
                ERROR_LANGUAGE_UNAVAILABLE,
                ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechError.LANGUAGE
                else -> SpeechError.UNKNOWN
            }
            Log.w(TAG, "recognition error code=$error -> $mapped")
            fail(mapped)
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

    override fun start(listener: SpeechInputListener, options: ListenOptions) {
        Log.w("CabEye.Stt", "CloudSpeechInput is not implemented; on-device remains the default")
        listener.onError(SpeechError.UNAVAILABLE)
    }

    override fun stop() = Unit
    override fun cancel() = Unit
    override fun release() = Unit
}
