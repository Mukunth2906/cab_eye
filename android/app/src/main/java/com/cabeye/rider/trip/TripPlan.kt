package com.cabeye.rider.trip

import com.cabeye.rider.state.PlaceOption

/**
 * What happens at a stop — the same three choices Uber and Rapido riders make.
 *
 *  - [DROP]: someone with the rider gets off; the rider stays in the car.
 *  - [PICKUP]: someone joins the ride; the rider stays in the car.
 *  - [WAIT]: the rider gets out for an errand and comes back. The car does not leave until the
 *    rider's phone has heard the boarding code again — the server enforces it.
 */
enum class StopKind(val spoken: String, val driverLabel: String) {
    DROP("dropping someone off", "Drop-off"),
    PICKUP("picking someone up", "Pick-up"),
    WAIT("the driver waits for you", "Wait for passenger");

    companion object {
        fun parse(raw: String?): StopKind =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DROP
    }
}

/**
 * One place on a planned route.
 *
 * @param query what to look up ("apollo pharmacy"), stopwords stripped
 * @param spoken what the rider actually said for it, kept for the memory agent's aliases
 * @param kind null until known; the destination never has one
 * @param note who is dropped or picked up ("my friend Priya") — shown to the driver
 * @param place filled in once the place is resolved
 */
data class PlannedLeg(
    val query: String,
    val spoken: String = query,
    val kind: StopKind? = null,
    val note: String = "",
    val place: PlaceOption? = null
) {
    val name: String get() = place?.name ?: query
}

/**
 * A multi-stop trip being planned by voice: up to [MAX_STOPS] stops, then the destination.
 *
 * Immutable; every edit returns a new plan or an [Edit.Refused] carrying the sentence to say.
 * Pure Kotlin, so every rule is a JVM test.
 */
data class TripPlan(
    val stops: List<PlannedLeg>,
    val destination: PlannedLeg
) {
    val legs: List<PlannedLeg> get() = stops + destination

    /** Every leg has a resolved place and every stop has a kind: ready to book. */
    val isComplete: Boolean get() = legs.all { it.place != null } && stops.all { it.kind != null }

    /** First leg still to be looked up, as an index into [legs]; null when all are resolved. */
    val nextUnresolved: Int? get() = legs.indexOfFirst { it.place == null }.takeIf { it >= 0 }

    /** First stop still without a kind, 0-based; null when all have one. */
    val nextWithoutKind: Int? get() = stops.indexOfFirst { it.kind == null }.takeIf { it >= 0 }

    sealed interface Edit {
        data class Ok(val plan: TripPlan, val said: String) : Edit
        data class Refused(val sentence: String) : Edit
    }

    fun withLeg(legIndex: Int, leg: PlannedLeg): TripPlan =
        if (legIndex == stops.size) copy(destination = leg)
        else copy(stops = stops.toMutableList().also { it[legIndex] = leg })

    /** [n] is 1-based, as the rider says it. */
    fun remove(n: Int): Edit {
        if (n !in 1..stops.size) return Edit.Refused(noSuchStop(n))
        val gone = stops[n - 1]
        return Edit.Ok(copy(stops = stops.filterIndexed { i, _ -> i != n - 1 }), "Removed ${gone.name}.")
    }

    fun swap(a: Int, b: Int): Edit {
        if (a !in 1..stops.size) return Edit.Refused(noSuchStop(a))
        if (b !in 1..stops.size) return Edit.Refused(noSuchStop(b))
        if (a == b) return Edit.Refused("Those are the same stop.")
        val list = stops.toMutableList()
        val tmp = list[a - 1]; list[a - 1] = list[b - 1]; list[b - 1] = tmp
        return Edit.Ok(copy(stops = list), "Swapped stops $a and $b.")
    }

    /**
     * Inserts a new, not-yet-resolved stop. [afterStop] is 1-based; 0 means first; null means
     * last, just before the destination.
     */
    fun add(leg: PlannedLeg, afterStop: Int? = null): Edit {
        if (stops.size >= MAX_STOPS) return Edit.Refused("You already have $MAX_STOPS stops, which is the most a ride can have.")
        val at = when (afterStop) {
            null -> stops.size
            else -> {
                if (afterStop !in 0..stops.size) return Edit.Refused(noSuchStop(afterStop))
                afterStop
            }
        }
        return Edit.Ok(copy(stops = stops.toMutableList().also { it.add(at, leg) }), "")
    }

    fun setKind(n: Int, kind: StopKind): Edit {
        if (n !in 1..stops.size) return Edit.Refused(noSuchStop(n))
        val leg = stops[n - 1]
        return Edit.Ok(withLeg(n - 1, leg.copy(kind = kind)), "Stop $n: ${kind.spoken}.")
    }

    /** A stop by the rider's words for it ("the pharmacy"), 1-based; null when none or ambiguous. */
    fun stopNamed(words: String): Int? {
        val w = norm(words)
        if (w.isBlank()) return null
        val hits = stops.withIndex().filter { (_, s) ->
            val names = listOf(norm(s.name), norm(s.query), norm(s.spoken))
            names.any { n -> n.isNotBlank() && (n.contains(w) || w.contains(n)) }
        }
        return hits.singleOrNull()?.index?.plus(1)
    }

    /**
     * The read-back: numbered, with what happens at each stop, then the destination.
     * "Stop one, Apollo Pharmacy, the driver waits for you. Stop two, Gandhipuram, dropping
     * someone off. Then PSG College."
     */
    fun readBack(): String = buildString {
        if (stops.isEmpty()) {
            append("Straight to ${destination.name}.")
            return@buildString
        }
        stops.forEachIndexed { i, s ->
            append("Stop ${ordinalWord(i + 1)}, ${s.name}")
            if (s.kind != null) append(", ${s.kind.spoken}")
            if (s.note.isNotBlank() && s.kind != StopKind.WAIT) append(", ${s.note}")
            append(". ")
        }
        append("Then ${destination.name}.")
    }

    private fun noSuchStop(n: Int) =
        if (stops.isEmpty()) "There are no stops yet." else "There's no stop $n. You have ${stops.size}."

    companion object {
        /** Uber allows two or three depending on the city; the server enforces three. */
        const val MAX_STOPS = 3

        fun ordinalWord(n: Int) = when (n) {
            1 -> "one"; 2 -> "two"; 3 -> "three"; else -> n.toString()
        }

        internal fun norm(s: String) =
            s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
                .removePrefix("the ").trim()
    }
}
