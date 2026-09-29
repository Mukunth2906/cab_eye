package com.cabeye.rider.dialogue

/**
 * Understanding the rider about the live camera: the answer to "can your driver see your
 * camera?", and "stop camera" at any time while it is on.
 *
 * Pure functions, so every phrase below is pinned by a unit test rather than found on a street.
 */
object CameraConsent {

    enum class Answer { YES, ALWAYS, NO }

    /** "Always", "don't ask me again" — share now and stop asking. "Always ask" is not it. */
    private val ALWAYS = Regex("\\balways\\b(?! ask)|\\bdon'?t ask\\b")
    private val DONT_ASK = Regex("\\bdon'?t ask\\b")
    private val NO = Regex(
        "\\b(no|nope|nah|don'?t|do not|not now|never|deny|refuse|nahi|illa|vendam|keep it off)\\b"
    )
    private val YES = Regex(
        "\\b(yes|yeah|yep|yup|ok|okay|sure|share|allow|go ahead|fine|please do|alright|all right|haan|ha haan|sari|seri)\\b"
    )

    /**
     * "stop camera", "turn off the camera", "camera off", "no more camera", "hide my camera".
     * Needs the word camera: a bare "stop" is the ride's cancel command, and must stay that.
     */
    private val STOP_CAMERA = Regex(
        "\\b(stop|turn off|switch off|close|end|hide|disable)\\b.*\\bcamera\\b" +
            "|\\bcamera\\b.*\\b(off|stop|close)\\b" +
            "|\\bno (more )?camera\\b"
    )

    /**
     * @return the answer, or null when it is neither — a code the driver is saying, say, which
     *   the caller then treats as the code it is.
     *
     * "No" wins over "yes" when both appear ("yes, no, don't"): a camera turned on by a
     * misheard answer is worse than one left off.
     */
    fun answer(heard: String): Answer? {
        val text = normalise(heard)
        if (text.isEmpty()) return null
        // "Don't ask me again" contains a "don't" that is not a refusal.
        val refusal = NO.containsMatchIn(text.replace(DONT_ASK, " "))
        if (refusal) return Answer.NO
        if (ALWAYS.containsMatchIn(text)) return Answer.ALWAYS
        if (YES.containsMatchIn(text)) return Answer.YES
        return null
    }

    fun isStopCamera(heard: String): Boolean = STOP_CAMERA.containsMatchIn(normalise(heard))

    private fun normalise(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9' ]+"), " ").replace(Regex("\\s+"), " ").trim()
}
