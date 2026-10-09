package com.cabeye.rider.walk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Walking routes, the rider's detail preference and the study log — on this phone only
 * (SharedPreferences, private to the app). Nothing here is sent to the server: where a blind
 * person walks, door to door, is more sensitive than where their cab went.
 */
class WalkStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("cabeye.walks", Context.MODE_PRIVATE)

    companion object {
        const val MAX_ROUTES = 50
        const val MAX_LOG = 2_000
    }

    fun routes(): List<WalkRoute> = runCatching {
        val arr = JSONArray(prefs.getString("routes", "[]"))
        (0 until arr.length()).map { routeFrom(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(route: WalkRoute) {
        val list = routes().filter { it.id != route.id } + route
        writeRoutes(list.sortedByDescending { it.updatedAt }.take(MAX_ROUTES))
    }

    fun delete(id: String) = writeRoutes(routes().filter { it.id != id })

    fun clear() {
        prefs.edit().remove("routes").remove("log").apply()
    }

    fun profile(): WalkProfile = WalkProfile(
        detailBias = prefs.getInt("detailBias", 0),
        consentGiven = prefs.getBoolean("consent", false)
    )

    fun saveProfile(p: WalkProfile) {
        prefs.edit().putInt("detailBias", p.detailBias.coerceIn(-2, 2)).putBoolean("consent", p.consentGiven).apply()
    }

    fun log(entry: WalkLogEntry) {
        val arr = runCatching { JSONArray(prefs.getString("log", "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("t", entry.t).put("route", entry.routeId).put("event", entry.event).put("detail", entry.detail))
        val trimmed = if (arr.length() > MAX_LOG) JSONArray().also { out ->
            for (i in arr.length() - MAX_LOG until arr.length()) out.put(arr.get(i))
        } else arr
        prefs.edit().putString("log", trimmed.toString()).apply()
    }

    fun logEntries(): List<WalkLogEntry> = runCatching {
        val arr = JSONArray(prefs.getString("log", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            WalkLogEntry(o.optLong("t"), o.optString("route"), o.optString("event"), o.optString("detail"))
        }
    }.getOrDefault(emptyList())

    private fun writeRoutes(list: List<WalkRoute>) {
        val arr = JSONArray()
        list.forEach { arr.put(routeJson(it)) }
        prefs.edit().putString("routes", arr.toString()).apply()
    }

    // ---- JSON ---------------------------------------------------------------------------

    private fun routeJson(r: WalkRoute): JSONObject = JSONObject()
        .put("id", r.id).put("name", r.name).put("aliases", JSONArray(r.aliases))
        .put("placeKey", r.placeKey).put("placeName", r.placeName).put("start", r.start)
        .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt).put("lastWalkedAt", r.lastWalkedAt)
        .put("walks", r.walks).put("helpRequests", r.helpRequests).put("indoor", r.indoor)
        .put("segments", JSONArray().also { arr ->
            r.segments.forEach { s ->
                arr.put(JSONObject().put("metres", s.metres).put("steps", s.steps ?: -1).put("turn", s.turn.name)
                    .put("landmarks", JSONArray().also { la -> s.landmarks.forEach { la.put(landmarkJson(it)) } }))
            }
        })

    private fun landmarkJson(l: Landmark) = JSONObject()
        .put("id", l.id).put("kind", l.kind.name).put("text", l.text).put("side", l.side.name)
        .put("atMetres", l.atMetres).put("cue", l.isTurnCue).put("recordedAt", l.recordedAt)
        .put("lastConfirmedAt", l.lastConfirmedAt).put("confirmations", l.confirmations).put("misses", l.misses)

    private fun routeFrom(o: JSONObject): WalkRoute {
        val segs = o.optJSONArray("segments") ?: JSONArray()
        return WalkRoute(
            id = o.getString("id"),
            name = o.optString("name"),
            aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            placeKey = o.optString("placeKey"),
            placeName = o.optString("placeName"),
            start = o.optString("start"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            lastWalkedAt = o.optLong("lastWalkedAt"),
            walks = o.optInt("walks"),
            helpRequests = o.optInt("helpRequests"),
            indoor = o.optBoolean("indoor"),
            segments = (0 until segs.length()).map { i ->
                val s = segs.getJSONObject(i)
                val ls = s.optJSONArray("landmarks") ?: JSONArray()
                Segment(
                    metres = s.optDouble("metres"),
                    steps = s.optInt("steps", -1).takeIf { it >= 0 },
                    turn = enumOr(s.optString("turn"), TurnDirection.NONE),
                    landmarks = (0 until ls.length()).map { j ->
                        val l = ls.getJSONObject(j)
                        Landmark(
                            id = l.optString("id"),
                            kind = enumOr(l.optString("kind"), LandmarkKind.OTHER),
                            text = l.optString("text"),
                            side = enumOr(l.optString("side"), Side.NONE),
                            atMetres = l.optDouble("atMetres", 0.0),
                            isTurnCue = l.optBoolean("cue"),
                            recordedAt = l.optLong("recordedAt"),
                            lastConfirmedAt = l.optLong("lastConfirmedAt"),
                            confirmations = l.optInt("confirmations", 1),
                            misses = l.optInt("misses")
                        )
                    }
                )
            }
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(fallback)
}
