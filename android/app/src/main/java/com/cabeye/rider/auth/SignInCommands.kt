package com.cabeye.rider.auth

/**
 * The handful of things a rider can say during sign-in that are not the answer itself.
 *
 * Kept tiny and separate from the ride [com.cabeye.rider.intent.Classifier]: during sign-in
 * "cancel" or "stop" must not cancel a ride that does not exist, and a destination parser has
 * no business reading a phone number.
 */
object SignInCommands {

    enum class Kind { YES, NO, SKIP, BACK, RESEND, HELP, REPEAT, NONE }

    private val YES = Regex("^\\s*(yes|yeah|yep|yup|correct|right|that's right|thats right|sure|ok|okay|confirm)\\b", RegexOption.IGNORE_CASE)
    private val NO = Regex("^\\s*(no|nope|nah|wrong|incorrect|not right)\\b", RegexOption.IGNORE_CASE)
    private val SKIP = Regex("\\b(skip|later|not now|guest|without (signing|sign in|an account)|continue without)\\b", RegexOption.IGNORE_CASE)
    private val BACK = Regex("\\b(go back|back|change (the |my )?number|wrong number|different number|another number|start over|sign in again)\\b", RegexOption.IGNORE_CASE)
    private val RESEND = Regex("\\b(send (it )?again|resend|new code|another code|did ?n'?t get|didnt get|no code)\\b", RegexOption.IGNORE_CASE)
    private val HELP = Regex("\\b(help|what (do|should) i say)\\b", RegexOption.IGNORE_CASE)
    private val REPEAT = Regex("\\b(repeat|say (that |it )?again|pardon)\\b", RegexOption.IGNORE_CASE)

    /**
     * Order matters: "send it again" is RESEND, not REPEAT; "no, go back" is BACK, not a bare
     * NO; and "skip" wins everywhere because a rider must always be able to leave sign-in.
     */
    fun classify(text: String): Kind {
        val t = text.trim()
        return when {
            t.isEmpty() -> Kind.NONE
            SKIP.containsMatchIn(t) -> Kind.SKIP
            RESEND.containsMatchIn(t) -> Kind.RESEND
            BACK.containsMatchIn(t) -> Kind.BACK
            HELP.containsMatchIn(t) -> Kind.HELP
            REPEAT.containsMatchIn(t) -> Kind.REPEAT
            YES.containsMatchIn(t) -> Kind.YES
            NO.containsMatchIn(t) -> Kind.NO
            else -> Kind.NONE
        }
    }

    private val NAME_CARRIERS = Regex(
        "^\\s*(my name is|my name's|i am|i'm|im|this is|it's|its|call me|name is|name)\\s+",
        RegexOption.IGNORE_CASE
    )

    /**
     * "my name is harshini sree" → "Harshini Sree". Null when nothing name-like is left.
     *
     * Capped at three words: a recogniser that mis-fires on background speech returns a
     * sentence, and a sentence is not a name to greet someone by every morning.
     */
    fun name(text: String): String? {
        val stripped = NAME_CARRIERS.replace(text.trim(), "")
            .replace(Regex("[^\\p{L} .'-]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (stripped.isEmpty() || stripped.size > 3) return null
        return stripped.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    }
}
