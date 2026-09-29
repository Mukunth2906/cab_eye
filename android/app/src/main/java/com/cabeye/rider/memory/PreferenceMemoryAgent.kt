package com.cabeye.rider.memory

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Preference-Memory Agent: reasons over a rider's previously visited places and trips to
 * suggest a destination — before they speak, or when the app could not understand them.
 *
 * Grounded in the retrieval-augmented / reflective memory pattern from the agent-memory
 * literature: **retrieve** relevant episodes (trips at this time of day, places that sound like
 * what was heard), **score** them, **ask** rather than act, and **reflect** on the answer so the
 * next suggestion is better calibrated (see [Thresholds.from]).
 *
 * ## Guardrails, all enforced by the caller and restated here because they are the design
 *  1. The agent only ever *proposes*. A "yes" still goes through the normal booking path with
 *     its five-second cancel window; nothing here books a ride.
 *  2. Every suggestion carries a spoken [Suggestion.reason], so the rider knows *why* the app
 *     thinks so ("like most weekday mornings") and can judge it.
 *  3. A place the rider keeps turning down is suggested less, and eventually not at all.
 *  4. "Forget my history" empties the memory it reasons over.
 *
 * Pure Kotlin — time, zone and location are passed in — so every rule has a JVM test.
 */
object PreferenceMemoryAgent {

    /** Where and when the rider is asking. */
    data class Moment(
        val now: Long,
        val zone: ZoneId,
        val pickupLatitude: Double? = null,
        val pickupLongitude: Double? = null
    ) {
        private val local = Instant.ofEpochMilli(now).atZone(zone)
        val minuteOfDay: Int = local.hour * 60 + local.minute
        val dayOfWeek: Int = local.dayOfWeek.value
        val isWeekend: Boolean = dayOfWeek >= 6
        val hour: Int = local.hour
        val minute: Int = local.minute
    }

    data class Suggestion(
        val place: VisitedPlace,
        /** 0..1 after every factor; compared against [Thresholds]. */
        val confidence: Float,
        val kind: SuggestionKind,
        /** Short clause for the narrator: "like most weekday mornings", "one of your usual places". */
        val reason: String,
        /** For REPAIR: how alike the heard words and the place are, before priors. */
        val similarity: Float = 0f
    )

    /**
     * How sure the agent must be before it speaks up — the Confidence Recalibration Loop.
     *
     * Starts at [BASE_PROACTIVE] / [BASE_REPAIR] and moves with the rider's own answers:
     * a rider who keeps saying "no" raises the bar (fewer, surer suggestions); one who keeps
     * saying "yes" lowers it a little. Laplace-smoothed, so two early answers cannot swing it
     * to an extreme, and clamped, so it never becomes either silent or reckless.
     */
    data class Thresholds(val proactive: Float, val repair: Float) {
        companion object {
            const val BASE_PROACTIVE = 0.55f
            const val BASE_REPAIR = 0.62f

            fun from(stats: MemoryStats): Thresholds = Thresholds(
                proactive = adjust(BASE_PROACTIVE, stats.proactiveAccepted, stats.proactiveRejected, 0.40f, 0.85f),
                repair = adjust(BASE_REPAIR, stats.repairAccepted, stats.repairRejected, 0.50f, 0.85f)
            )

            private fun adjust(base: Float, accepted: Int, rejected: Int, lo: Float, hi: Float): Float {
                val rate = (accepted + 1f) / (accepted + rejected + 2f)
                return (base + (0.5f - rate) * 0.6f).coerceIn(lo, hi)
            }
        }
    }

    /** Trips within this many minutes of now (either side, same kind of day) count as a pattern. */
    const val TIME_WINDOW_MIN = 60

    /** Fewer matching trips than this is a coincidence, not a habit. */
    const val MIN_SUPPORT = 2

    /** Already standing at the place: never suggest going there. */
    const val ALREADY_THERE_KM = 0.4

    /**
     * Similarity at or above which heard words *are* the place: a learned alias or the exact
     * name (1.0), or a spoken acronym of it (0.95). Deliberately above a mere shared word
     * (0.92) — "Gandhipuram" must not book "Gandhipuram Central Bus Stand" unasked, because
     * the rider may mean the area; that case goes to Places, or to a "did you mean" question.
     */
    const val DIRECT_SIMILARITY = 0.95f

