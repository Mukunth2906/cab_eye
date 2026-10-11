package com.cabeye.rider.mindmap

/**
 * What the rider says to the mind map.
 *
 * Checked after the walking-route commands ("remember my walk" records a walk) and before cab
 * booking, so "what's around the clinic" is never booked as a ride. A phrase that matches
 * nothing here goes on unchanged.
 */
object MindMapCommands {

    sealed interface Command {
        /** "what's around the clinic" — blank [name] = around here. */
        data class Around(val name: String) : Command
        /** "what's famous around here", "best places near me". */
        data object Nearby : Command
        /** "what do I know about Ravi's shop" */
        data class About(val name: String) : Command
        /** "remember Ravi's tea shop, where I stop on Fridays" */
        data class Remember(val anchor: AnchorParser.Anchor) : Command
        /** "the red sign means turn left": refused, with the reason. */
        data class VisualOnly(val text: String) : Command
        /** "forget the note about Ravi's shop" */
        data class Forget(val name: String) : Command
        data object ForgetAll : Command
        data object Summary : Command
        /** "stop suggesting places" / "suggest places again" */
        data class Suggestions(val on: Boolean) : Command
    }

    private const val NAME = "(?:the |my |a )?(.+?)"

    private val NEARBY = Regex(
        "^(?:what|what'?s|what is|what are|tell me|show me|any|are there|is there|find)\\b.*\\b(?:famous|popular|best|well known|interesting|top|good|nice)\\b" +
            ".*\\b(?:places?|spots?|around|near|nearby|here|to see|to visit)$|" +
            "^(?:suggest|recommend)\\s+(?:some\\s+)?(?:good\\s+)?(?:places?|somewhere)(?:\\s+(?:near|around)\\s+(?:here|me))?$|" +
            "^(?:what'?s|what is) nearby$"
    )
    private val AROUND = Regex(
        "^(?:what(?:'?s| is| do i know| do you know)|tell me (?:what'?s|what is)|describe (?:the area|what'?s))\\s+" +
            "(?:around|near|close to|next to|by)\\s+$NAME$|" +
            "^(?:tell me about|describe) the (?:area|surroundings|place) (?:around|near) $NAME$|" +
            "^(?:what'?s|what is) (?:around|near) (?:here|me)$|^where am i walking$|^describe (?:my )?surroundings$"
    )
    private val ABOUT = Regex(
        "^(?:what do|what did) (?:you|i) (?:know|remember|tell you|say) about\\s+$NAME$|" +
            "^what have i told you about\\s+$NAME$|^what does\\s+$NAME\\s+mean(?: to me)?$"
    )
    private val FORGET_ALL = Regex("^(?:forget|delete|clear|erase)\\s+(?:all\\s+)?(?:of\\s+)?my\\s+(?:mind map|notes|personal notes)$")
    private val FORGET = Regex(
        "^(?:forget|delete|remove|erase)\\s+(?:the |my )?(?:note|notes|memory)\\s+(?:about|for|on)\\s+$NAME$|" +
            "^(?:forget|delete) what i (?:said|told you) about\\s+$NAME$"
    )
    // "what places do you know" stays with the booking dialogue, which lists the rider's places.
    private val SUMMARY = Regex(
        "^(?:read |tell me |what'?s in |what is in )?(?:my |the )?mind ?map$|^what do you know about me$"
    )
    private val SUGGEST_OFF = Regex("^(?:stop|don'?t|no more)\\s+(?:suggesting|suggest|recommending)(?:\\s+(?:places|nearby places))?$|^no more (?:place )?suggestions$")
    private val SUGGEST_ON = Regex("^(?:suggest|start suggesting|turn on)\\s+(?:nearby )?(?:places|suggestions)\\s+again$|^(?:start|turn on)\\s+(?:place )?suggestions$")

    /**
     * @param requireLead pass true at the booking question, where "the mall where I bought shoes"
     *   is a destination — only "remember …" makes it a personal note there
     */
    fun parse(text: String, requireLead: Boolean = false): Command? {
        val t = norm(text)
        if (t.isBlank()) return null
        if (SUGGEST_OFF.matches(t)) return Command.Suggestions(on = false)
        if (SUGGEST_ON.matches(t)) return Command.Suggestions(on = true)
        if (FORGET_ALL.matches(t)) return Command.ForgetAll
        FORGET.find(t)?.let { return Command.Forget(name(it)) }
        if (SUMMARY.matches(t)) return Command.Summary
        if (NEARBY.matches(t)) return Command.Nearby
        AROUND.find(t)?.let { m ->
            val n = name(m)
            return Command.Around(if (n in setOf("here", "me", "this place", "this")) "" else n)
        }
        ABOUT.find(t)?.let { return Command.About(name(it)) }
        return when (val a = AnchorParser.parse(t, requireLead)) {
            is AnchorParser.Result.Found -> Command.Remember(a.anchor)
            is AnchorParser.Result.VisualOnly -> Command.VisualOnly(a.text)
            null -> null
        }
    }

    private fun name(m: MatchResult): String = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        .replace(Regex("\\s+(?:please|now)$"), "")
        .replace(Regex("^(?:the|my|a)\\s+"), "")
        .trim()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
}
