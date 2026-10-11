package com.cabeye.rider.places

import com.cabeye.rider.memory.TextSimilarity
import com.cabeye.rider.mindmap.Geo
import com.cabeye.rider.mindmap.NearbyPlace
import com.cabeye.rider.state.PlaceOption
import org.json.JSONObject

/** A named neighbourhood, suburb or village, as a point. */
data class AtlasArea(val name: String, val kind: String, val lat: Double, val lng: Double)

/** A real place: a temple, a station, a hospital, a mall. [score] = how well known, from the map itself. */
data class AtlasPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val types: List<String> = emptyList(),
    val score: Int = 0,
    val aliases: List<String> = emptyList(),
    val wikidata: String = ""
)

/**
 * The offline map of Coimbatore: real places and area names from OpenStreetMap, bundled with
 * the app (assets/atlas/coimbatore.json, built by scripts/build_coimbatore_atlas.py). No paid
 * service, no API key and no network — the phone's own GPS says where the rider is, and this
 * says what is there.
 *
 *  - [areaAt]: the neighbourhood a point is in ("RS Puram"), for the ride guide and "where am I"
 *  - [nearby]: the best-known places around a point, for "what's famous around here"
 *  - [search]: places by name for booking ("take me to Koniamman Temple"), scored by the same
 *    rules as the built-in places so the confidence gate works unchanged
 *
 * Pure Kotlin (org.json only), so every rule is a JVM test.
 */
