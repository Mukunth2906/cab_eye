package com.cabeye.rider.memory

import java.time.DayOfWeek
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * The memory agent's second skill: **routes**, not just places.
 *
 * A rider who goes pharmacy → office most Monday mornings should hear one question, not three:
 * "It's Monday morning. Your usual route: Apollo Pharmacy, then PSG College. Shall I book it?"
 *
 * Same rules as [PreferenceMemoryAgent] — the gist's memory-agent rules apply unchanged:
 *  - rule- and score-based, no language model; every factor is explainable;
 *  - learned only from the rider's own completed trips (stops actually visited, in order);
 *  - a habit needs [PreferenceMemoryAgent.MIN_SUPPORT] matching trips around this time on this
 *    kind of day, and must clear the same recalibrated proactive threshold;
 *  - it only proposes: a yes goes through the normal read-back and 5-second cancel window;
 *  - every suggestion says why ("like most Monday mornings");
 *  - a route the rider turns down is not offered again in this session; "forget my history"
 *    empties everything it reasons over.
 *
 * Named routes ("Monday errands") are different: the rider named them, so they are booked when
 * asked for by name, never suggested unprompted.
 *
 * Pure Kotlin — time, zone and location come in through [PreferenceMemoryAgent.Moment].
 */
object RoutineAgent {

    data class Routine(
        /** Stops in order, as visited. */
        val stops: List<TripStop>,
        val destinationKey: String,
        val destinationName: String,
        /** Matching past trips. */
        val support: Int,
        val confidence: Float,
        /** "like most Monday mornings" */
        val reason: String
    ) {
        val signature: String get() = signature(stops, destinationKey)

        /** "Apollo Pharmacy, then PSG College of Technology" */
        fun spokenRoute(): String = (stops.map { it.name } + destinationName).joinToString(", then ")
    }

    fun signature(stops: List<TripStop>, destinationKey: String): String =
        (stops.map { "${it.placeKey}#${it.kind}" } + destinationKey).joinToString(" > ")

    /**
     * The habitual route for this moment, when the memory is sure enough — or null.
     *
     * score = share × saturation(support) × recency × rider's verdicts on the
     * destination, with the same meaning as [PreferenceMemoryAgent.scoreByTime]. "Share" is out
     * of every trip around this time (plain ones included) — either on this kind of day, or on
     * this very weekday when that is the stronger habit — so a route must beat the rider's
     * single-destination habits fairly, not just exist.
     */
    fun proactive(
        memory: RiderMemory,
        moment: PreferenceMemoryAgent.Moment,
        thresholds: PreferenceMemoryAgent.Thresholds,
        rejectedSignatures: Set<String> = emptySet()
    ): Routine? {
        val window = memory.trips.filter { t ->
            val local = Instant.ofEpochMilli(t.bookedAt).atZone(moment.zone)
            val minute = local.hour * 60 + local.minute
            circularMinutes(minute, moment.minuteOfDay) <= PreferenceMemoryAgent.TIME_WINDOW_MIN &&
                (local.dayOfWeek.value >= 6) == moment.isWeekend
        }
        if (window.size < PreferenceMemoryAgent.MIN_SUPPORT) return null

        val candidates = window
            .filter { it.stops.isNotEmpty() }
            .groupBy { signature(it.stops, it.placeKey) }
            .filter { (sig, trips) -> trips.size >= PreferenceMemoryAgent.MIN_SUPPORT && sig !in rejectedSignatures }

        var best: Routine? = null
        for ((_, trips) in candidates) {
            val latest = trips.maxBy { it.bookedAt }
            val destination = memory.places.firstOrNull { it.placeKey == latest.placeKey }
            // Standing at the first stop (or the destination) already: never suggest it.
            if (destination != null && isNear(destination, moment)) continue
            val firstStop = memory.places.firstOrNull { it.placeKey == latest.stops.first().placeKey }
            if (firstStop != null && isNear(firstStop, moment)) continue
            if (destination != null && destination.rejected >= 3 && destination.accepted == 0) continue

            // Two ways a route is a habit: most trips at this time on this kind of day take it,
            // or most trips at this time on THIS weekday do ("pharmacy first, every Monday").
            // A weekly errand among daily commutes only shows up the second way.
            val support = trips.size
            val dayWindow = window.filter { dayOf(it, moment) == moment.dayOfWeek }
            val sameDay = trips.count { dayOf(it, moment) == moment.dayOfWeek }
            val kindShare = support.toFloat() / window.size
            val dayShare = if (sameDay >= PreferenceMemoryAgent.MIN_SUPPORT && dayWindow.isNotEmpty())
                sameDay.toFloat() / dayWindow.size else 0f
            // Equal shares mean every matching trip was on this weekday: say so ("most Wednesdays").
            val weekdayHabit = dayShare > 0f && dayShare >= kindShare
            val share = if (weekdayHabit) dayShare else kindShare
            val counted = if (weekdayHabit) sameDay else support
            val saturation = counted / (counted + 1f)
            val ageDays = (moment.now - latest.bookedAt) / 86_400_000.0
            val recency = when {
                ageDays <= 14 -> 1f
                ageDays <= 45 -> 0.85f
                else -> 0.6f
            }
            val verdict = if (destination == null) 1f
            else min(1f, 0.5f + (destination.accepted + 1f) / (destination.accepted + destination.rejected + 2f))

            val score = share * saturation * recency * verdict
            if (best == null || score > best.confidence) {
                best = Routine(
                    stops = latest.stops,
                    destinationKey = latest.placeKey,
                    destinationName = latest.destination.ifBlank { destination?.name.orEmpty() },
                    support = support,
                    confidence = score,
                    reason = if (weekdayHabit) "like most ${dayName(moment.dayOfWeek)} ${partOfDay(moment.hour)}"
                    else PreferenceMemoryAgent.timeReason(moment)
                )
            }
        }
        return best?.takeIf { it.confidence >= thresholds.proactive }
    }

