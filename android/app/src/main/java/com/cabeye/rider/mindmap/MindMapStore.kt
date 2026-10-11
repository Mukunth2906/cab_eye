package com.cabeye.rider.mindmap

import android.content.Context
import com.cabeye.rider.memory.TextSimilarity
import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.Side
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which part of the app a piece of the mind map came from. Each has its own storage.
 *
 *  - [RIDES]: cab destinations, ride cues, the areas a cab passes through, ride paths, places
 *    offered after a ride. Ids `place:`, `cue:`, `area:`, `near:`.
 *  - [EXPLORE]: what the rider learns on foot — named spots and personal notes (`a:`), things the
 *    camera saw while exploring (`obs:`), and anything tied to a walk (`lm:`, `walk:`).
 *
 * An edge belongs to EXPLORE when either end does (a note about a cab destination is the rider's
 * own words, kept with their notes); otherwise to RIDES.
 */
enum class MindModule {
    RIDES, EXPLORE;

    companion object {
        private val RIDE_PREFIXES = listOf("place:", "cue:", "area:", "near:")

        fun of(nodeId: String): MindModule = if (RIDE_PREFIXES.any { nodeId.startsWith(it) }) RIDES else EXPLORE

        fun of(edge: Edge): MindModule = if (of(edge.from) == EXPLORE || of(edge.to) == EXPLORE) EXPLORE else RIDES

        /** [map] split into (rides, explore). */
        fun split(map: MindMap): Pair<MindMap, MindMap> {
            val (ridesNodes, exploreNodes) = map.nodes.partition { of(it.id) == RIDES }
            val (ridesEdges, exploreEdges) = map.edges.partition { of(it) == RIDES }
            return MindMap(ridesNodes, ridesEdges) to MindMap(exploreNodes, exploreEdges)
        }
    }
}

/**
 * The rider's own mind-map content, in two stores on this phone only (SharedPreferences, private
 * to the app): one for the ride module ([MindModule.RIDES]: ride cues, areas, ride paths) and one
 * for the explore module ([MindModule.EXPLORE]: named spots, notes, camera sightings). Like the
 * walking routes, none of it is sent to the server: what a place means to a blind rider, and the
 * way they ride home, is theirs.
 *
 * [authored] joins both, so everything that reads the whole mind map keeps working; [saveAuthored]
 * puts each piece back into its own store. Data saved before the split is moved over once.
 */
class MindMapStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("cabeye.mindmap", Context.MODE_PRIVATE)
    private val ridePrefs = context.applicationContext.getSharedPreferences("cabeye.mindmap.rides", Context.MODE_PRIVATE)
    private val explorePrefs = context.applicationContext.getSharedPreferences("cabeye.mindmap.explore", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    /** One-time move from the single store used before rides and explore were separated. */
    private fun migrate() {
        val legacyMap = prefs.getString("map", null)
        val legacyTraces = prefs.getString("traces", null)
        if (legacyMap == null && legacyTraces == null) return
        if (legacyMap != null) {
            val (r, e) = MindModule.split(MindMapJson.map(legacyMap))
            saveRides(rides().plus(r))
            saveExplore(explore().plus(e))
        }
        if (legacyTraces != null) {
            ridePrefs.edit().putString(
                "traces",
                MindMapJson.tracesJson(MindMapJson.keepTraces(traces() + MindMapJson.traces(legacyTraces))).toString()
            ).apply()
        }
        prefs.edit().remove("map").remove("traces").apply()
    }

    /** The ride module's part: ride cues, areas, places offered after rides. */
    fun rides(): MindMap = MindMapJson.map(ridePrefs.getString("map", null))

    /** The explore module's part: named spots, notes, camera sightings. */
    fun explore(): MindMap = MindMapJson.map(explorePrefs.getString("map", null))

    private fun saveRides(map: MindMap) {
        ridePrefs.edit().putString("map", MindMapJson.json(MindMapJson.capped(map)).toString()).apply()
    }

    private fun saveExplore(map: MindMap) {
        explorePrefs.edit().putString("map", MindMapJson.json(MindMapJson.capped(map)).toString()).apply()
    }

    /** Both modules, joined. */
    fun authored(): MindMap = rides().plus(explore())

    /** Saves [map], each node and edge into its own module's store. */
    fun saveAuthored(map: MindMap) {
        val (r, e) = MindModule.split(map)
        saveRides(r)
        saveExplore(e)
    }

    /** Adds what the camera saw while exploring, merged with earlier sightings of the same thing. */
    fun observe(seen: List<Node>, now: Long) {
        if (seen.isEmpty()) return
        saveExplore(Observations.merge(explore(), seen, now))
    }

    fun traces(): List<RideTrace> = MindMapJson.traces(ridePrefs.getString("traces", null))

    /** Keeps the last [MindMapJson.TRACES_PER_PLACE] rides to each place. */
    fun addTrace(trace: RideTrace) {
        val kept = MindMapJson.keepTraces(traces() + trace)
        ridePrefs.edit().putString("traces", MindMapJson.tracesJson(kept).toString()).apply()
    }

    /** Replaces every stored ride path with [list] (kept to the usual limits). */
    fun replaceTraces(list: List<RideTrace>) {
        ridePrefs.edit().putString("traces", MindMapJson.tracesJson(MindMapJson.keepTraces(list)).toString()).apply()
    }

    fun clear() {
        ridePrefs.edit().remove("map").remove("traces").apply()
        explorePrefs.edit().remove("map").apply()
    }

    /** "Forget my history": the ways the rider rode, not their own notes and cues. */
    fun clearTraces() {
        ridePrefs.edit().remove("traces").apply()
    }

    fun nearbyGuard(): NearbySuggestionAgent.Guard =
        NearbySuggestionAgent.Guard(prefs.getInt("nearbyDeclines", 0), prefs.getBoolean("nearbyOff", false))

    fun saveNearbyGuard(g: NearbySuggestionAgent.Guard) {
        prefs.edit().putInt("nearbyDeclines", g.declines).putBoolean("nearbyOff", g.off).apply()
    }

    /** The ride guide has introduced itself once. */
    var rideIntroDone: Boolean
        get() = prefs.getBoolean("rideIntroDone", false)
        set(v) { prefs.edit().putBoolean("rideIntroDone", v).apply() }

    var rideQuiet: Boolean
        get() = prefs.getBoolean("rideQuiet", false)
        set(v) { prefs.edit().putBoolean("rideQuiet", v).apply() }

    /** The rider agreed to send single sentences to the server to be understood (see [MindMapInterpreter]). */
    var interpretConsent: Boolean
        get() = prefs.getBoolean("interpretConsent", false)
        set(v) { prefs.edit().putBoolean("interpretConsent", v).apply() }

    /** Asked once; a "no" is not asked again. */
    var interpretAsked: Boolean
        get() = prefs.getBoolean("interpretAsked", false)
        set(v) { prefs.edit().putBoolean("interpretAsked", v).apply() }
}

/**
 * Camera sightings while exploring. The same thing seen again close by (same name, within
 * [SAME_PLACE_M]) is one memory seen more times, not a new one — a rider walking past a bench
 * every morning has one bench, confirmed every morning.
 */
object Observations {
    const val SAME_PLACE_M = 12.0
    const val MAX_OBSERVATIONS = 600

    fun merge(map: MindMap, seen: List<Node>, now: Long): MindMap {
        val nodes = map.nodes.toMutableList()
        for (s in seen) {
            if (s.kind != NodeKind.OBSERVED || !s.located) continue
            val key = TextSimilarity.normalise(s.label)
            val i = nodes.indexOfFirst { n ->
                n.kind == NodeKind.OBSERVED && n.located &&
                    TextSimilarity.normalise(n.label) == key &&
                    Geo.metres(n.lat!!, n.lng!!, s.lat!!, s.lng!!) <= SAME_PLACE_M
            }
            if (i >= 0) {
                val old = nodes[i]
                nodes[i] = old.copy(
                    confirmations = old.confirmations + 1, lastConfirmedAt = now,
                    side = if (s.side != Side.NONE) s.side else old.side
                )
            } else {
                nodes += s.copy(createdAt = if (s.createdAt == 0L) now else s.createdAt, lastConfirmedAt = now)
            }
        }
        // Oldest one-off sightings go first when there are too many.
        val obs = nodes.filter { it.kind == NodeKind.OBSERVED }
        if (obs.size <= MAX_OBSERVATIONS) return MindMap(nodes, map.edges)
        val drop = obs.sortedWith(compareBy<Node> { it.confirmations }.thenBy { it.lastConfirmedAt })
            .take(obs.size - MAX_OBSERVATIONS).map { it.id }.toSet()
        return MindMap(nodes.filter { it.id !in drop }, map.edges.filter { it.from !in drop && it.to !in drop })
    }
}

/** JSON for the mind map and ride traces. Pure (org.json only), so it is a JVM test. */
object MindMapJson {
    const val MAX_NODES = 1_500
    const val TRACES_PER_PLACE = 5
    const val MAX_TRACES = 100

    fun json(m: MindMap): JSONObject = JSONObject()
        .put("nodes", JSONArray().also { a -> m.nodes.forEach { a.put(node(it)) } })
        .put("edges", JSONArray().also { a ->
            m.edges.forEach { e -> a.put(JSONObject().put("from", e.from).put("to", e.to).put("kind", e.kind.name).put("note", e.note)) }
        })