    /** Below this, a heard phrase is not about the place at all, whatever the priors say. */
    const val MIN_REPAIR_SIMILARITY = 0.55f

    // =================================================================================
    //  Proactive: "PSG College, like usual?"
    // =================================================================================

    /** The habitual destination for this moment, if the memory is confident enough. */
    fun proactive(memory: RiderMemory, moment: Moment, thresholds: Thresholds, exclude: Set<String> = emptySet()): Suggestion? {
        val best = scoreByTime(memory, moment)
            .filterKeys { it !in exclude }
            .maxByOrNull { it.value } ?: return null
        val place = memory.places.firstOrNull { it.placeKey == best.key } ?: return null
        if (best.value < thresholds.proactive) return null
        return Suggestion(place, best.value, SuggestionKind.PROACTIVE, timeReason(moment))
    }

    /**
     * Every place's time-of-travel score for [moment], 0..1.
     *
     * score = share × saturation(support) × recency × pickup × rider's own verdicts, where
     *  - share: of all trips taken around this time on this kind of day, how many went here;
     *  - saturation: support/(support+1), so 2 trips count less than 5;
     *  - recency: a habit from last week beats one from three months ago;
     *  - pickup: trips that started near where the rider is now count for more;
     *  - verdicts: how often the rider accepted versus rejected this place when offered.
     */
    fun scoreByTime(memory: RiderMemory, moment: Moment): Map<String, Float> {
        val window = memory.trips.filter { t ->
            val local = Instant.ofEpochMilli(t.bookedAt).atZone(moment.zone)
            val minute = local.hour * 60 + local.minute
            circularMinutes(minute, moment.minuteOfDay) <= TIME_WINDOW_MIN &&
                (local.dayOfWeek.value >= 6) == moment.isWeekend
        }
        if (window.size < MIN_SUPPORT) return emptyMap()

        val out = mutableMapOf<String, Float>()
        for ((key, trips) in window.groupBy { it.placeKey }) {
            val support = trips.size
            if (support < MIN_SUPPORT) continue
            val place = memory.places.firstOrNull { it.placeKey == key } ?: continue
            if (isAlreadyThere(place, moment)) continue
            if (place.rejected >= 3 && place.accepted == 0) continue

            val share = support.toFloat() / window.size
            val saturation = support / (support + 1f)
            val ageDays = (moment.now - trips.maxOf { it.bookedAt }) / 86_400_000.0
            val recency = when {
                ageDays <= 14 -> 1f
                ageDays <= 45 -> 0.85f
                else -> 0.6f
            }
            val pickup = pickupFactor(trips, moment)
            val verdict = min(1f, 0.5f + (place.accepted + 1f) / (place.accepted + place.rejected + 2f))
            out[key] = share * saturation * recency * pickup * verdict
        }
        return out
    }

    // =================================================================================
    //  Repair: "Did you mean PSG College?"
    // =================================================================================

    /**
     * The visited place the misheard [heard] most plausibly refers to.
     *
     * confidence = 0.75 × similarity + 0.15 × time-of-travel prior + 0.10 × how often visited.
     * The time prior is what makes "college" at 8:40 on a Monday mean *the rider's* college.
     */
    fun repair(
        heard: String,
        memory: RiderMemory,
        moment: Moment,
        thresholds: Thresholds,
        exclude: Set<String> = emptySet()
    ): Suggestion? {
        val ranked = rankByWords(heard, memory, moment, exclude)
        val top = ranked.firstOrNull() ?: return null
        if (top.similarity >= MIN_REPAIR_SIMILARITY && top.confidence >= thresholds.repair) return top

        // Only a kind of place was heard — "college", "the mall". The words cannot say which,
        // but the time of travel can: at 8:40 on a Monday "college" means the college this
        // rider goes to every weekday morning. Held to the (stricter) proactive bar.
        if (TextSimilarity.isGenericOnly(heard)) {
            val words = TextSimilarity.normalise(heard).split(" ").toSet()
            val byTime = scoreByTime(memory, moment)
            val candidate = memory.places
                .filter { it.placeKey !in exclude }
                .filter { p ->
                    (listOf(p.name) + p.aliases).any { n -> TextSimilarity.normalise(n).split(" ").any { it in words } }
                }
                .maxByOrNull { byTime[it.placeKey] ?: 0f } ?: return null
            val prior = byTime[candidate.placeKey] ?: 0f
            if (prior >= thresholds.proactive) {
                return Suggestion(candidate, prior, SuggestionKind.REPAIR, timeReason(moment), TextSimilarity.GENERIC_CAP)
            }
        }
        return null
    }

