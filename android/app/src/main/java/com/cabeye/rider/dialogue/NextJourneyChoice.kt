package com.cabeye.rider.dialogue

/** The answer to "Book another ride now, schedule one for later, or are you done?" */
object NextJourneyChoice {

    enum class Kind { NOW, LATER, DONE, NONE }

    private val LATER = Regex("\\b(later|schedule|scheduled|tomorrow|tonight|in \\w+ (hours?|minutes?)|at \\d|evening|morning|afternoon)\\b", RegexOption.IGNORE_CASE)
    private val NOW = Regex("\\b(now|another|book|yes|yeah|sure|again|right away|one more)\\b", RegexOption.IGNORE_CASE)
    private val DONE = Regex("\\b(done|no|nope|nothing|that's all|thats all|finished|not now|no thanks|bye|stop)\\b", RegexOption.IGNORE_CASE)

    /** Later beats now ("book one for tomorrow"), and a plain "no" is done. */
    fun classify(text: String): Kind = when {
        LATER.containsMatchIn(text) -> Kind.LATER
        DONE.containsMatchIn(text) && !Regex("\\b(book|another)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Kind.DONE
        NOW.containsMatchIn(text) -> Kind.NOW
        else -> Kind.NONE
    }
}
