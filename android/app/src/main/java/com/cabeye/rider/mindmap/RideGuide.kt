package com.cabeye.rider.mindmap

import com.cabeye.rider.memory.TextSimilarity
import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.LandmarkParser
import kotlin.math.roundToInt

/**
 * The ride, as the rider can sense and remember it.
 *
 * A sighted passenger follows a ride by looking out of the window. A blind rider follows it by
 * what they feel and hear — the climb onto the flyover, the speed bumps by the school, the
 * market noise, the sharp right at the temple. The ride guide lets the rider mark those cues,
 * says them back on the next ride along the same way ("flyover coming up"), names the areas the
 * cab passes through while the way is new, warns a little before arrival so the rider can get
 * ready to step out, and says once, calmly, when the cab is not on the rider's usual way.
 *
 * Like the walking agent it says less as a ride becomes familiar: on the third ride the same way
 * the preview shrinks to one line and area names stop — unless the cab leaves the usual way,
 * when knowing the area matters again.
 *
 * Everything here is pure Kotlin and stays on the phone.
 */

/** One sampled point of a ride. [metres] = distance from the start of the ride. */
data class TracePoint(val lat: Double, val lng: Double, val metres: Double, val t: Long)

/** A ride as it went: sampled points and the areas passed through, in order. */
data class RideTrace(
    val placeKey: String,
    val placeName: String,
    val points: List<TracePoint>,
    val areas: List<String> = emptyList(),
    val recordedAt: Long = 0
) {
    val totalMetres: Double get() = points.lastOrNull()?.metres ?: 0.0
    val path: List<Pair<Double, Double>> get() = points.map { it.lat to it.lng }
    val durationMs: Long get() = if (points.size < 2) 0 else points.last().t - points.first().t
}

/** What the rider says to the ride guide in the cab (besides marking a cue). */
object RideCommands {
    enum class Command { WHERE_AM_I, QUIET, TALK, PREVIEW, UNDO }

    private val WHERE = Regex(
        "^(?:so |ok |okay )?(?:where am i|where are we|where are we now|where is this|which area(?: is this| are we in)?|" +
            "what area(?: is this| are we in)?|what place is this|what road is this|where are we going through|" +
            "how far(?: are we)?(?: from (?:there|home))?|how much (?:longer|further|farther))$"
    )
    private val QUIET = Regex(
        "^(?:quiet ride|quiet please|be quiet|less talking|no announcements|stop (?:the )?(?:announcements|cues|area names|guiding the ride))$"
    )
    private val TALK = Regex(
        "^(?:talk to me|announce again|turn on announcements|resume announcements|guide my ride|tell me the (?:areas|cues)(?: again)?)$"
    )
    private val PREVIEW = Regex(
        "^(?:tell me the (?:ride|way)|what'?s on the way|preview the ride|how do we usually go|which way do we usually go)$"
    )
    private val UNDO = Regex(
        "^(?:no |oops )?(?:undo|undo that|forget that|delete that|remove that|scratch that|that'?s wrong|not that|wrong one|cancel that cue)$"
    )

    fun parse(text: String): Command? {
        val t = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            UNDO.matches(t) -> Command.UNDO
            WHERE.matches(t) -> Command.WHERE_AM_I
            QUIET.matches(t) -> Command.QUIET
            TALK.matches(t) -> Command.TALK
            PREVIEW.matches(t) -> Command.PREVIEW
            else -> null
        }
    }
}

/** The rider's words in the cab → a ride cue. */
object RideCueParser {

    sealed interface Result {
        data class Cue(val label: String, val kind: LandmarkKind) : Result
        data class VisualOnly(val text: String) : Result
    }