    fun map(raw: String?): MindMap = runCatching {
        val o = JSONObject(raw ?: return MindMap())
        val ns = o.optJSONArray("nodes") ?: JSONArray()
        val es = o.optJSONArray("edges") ?: JSONArray()
        MindMap(
            (0 until ns.length()).mapNotNull { runCatching { nodeFrom(ns.getJSONObject(it)) }.getOrNull() },
            (0 until es.length()).mapNotNull {
                runCatching {
                    val e = es.getJSONObject(it)
                    Edge(e.getString("from"), e.getString("to"), EdgeKind.valueOf(e.getString("kind")), e.optString("note"))
                }.getOrNull()
            }
        )
    }.getOrDefault(MindMap())

    /** Oldest-touched nodes go first when the map is full; the rider's own notes go last. */
    fun capped(m: MindMap): MindMap {
        if (m.nodes.size <= MAX_NODES) return m
        val keep = m.nodes.sortedWith(compareBy<Node> { it.kind == NodeKind.ANCHOR }.thenBy { maxOf(it.lastConfirmedAt, it.createdAt) })
            .takeLast(MAX_NODES).map { it.id }.toSet()
        return MindMap(m.nodes.filter { it.id in keep }, m.edges.filter { it.from in keep || it.to in keep })
    }

    private fun node(n: Node) = JSONObject()
        .put("id", n.id).put("kind", n.kind.name).put("label", n.label)
        .put("lat", n.lat ?: JSONObject.NULL).put("lng", n.lng ?: JSONObject.NULL)
        .put("sense", n.sense?.name ?: JSONObject.NULL).put("side", n.side.name)
        .put("source", n.source).put("person", n.person).put("habit", n.habit).put("meaning", n.meaning)
        .put("words", n.words).put("atMetres", n.atMetres ?: JSONObject.NULL)
        .put("createdAt", n.createdAt).put("lastConfirmedAt", n.lastConfirmedAt)
        .put("confirmations", n.confirmations).put("misses", n.misses)

    private fun nodeFrom(o: JSONObject) = Node(
        id = o.getString("id"),
        kind = NodeKind.valueOf(o.getString("kind")),
        label = o.optString("label"),
        lat = o.optDoubleOrNull("lat"),
        lng = o.optDoubleOrNull("lng"),
        sense = o.optString("sense").takeIf { it.isNotBlank() && it != "null" }?.let { s -> runCatching { LandmarkKind.valueOf(s) }.getOrNull() },
        side = runCatching { Side.valueOf(o.optString("side")) }.getOrDefault(Side.NONE),
        source = o.optString("source"),
        person = o.optString("person"),
        habit = o.optString("habit"),
        meaning = o.optString("meaning"),
        words = o.optString("words"),
        atMetres = o.optDoubleOrNull("atMetres"),
        createdAt = o.optLong("createdAt"),
        lastConfirmedAt = o.optLong("lastConfirmedAt"),
        confirmations = o.optInt("confirmations", 1),
        misses = o.optInt("misses")
    )

    fun tracesJson(list: List<RideTrace>): JSONArray = JSONArray().also { a ->
        list.forEach { t ->
            a.put(JSONObject().put("placeKey", t.placeKey).put("placeName", t.placeName).put("recordedAt", t.recordedAt)
                .put("areas", JSONArray(t.areas))
                // Compact: one flat array of lat, lng, metres, t per point.
                .put("p", JSONArray().also { pa -> t.points.forEach { p -> pa.put(p.lat).put(p.lng).put(p.metres).put(p.t) } }))
        }
    }

    fun traces(raw: String?): List<RideTrace> = runCatching {
        val a = JSONArray(raw ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                val p = o.getJSONArray("p")
                val areas = o.optJSONArray("areas")
                RideTrace(
                    placeKey = o.getString("placeKey"),
                    placeName = o.optString("placeName"),
                    points = (0 until p.length() / 4).map { k ->
                        TracePoint(p.getDouble(4 * k), p.getDouble(4 * k + 1), p.getDouble(4 * k + 2), p.getLong(4 * k + 3))
                    },
                    areas = if (areas == null) emptyList() else (0 until areas.length()).map { areas.getString(it) },
                    recordedAt = o.optLong("recordedAt")
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun keepTraces(list: List<RideTrace>): List<RideTrace> =
        list.groupBy { it.placeKey }
            .flatMap { (_, ts) -> ts.sortedBy { it.recordedAt }.takeLast(TRACES_PER_PLACE) }
            .sortedBy { it.recordedAt }
            .takeLast(MAX_TRACES)

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
}