class Atlas(
    val region: String,
    private val south: Double,
    private val west: Double,
    private val north: Double,
    private val east: Double,
    val areas: List<AtlasArea>,
    val places: List<AtlasPlace>,
    val attribution: String
) {
    companion object {
        /** Well-known enough to offer unasked: a station, a monument, a mall, a park — or a Wikipedia link. */
        const val NOTABLE = 30
        /** Around the edges of the data, names are still mostly right. */
        const val MARGIN_DEG = 0.02

        /** How far each kind of area reaches: a neighbourhood is small, a village large. */
        private val REACH_M = mapOf(
            "neighbourhood" to 700.0, "quarter" to 900.0, "locality" to 900.0,
            "suburb" to 1_600.0, "hamlet" to 1_200.0, "village" to 2_500.0, "town" to 3_500.0
        )

        /** The app speaks English: a name with no Latin letters can't be said or matched. */
        private val SAYABLE = Regex("[A-Za-z]")

        fun parse(json: String): Atlas? = runCatching {
            val o = JSONObject(json)
            val bbox = o.getJSONArray("bbox")
            val a = o.getJSONArray("areas")
            val p = o.getJSONArray("places")
            Atlas(
                region = o.optString("region"),
                south = bbox.getDouble(0), west = bbox.getDouble(1), north = bbox.getDouble(2), east = bbox.getDouble(3),
                areas = (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    AtlasArea(x.getString("n"), x.optString("k"), x.getDouble("la"), x.getDouble("lo"))
                },
                places = (0 until p.length()).map { i ->
                    val x = p.getJSONObject(i)
                    val ty = x.optJSONArray("ty")
                    val al = x.optJSONArray("a")
                    AtlasPlace(
                        name = x.getString("n"), lat = x.getDouble("la"), lng = x.getDouble("lo"),
                        types = if (ty == null) emptyList() else (0 until ty.length()).map { ty.getString(it) },
                        score = x.optInt("s"),
                        aliases = if (al == null) emptyList() else (0 until al.length()).map { al.getString(it) },
                        wikidata = x.optString("w")
                    )
                }.filter { SAYABLE.containsMatchIn(it.name) },
                attribution = o.optString("attribution")
            ).let { a -> if (a.areas.all { SAYABLE.containsMatchIn(it.name) }) a else a.copyAreas(a.areas.filter { SAYABLE.containsMatchIn(it.name) }) }
        }.getOrNull()
    }

    private fun copyAreas(newAreas: List<AtlasArea>) = Atlas(region, south, west, north, east, newAreas, places, attribution)

    /** With [extra] areas added where the atlas has none of that name. */
    fun withAreas(extra: List<AtlasArea>): Atlas {
        val have = areas.map { TextSimilarity.normalise(it.name) }.toSet()
        val add = extra.filter { TextSimilarity.normalise(it.name) !in have }
        return if (add.isEmpty()) this else Atlas(region, south, west, north, east, areas + add, places, attribution)
    }

    fun covers(lat: Double, lng: Double): Boolean =
        lat in (south - MARGIN_DEG)..(north + MARGIN_DEG) && lng in (west - MARGIN_DEG)..(east + MARGIN_DEG)

    /**
     * The area a point is in: the named area whose point is nearest, relative to how far that
     * kind of area reaches (a neighbourhood 400 m away beats a suburb 600 m away). Null outside
     * the data or when nothing named is near — then nothing is said rather than a guess.
     */
    fun areaAt(lat: Double, lng: Double): String? {
        if (!covers(lat, lng)) return null
        return areas.asSequence()
            .mapNotNull { a ->
                val reach = REACH_M[a.kind] ?: return@mapNotNull null
                val d = Geo.metres(lat, lng, a.lat, a.lng)
                if (d <= reach) a to d / reach else null
            }
            .minByOrNull { it.second }?.first?.name
    }

    /**
     * Places a rider can book a ride to, but which are not "well-known places nearby" to offer
     * someone getting to know an area — a school with a Wikipedia page is not a sight.
     */
    private val notOffered = setOf("school", "bank", "clinic", "post_office", "police", "supermarket", "hotel", "government")

    /** The best-known places within [radiusM], best first: more famous first, then nearer. */
    fun nearby(lat: Double, lng: Double, radiusM: Double, max: Int = 10, minScore: Int = NOTABLE): List<NearbyPlace> {
        if (!covers(lat, lng)) return emptyList()
        return places.asSequence()
            .filter { it.score >= minScore && it.types.none { t -> t in notOffered } }
            .map { it to Geo.metres(lat, lng, it.lat, it.lng) }
            .filter { it.second <= radiusM }
            .sortedWith(compareByDescending<Pair<AtlasPlace, Double>> { it.first.score }.thenBy { it.second })
            .distinctBy { TextSimilarity.normalise(it.first.name) }
            .take(max)
            .map { (p, _) ->
                NearbyPlace(p.name, p.lat, p.lng, p.types, placeId = p.wikidata.takeIf { it.isNotBlank() }?.let { "osm:$it" } ?: "", ranked = true)
            }
            .toList()
    }

    /**
     * What search looks through: the areas, and the places — except a place that only carries a
     * nearby area's name (the "Kalapatti" post office in Kalapatti). "Take me to Kalapatti" means
     * the area, and asking "Kalapatti or Kalapatti?" would help nobody.
     */
    private val asGazetteer: List<Place> by lazy {
        val named = places.filterNot { p ->
            areas.any { a -> a.name.equals(p.name, ignoreCase = true) && Geo.metres(a.lat, a.lng, p.lat, p.lng) < 2_000 }
        }
        named.map { Place(it.name, it.lat, it.lng, it.aliases) } + areas.map { Place(it.name, it.lat, it.lng) }
    }
    private val areaKeys: Set<Triple<String, Double, Double>> by lazy { areas.map { Triple(it.name, it.lat, it.lng) }.toSet() }

    /**
     * Places — and areas — by what the rider said, scored exactly like the built-in places.
     * Equal scores go to the place nearer the rider (there are many "Amman temple"s), then to the
     * better known. An area ("Kalapatti") is marked vague: an area is not an address, so the app
     * asks who is meeting the rider, as it does for a road.
     */
    fun search(spoken: String, nearLat: Double? = null, nearLng: Double? = null, max: Int = 8): List<PlaceOption> {
        val byName = places.associateBy { it.name }
        return Gazetteer.scorePlaces(asGazetteer, spoken, region)
            .map { if (Triple(it.name, it.latitude, it.longitude) in areaKeys) it.copy(isVague = true) else it }
            .sortedWith(
                compareByDescending<PlaceOption> { it.score }
                    .thenBy { if (nearLat != null && nearLng != null) Geo.metres(nearLat, nearLng, it.latitude, it.longitude) else 0.0 }
                    .thenByDescending { byName[it.name]?.score ?: 0 }
            )
            .distinctBy { TextSimilarity.normalise(it.name) to (it.latitude * 200).toInt() to (it.longitude * 200).toInt() }
            .take(max)
    }
}
