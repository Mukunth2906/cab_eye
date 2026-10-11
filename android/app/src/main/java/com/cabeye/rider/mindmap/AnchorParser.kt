package com.cabeye.rider.mindmap

import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.LandmarkParser
import com.cabeye.rider.walk.Side

/**
 * The rider's own words about what a place means to them → a personal anchor.
 *
 *  - "Ravi's tea shop, where I stop on Fridays"        → subject, person Ravi, habit
 *  - "the temple bell means I'm near Amma's house"       → subject, sense SOUND, meaning, relates to
 *  - "when I smell the bakery I'm almost at the clinic" → the same, said the other way round
 *  - "remember this as the place I meet Priya"           → this place, person Priya
 *
 * Personal memories are how blind travellers actually hold a route ("left after Ravi's shop"),
 * so the agent keeps them in the rider's words and reads them back in the second person.
 * A purely visual cue ("the red sign means turn") is refused, as on walks.
 *
 * Returns null when the words carry no anchor — that sentence then goes on to booking, or to
 * the optional free-form interpreter.
 */
object AnchorParser {

    data class Anchor(
        /** What the anchor is about, cleaned ("ravi's tea shop", "temple bell"). Blank when [here]. */
        val subject: String,
        /** "this place", "here": about where the rider is now. */
        val here: Boolean = false,
        val person: String = "",
        /** Second person: "you stop on fridays". */
        val habit: String = "",
        /** Second person: "you're near amma's house". */
        val meaning: String = "",
        /** The other place the meaning points at ("amma's house"), to be linked if known. */
        val relatesTo: String = "",
        val sense: LandmarkKind? = null,
        val side: Side = Side.NONE,
        val words: String = ""
    ) {
        /** One line to read back: "Ravi's tea shop: you stop on Fridays." */
        fun spoken(): String {
            val name = subject.ifBlank { "this place" }
            val parts = listOfNotNull(habit.takeIf { it.isNotBlank() }, meaning.takeIf { it.isNotBlank() })
            return if (parts.isEmpty()) name else "$name: ${parts.joinToString(", and ")}"
        }
    }

    sealed interface Result {
        data class Found(val anchor: Anchor) : Result
        data class VisualOnly(val text: String) : Result
    }

    private val LEAD = Regex("^(?:please\\s+)?(?:remember|note|make a note|keep in mind|save|learn)(?:\\s+that)?\\s+")
    private val HERE = Regex(
        // "save this as Monday errands" names a saved route: only "save this PLACE as" is a note.
        "^(?:please\\s+)?(?:(?:remember|mark|call)\\s+(?:this|here)(?:\\s+place|\\s+spot)?|save\\s+(?:this|here)\\s+(?:place|spot))" +
            "\\s+(?:as\\s+|is\\s+)?(.+)$|" +
            "^this\\s+(?:place|spot)\\s+is\\s+(?:called\\s+)?(.+)$"
    )
    private val MEANS = Regex(
        "^(.+?)\\s+(?:means|tells me|reminds me(?:\\s+of|\\s+that)?|is how i know|is my cue that|is the sign that)\\s+(?:that\\s+)?(.+)$"
    )
    private val WHEN = Regex(
        "^when\\s+(?:i|you)\\s+(hear|smell|feel|reach|pass|cross|get to)\\s+(.+?)\\s*(?:then\\s+)?\\b(i'?m|i am|it'?s|that'?s|we'?re|i know)\\s+(.+)$"
    )
    private val WHERE = Regex(
        "^(.+?)\\s+(?:is\\s+)?(?:the\\s+(?:place|spot|shop)\\s+)?(?:where|when)\\s+(i|we|my\\s+[a-z]+)\\s+(.+)$"
    )
    /** "the place I meet Priya", "the spot where we wait", "where my uncle works": this place, by its habit. */
    private val PLACE_I = Regex("^(?:(?:the\\s+)?(?:place|spot)\\s+(?:where\\s+)?|where\\s+)(i|we|my\\s+[a-z]+)\\s+(.+)$")
    private val TOWARDS = Regex("\\b(?:near|close to|next to|almost at|nearly at|reaching|at|by|of|to)\\s+(?:the\\s+)?(.+)$")
    private val POSSESSIVE = Regex("\\b([a-z]+)'s\\b")
    private val MEET = Regex("\\b(?:meet|see|visit|wait for|pick up)\\s+([a-z]+)\\b")
    private val NOT_PEOPLE = setOf(
        "it", "that", "there", "here", "what", "who", "one", "today", "everyone", "someone", "let", "he", "she",
        "my", "the", "this", "where", "india", "god", "children", "people"
    )
    private val RELATIONS = setOf(
        "mom", "mum", "mother", "dad", "father", "brother", "sister", "uncle", "aunt", "aunty", "friend", "wife",
        "husband", "son", "daughter", "grandma", "grandpa", "cousin", "teacher", "doctor", "boss"
    )
    private val BOOKING = Regex("^(?:take|drop|book|get|go|bring|send)\\s+")
    /** "Save this route as Monday errands" names a saved cab route, not a note. */
    private val SAVED_ROUTE = Regex("^(?:please\\s+)?(?:save|call|name|remember)\\s+(?:this|it|that|the)\\s+(?:route|trip|ride|walk)\\b")

