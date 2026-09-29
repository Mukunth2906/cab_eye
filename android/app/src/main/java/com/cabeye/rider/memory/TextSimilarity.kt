package com.cabeye.rider.memory

import kotlin.math.max
import kotlin.math.min

/**
 * How alike a misheard phrase is to a place the rider has been before.
 *
 * On-device recognition mangles Indian place names in a few predictable ways, and each has its
 * own measure here:
 *
 *  - **Spelling drift** ("Brook fields" / "Brookefields") — Jaro-Winkler on the whole string.
 *  - **Half the name** ("PSG" for "PSG College of Technology") — token overlap, with generic
 *    words like "college" or "road" counting for little, since every city has dozens.
 *  - **Sound-alikes** ("Peelameedu" / "Peelamedu", "Gandipuram" / "Gandhipuram") — a phonetic
 *    key tuned for Indian-English: aspirates folded (dh→d, bh→b), vowels dropped after the first.
 *  - **Spoken acronyms** ("piece g", "pee ess gee" for PSG) — letter names read back as letters.
 *
 * All of it is pure Kotlin and covered by JVM tests.
 */
object TextSimilarity {

    /** Words that name a *kind* of place. Matching one says little about *which* place. */
    val GENERIC = setOf(
        "college", "school", "hospital", "road", "street", "st", "mall", "station", "bus", "stand",
        "stop", "temple", "church", "mosque", "market", "nagar", "colony", "layout", "park", "office",
        "hotel", "university", "institute", "technology", "of", "the", "and", "junction", "main",
        "cross", "railway", "airport", "house", "home", "block", "gate", "north", "south", "east", "west",
        "new", "old", "near", "opposite", "city", "centre", "center", "complex", "tower", "towers"
    )

    /** Letter names as a recogniser writes them, so "piece g" can become "psg". */
    private val LETTER_WORDS = mapOf(
        "a" to "a", "ay" to "a", "b" to "b", "bee" to "b", "be" to "b", "c" to "c", "see" to "c", "sea" to "c",
        "d" to "d", "dee" to "d", "e" to "e", "ee" to "e", "f" to "f", "ef" to "f", "eff" to "f",
        "g" to "g", "gee" to "g", "ji" to "g", "jee" to "g", "h" to "h", "aitch" to "h", "edge" to "h",
        "i" to "i", "eye" to "i", "j" to "j", "jay" to "j", "k" to "k", "kay" to "k", "l" to "l", "el" to "l",
        "m" to "m", "em" to "m", "n" to "n", "en" to "n", "o" to "o", "oh" to "o", "p" to "p", "pee" to "p",
        "pea" to "p", "q" to "q", "cue" to "q", "queue" to "q", "r" to "r", "are" to "r", "s" to "s",
        "es" to "s", "ess" to "s", "t" to "t", "tee" to "t", "tea" to "t", "u" to "u", "you" to "u",
        "v" to "v", "vee" to "v", "w" to "w", "x" to "x", "ex" to "x", "y" to "y", "why" to "y",
        "z" to "z", "zed" to "z", "zee" to "z",
        // Two letters fused into one word by the recogniser.
        "piece" to "ps", "peace" to "ps", "pieces" to "ps", "cs" to "cs", "mg" to "mg",
        "kg" to "kg", "rs" to "rs", "vo" to "vo", "vc" to "vc"
    )

