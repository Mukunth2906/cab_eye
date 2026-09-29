package com.cabeye.rider.dialogue

import com.cabeye.rider.auth.SpokenNumbers

/**
 * Understanding spoken ride feedback.
 *
 * Feedback is optional and must cost a rider who does not want to give it exactly one word
 * ("skip") or nothing at all (silence). A rider who does want to report something must be able
 * to say it in their own words and have it land in the right queue — above all, a safety report
 * must never be filed as "other".
 */
object FeedbackParser {

    enum class Category(val spoken: String) {
        SAFETY("a safety issue"),
        DRIVER("an issue with the driver"),
        PICKUP("a pickup problem"),
        ROUTE("a route problem"),
        PAYMENT("a payment problem"),
        VEHICLE("a vehicle problem"),
        OTHER("feedback")
    }

    enum class Intent { SKIP, REPORT, RATING, NONE }

    private val SKIP = Regex("\\b(skip|no thanks|not now|later|nothing|no feedback|nope|done|that's all|thats all)\\b|^\\s*no\\s*$", RegexOption.IGNORE_CASE)
    private val REPORT = Regex("\\b(report|problem|issue|complain|complaint|something wrong|went wrong|not happy|unhappy|bad experience)\\b", RegexOption.IGNORE_CASE)

    private val WORD_RATINGS = listOf(
        Regex("\\b(excellent|amazing|perfect|superb|fantastic|outstanding|great)\\b", RegexOption.IGNORE_CASE) to 5,
        Regex("\\b(very good|really good|nice|good|fine)\\b", RegexOption.IGNORE_CASE) to 4,
        Regex("\\b(okay|ok|average|alright|all right|so so)\\b", RegexOption.IGNORE_CASE) to 3,
        Regex("\\b(bad|poor|not good)\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("\\b(terrible|awful|horrible|worst|very bad|dangerous|unsafe)\\b", RegexOption.IGNORE_CASE) to 1
    )

    /** What the rider meant when asked "How was your ride?" */
    fun intentOf(text: String): Intent = when {
        rating(text) != null -> Intent.RATING
        REPORT.containsMatchIn(text) -> Intent.REPORT
        SKIP.containsMatchIn(text) -> Intent.SKIP
        else -> Intent.NONE
    }

    /**
     * 1–5 from "four", "4 stars", "five out of five", "it was good". Null when no rating.
     * The first digit counts ("five out of five" → 5), and only 1–5 are ratings.
     */
    fun rating(text: String): Int? {
        // Asked for a number from one to five, a lone "won", "to", "free" or "for" is the
        // recogniser mishearing the number — here, and only when it is the whole answer.
        LONE_NUMBER_WORDS[text.trim().lowercase().trimEnd('.', '!', '?')]?.let { return it }
        val digits = SpokenNumbers.digits(text)
        digits.firstOrNull()?.digitToInt()?.takeIf { it in 1..5 }?.let { return it }
        // Worst first, so "very bad" is 1 rather than matching "bad" as 2.
        for ((pattern, value) in WORD_RATINGS.sortedBy { it.second }) {
            if (pattern.containsMatchIn(text)) return value
        }
        return null
    }

    private val LONE_NUMBER_WORDS = mapOf(
        "won" to 1, "to" to 2, "too" to 2, "tree" to 3, "free" to 3,
        "for" to 4, "fore" to 4, "fife" to 5, "hive" to 5
    )

    private val CATEGORY_WORDS: List<Pair<Category, Regex>> = listOf(
        Category.SAFETY to Regex(
            "\\b(unsafe|safety|scared|afraid|danger|dangerous|rash|speeding|too fast|drunk|harass|harassed|" +
                "harassment|touched|touch|threat|threatened|followed|abuse|abused|accident|crash|hit|" +
                "inappropriate|misbehav\\w*|stalk\\w*|" +
                // Found in the demo: "there was no OTP verification done" was filed as OTHER.
                // An unchecked boarding code means the rider could not be sure it was their car.
                "otp|o t p|boarding code|code (was )?(not|never)|no code|verification|verify|verified|" +
                "wrong (car|driver|auto|vehicle|person|cab)|not my driver|" +
                "different (car|driver|auto|vehicle|person|cab)|fake)\\b", RegexOption.IGNORE_CASE
        ),
        Category.PAYMENT to Regex(
            "\\b(overcharg\\w*|extra money|more money|charged|fare|cash|payment|paid twice|refund|change)\\b",
            RegexOption.IGNORE_CASE
        ),
        Category.PICKUP to Regex(
            "\\b(pickup|pick up|picked|couldn'?t find|could not find|wrong place|waited|waiting|didn'?t come|" +
                "did not come|late)\\b", RegexOption.IGNORE_CASE
        ),
        Category.ROUTE to Regex(
            "\\b(route|long way|longer way|detour|wrong way|lost|went around|drop|dropped)\\b",
            RegexOption.IGNORE_CASE
        ),
        Category.VEHICLE to Regex(
            "\\b(dirty|smell\\w*|ac|air condition\\w*|broken|seat|vehicle|car|auto was|noisy)\\b",
            RegexOption.IGNORE_CASE
        ),
        Category.DRIVER to Regex(
            "\\b(rude|shout\\w*|behaviou?r|attitude|phone|talking|argued|argument|driver|impolite)\\b",
            RegexOption.IGNORE_CASE
        )
    )

    /**
     * Which queue a spoken report belongs in. Checked in priority order, so a report that
     * mentions both the fare and feeling unsafe is a SAFETY report.
     */
    fun categorise(text: String): Category =
        CATEGORY_WORDS.firstOrNull { it.second.containsMatchIn(text) }?.first ?: Category.OTHER

    // ---------------------------------------------------------------------------------
    //  Keeping the report to what the rider actually meant to report
    // ---------------------------------------------------------------------------------

    /**
     * "Send it", "send this as feedback", "submit", "go ahead" — an instruction to the app, not
     * part of the report. Found in the demo: at "Shall I send it?" the rider said "send this as
     * feedback" and it was appended to the complaint instead of sending it.
     */
    private val SEND = Regex(
        "\\b(send|sent|submit|go ahead|post it|file it)\\b", RegexOption.IGNORE_CASE
    )

    /**
     * Instructions to the app about the report ("send this as feedback", "need of sending now",
     * "that's all"). Only removed at the very start or end of what was said, so a complaint
     * like "he asked me to send money" keeps its words.
     */
    private const val META_CORE =
        "(please |pls |now |just |so |you can |can you |could you |i )*" +
            "((need|want|would like|like)( of| to)? )?" +
            "(send|sending|submit|submitting)( it| this| that| my)?" +
            "( (as|for) (a |my |the )?(feedback|complaint|report|review))?( now| please)?" +
            "|that'?s all|that is all|that'?s it|that is it"
    private val META_END = Regex("[\\s,.;:!-]*\\b($META_CORE)[\\s.!]*$", RegexOption.IGNORE_CASE)
    private val META_START = Regex("^\\s*($META_CORE)\\b[\\s,.;:!-]*", RegexOption.IGNORE_CASE)

    private val NUMBER_WORDS = mapOf(
        1 to setOf("1", "one", "won"), 2 to setOf("2", "two", "to", "too"), 3 to setOf("3", "three", "tree", "free"),
        4 to setOf("4", "four", "for", "fore"), 5 to setOf("5", "five", "fife", "hive")
    )

    /** True when the words ask the app to send the report. */
    fun isSendCommand(text: String): Boolean = SEND.containsMatchIn(text)

    /**
     * The report as the rider meant it: without a leading rating ("one, there was no OTP…"
     * after a rating of 1, or "one out of five, …") and without instructions to the app.
     */
    fun cleanReport(text: String, rating: Int? = null): String {
        var out = text.trim()
        if (rating != null) {
            val words = NUMBER_WORDS[rating].orEmpty().joinToString("|") { Regex.escape(it) }
            out = out.replace(
                Regex("^($words)( (out of|by) (5|five))?( stars?)?[\\s,.:-]+", RegexOption.IGNORE_CASE), ""
            )
        }
        // Repeated, because riders stack them: "…done. need of sending now send this as feedback".
        while (true) {
            val next = out.replace(META_END, "").replace(META_START, "")
            if (next == out) break
            out = next
        }
        return out.replace(Regex("\\s+"), " ").trim(' ', ',', '.', '-', ':').trim()
    }

    /** "Yes, and he was rude" → "he was rude". */
    fun afterYes(text: String): String =
        cleanReport(text).replace(
            Regex("^(yes|yeah|yep|ok|okay|correct|right|sure)\\b[\\s,.]*(and |also )*", RegexOption.IGNORE_CASE), ""
        ).trim()

    /** How many real words are left — used to tell "send it" from "send it, he was also rude". */
    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}