    /**
     * @param requireLead true where a destination may be said (the booking question): there,
     *   "the mall where I bought shoes" is a place to go, so only "remember …" makes it an anchor.
     */
    fun parse(spoken: String, requireLead: Boolean = false): Result? {
        val raw = norm(spoken)
        if (raw.isBlank() || BOOKING.containsMatchIn(raw) || SAVED_ROUTE.containsMatchIn(raw)) return null
        val hadLead = LEAD.containsMatchIn(raw)
        var t = raw.replace(LEAD, "")

        // "this is ravi's tea shop", "remember this as the place i meet priya"
        HERE.find(raw)?.let { m ->
            val rest = m.groupValues.drop(1).first { it.isNotBlank() }
            if (rest.trim() in setOf("place", "spot", "one", "please")) return null
            val inner = parse("remember $rest") as? Result.Found
            // "remember this as the temple bell": still a sound, even said as a name for here.
            val sensed = LandmarkParser.parse(rest) as? LandmarkParser.Result.Found
            val base = inner?.anchor ?: Anchor(
                subject = cleanSubject(if (sensed != null && sensed.kind != LandmarkKind.OTHER) sensed.text else rest),
                sense = sensed?.kind?.takeIf { it != LandmarkKind.OTHER },
                side = sensed?.side ?: Side.NONE
            )
            if (base.subject.isBlank() && base.habit.isBlank()) return null
            val placeHabit = PLACE_I.find(rest)
            return Result.Found(
                base.copy(
                    subject = if (placeHabit != null) "" else base.subject,
                    here = true,
                    habit = placeHabit?.let { second("${it.groupValues[1]} ${it.groupValues[2]}") } ?: base.habit,
                    person = base.person.ifBlank { personIn(rest) },
                    words = raw
                )
            )
        }

        var subject: String
        var habit = ""
        var meaning = ""
        var heardBy: LandmarkKind? = null
        when {
            WHEN.find(t) != null -> {
                val m = WHEN.find(t)!!
                subject = m.groupValues[2]
                heardBy = when (m.groupValues[1]) {
                    "hear" -> LandmarkKind.SOUND
                    "smell" -> LandmarkKind.SMELL
                    "feel" -> LandmarkKind.TEXTURE
                    else -> null
                }
                meaning = second(if (m.groupValues[3] == "i know") m.groupValues[4] else "${m.groupValues[3]} ${m.groupValues[4]}")
            }
            MEANS.find(t) != null -> {
                val m = MEANS.find(t)!!
                subject = m.groupValues[1]
                meaning = second(m.groupValues[2])
            }
            WHERE.find(t) != null && (hadLead || !requireLead) -> {
                val m = WHERE.find(t)!!
                subject = m.groupValues[1]
                habit = second("${m.groupValues[2]} ${m.groupValues[3]}")
            }
            hadLead -> {
                // "remember ravi's tea shop" — a name with a person in it is still worth keeping.
                subject = t
                if (POSSESSIVE.find(t)?.groupValues?.get(1)?.let { it in NOT_PEOPLE } != false) return null
            }
            else -> return null
        }
        subject = subject.replace(Regex(",$"), "").trim()

        var sense: LandmarkKind? = null
        var side = Side.NONE
        when (val lp = LandmarkParser.parse(subject)) {
            is LandmarkParser.Result.Found -> {
                sense = lp.kind.takeIf { it != LandmarkKind.OTHER }
                side = lp.side
                if (sense != null) subject = lp.text
            }
            is LandmarkParser.Result.VisualOnly -> if (POSSESSIVE.find(subject) == null) return Result.VisualOnly(lp.text)
            else -> Unit
        }
        subject = cleanSubject(subject)
        if (subject.isBlank()) return null
        if (sense == null && heardBy != null) {
            sense = heardBy
            if (heardBy == LandmarkKind.SMELL && !subject.contains("smell")) subject = "$subject smell"
            if (heardBy == LandmarkKind.SOUND && LandmarkParser.KINDS[1].second.find(subject) == null) subject = "$subject sound"
        }

        val relatesTo = TOWARDS.find(meaning)?.groupValues?.get(1)
            ?.replace(Regex("^(?:your|the)\\s+"), "")
            ?.replace(Regex("\\s+(?:now|soon|then)$"), "")
            ?.trim().orEmpty()

        return Result.Found(
            Anchor(
                subject = subject,
                person = personIn(subject).ifBlank { personIn(habit) }.ifBlank { personIn(meaning) },
                habit = habit,
                meaning = meaning,
                relatesTo = relatesTo,
                sense = sense,
                side = side,
                words = raw
            )
        )
    }