    fun normalise(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * 0..1 — how likely [heard] refers to [target].
     *
     * The maximum of the individual measures, each scaled by how much it can be trusted:
     * an exact acronym or whole-name match can reach 1.0; a sound-alike alone tops out lower.
     */
    fun similarity(heard: String, target: String): Float {
        val h = normalise(heard)
        val t = normalise(target)
        if (h.isEmpty() || t.isEmpty()) return 0f
        if (h == t) return 1f

        val whole = jaroWinkler(h.replace(" ", ""), t.replace(" ", ""))
        val tokens = tokenOverlap(h, t)
        val sound = jaroWinkler(phoneticKey(h), phoneticKey(t))
        val acronym = acronymMatch(h, t)

        val best = maxOf(whole, 0.92f * tokens, 0.85f * sound, acronym).coerceIn(0f, 1f)
        // "college", "mall", "bus stand": words that name a kind of place say almost nothing
        // about which one. Whatever the string measures think, they stay weak evidence.
        return if (isGenericOnly(h)) min(best, GENERIC_CAP) else best
    }

    const val GENERIC_CAP = 0.45f

    /** True when every word is a kind-of-place word. */
    fun isGenericOnly(phrase: String): Boolean {
        val words = normalise(phrase).split(" ").filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { it in GENERIC }
    }

    /**
     * Fraction of the *distinctive* heard words found in the target, generic words counting
     * for 0.3 of a distinctive one. "psg" in "psg college of technology" → 1.0; "college"
     * alone → 0.3.
     */
    fun tokenOverlap(heard: String, target: String): Float {
        val hTokens = heard.split(" ").filter { it.isNotBlank() }
        val tTokens = target.split(" ").filter { it.isNotBlank() }
        if (hTokens.isEmpty() || tTokens.isEmpty()) return 0f
        var total = 0f
        var matched = 0f
        for (w in hTokens) {
            val weight = if (w in GENERIC) 0.3f else 1f
            total += weight
            val best = tTokens.maxOf { jaroWinkler(w, it) }
            if (best >= 0.90f) matched += weight
        }
        if (total == 0f) return 0f
        // A match made only of generic words is never strong evidence.
        val distinctiveMatched = hTokens.any { it !in GENERIC && tTokens.any { t -> jaroWinkler(it, t) >= 0.90f } }
        val score = matched / total
        return if (distinctiveMatched) score else min(score, 0.4f)
    }

    /** "piece g" → "psg"; null when the words are not all letter names. */
    fun spokenAcronym(heard: String): String? {
        val words = heard.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 6) return null
        val letters = StringBuilder()
        for (w in words) letters.append(LETTER_WORDS[w] ?: return null)
        return letters.toString().takeIf { it.length in 2..5 }
    }

    private fun acronymMatch(heard: String, target: String): Float {
        // A single short word is tried as an acronym too ("psg"), unless it is an ordinary
        // kind-of-place word ("mall") that merely happens to be short.
        val spoken = spokenAcronym(heard)
            ?: heard.takeIf { it.length in 2..5 && !it.contains(' ') && it !in GENERIC }
            ?: return 0f
        val tTokens = target.split(" ").filter { it.isNotBlank() }
        if (tTokens.any { it == spoken }) return 0.95f
        val initials = tTokens.filter { it !in setOf("of", "the", "and") }.joinToString("") { it.take(1) }
        if (initials.startsWith(spoken) && spoken.length >= 2) return 0.85f
        return 0f
    }

    /** A coarse sound key for Indian-English place names. */
    fun phoneticKey(s: String): String {
        var w = normalise(s).replace(" ", "")
        if (w.isEmpty()) return ""
        val pairs = listOf(
            "ph" to "f", "th" to "t", "dh" to "d", "kh" to "k", "gh" to "g", "bh" to "b", "sh" to "s",
            "ch" to "c", "zh" to "l", "ck" to "k", "ee" to "i", "oo" to "u", "aa" to "a", "w" to "v",
            "z" to "s", "q" to "k", "x" to "ks"
        )
        for ((from, to) in pairs) w = w.replace(from, to)
        w = w.replace('c', 'k').replace('j', 'g')
        val first = w.first()
        val rest = w.drop(1).filter { it !in "aeiouyh" }
        val out = StringBuilder().append(first)
        for (ch in rest) if (out.last() != ch) out.append(ch)
        return out.toString()
    }

    /** Standard Jaro-Winkler, 0..1. */
    fun jaroWinkler(a: String, b: String): Float {
        if (a == b) return if (a.isEmpty()) 0f else 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val range = max(0, max(a.length, b.length) / 2 - 1)
        val aMatch = BooleanArray(a.length)
        val bMatch = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val lo = max(0, i - range)
            val hi = min(b.length - 1, i + range)
            for (j in lo..hi) {
                if (bMatch[j] || a[i] != b[j]) continue
                aMatch[i] = true; bMatch[j] = true; matches++; break
            }
        }
        if (matches == 0) return 0f
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatch[i]) continue
            while (!bMatch[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toFloat()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2f) / m) / 3f
        var prefix = 0
        while (prefix < min(4, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1f * (1 - jaro)
    }
}
