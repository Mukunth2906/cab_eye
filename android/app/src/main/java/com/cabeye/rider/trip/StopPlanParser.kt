package com.cabeye.rider.trip

import com.cabeye.rider.intent.IntentParser
import com.cabeye.rider.state.RideType

/**
 * Understanding multi-stop speech: the trip itself, edits to it, and what the rider says about
 * stops during the ride.
 *
 * The rule throughout is that a single-destination booking must never be mistaken for a
 * multi-stop one. "Take me to Ukkadam bus stop", "drop me at the bus stop at Gandhipuram" and
 * "Fun Republic and back" are all one place; only explicit sequencing words ("then", "after
 * that", "via", "stopping at", "with a stop at") make a route.
 */
object StopPlanParser {

    // =================================================================================
    //  The trip: "pharmacy, then Gandhipuram, then home"
    // =================================================================================

    data class ParsedTrip(val legs: List<PlannedLeg>, val rideType: RideType, val rideTypeWasExplicit: Boolean) {
        /** Everything but the last leg is a stop; the last is the destination. */
        fun toPlan(): TripPlan = TripPlan(legs.dropLast(1), legs.last().copy(kind = null))
    }

    /** "then" family: the next place comes after the previous one. */
    private val SEQUENTIAL = Regex(
        "\\s*(?:,\\s*)?\\b(?:and then|after that|afterwards|followed by|then)\\b\\s*(?:,\\s*)?",
        RegexOption.IGNORE_CASE
    )

    /** "via" family: the next place is a stop on the way to the previous one. */
    private val ON_THE_WAY = Regex(
        "\\s*(?:,\\s*)?\\b(?:via|by way of|stopping (?:at|in|by)|with (?:a|one) (?:quick )?stop (?:at|in|by)|" +
            "(?:and )?(?:make|making) (?:a )?(?:quick )?stop (?:at|in|by)|stop over at|on the way stop (?:at|in|by))\\b\\s*",
        RegexOption.IGNORE_CASE
    )

    private val FIRST = Regex("^\\s*(?:first(?:ly)?|to start with)\\b[,\\s]*", RegexOption.IGNORE_CASE)
    private val ON_THE_WAY_SUFFIX = Regex("\\s*\\b(?:on the way|on my way|en route)\\b\\s*$", RegexOption.IGNORE_CASE)

    /**
     * Parses a whole multi-stop request. Null when it is an ordinary single-destination
     * booking, or names more than [TripPlan.MAX_STOPS] stops (the caller then explains the
     * limit rather than silently dropping places).
     */
    fun parseTrip(text: String): ParsedTrip? {
        val whole = IntentParser.extract(text)
        val tokens = tokenize(text) ?: return null
        val legs = mutableListOf<PlannedLeg>()
        for ((segment, onTheWay) in tokens) {
            val leg = parseLeg(segment) ?: continue
            if (onTheWay && legs.isNotEmpty()) legs.add(legs.size - 1, leg) else legs.add(leg)
        }
        if (legs.size < 2) return null
        return ParsedTrip(legs, whole.rideType, whole.rideTypeWasExplicit)
    }

    /** True when the words name more stops than a ride may have — so the app can say so. */
    fun tooManyStops(text: String): Boolean {
        val tokens = tokenize(text) ?: return false
        return tokens.count { parseLeg(it.first) != null } > TripPlan.MAX_STOPS + 1
    }

    /** Splits on sequencing words; each segment is flagged when it was introduced by "via". */
    private fun tokenize(text: String): List<Pair<String, Boolean>>? {
        val clean = text.trim().replace(ON_THE_WAY_SUFFIX, "")
        val marks = (SEQUENTIAL.findAll(clean).map { it to false } + ON_THE_WAY.findAll(clean).map { it to true })
            .sortedBy { it.first.range.first }
            .toList()
        if (marks.isEmpty()) return null
        val out = mutableListOf<Pair<String, Boolean>>()
        var cursor = 0
        var nextIsOnTheWay = false
        for ((m, viaType) in marks) {
            if (m.range.first < cursor) continue // overlapping matches: the earlier one won
            out += clean.substring(cursor, m.range.first) to nextIsOnTheWay
            cursor = m.range.last + 1
            nextIsOnTheWay = viaType
        }
        out += clean.substring(cursor) to nextIsOnTheWay
        return out.map { (s, v) -> s.replace(FIRST, "") to v }
    }