    /**
     * Which proactive question to ask when both agents have one. A route ending where the
     * single-place suggestion goes is the more specific answer to the same habit ("pharmacy,
     * then college" refines "college"), so it wins whenever it clears the bar on its own;
     * otherwise the more confident of the two.
     */
    fun preferRoutine(routine: Routine?, single: PreferenceMemoryAgent.Suggestion?): Boolean = when {
        routine == null -> false
        single == null -> true
        single.place.placeKey == routine.destinationKey -> true
        else -> routine.confidence > single.confidence
    }

    /**
     * A saved route the rider asked for by name: "book Monday errands", "my Monday errands".
     * Null unless one route clearly matches.
     */
    fun named(heard: String, routes: List<SavedRoute>): SavedRoute? {
        if (routes.isEmpty()) return null
        val words = TextSimilarity.normalise(heard)
            .replace(Regex("^(please )?(book|take me on|start|do|run|go on|let'?s do)( my| the)?\\s*"), "")
            .replace(Regex("^(my|the) "), "")
            .replace(Regex("\\s*(route|trip|run)$"), "")
            .trim()
        if (words.isBlank()) return null
        val scored = routes.map { it to TextSimilarity.similarity(words, it.name) }.sortedByDescending { it.second }
        val top = scored.first()
        if (top.second < NAMED_SIMILARITY) return null
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && runnerUp.second >= NAMED_SIMILARITY && top.second - runnerUp.second < 0.05f) return null
        return top.first
    }

    /** At or above this, heard words are the route's name. */
    const val NAMED_SIMILARITY = 0.85f

    // ---------------------------------------------------------------------------------

    private fun dayOf(t: TripRecord, moment: PreferenceMemoryAgent.Moment): Int =
        Instant.ofEpochMilli(t.bookedAt).atZone(moment.zone).dayOfWeek.value

    fun dayName(isoDay: Int): String =
        DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private fun partOfDay(hour: Int) = when (hour) {
        in 5..11 -> "mornings"
        in 12..16 -> "afternoons"
        in 17..20 -> "evenings"
        else -> "nights"
    }

    private fun isNear(place: VisitedPlace, moment: PreferenceMemoryAgent.Moment): Boolean {
        val lat = moment.pickupLatitude ?: return false
        val lng = moment.pickupLongitude ?: return false
        val pLat = place.latitude ?: return false
        val pLng = place.longitude ?: return false
        return PreferenceMemoryAgent.distanceKm(lat, lng, pLat, pLng) <= PreferenceMemoryAgent.ALREADY_THERE_KM
    }

    private fun circularMinutes(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 1440 - d)
    }
}