    /**
     * An unambiguous hit on the rider's own words — an alias they confirmed before, or the
     * place's exact name — which may go straight to the normal booking announcement.
     */
    fun direct(heard: String, memory: RiderMemory, moment: Moment): Suggestion? {
        val ranked = rankByWords(heard, memory, moment, emptySet())
        val top = ranked.firstOrNull() ?: return null
        if (top.similarity < DIRECT_SIMILARITY) return null
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && runnerUp.similarity >= 0.8f) return null // two plausible places: ask
        if (top.place.latitude == null || top.place.longitude == null) return null
        return top.copy(reason = "one of your usual places")
    }

    /**
     * When nothing intelligible was heard at all, the best the agent can do is the time-based
     * guess: "I didn't catch that. Were you going to PSG College?" Held to the proactive bar.
     */
    fun guessWhenUnheard(memory: RiderMemory, moment: Moment, thresholds: Thresholds, exclude: Set<String>): Suggestion? =
        proactive(memory, moment, thresholds, exclude)?.copy(kind = SuggestionKind.REPAIR)

    fun rankByWords(heard: String, memory: RiderMemory, moment: Moment, exclude: Set<String>): List<Suggestion> {
        val h = TextSimilarity.normalise(heard)
        if (h.isBlank() || memory.places.isEmpty()) return emptyList()
        val timeScores = scoreByTime(memory, moment)
        val maxVisits = memory.places.maxOf { it.visitCount }.coerceAtLeast(1)

        return memory.places
            .filter { it.placeKey !in exclude }
            .map { p ->
                val sim = (listOf(p.name) + p.aliases).maxOf { TextSimilarity.similarity(h, it) }
                val prior = timeScores[p.placeKey] ?: 0f
                val freq = p.visitCount.toFloat() / maxVisits
                val confidence = 0.75f * sim + 0.15f * prior + 0.10f * freq
                val reason = when {
                    prior >= 0.3f -> timeReason(moment)
                    p.visitCount >= 2 -> "where you've been ${p.visitCount} times"
                    else -> "where you went before"
                }
                Suggestion(p, confidence, SuggestionKind.REPAIR, reason, sim)
            }
            .sortedByDescending { it.confidence }
    }

    // =================================================================================
    //  Speech helpers
    // =================================================================================

    /** "like most weekday mornings" */
    fun timeReason(moment: Moment): String {
        val part = when (moment.hour) {
            in 5..11 -> "mornings"
            in 12..16 -> "afternoons"
            in 17..20 -> "evenings"
            else -> "nights"
        }
        val days = if (moment.isWeekend) "weekend" else "weekday"
        return "like most $days $part"
    }

    /** "8:40" as a TTS engine reads it naturally: "8 40 AM". */
    fun spokenTime(moment: Moment): String {
        val h12 = if (moment.hour % 12 == 0) 12 else moment.hour % 12
        val mm = if (moment.minute == 0) "" else " " + moment.minute.toString().padStart(2, '0')
        val ampm = if (moment.hour < 12) "AM" else "PM"
        return "$h12$mm $ampm"
    }

    // =================================================================================
    //  Internals
    // =================================================================================

    private fun circularMinutes(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 1440 - d)
    }

    private fun pickupFactor(trips: List<TripRecord>, moment: Moment): Float {
        val lat = moment.pickupLatitude ?: return 0.9f
        val lng = moment.pickupLongitude ?: return 0.9f
        val known = trips.filter { it.pickupLatitude != null && it.pickupLongitude != null }
        if (known.isEmpty()) return 0.9f
        val near = known.count { distanceKm(lat, lng, it.pickupLatitude!!, it.pickupLongitude!!) <= 1.5 }
        return 0.7f + 0.3f * near / known.size
    }

    private fun isAlreadyThere(place: VisitedPlace, moment: Moment): Boolean {
        val lat = moment.pickupLatitude ?: return false
        val lng = moment.pickupLongitude ?: return false
        val pLat = place.latitude ?: return false
        val pLng = place.longitude ?: return false
        return distanceKm(lat, lng, pLat, pLng) <= ALREADY_THERE_KM
    }

    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