    // ---- One leg: "wait for me at the pharmacy", "drop my friend at Gandhipuram" -------

    private val WAIT_PHRASE = Regex(
        "\\b(?:and )?(?:(?:please )?wait(?:ing)? (?:for me )?(?:there|for me)|i'?ll come back|i will come back|" +
            "i'?ll be back|i will be back|come back|quick errand|errand)\\b|" +
            "\\bwait(?:ing)? (?:for me )?(?:at|in|near|outside)\\b",
        RegexOption.IGNORE_CASE
    )
    private val DROP_PHRASE = Regex(
        "\\bdrop(?:ping)? (?:off )?(?!me\\b)((?:my |our )?[a-z]+(?: [a-z]+)?) (?:off )?(?:at|in|near|by|outside)\\b",
        RegexOption.IGNORE_CASE
    )
    private val PICKUP_PHRASE = Regex(
        "\\bpick(?:ing)? up ((?:my |our )?[a-z]+(?: [a-z]+)?) (?:from|at|in|near|by|outside)\\b|" +
            "\\bpick (?:up )?((?:my |our )?[a-z]+(?: [a-z]+)?) up (?:from|at|in|near|by)\\b",
        RegexOption.IGNORE_CASE
    )

    /** One segment of a route; null when nothing place-like is left. */
    fun parseLeg(segment: String): PlannedLeg? {
        var text = segment.trim().trim(',', '.')
        var kind: StopKind? = null
        var note = ""

        PICKUP_PHRASE.find(text)?.let { m ->
            kind = StopKind.PICKUP
            note = (m.groups[1]?.value ?: m.groups[2]?.value).orEmpty().trim()
            text = text.removeRange(m.range).trim()
        }
        if (kind == null) DROP_PHRASE.find(text)?.let { m ->
            kind = StopKind.DROP
            note = m.groupValues[1].trim()
            text = text.removeRange(m.range).trim()
        }
        if (kind == null) WAIT_PHRASE.find(text)?.let { m ->
            kind = StopKind.WAIT
            text = text.removeRange(m.range).trim()
        }

        val e = IntentParser.extract(text)
        if (e.destination.isBlank()) return null
        return PlannedLeg(query = e.destination, spoken = e.rawDestination, kind = kind, note = note)
    }

    // =================================================================================
    //  Starting a plan step by step: "I have a few stops"
    // =================================================================================

