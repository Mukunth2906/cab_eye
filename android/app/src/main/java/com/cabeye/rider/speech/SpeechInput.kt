package com.cabeye.rider.speech

/**
 * Speech recognition, behind an interface so the engine is swappable.
 *
 * The default is Android's [android.speech.SpeechRecognizer] — online first for accuracy,
 * on-device when there is no network — with streaming partial results and several guesses per
 * utterance (see [SpeechInputListener.onFinalAlternatives]).
 *
 * [CloudSpeechInput] exists so the recogniser can be **measured against** another engine later.
 */
interface SpeechInput {

    /** Whether any recogniser is installed and usable on this device. */
    val isAvailable: Boolean

    /**
     * Opens the microphone and begins streaming recognition.
     *
     * Must be called from the main thread — Android's [android.speech.SpeechRecognizer]
     * enforces this and throws otherwise.
     *
     * **The caller must guarantee TTS is not speaking.** If it is, the recogniser hears the
     * phone's own voice and transcribes the app talking to itself.
     */
    fun start(listener: SpeechInputListener)

    /**
     * Stops capturing and processes what was heard so far.
     *
     * This is the release of a press-and-hold, so a final result is still expected — unlike
     * [cancel], which discards.
     */
    fun stop()

    /** Aborts and discards. No final result will be delivered. */
    fun cancel()

    /** True between [start] and a final result or error. */
    val isListening: Boolean

    fun release()
}

/**
 * Callbacks from a recognition session. All are delivered on the main thread.
 */
interface SpeechInputListener {

    /** Microphone is genuinely open. The cue earcon should fire here, not at [SpeechInput.start]. */
    fun onReadyForSpeech()

    /**
     * A streaming partial result.
     *
     * Displayed for sighted observers but never spoken back — reading the rider their own
     * words costs time and tells them nothing they did not just say.
     */
    fun onPartial(text: String)

    /** The final transcript. Marks T1 in the latency budget. */
    fun onFinal(text: String)

    /**
     * Every transcript the recogniser offered for this utterance, best guess first.
     *
     * The recogniser's first guess is often not the one that fits the question: asked for a
     * ten-digit number it may rank "98765 for 3210" above "98765 43210". A listener that knows
     * what it asked for overrides this and picks the first guess that makes sense. By default
     * only the best guess is used.
     */
    fun onFinalAlternatives(alternatives: List<String>) {
        alternatives.firstOrNull()?.let { onFinal(it) }
    }

    /** Normalised 0..1 microphone level, for the visual ring only. */
    fun onLevel(level: Float)

    /** Recognition failed. */
    fun onError(error: SpeechError)
}

/**
 * Why recognition failed, reduced to the cases that need genuinely different handling.
 *
 * Android's raw `ERROR_*` constants are too fine-grained to act on: eight of them all mean
 * "try again" and only differ in a detail the rider cannot do anything about.
 */
enum class SpeechError(val spokenExplanation: String) {

    /** Nothing was said, or nothing intelligible was. Re-prompt. */
    NO_MATCH("I didn't catch that."),

    /** Silence throughout. Usually the rider did not realise the mic had opened. */
    NO_SPEECH("I didn't hear anything."),

    /** Microphone permission missing or revoked. */
    PERMISSION("I need microphone permission."),

    /** No recogniser installed at all. */
    UNAVAILABLE("Speech recognition isn't available on this phone."),

    /**
     * The recogniser has no model for the requested language, even after dropping the
     * offline preference. Distinct from [UNAVAILABLE] because the fix is different: the
     * rider (or their helper) needs to install a language pack, not an app.
     */
    LANGUAGE("I can't recognise speech in this language yet."),

    /** Network needed and absent — only reachable when on-device models are missing. */
    NETWORK("I can't reach the network."),

    /** Recogniser busy or in a bad state. Retrying usually clears it. */
    BUSY("One moment."),

    /** Anything else. */
    UNKNOWN("Something went wrong.")
}
