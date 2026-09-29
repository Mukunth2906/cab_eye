package com.cabeye.rider.auth

/**
 * Turning dictated numbers into digits, and digits back into something worth hearing.
 *
 * A blind rider signs in by *saying* a ten-digit mobile number. On-device recognition hands
 * that back in whatever shape it guessed: "98765 43210", "nine eight seven six five…",
 * "double nine eight seven…", "ninety eight seventy six…", or a mix. All of them are the same
 * number, and failing to read any of them would push the rider into asking a sighted person
 * to type it — the exact dependency this app exists to remove.
 *
 * No Android dependencies, so every shape is covered by a JVM test.
 */
object SpokenNumbers {

    private val UNITS = mapOf(
        "zero" to 0, "oh" to 0, "o" to 0, "nil" to 0,
        "one" to 1, "won" to 1,
        "two" to 2, "to" to 2, "too" to 2,
        "three" to 3, "tree" to 3,
        "four" to 4, "for" to 4, "fore" to 4,
        "five" to 5,
        "six" to 6, "sex" to 6,
        "seven" to 7,
        "eight" to 8, "ate" to 8,
        "nine" to 9, "nein" to 9
    )

    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )

    private val TENS = mapOf(
        "twenty" to 2, "thirty" to 3, "forty" to 4, "fourty" to 4, "fifty" to 5,
        "sixty" to 6, "seventy" to 7, "eighty" to 8, "ninety" to 9
    )

    private val REPEATERS = mapOf("double" to 2, "triple" to 3)

    /**
     * Every digit in [text], in order.
     *
     * "double nine" → "99", "ninety eight" → "98", "ninety" alone → "90",
     * "nine eight 765" → "98765". Words that are not numbers are skipped, so
     * "my number is nine eight…" works as well as the bare digits.
     */
    fun digits(text: String): String {
        val tokens = text.lowercase()
            .replace(Regex("(\\d)"), " $1 ")          // "98765" → "9 8 7 6 5"
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

        val out = StringBuilder()
        var repeat = 1
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.length == 1 && t[0].isDigit() -> {
                    out.append(t.repeat(repeat)); repeat = 1
                }
                REPEATERS.containsKey(t) -> repeat = REPEATERS.getValue(t)
                TENS.containsKey(t) -> {
                    val tens = TENS.getValue(t)
                    val next = tokens.getOrNull(i + 1)
                    val unit = next?.let { UNITS[it] }?.takeIf { it in 1..9 && next !in AMBIGUOUS_AFTER_TENS }
                    val pair = if (unit != null) {
                        i++
                        "$tens$unit"
                    } else {
                        // "twenty to" — the "to" is the preposition we just declined to read
                        // as a digit, so it is consumed here rather than re-read as a 2.
                        if (next in AMBIGUOUS_AFTER_TENS) i++
                        "${tens}0"
                    }
                    out.append(pair.repeat(repeat)); repeat = 1
                }
                TEENS.containsKey(t) -> {
                    out.append(TEENS.getValue(t).toString().repeat(repeat)); repeat = 1
                }
                UNITS.containsKey(t) && !(t in AMBIGUOUS_ALONE && !looksNumeric(tokens, i)) -> {
                    out.append(UNITS.getValue(t).toString().repeat(repeat)); repeat = 1
                }
                else -> repeat = 1
            }
            i++
        }
        return out.toString()
    }

    /**
     * Words that are numbers only when surrounded by other numbers. "to", "for" and "o" are
     * far more often English than digits — "my number is for you" must not gain a 4 — so they
     * count only when a real number word or digit sits right next to them.
     */
    private val AMBIGUOUS_ALONE = setOf("to", "too", "for", "fore", "o", "won", "ate", "tree", "sex", "nein", "nil")

    /** After "twenty", "to" is almost always a preposition, never "twenty-two". */
    private val AMBIGUOUS_AFTER_TENS = setOf("to", "too", "for", "fore", "o", "won", "ate", "tree", "sex", "nein", "nil")

    private fun looksNumeric(tokens: List<String>, i: Int): Boolean {
        fun strong(t: String?): Boolean = t != null && (
            (t.length == 1 && t[0].isDigit()) ||
                (UNITS.containsKey(t) && t !in AMBIGUOUS_ALONE) ||
                TENS.containsKey(t) || TEENS.containsKey(t) || REPEATERS.containsKey(t)
            )
        return strong(tokens.getOrNull(i - 1)) || strong(tokens.getOrNull(i + 1))
    }

    /**
     * An Indian mobile number from a transcript: ten digits, optional +91 or leading 0.
     *
     * @return the canonical ten digits, or null when the transcript does not hold one
     */
    fun mobileNumber(text: String): String? {
        var d = digits(text)
        if (d.length == 12 && d.startsWith("91")) d = d.substring(2)
        if (d.length == 11 && d.startsWith("0")) d = d.substring(1)
        if (d.length != 10) return null
        if (d[0] < '6') return null
        return d
    }

    /** A six-digit one-time code from a transcript, or null. */
    fun otp(text: String, length: Int = 6): String? {
        val d = digits(text)
        return if (d.length == length) d else null
    }

    /**
     * "9876543210" → "9 8 7 6 5. 4 3 2 1 0."
     *
     * Read in two groups of five with a full pause between, which is how Indian mobile numbers
     * are said aloud — and a TTS engine given "9876543210" as one token reads it as nine
     * billion, which nobody can check against the number in their head.
     */
    fun speakable(digits: String): String {
        if (digits.isEmpty()) return ""
        val groups = if (digits.length == 10) listOf(digits.take(5), digits.drop(5))
        else digits.chunked(3)
        return groups.joinToString(". ", postfix = ".") { g -> g.toCharArray().joinToString(" ") }
    }
}