    private val START_PLAN = Regex(
        "\\b(?:add (?:a |some )?stops?|multiple stops|more than one stop|(?:a )?few stops|several stops|" +
            "two stops|three stops|with stops|multi ?stop|i have (?:some |a few |two |three )?stops)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isPlanStart(text: String): Boolean = START_PLAN.containsMatchIn(text)

    private val DONE_ADDING = Regex(
        "^\\s*(?:no|nope|that'?s (?:all|it)|that is (?:all|it)|done|no more( stops)?|nothing else|finished|go)\\b",
        RegexOption.IGNORE_CASE
    )

    /** "That's all" while collecting stops: the next place named is the destination. */
    fun isDoneAdding(text: String): Boolean = DONE_ADDING.containsMatchIn(text)

    // =================================================================================
    //  "At the pharmacy — should the driver wait, or are you dropping someone?"
    // =================================================================================

    fun kindAnswer(text: String): StopKind? {
        val t = text.lowercase()
        return when {
            Regex("\\bpick(ing)? ?up\\b|\\bjoin|\\bcollect").containsMatchIn(t) -> StopKind.PICKUP
            // Wait before drop: "I'm getting out, wait for me" is the rider's own errand.
            Regex("\\bwait|\\bcome back|\\bbe back|\\berrand|\\bi('m| am|'ll| will) (be )?(getting|get) (off|out)|\\bshop(ping)?\\b").containsMatchIn(t) -> StopKind.WAIT
            Regex("\\bdrop|\\bget(s|ting)? (off|out)\\b|\\bleav(e|ing)\\b|\\bsomeone else\\b").containsMatchIn(t) -> StopKind.DROP
            else -> null
        }
    }

    // =================================================================================
    //  Editing the plan at the read-back
    // =================================================================================

    sealed interface PlanEdit {
        data class Remove(val stop: Int?, val words: String = "") : PlanEdit
        data class Swap(val a: Int, val b: Int) : PlanEdit
        data class Change(val stop: Int, val leg: PlannedLeg) : PlanEdit
        data class Add(val leg: PlannedLeg, val afterStop: Int?) : PlanEdit
        data class SetKind(val stop: Int, val kind: StopKind) : PlanEdit
        data class ChangeDestination(val leg: PlannedLeg) : PlanEdit
        data class SaveAs(val name: String) : PlanEdit
        data object ReadAgain : PlanEdit
    }

    private const val NUM = "(one|two|three|first|second|third|last|1|2|3|to|too|won|tree)"

    private val REMOVE_N = Regex("\\b(?:remove|delete|drop|take out|skip|no)\\b (?:the )?(?:stop (?:number )?$NUM|$NUM stop)\\b", RegexOption.IGNORE_CASE)
    private val REMOVE_NAMED = Regex("\\b(?:remove|delete|take out|skip|don'?t stop at|no need to stop at)\\b (?:the stop at |stop at )?(.+)$", RegexOption.IGNORE_CASE)
    private val SWAP = Regex("\\b(?:swap|switch|exchange)\\b (?:stops? )?$NUM (?:and|with) (?:stop )?$NUM\\b", RegexOption.IGNORE_CASE)
    private val CHANGE = Regex("\\b(?:change|replace|make)\\b (?:the )?(?:stop (?:number )?$NUM|$NUM stop) (?:to|with|into) (.+)$", RegexOption.IGNORE_CASE)
    private val SET_KIND = Regex("\\b(?:at|for|make|set|change) (?:the )?(?:stop (?:number )?$NUM|$NUM stop),? (?:a |to |to a |as )?(.+)$", RegexOption.IGNORE_CASE)
    private val ADD = Regex(
        "\\b(?:add|also stop at|another stop at)\\b (?:a |another )?(?:stop )?(?:at |in |by )?(.+?)" +
            "(?: (after|before) (?:stop )?$NUM| (first|at the start|at the end|last))?$",
        RegexOption.IGNORE_CASE
    )
    private val CHANGE_DEST = Regex("\\b(?:change|make) (?:the |my )?(?:destination|final stop|last place|end) (?:to|into) (.+)$|\\b(?:end|finish) (?:the trip |the ride )?at (.+)$", RegexOption.IGNORE_CASE)
    private val SAVE_AS = Regex("\\b(?:save|call|name|remember)\\b (?:this|it|the route|this route|that)(?: route| trip)? as (.+)$", RegexOption.IGNORE_CASE)
    private val READ_AGAIN = Regex("\\b(?:read (?:it|them|the stops|my stops|that)?(?: again| back)?|repeat|say (?:it|that) again|what are (?:the|my) stops)\\b", RegexOption.IGNORE_CASE)

    /** An edit said at the plan read-back, or null (then yes/no/other is the caller's job). */
    fun planEdit(text: String): PlanEdit? {
        val t = text.trim()
        SAVE_AS.find(t)?.let { return PlanEdit.SaveAs(it.groupValues[1].trim().trim('.', '!')) }
        SWAP.find(t)?.let { m ->
            val a = number(m.groupValues[1]); val b = number(m.groupValues[2])
            if (a != null && b != null) return PlanEdit.Swap(a, b)
        }
        CHANGE_DEST.find(t)?.let { m ->
            val words = m.groupValues[1].ifBlank { m.groupValues[2] }
            parseLeg(words)?.let { return PlanEdit.ChangeDestination(it.copy(kind = null)) }
        }
        CHANGE.find(t)?.let { m ->
            val n = number(m.groupValues[1].ifBlank { m.groupValues[2] })
            val leg = parseLeg(m.groupValues[3])
            if (n != null && leg != null) return PlanEdit.Change(n, leg)
        }
        REMOVE_N.find(t)?.let { m ->
            return PlanEdit.Remove(number(m.groupValues[1].ifBlank { m.groupValues[2] }))
        }
        SET_KIND.find(t)?.let { m ->
            val n = number(m.groupValues[1].ifBlank { m.groupValues[2] })
            val kind = kindAnswer(m.groupValues[3])
            if (n != null && kind != null) return PlanEdit.SetKind(n, kind)
        }
        ADD.find(t)?.let { m ->
            val leg = parseLeg(m.groupValues[1])
            if (leg != null) {
                val after = when {
                    m.groupValues[2].equals("after", true) -> number(m.groupValues[3])
                    m.groupValues[2].equals("before", true) -> number(m.groupValues[3])?.minus(1)
                    m.groupValues[4].lowercase() in setOf("first", "at the start") -> 0
                    else -> null
                }
                return PlanEdit.Add(leg, after)
            }
        }
        REMOVE_NAMED.find(t)?.let { m ->
            val words = m.groupValues[1].trim().trim('.').replace(Regex("^(the|my) ", RegexOption.IGNORE_CASE), "")
            if (words.isNotBlank() && number(words) == null) return PlanEdit.Remove(null, words)
        }
        if (READ_AGAIN.containsMatchIn(t)) return PlanEdit.ReadAgain
        return null
    }

    // =================================================================================
    //  During the ride
    // =================================================================================

    sealed interface TripCommand {
        /** "I'm back" at a WAIT stop: listen for the driver saying the code. */
        data object ImBack : TripCommand
        /** Skip the next (or a numbered) stop. */
        data class Skip(val stop: Int?) : TripCommand
        data object ReadStops : TripCommand
        data class AddStop(val leg: PlannedLeg) : TripCommand
    }

    private val IM_BACK = Regex("\\b(?:i'?m|i am|im) (?:back|in|inside|here)\\b|\\bback in (?:the )?(?:car|auto|cab)\\b|\\bi'?ve got (?:back )?in\\b|\\bgot back in\\b", RegexOption.IGNORE_CASE)
    private val SKIP = Regex("\\b(?:skip|cancel|don'?t need|no need for|forget)\\b (?:the |my )?(?:(next|this) stop|stop (?:number )?$NUM|$NUM stop)\\b", RegexOption.IGNORE_CASE)
    private val READ_STOPS = Regex("\\b(?:my|the|next|remaining) stops?\\b|\\bwhere(?:'?s| is) (?:my|the) next stop\\b|\\bhow many stops\\b", RegexOption.IGNORE_CASE)
    private val ADD_STOP = Regex("\\b(?:add (?:a |another )?stop|also stop|stop also|make (?:a |another )?stop)\\b (?:at |in |by )?(.+)$", RegexOption.IGNORE_CASE)

    fun tripCommand(text: String): TripCommand? {
        val t = text.trim()
        if (IM_BACK.containsMatchIn(t)) return TripCommand.ImBack
        SKIP.find(t)?.let { m ->
            val which = m.groupValues[1].lowercase()
            val n = number(m.groupValues[2].ifBlank { m.groupValues[3] })
            return TripCommand.Skip(if (which == "next" || which == "this") null else n)
        }
        ADD_STOP.find(t)?.let { m -> parseLeg(m.groupValues[1])?.let { return TripCommand.AddStop(it) } }
        if (READ_STOPS.containsMatchIn(t)) return TripCommand.ReadStops
        return null
    }

    // =================================================================================

    /** "two", "second", "2" → 2. "last" → -1 (the caller knows how many there are). */
    fun number(word: String?): Int? = when (word?.trim()?.lowercase()) {
        "one", "first", "1", "won" -> 1
        "two", "second", "2", "to", "too" -> 2
        "three", "third", "3", "tree" -> 3
        "last" -> -1
        else -> null
    }
}
