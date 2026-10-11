package com.cabeye.rider.mindmap

import android.content.Context
import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.Side
import org.json.JSONArray
import org.json.JSONObject

/**
 * The rider's own mind-map content — personal notes, ride cues, areas, remembered places — and
 * the traces of past rides, on this phone only (SharedPreferences, private to the app). Like the
 * walking routes, none of it is sent to the server: what a place means to a blind rider, and
 * the way they ride home, is theirs.
 */
class MindMapStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("cabeye.mindmap", Context.MODE_PRIVATE)

    fun authored(): MindMap = MindMapJson.map(prefs.getString("map", null))

    fun saveAuthored(map: MindMap) {
        prefs.edit().putString("map", MindMapJson.json(MindMapJson.capped(map)).toString()).apply()
    }

    fun traces(): List<RideTrace> = MindMapJson.traces(prefs.getString("traces", null))

    /** Keeps the last [MindMapJson.TRACES_PER_PLACE] rides to each place. */
    fun addTrace(trace: RideTrace) {
        val kept = MindMapJson.keepTraces(traces() + trace)
        prefs.edit().putString("traces", MindMapJson.tracesJson(kept).toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("map").remove("traces").apply()
    }

    /** "Forget my history": the ways the rider rode, not their own notes and cues. */
    fun clearTraces() {
        prefs.edit().remove("traces").apply()
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
