package com.cabeye.rider.explore

import java.util.Locale

/**
 * What the rider can say to the explore module, and the sentences that open it from anywhere.
 * Pure, so every phrase is a JVM test.
 */
object ExploreCommands {

    sealed interface Command {
        object Describe : Command
        object Read : Command
        object KeepTelling : Command
        object Stop : Command
        data class Find(val target: String) : Command
        data class Name(val name: String) : Command
        object Finish : Command
        object Discard : Command
    }

    private val FIND = Regex("^(?:please )?(?:find|where(?:'s| is)|look for|search for|help me find|show me)\\s+(?:the |a |an |my )?(.+?)\\??$")
    private val NAME = Regex(
        "^(?:this is|this place is|here is|name (?:this|it)(?: place)?(?: as)?|call (?:this|it)(?: place)?|" +
            "remember (?:this|here)(?: place)? as|save (?:this|here) as|mark (?:this|here) as)\\s+(.+)$"
    )
    private val DESCRIBE = Regex(
        "\\b(what(?:'s| is) (?:in front of me|ahead|here|around me|this)|what do you see|what can you see|describe|" +
            "look(?: around)?|tell me what you see|what am i (?:looking at|facing))\\b"
    )
    private val READ = Regex("\\b(read(?: (?:this|it|that|the sign|the text|the board|out))?|what does (?:it|this|the sign) say)\\b")
    private val KEEP = Regex("\\b(keep (?:telling|describing|looking|going)|tell me as i walk|continuous|guide me with the camera|walk mode)\\b")
    private val STOP = Regex("^(?:stop|stop (?:looking|telling me|it)|pause|quiet|be quiet|enough|that'?s enough)$")
    private val FINISH = Regex("^(?:save(?: it| this| and finish)?|finish|finished|done|i'?m done|close|exit|end|that'?s all|stop exploring|close (?:the )?camera)$")
    private val DISCARD = Regex("^(?:discard|don'?t save|cancel|throw it away|forget it)$")

    /** Sentences that open explore from the main screen ("what's in front of me"). */
    private val OPEN = Regex(
        "\\b(explore|exploring|open (?:the )?camera|camera mode|what(?:'s| is) in front of me|what do you see|" +
            "what can you see|look around|describe (?:what'?s )?(?:around|in front)|walk around|be my eyes)\\b"
    )

    fun parse(text: String): Command? {
        val t = norm(text)
        if (t.isEmpty()) return null
        DISCARD.find(t)?.let { return Command.Discard }
        FINISH.find(t)?.let { return Command.Finish }
        STOP.find(t)?.let { return Command.Stop }
        NAME.find(t)?.let { m -> return m.groupValues[1].trim().takeIf { it.isNotBlank() }?.let { Command.Name(original(text, it)) } }
        KEEP.find(t)?.let { return Command.KeepTelling }
        READ.find(t)?.let { return Command.Read }
        FIND.find(t)?.let { m -> return Command.Find(m.groupValues[1].trim()) }
        DESCRIBE.find(t)?.let { return Command.Describe }
        return null
    }

    /** Whether [text], said at the main question, means "open the camera and look". */
    fun opens(text: String): Boolean = OPEN.containsMatchIn(norm(text))

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** The name as the rider said it, with its capitals, when the recogniser kept them. */
    private fun original(said: String, lower: String): String {
        val i = said.lowercase(Locale.ROOT).indexOf(lower)
        return if (i >= 0) said.substring(i, i + lower.length) else lower
    }
}