    /** "ravi's tea shop" → "Ravi"; "my uncle's shop" → "your uncle"; "where you meet priya" → "Priya". */
    fun personIn(s: String): String {
        POSSESSIVE.findAll(s).map { it.groupValues[1] }.firstOrNull { it !in NOT_PEOPLE }?.let { w ->
            return if (w in RELATIONS) "your $w" else w.replaceFirstChar { it.uppercase() }
        }
        MEET.find(s)?.groupValues?.get(1)?.takeIf { it !in NOT_PEOPLE && it !in setOf("you", "him", "her", "them", "a", "the", "my", "your") }?.let { w ->
            return if (w in RELATIONS) "your $w" else w.replaceFirstChar { it.uppercase() }
        }
        return ""
    }

    /** First person → second person, so the rider hears their own memory said back to them. */
    fun second(s: String): String = norm(s).split(' ').joinToString(" ") { w ->
        when (w) {
            "i" -> "you"
            "i'm", "im" -> "you're"
            "i've" -> "you've"
            "i'll" -> "you'll"
            "i'd" -> "you'd"
            "me" -> "you"
            "my" -> "your"
            "mine" -> "yours"
            "myself" -> "yourself"
            "we" -> "you"
            "we're" -> "you're"
            "our" -> "your"
            "us" -> "you"
            else -> w
        }
    }.trim()

    /** Second person back to the rider's own words: "where you meet Priya" → "where I meet Priya". */
    fun firstPerson(s: String): String = s.split(' ').joinToString(" ") { w ->
        when (w.lowercase()) {
            "you" -> "I"
            "you're" -> "I'm"
            "you've" -> "I've"
            "you'll" -> "I'll"
            "your" -> "my"
            "yours" -> "mine"
            "yourself" -> "myself"
            else -> w
        }
    }

    private fun cleanSubject(s: String): String =
        LandmarkParser.clean(LandmarkParser.COLOURS.replace(s, " "))
            .replace(Regex("^(?:this|that|my)\\s+"), "")
            .replace(Regex("\\s+(?:place|spot)$"), "")
            .trim()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
}
