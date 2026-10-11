package com.cabeye.rider.mindmap

import com.cabeye.rider.memory.TextSimilarity
import com.cabeye.rider.memory.VisitedPlace

/** A well-known place near the rider, from Google Places (Nearby Search) or the offline gazetteer. */
data class NearbyPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val types: List<String> = emptyList(),
    val rating: Double? = null,
    val ratings: Int = 0,
    val placeId: String = "",
    /** From the app's own curated list: known, but with no rating to check. */
    val curated: Boolean = false,
    /**
     * Already judged well known by its source and in that source's order — OpenStreetMap, where
     * there are no ratings but a Wikipedia link, an attraction or a station says as much.
     */
    val ranked: Boolean = false
)

/** The backend's OpenStreetMap answer (`GET /me/places/nearby`) → places, in the server's order. */
object NearbyJson {
    fun osm(raw: String): List<NearbyPlace> = runCatching {
        val arr = org.json.JSONObject(raw).optJSONArray("places") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            if (name.isEmpty() || !o.has("lat") || !o.has("lng")) return@mapNotNull null
            val types = o.optJSONArray("types")
            NearbyPlace(
                name = name,
                lat = o.getDouble("lat"),
                lng = o.getDouble("lng"),
                types = if (types == null) emptyList() else (0 until types.length()).map { types.getString(it) },
                placeId = o.optString("wikidata").takeIf { it.isNotBlank() }?.let { "osm:$it" } ?: "",
                ranked = true
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Arriving somewhere new: "what is around me?"
 *
 * A sighted visitor glances around and sees the temple, the mall, the park. A blind rider
 * arriving somewhere for the first time has nothing to hang a mental map on. So after a ride to
 * a place the rider has not been to before, the agent offers — once, briefly — the few
 * best-known places close by, with distance and direction, and the rider can hear more, book a
 * cab to one, or keep them in their mind map.
 *
 * Same guardrails as the other suggestions: it asks and never acts, two "no"s in a row and it
 * stops offering by itself (it still answers "what's famous around here"), and the rider can
 * turn it off.
 */
object NearbySuggestionAgent {

    const val RADIUS_M = 600.0
    const val MIN_RATING = 4.0
    const val MIN_RATINGS = 100
    const val MAX_PLACES = 3
    /** Closer than this is where the rider already is. */
    const val MIN_DISTANCE_M = 40.0
    const val QUIET_AFTER_DECLINES = 2

    data class Guard(val declines: Int = 0, val off: Boolean = false) {
        val offersProactively: Boolean get() = !off && declines < QUIET_AFTER_DECLINES
    }

    /**
     * First time here: no saved walk, and no visits before this one.
     *
     * @param countsThisVisit true when [places] already includes the ride that just ended (the
     *   server recorded it); false when it is the memory from before the ride
     */
    fun isNew(placeKey: String, places: List<VisitedPlace>, hasWalk: Boolean, name: String = "", countsThisVisit: Boolean = true): Boolean {
        if (hasWalk) return false
        val n = TextSimilarity.normalise(name)
        val visits = places.firstOrNull { p ->
            p.placeKey == placeKey || (p.placeId.isNotBlank() && p.placeId == placeKey) ||
                (n.isNotBlank() && (p.placeKey == "name:$n" || TextSimilarity.normalise(p.name) == n))
        }?.visitCount ?: 0
        return visits <= (if (countsThisVisit) 1 else 0)
    }

    /** The few worth saying, nearest-popular first, with their distance. */
    fun pick(
        lat: Double,
        lng: Double,
        candidates: List<NearbyPlace>,
        hereName: String = "",
        radiusM: Double = RADIUS_M
    ): List<Pair<NearbyPlace, Double>> {
        val here = TextSimilarity.normalise(hereName)
        return candidates.asSequence()
            .filter { it.curated || it.ranked || ((it.rating ?: 0.0) >= MIN_RATING && it.ratings >= MIN_RATINGS) }
            .filter { here.isBlank() || TextSimilarity.similarity(it.name, here) < 0.85f }
            .map { it to Geo.metres(lat, lng, it.lat, it.lng) }
            .filter { (_, d) -> d in MIN_DISTANCE_M..radiusM }
            // Google's are already in popularity order; the app's own list has no ranking, so nearest first.
            .sortedBy { (p, d) -> if (p.curated) d else 0.0 }
            .distinctBy { TextSimilarity.normalise(it.first.name) }
            .take(MAX_PLACES)
            .toList()
    }

    fun offer(picks: List<Pair<NearbyPlace, Double>>, lat: Double, lng: Double, newHere: Boolean, heading: Double? = null): String {
        if (picks.isEmpty()) return "I couldn't find well-known places close by."
        val lead = if (newHere) "This is new for you. Well-known places close by:" else "Well-known places close by:"
        return "$lead ${picks.joinToString("; ") { (p, d) -> "${p.name}, ${where(p, d, lat, lng, heading)}" }}. " +
            "Say tell me more, take me to one of them, or remember them."
    }

    fun more(picks: List<Pair<NearbyPlace, Double>>, lat: Double, lng: Double, heading: Double? = null): String =
        picks.joinToString(" ") { (p, d) ->
            val kind = kindOf(p.types)?.let { ", $it" } ?: ""
            val rated = p.rating?.takeIf { p.ratings > 0 }?.let { r -> ", rated ${"%.1f".format(java.util.Locale.ROOT, r)} by ${people(p.ratings)}" } ?: ""
            "${p.name}$kind$rated, ${where(p, d, lat, lng, heading)}."
        } + " Distances are straight-line, not the walking route." +
            // OpenStreetMap's licence asks for credit; said once, at the end of the details.
            (if (picks.any { it.first.ranked }) " Place information from OpenStreetMap." else "")

    /** "the temple", "the second one", "Brookefields" → which of the offered places. */
    fun choose(picks: List<Pair<NearbyPlace, Double>>, heard: String): NearbyPlace? {
        val t = TextSimilarity.normalise(heard)
        val ordinal = when {
            Regex("\\blast\\b").containsMatchIn(t) -> picks.lastIndex
            Regex("\\b(third|3rd|number three)\\b").containsMatchIn(t) -> 2
            Regex("\\b(second|2nd|number two)\\b").containsMatchIn(t) -> 1
            Regex("\\b(first|1st|number one)\\b").containsMatchIn(t) -> 0
            else -> null
        }
        if (ordinal != null) return picks.getOrNull(ordinal)?.first
        val words = t.replace(Regex("^(?:take me to|go to|book|remember|tell me about|the)\\s+"), "").trim()
        if (words.isBlank()) return picks.singleOrNull()?.first
        val scored = picks.map { (p, _) ->
            p to maxOf(
                TextSimilarity.similarity(words, p.name),
                if (kindOf(p.types)?.contains(words) == true) 0.85f else 0f
            )
        }.filter { it.second >= 0.7f }.sortedByDescending { it.second }
        if (scored.size > 1 && scored[0].second - scored[1].second < 0.05f) return null
        return scored.firstOrNull()?.first
    }

    /** Remembered places become mind-map PLACE nodes, NEAR where the rider was dropped. */
    fun remember(picks: List<Pair<NearbyPlace, Double>>, near: Node?, now: Long): Pair<List<Node>, List<Edge>> {
        val nodes = picks.map { (p, _) ->
            Node(
                id = "near:${p.placeId.ifBlank { TextSimilarity.normalise(p.name).replace(' ', '-') }}",
                kind = NodeKind.PLACE, label = p.name, lat = p.lat, lng = p.lng,
                source = p.placeId, createdAt = now, lastConfirmedAt = now
            )
        }
        val edges = if (near == null) emptyList() else nodes.map { Edge(it.id, near.id, EdgeKind.NEAR) }
        return nodes to edges
    }

    private fun where(p: NearbyPlace, d: Double, lat: Double, lng: Double, heading: Double?): String {
        val b = Geo.bearing(lat, lng, p.lat, p.lng)
        return "${Geo.spokenDistance(d)} ${heading?.let { Geo.clock(b, it) } ?: Geo.compass(b)}"
    }

    fun kindOf(types: List<String>): String? {
        val t = types.map { it.lowercase() }
        return when {
            "hindu_temple" in t -> "a temple"
            "church" in t -> "a church"
            "mosque" in t -> "a mosque"
            "place_of_worship" in t -> "a place of worship"
            "museum" in t -> "a museum"
            "park" in t -> "a park"
            "shopping_mall" in t -> "a shopping mall"
            "hospital" in t -> "a hospital"
            "train_station" in t || "railway_station" in t -> "a railway station"
            "bus_station" in t || "transit_station" in t -> "a bus or transit stop"
            "university" in t -> "a college"
            "stadium" in t -> "a stadium"
            "market" in t -> "a market"
            "restaurant" in t -> "a restaurant"
            "cafe" in t -> "a cafe"
            "tourist_attraction" in t -> "a well-known sight"
            else -> null
        }
    }

    private fun people(n: Int) = when {
        n >= 10_000 -> "over ${n / 1000} thousand people"
        n >= 1_000 -> "about ${n / 1000} thousand people"
        else -> "$n people"
    }
}