    private val RIDE = Regex(
        "\\b(speed (?:bump|breaker|hump)s?|bumps?|humps?|potholes?|bumpy|rough road|flyover|bridge|overpass|underpass|tunnel|" +
            "signal|traffic lights?|roundabout|circle|junction|railway (?:gate|crossing)|level crossing|" +
            "sharp (?:left|right)|(?:left|right) turn|u ?turn|climb|slope|uphill|downhill|" +
            "market|temple|church|mosque|school|bus stand|bus stop|toll|petrol (?:bunk|pump)|honking|traffic|" +
            "construction|railway|river|canal|lake)\\b"
    )

    /**
     * Words in front of the cue that are not the cue: "okay that was the flyover", "we just went
     * over a bump", "I can hear the market". Whole words only — "overpass" keeps its "over".
     */
    private val FILLER = setOf(
        "okay", "ok", "so", "now", "and", "mark", "note", "remember", "cue", "landmark", "that", "this", "here", "there",
        "that's", "thats", "there's", "theres", "it's", "its", "was", "is", "we", "we're", "were", "we've", "are", "just",
        "on", "at", "over", "onto", "crossing", "passing", "passed", "crossed", "went", "going", "go", "up", "down",
        "i", "i'm", "can", "feel", "hear", "smell", "felt", "heard", "a", "an", "the", "some", "another"
    )

    /** A question ("how far is the school?") or a negation ("I can't hear you") is not a cue. */
    private val QUESTION = Regex(
        "^(?:how|what|what'?s|when|where|why|who|which|is|are|can|could|will|would|do|does|did|should|tell me)\\b|" +
            "\\b(?:[a-z]+n't|cannot|not)\\b"
    )
    private const val MAX_WORDS = 8

    fun parse(spoken: String): Result? {
        val t = spoken.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isBlank() || QUESTION.containsMatchIn(t) || t.split(' ').size > MAX_WORDS) return null
        val ride = RIDE.find(t)
        val walk = LandmarkParser.parse(t)
        if (ride == null) {
            return when (walk) {
                is LandmarkParser.Result.Found -> Result.Cue(walk.text, walk.kind)
                is LandmarkParser.Result.TurnHere ->
                    Result.Cue("${walk.direction.spoken.removePrefix("turn ").removePrefix("bear ")} turn", LandmarkKind.OTHER)
                is LandmarkParser.Result.VisualOnly -> Result.VisualOnly(walk.text)
                null -> null
            }
        }
        val words = LandmarkParser.COLOURS.replace(t, " ").split(' ').filter { it.isNotBlank() }
        val label = words.dropWhile { it in FILLER }.joinToString(" ").ifBlank { ride.value }
        return Result.Cue(label, kindOf(ride.value, (walk as? LandmarkParser.Result.Found)?.kind))
    }

    private fun kindOf(word: String, walkKind: LandmarkKind?): LandmarkKind = when {
        Regex("bump|hump|pothole|bumpy|rough").containsMatchIn(word) -> LandmarkKind.TEXTURE
        Regex("flyover|bridge|overpass|climb|slope|uphill|downhill|underpass|tunnel").containsMatchIn(word) -> LandmarkKind.RAMP
        Regex("signal|traffic light|junction|roundabout|circle|crossing|gate").containsMatchIn(word) -> LandmarkKind.CROSSING
        Regex("market|honking|traffic|construction").containsMatchIn(word) -> LandmarkKind.SOUND
        walkKind != null -> walkKind
        else -> LandmarkKind.OTHER
    }
}

/**
 * One ride's guide. Created when the trip starts, fed GPS fixes and area names, asked
 * "where am I", and turned into a [RideTrace] when the ride ends.
 *
 * @param usual earlier traces of rides to the same place
 * @param cues the rider's ride cues for this destination (RIDE_CUE nodes)
 * @param destLat where the ride ends, when known — for "almost there"
 */
