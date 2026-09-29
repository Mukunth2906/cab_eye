package com.cabeye.rider.dialogue

import com.cabeye.rider.places.Gazetteer

/**
 * What the rider meant when answering "Did you mean A, or B?".
 *
 * Pulled out of the view model so every answer shape is provable by JVM test. The old inline
 * version had two failures a rider could fall into and never climb out of:
 *
 *  - **"None"** contained "one", so "none of them" booked option A.
 *  - **"No" / "neither"** matched nothing, so the app asked "A, or B?" again — forever. There was
 *    no way back to naming the place again short of pressing the screen.
 *
 * Now every answer lands somewhere: a choice, a way back to the previous question, or — when
 * the rider simply named a different place — that place, resolved from scratch.
 */
object ClarifyAnswer {

    enum class Kind {
        /** First option. */
        A,
        /** Second option. */
        B,
        /** "neither", "no", "go back" — return to asking for the destination. */
        NEITHER,
        /** A bare "yes" to a two-way question: which one is still unknown. */
        WHICH,
        /** Nothing recognisable. Re-ask once, then treat it as a new destination. */
        UNCLEAR
    }

    private val FIRST = Regex("\\b(first|first one|one|1|former|top)\\b")
    private val SECOND = Regex("\\b(second|second one|two|2|latter|other|other one|last)\\b")
    private val NEITHER = Regex(
        "\\b(neither|none|no|nope|not (either|both|those|them|these)|wrong|go back|back|start over|something else|different)\\b"
    )
    private val YES = Regex("^\\s*(yes|yeah|yep|yup|correct|right|ok|okay|sure)\\b")

    fun interpret(text: String, optionA: String, optionB: String): Kind {
        val t = Gazetteer.normalise(text)
        if (t.isBlank()) return Kind.UNCLEAR

        val a = Gazetteer.normalise(optionA)
        val b = Gazetteer.normalise(optionB)

        // A place name is the strongest signal and wins over any ordinal around it:
        // "no, Anna Salai" means Anna Salai.
        val saysA = a.isNotBlank() && t.contains(a)
        val saysB = b.isNotBlank() && t.contains(b)
        if (saysA && !saysB) return Kind.A
        if (saysB && !saysA) return Kind.B

        // Distinctive words of each name ("salai" vs "nagar") catch a half-said option.
        val onlyA = distinctive(a, b).any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        val onlyB = distinctive(b, a).any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        if (onlyA && !onlyB) return Kind.A
        if (onlyB && !onlyA) return Kind.B

        if (NEITHER.containsMatchIn(t)) return Kind.NEITHER
        val first = FIRST.containsMatchIn(t)
        val second = SECOND.containsMatchIn(t)
        if (first && !second) return Kind.A
        if (second && !first) return Kind.B
        if (YES.containsMatchIn(t)) return Kind.WHICH
        return Kind.UNCLEAR
    }

    /** Words of [name] that do not appear in [other] and are long enough to mean something. */
    private fun distinctive(name: String, other: String): List<String> {
        val otherWords = other.split(" ").toSet()
        return name.split(" ").filter { it.length >= 4 && it !in otherWords }
    }
}
