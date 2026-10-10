package com.cabeye.rider.walk

/**
 * What the rider says to the walking-route agent.
 *
 * Kept apart from cab booking on purpose: "take me to the clinic" books a cab; "how do I get
 * to the clinic", "guide me to the clinic door" and "record my walk" are about walking. A
 * phrase that matches nothing here goes on to the booking dialogue unchanged.
 */
object WalkCommands {

    sealed interface Command {
        /** "record my walk to the clinic door" — [name] may be blank. */
        data class Record(val name: String) : Command
        /** "how do I get to the clinic" (whole route) or "guide me to the clinic" (part by part). Blank [name] = this place. */
        data class Recall(val name: String, val guided: Boolean) : Command
        data object List : Command
        data class Forget(val name: String) : Command
        data object ForgetAll : Command
        data object Export : Command
    }

    /** Said while being guided, or after a recall. */
    enum class Guide { NEXT, REPEAT, PREVIOUS, MORE_DETAIL, LESS_DETAIL, ARRIVED, STOP }

    /** Said while a walk is being recorded. */
    sealed interface RecordInput {
        data object Finish : RecordInput
        data object Cancel : RecordInput
        data class Turn(val direction: TurnDirection) : RecordInput
        data class Mark(val result: LandmarkParser.Result.Found, val spoken: String) : RecordInput
        data class Visual(val text: String) : RecordInput
    }

    private const val NAME = "(?:the |my |a )?(.+?)"

    private val RECORD = Regex(
        "^(?:please |can you |could you )?(?:record|save|remember|learn|map)\\s+(?:my|this|the|a)\\s+(?:walk|route|way|path)" +
            "(?:\\s+(?:to|from|for|into)\\s+$NAME)?$|" +
            "^(?:start|begin)\\s+recording(?:\\s+(?:my|the|this)\\s+(?:walk|route|way))?(?:\\s+(?:to|for)\\s+$NAME)?$"
    )
    private val GUIDE = Regex(
        "^(?:please |can you |could you )?(?:guide|walk|lead|navigate)\\s+me\\s+(?:to|into)\\s+$NAME$|" +
            "^(?:please )?(?:guide|walk|lead)\\s+me(?:\\s+there)?(?:\\s+from here)?$|" +
            "^(?:start|begin)\\s+(?:the\\s+)?(?:walk|walking route|route guidance)(?:\\s+to\\s+$NAME)?$"
    )
    private val RECALL = Regex(
        "^(?:so |ok |okay )?how (?:do|can|should) (?:i|we) (?:get|walk|go|reach)\\s+(?:to|into)\\s+$NAME(?:\\s+from here)?$|" +
            "^(?:please )?remind me (?:of )?(?:the |my )?(?:route|way|walk|path)\\s+(?:to|into|for)\\s+$NAME$|" +
            "^(?:the |my )?(?:walking )?(?:route|way|walk|directions)\\s+(?:to|into|for)\\s+$NAME$|" +
            "^(?:tell me|read me|give me|preview|practi[cs]e|rehearse|read)\\s+(?:the |my )?(?:walk|route|walking route|way|directions)(?:\\s+(?:to|into|for)\\s+$NAME)?(?:\\s+again)?$|" +
            "^(?:what'?s|what is) (?:the |my )?(?:walk|route|way)\\s+(?:to|into)\\s+$NAME$"
    )
    private val LIST = Regex("^(?:what are |list |read )?(?:my |the )?(?:walking |walk |saved )?(?:routes|walks)(?: do you (?:know|have))?$|^what routes (?:do you know|have you saved|have i saved)$")
    private val FORGET_ALL = Regex("^(?:forget|delete|clear|erase)\\s+(?:all\\s+)?(?:of\\s+)?my\\s+(?:walking\\s+)?(?:routes|walks)$")
    private val FORGET = Regex("^(?:forget|delete|remove|erase)\\s+(?:the |my )?(?:walking )?(?:route|walk|way)\\s+(?:to|for|into)\\s+$NAME$")
    private val EXPORT = Regex("^(?:export|share|send)\\s+(?:my |the )?(?:walking |walk |route )?(?:log|study log|walking data)$")

    fun parse(text: String): Command? {
        val t = norm(text)
        if (t.isBlank()) return null
        EXPORT.find(t)?.let { return Command.Export }
        FORGET_ALL.find(t)?.let { return Command.ForgetAll }
        FORGET.find(t)?.let { return Command.Forget(name(it)) }
        LIST.find(t)?.let { return Command.List }
        RECORD.find(t)?.let { return Command.Record(name(it)) }
        GUIDE.find(t)?.let { return Command.Recall(name(it), guided = true) }
        RECALL.find(t)?.let { return Command.Recall(name(it), guided = false) }
        return null
    }

    fun guide(text: String): Guide? {
        val t = norm(text)
        return when {
            Regex("^(?:i'?m there|i am there|i'?ve arrived|i have arrived|i'?m here|i reached|reached|arrived|found it|i found it|i'?m at the door)$").matches(t) -> Guide.ARRIVED
            Regex("^(?:stop|stop guiding|stop guidance|that'?s enough|enough|end|end guidance|quit|exit|cancel)$").matches(t) -> Guide.STOP
            Regex("^(?:more detail|more details|tell me more|more|slower|in detail|full detail|full details)$").matches(t) -> Guide.MORE_DETAIL
            Regex("^(?:less detail|less details|less|shorter|too much|just the landmarks|only landmarks|brief|keep it short)$").matches(t) -> Guide.LESS_DETAIL
            Regex("^(?:repeat|repeat that|again|say that again|say it again|what|what was that|pardon|sorry)$").matches(t) -> Guide.REPEAT
            Regex("^(?:previous|previous part|back|go back|last part|before that|the part before)$").matches(t) -> Guide.PREVIOUS
            Regex("^(?:next|next part|okay next|ok next|done|got it|continue|go on|i'?m at the turn|turned|i turned|ok|okay|yes)$").matches(t) -> Guide.NEXT
            else -> null
        }
    }

    fun record(text: String): RecordInput? {
        val t = norm(text)
        if (t.isBlank()) return null
        if (Regex("^(?:stop recording|finish(?: recording| the route| the walk)?|done recording|end recording|i'?m there|i am there|i'?ve arrived|i have arrived|i'?m here|i reached|arrived|that'?s the end|this is the end|save it|save the route)$").matches(t)) {
            return RecordInput.Finish
        }
        if (Regex("^(?:cancel|cancel recording|stop and delete|don'?t save|discard|throw it away)$").matches(t)) return RecordInput.Cancel
        return when (val r = LandmarkParser.parse(t)) {
            is LandmarkParser.Result.TurnHere -> RecordInput.Turn(r.direction)
            is LandmarkParser.Result.Found -> RecordInput.Mark(r, t)
            is LandmarkParser.Result.VisualOnly -> RecordInput.Visual(r.text)
            null -> null
        }
    }

    private fun name(m: MatchResult): String = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        .replace(Regex("\\s+(?:please|now|from here|again)$"), "")
        .replace(Regex("^(?:the|my|a)\\s+"), "")
        .trim()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
}