class RideGuideAgent(
    val placeKey: String,
    val placeName: String,
    usual: List<RideTrace>,
    cues: List<Node>,
    var quiet: Boolean = false,
    private val destLat: Double? = null,
    private val destLng: Double? = null
) {
    enum class Kind { CUE, AREA, DETOUR, BACK_ON_ROUTE, ARRIVING }
    data class Say(val text: String, val kind: Kind)

    sealed interface Marked {
        data class Cue(val node: Node, val spoken: String, val isNew: Boolean) : Marked
        data class VisualOnly(val text: String) : Marked
    }

    companion object {
        const val SAMPLE_M = 50.0
        /** An earlier ride whose path passes this close to where this one starts is "the same way". */
        const val SAME_PATH_M = 500.0
        /** …and one that also started this close was the same whole ride (for distance-only cues). */
        const val SAME_START_M = 1_500.0
        const val CUE_NEAR_M = 150.0
        /** Other cues this close are said in the same sentence. */
        const val CUE_GROUP_M = 350.0
        const val CUE_GAP_MS = 60_000L
        const val AREA_GAP_MS = 120_000L
        const val DETOUR_M = 300.0
        const val BACK_M = 120.0
        /** Fixes in a row off the usual way before saying so: one bad GPS fix is not a detour. */
        const val DETOUR_FIXES = 2
        /** Not before this far into the ride: pickups vary. */
        const val DETOUR_AFTER_M = 400.0
        const val MAX_JUMP_M = 1_000.0
        /** "Almost there" this far (straight line) from the destination. */
        const val ARRIVING_M = 400.0
        /** From this many earlier rides the same way, the ride is familiar: less is said. */
        const val FAMILIAR_RIDES = 3
    }

    private val all = usual.filter { it.placeKey == placeKey && it.points.size >= 2 }
    private var same: List<RideTrace> = emptyList()
    private var sameStart = false
    private val cueNodes = cues.toMutableList()
    private val changedIds = mutableSetOf<String>()
    private val announced = mutableSetOf<String>()
    private val points = mutableListOf<TracePoint>()
    private val areas = mutableListOf<String>()
    private var pendingArea: String? = null
    private var odometer = 0.0
    private var last: TracePoint? = null
    private var lastCueAt = Long.MIN_VALUE / 2
    private var lastAreaAt = Long.MIN_VALUE / 2
    private var offFixes = 0
    private var detourSaid = false
    private var onDetour = false
    private var arrivalSaid = false
    /** The cue marked last on this ride, and what it was before (null when it was new) — for "undo". */
    private var lastMarked: Pair<String, Node?>? = null

    val metres: Double get() = odometer
    val hasUsualWay: Boolean get() = same.isNotEmpty()
    val currentArea: String? get() = areas.lastOrNull()
    /** Ridden this way often enough that area names and the long preview are noise. */
    val familiar: Boolean get() = all.size >= FAMILIAR_RIDES

    /** Said when the trip starts, on a ride the rider has taken before. Null on a first ride. */
    fun preview(): String? {
        val typical = all.maxByOrNull { it.recordedAt } ?: return null
        val minutes = minutes(typical.durationMs)
        val ownCues = cueNodes.sortedBy { it.atMetres ?: 0.0 }.map { MindMapAgent.the(it.label) }.distinct().take(3)
        if (familiar) {
            // The tenth commute does not need the whole story again.
            return if (minutes >= 1) "About $minutes minutes by your usual way." else null
        }
        val via = typical.areas.distinct().filter { !it.equals(placeName, true) }.take(3)
        val parts = mutableListOf<String>()
        parts += "You've ridden to $placeName before" + (if (minutes >= 1) ", about $minutes minutes" else "") +
            (if (via.isNotEmpty()) ", through ${via.joinToString(", then ")}" else "") + "."
        if (ownCues.isNotEmpty()) parts += "Your cues on the way: ${ownCues.joinToString(", then ")}. I'll tell you as they come up."
        return parts.joinToString(" ")
    }

    /**
     * A GPS fix from the rider's own phone. @return what to say now, usually nothing. A detour
     * notice and "almost there" are said even when the rider asked for a quiet ride: those two
     * are about safety and getting out.
     */
    fun onFix(lat: Double, lng: Double, t: Long): List<Say> {
        val prev = last
        if (prev != null) {
            val step = Geo.metres(prev.lat, prev.lng, lat, lng)
            if (step > MAX_JUMP_M && t - prev.t < 30_000) return emptyList() // a GPS jump, not a drive
            odometer += step
        } else {
            // Matched by the path, not only the start: after the app restarts mid-ride, or from
            // a pickup along the usual road, the rest of the way is still the usual way.
            same = all.filter { Geo.metresToPath(lat, lng, it.path) <= SAME_PATH_M }
            sameStart = all.any { Geo.metres(it.points.first().lat, it.points.first().lng, lat, lng) <= SAME_START_M }
        }
        val here = TracePoint(lat, lng, odometer, t)
        last = here
        if (points.isEmpty() || odometer - points.last().metres >= SAMPLE_M) points += here

        val out = mutableListOf<Say>()
        detour(lat, lng)?.let { out += it }
        arriving(lat, lng)?.let { out += it }
        if (!quiet && t - lastCueAt >= CUE_GAP_MS) cueSay(lat, lng, t)?.let { out += it }
        return out
    }

    /**
     * The server's trip meter, for a phone without GPS: distance into the ride is then the only
     * way to place cues, and cues marked by distance are said by distance.
     */
    fun onTripMeters(metresIn: Double, t: Long): List<Say> {
        if (last != null || metresIn <= odometer) return emptyList()
        odometer = metresIn
        if (quiet || t - lastCueAt < CUE_GAP_MS) return emptyList()
        return listOfNotNull(cueSay(Double.NaN, Double.NaN, t))
    }

    /**
     * The area the cab is in now (from the phone's geocoder). Said when it changes and has held
     * for two readings (a boundary road flips between two names); not on a familiar ride unless
     * the cab has left the usual way.
     */
    fun onArea(area: String, t: Long): Say? {
        val name = area.trim()
        if (name.isBlank()) return null
        if (name.equals(areas.lastOrNull(), ignoreCase = true)) {
            pendingArea = null
            return null
        }
        if (areas.isNotEmpty() && !name.equals(pendingArea, ignoreCase = true)) {
            pendingArea = name
            return null
        }
        pendingArea = null
        val first = areas.isEmpty()
        areas += name
        // After "almost there" the rider is getting ready to step out: an area name is noise.
        if (first || quiet || arrivalSaid || (familiar && !onDetour) || t - lastAreaAt < AREA_GAP_MS) return null
        lastAreaAt = t
        return Say("Now in $name.", Kind.AREA)
    }

    /** The cab's last GPS fix, for a note the rider makes about "this place" in the cab. */
    val position: Pair<Double, Double>? get() = last?.let { it.lat to it.lng }

    /** Added to the ordinary status: "You're in Gandhipuram. Usually about 8 minutes from here." */
    fun briefStatus(): String? {
        val parts = mutableListOf<String>()
        currentArea?.let { parts += "You're in $it." }
        last?.let { p -> remainingMinutes(p.lat, p.lng) }?.let { parts += "Usually about $it ${if (it == 1) "minute" else "minutes"} from here." }
        if (onDetour) parts += "This isn't your usual way."
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** "Where am I?" */
    fun whereAmI(tripMeters: Double?): String {
        val parts = mutableListOf<String>()
        val km = tripMeters ?: odometer
        parts += (currentArea?.let { "You're in $it" } ?: "I can't name this area") +
            (if (km >= 100) ", ${Geo.spokenDistance(km).removePrefix("about ")} into the ride." else ", near the start of the ride.")
        val p = last
        if (p != null) {
            val eta = remainingMinutes(p.lat, p.lng)
            if (eta != null) parts += "Usually about $eta ${if (eta == 1) "minute" else "minutes"} from here to $placeName."
            else if (destLat != null && destLng != null) {
                parts += "$placeName is ${Geo.spokenDistance(Geo.metres(p.lat, p.lng, destLat, destLng))} away in a straight line."
            }
            nextCue(p.lat, p.lng)?.let { (cue, d) ->
                parts += "Next you'll notice ${MindMapAgent.the(cue.label)}, in ${Geo.spokenDistance(d).removePrefix("about ")}."
            }
        }
        if (onDetour) parts += "This isn't your usual way."
        return parts.joinToString(" ")
    }

    /** The rider marks a cue: "speed bump", "that was the flyover", "market noise on the left". */
    fun mark(spoken: String, t: Long, newId: String): Marked? {
        val r = RideCueParser.parse(spoken) ?: return null
        if (r is RideCueParser.Result.VisualOnly) return Marked.VisualOnly(r.text)
        r as RideCueParser.Result.Cue
        val p = last
        val existing = cueNodes.firstOrNull { c ->
            TextSimilarity.similarity(c.label, r.label) >= 0.8f && when {
                p != null && c.located -> Geo.metres(c.lat!!, c.lng!!, p.lat, p.lng) <= CUE_NEAR_M * 2
                else -> c.atMetres != null && kotlin.math.abs(c.atMetres - odometer) <= CUE_NEAR_M * 2
            }
        }
        if (existing != null) {
            val confirmed = existing.copy(confirmations = existing.confirmations + 1, misses = 0, lastConfirmedAt = t)
            cueNodes[cueNodes.indexOf(existing)] = confirmed
            changedIds += confirmed.id
            announced += confirmed.id
            lastMarked = confirmed.id to existing
            return Marked.Cue(confirmed, "Yes, ${MindMapAgent.the(r.label)}. I had that one already.", isNew = false)
        }
        val node = Node(
            id = "cue:$newId", kind = NodeKind.RIDE_CUE, label = r.label, lat = p?.lat, lng = p?.lng,
            sense = r.kind, source = placeKey, atMetres = odometer, words = spoken,
            createdAt = t, lastConfirmedAt = t
        )
        cueNodes += node
        changedIds += node.id
        announced += node.id
        lastMarked = node.id to null
        return Marked.Cue(
            node,
            "Noted: ${MindMapAgent.the(r.label)}. I'll mention it next time you ride to $placeName. If that's wrong, say undo.",
            isNew = true
        )
    }

    /** What "undo" did: [removed] a new cue, or put a confirmed one back to [restored]. */
    data class Undone(val removed: Node?, val restored: Node?)

    /** "Undo": the cue just marked on this ride, taken back. Null when there is none. */
    fun undo(): Undone? {
        val (id, before) = lastMarked ?: return null
        lastMarked = null
        val i = cueNodes.indexOfFirst { it.id == id }
        if (i < 0) return null
        return if (before == null) {
            val n = cueNodes.removeAt(i)
            changedIds -= id
            Undone(removed = n, restored = null)
        } else {
            cueNodes[i] = before
            Undone(removed = null, restored = before)
        }
    }

    /** Cues marked or confirmed on this ride, to save. */
    fun changedCues(): List<Node> = cueNodes.filter { it.id in changedIds }

    /** This ride as a trace, or null when too little was recorded to be useful. */
    fun trace(now: Long): RideTrace? {
        val pts = points.toMutableList()
        last?.let { if (pts.lastOrNull() != it) pts += it }
        if (pts.size < 3 || odometer < 300) return null
        return RideTrace(placeKey, placeName, pts, areas.toList(), now)
    }

    // ---- internals ----------------------------------------------------------------------

    private fun cueSay(lat: Double, lng: Double, t: Long): Say? {
        // Cues close together are said together: "the speed bump, then the market noise coming
        // up" — said a minute apart, the second would come after it had passed.
        if (cuesWithin(lat, lng, CUE_NEAR_M).isEmpty()) return null
        val group = cuesWithin(lat, lng, CUE_GROUP_M)
        group.forEach { announced += it.id }
        lastCueAt = t
        val eta = if (lat.isNaN()) null else remainingMinutes(lat, lng)
        return Say(
            group.joinToString(", then ") { MindMapAgent.the(it.label) }.replaceFirstChar { it.uppercase() } +
                " coming up." + (eta?.let { " Usually about $it ${if (it == 1) "minute" else "minutes"} from here to $placeName." } ?: ""),
            Kind.CUE
        )
    }

    /** Cues not said yet within [radius] of here, nearest first. */
    private fun cuesWithin(lat: Double, lng: Double, radius: Double): List<Node> =
        cueNodes.filter { it.id !in announced }.mapNotNull { c ->
            val d = when {
                c.located && !lat.isNaN() -> Geo.metres(lat, lng, c.lat!!, c.lng!!)
                // A cue marked without GPS is placed by distance into the ride: only on a ride
                // that started where that one did, or when there is no GPS at all.
                c.atMetres != null && !c.located && (sameStart || last == null) -> (c.atMetres - odometer).takeIf { it >= 0 }
                else -> null
            }
            d?.takeIf { it <= radius }?.let { c to it }
        }.sortedBy { it.second }.map { it.first }

    private fun nextCue(lat: Double, lng: Double): Pair<Node, Double>? {
        val trace = same.minByOrNull { Geo.metresToPath(lat, lng, it.path) } ?: return null
        val at = nearest(trace, lat, lng)?.metres ?: return null
        return cueNodes.filter { it.id !in announced && it.located }.mapNotNull { c ->
            val cueAt = nearest(trace, c.lat!!, c.lng!!) ?: return@mapNotNull null
            if (Geo.metres(c.lat, c.lng, cueAt.lat, cueAt.lng) > CUE_NEAR_M * 2) return@mapNotNull null // not on this way
            val ahead = cueAt.metres - at
            if (ahead > 0) c to ahead else null
        }.minByOrNull { it.second }
    }

    private fun remainingMinutes(lat: Double, lng: Double): Int? {
        val trace = same.filter { Geo.metresToPath(lat, lng, it.path) <= DETOUR_M }
            .minByOrNull { Geo.metresToPath(lat, lng, it.path) } ?: return null
        val at = nearest(trace, lat, lng) ?: return null
        val m = minutes(trace.points.last().t - at.t)
        return m.takeIf { it >= 1 }
    }

    private fun nearest(trace: RideTrace, lat: Double, lng: Double): TracePoint? =
        trace.points.minByOrNull { Geo.metres(lat, lng, it.lat, it.lng) }

    private fun arriving(lat: Double, lng: Double): Say? {
        if (arrivalSaid || destLat == null || destLng == null || odometer < 300) return null
        val d = Geo.metres(lat, lng, destLat, destLng)
        if (d > ARRIVING_M) return null
        arrivalSaid = true
        return Say("Almost there: $placeName is ${Geo.spokenDistance(d)} away.", Kind.ARRIVING)
    }

    private fun detour(lat: Double, lng: Double): Say? {
        if (same.isEmpty() || odometer < DETOUR_AFTER_M) return null
        val off = same.minOf { Geo.metresToPath(lat, lng, it.path) }
        if (off > DETOUR_M) {
            offFixes++
            if (offFixes >= DETOUR_FIXES && !onDetour) {
                onDetour = true
                lastAreaAt = Long.MIN_VALUE / 2 // off the usual way, the next area name matters
                if (!detourSaid) {
                    detourSaid = true
                    return Say(
                        "This isn't your usual way to $placeName. It may just be a detour for traffic. " +
                            "If anything feels wrong, press and hold the S O S button for two seconds.",
                        Kind.DETOUR
                    )
                }
            }
        } else {
            offFixes = 0
            if (onDetour && off <= BACK_M) {
                onDetour = false
                return Say("You're back on your usual way.", Kind.BACK_ON_ROUTE)
            }
        }
        return null
    }

    private fun minutes(ms: Long) = (ms / 60_000.0).roundToInt()
}
