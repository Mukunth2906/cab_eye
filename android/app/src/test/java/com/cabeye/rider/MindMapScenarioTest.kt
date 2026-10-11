package com.cabeye.rider

import com.cabeye.rider.memory.VisitedPlace
import com.cabeye.rider.mindmap.AnchorParser
import com.cabeye.rider.mindmap.Edge
import com.cabeye.rider.mindmap.EdgeKind
import com.cabeye.rider.mindmap.Geo
import com.cabeye.rider.mindmap.MindMap
import com.cabeye.rider.mindmap.MindMapAgent
import com.cabeye.rider.mindmap.MindMapBuilder
import com.cabeye.rider.mindmap.MindMapCommands
import com.cabeye.rider.mindmap.NearbySuggestionAgent
import com.cabeye.rider.mindmap.Node
import com.cabeye.rider.mindmap.NodeKind
import com.cabeye.rider.mindmap.RideCommands
import com.cabeye.rider.mindmap.RideGuideAgent
import com.cabeye.rider.mindmap.RideTrace
import com.cabeye.rider.walk.DetailLevel
import com.cabeye.rider.walk.Landmark
import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.Segment
import com.cabeye.rider.walk.Side
import com.cabeye.rider.walk.TurnDirection
import com.cabeye.rider.walk.WalkNarrator
import com.cabeye.rider.walk.WalkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil

/**
 * Five days of one rider in Coimbatore, through the real mind-map code, with no backend, no
 * network and no API keys: area names and well-known places come from the offline Coimbatore
 * atlas bundled in the app (real OpenStreetMap data), and Grok is off. Only the movement — the
 * rider's GPS along the way — is synthetic.
 *
 * Writes what the rider would hear to build/mind-map-simulation.txt, and checks the key moments.
 * Only the ViewModel's glue (screens, microphone, TTS) is not exercised here.
 */
class MindMapScenarioTest {

    private val out = StringBuilder()
    private fun scene(s: String) { out.append("\n=== ").append(s).append(" ===\n") }
    private fun rider(s: String) { out.append("  RIDER: ").append(s).append('\n') }
    private fun app(s: String) { out.append("  APP:   ").append(s).append('\n') }
    private fun note(s: String) { out.append("         (").append(s).append(")\n") }

    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L

    // Home in Saibaba Colony; the clinic near Town Hall (≈3.5 km).
    private val clinicLat = 10.9985
    private val clinicLng = 76.9640
    private val way = listOf(
        11.0244 to 76.9450, // home, Saibaba Colony
        11.0150 to 76.9500,
        11.0068 to 76.9560, // RS Puram
        11.0030 to 76.9600,
        clinicLat to clinicLng
    )
    private val key = "p-clinic"

    /** The ride as fixes every 100 m, 15 s apart; [detour] pushes the middle 600 m west. */
    private fun route(detour: Boolean): List<Pair<Double, Double>> {
        val pts = mutableListOf(way.first())
        for (i in 0 until way.size - 1) {
            val (a, b) = way[i] to way[i + 1]
            val n = ceil(Geo.metres(a.first, a.second, b.first, b.second) / 100).toInt()
            for (k in 1..n) pts += (a.first + (b.first - a.first) * k / n) to (a.second + (b.second - a.second) * k / n)
        }
        if (!detour) return pts
        return pts.mapIndexed { i, p ->
            if (i in 14..19) p.first to p.second - 600 / (111_320.0 * Math.cos(Math.toRadians(p.first))) else p
        }
    }

    // ---- live mode: real OpenStreetMap data through a running backend ---------------------
    // Set CABEYE_LIVE_URL (e.g. http://localhost:8080) and CABEYE_LIVE_TOKEN (a rider's token)
    // and the area names and nearby places come from the backend's /me/places endpoints —
    // the same calls the app makes outside the atlas — instead of the bundled atlas.
    private val liveUrl: String? = System.getenv("CABEYE_LIVE_URL")?.trimEnd('/')
    private val liveToken: String? = System.getenv("CABEYE_LIVE_TOKEN")
    private val live get() = !liveUrl.isNullOrBlank() && !liveToken.isNullOrBlank()

    private fun liveGet(path: String): String? = runCatching {
        val c = java.net.URL(liveUrl + path).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $liveToken")
        c.connectTimeout = 5_000
        c.readTimeout = 40_000
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader(Charsets.UTF_8).readText() else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun liveArea(lat: Double, lng: Double): String? =
        liveGet(String.format(java.util.Locale.ROOT, "/me/places/area?lat=%.5f&lng=%.5f", lat, lng))
            ?.let { org.json.JSONObject(it).optString("area").takeIf { a -> a.isNotBlank() && a != "null" } }

    /** The offline atlas the app ships with: real places and areas, no network. */
    private val atlas = com.cabeye.rider.places.Atlas.parse(File("src/main/assets/atlas/coimbatore.json").readText(Charsets.UTF_8))!!
        .withAreas(com.cabeye.rider.places.AtlasStore.gazetteerAreas())

    /** The area along the way: the bundled atlas, as the app does in Coimbatore (or the backend, live). */
    private fun areaAt(lat: Double, lng: Double): String? = if (live) liveArea(lat, lng) else atlas.areaAt(lat, lng)

    /**
     * One ride, the way the ViewModel drives the guide: a fix every 15 s, the area every fourth
     * fix, the rider's words at given fixes. @return the guide, to save its trace and cues.
     */
    private fun ride(
        dayStart: Long, usual: List<RideTrace>, cues: List<Node>, detour: Boolean = false,
        words: Map<Int, String> = emptyMap(), tapAt: Int? = null, walk: WalkRoute? = null
    ): RideGuideAgent {
        val g = RideGuideAgent(key, "KG Clinic", usual, cues, destLat = clinicLat, destLng = clinicLng)
        app("On the way to KG Clinic.")
        g.preview()?.let { app(it) } ?: note("first ride there: no preview")
        val pts = route(detour)
        pts.forEachIndexed { i, (lat, lng) ->
            val t = dayStart + i * 15_000L
            g.onFix(lat, lng, t).forEach { say ->
                val extra = if (say.kind == RideGuideAgent.Kind.ARRIVING)
                    walk?.let { " Your walk to ${it.name}: ${WalkNarrator.cueLine(it)}. Say guide me when you're out." } ?: " Get ready to step out."
                else ""
                app(say.text + extra)
            }
            if (i % 4 == 0) areaAt(lat, lng)?.let { area -> g.onArea(area, t)?.let { app(it.text) } }
            if (i == tapAt) {
                rider("(taps the screen)")
                app("On the way to KG Clinic. ${Geo.spokenDistance(g.metres).removePrefix("about ")} so far." +
                    (g.briefStatus()?.let { " $it" } ?: ""))
            }
            words[i]?.let { said ->
                rider("(holds the screen) $said")
                when (RideCommands.parse(said)) {
                    RideCommands.Command.WHERE_AM_I -> app(g.whereAmI(null))
                    RideCommands.Command.UNDO -> g.undo()?.removed?.let { app("Okay, I've taken out ${MindMapAgent.the(it.label)}.") }
                    else -> when (val m = g.mark(said, t, "d${dayStart / day}-$i")) {
                        is RideGuideAgent.Marked.Cue -> app(m.spoken)
                        is RideGuideAgent.Marked.VisualOnly -> app("I'll only keep what you can feel, hear or smell on the way.")
                        null -> app("(not a cue: the app answers with the ride status)")
                    }
                }
            }
        }
        return g
    }

    private val clinicWalk = WalkRoute(
        id = "w-clinic", name = "clinic door", placeKey = key, placeName = "KG Clinic", start = "where the cab stops",
        segments = listOf(
            Segment(20.0, 30, TurnDirection.LEFT, listOf(
                Landmark("l1", LandmarkKind.SMELL, "bakery smell", Side.LEFT, 18.0, isTurnCue = true, recordedAt = t0, lastConfirmedAt = t0))),
            Segment(15.0, 22, TurnDirection.NONE, listOf(
                Landmark("l2", LandmarkKind.KERB, "kerb", atMetres = 5.0, recordedAt = t0, lastConfirmedAt = t0),
                Landmark("l3", LandmarkKind.DOOR, "glass door", Side.AHEAD, 15.0, recordedAt = t0, lastConfirmedAt = t0)))
        ),
        createdAt = t0, updatedAt = t0, lastWalkedAt = t0, walks = 1
    )

    @Test
    fun fiveDaysInCoimbatore() {
        var authored = MindMap()
        val traces = mutableListOf<RideTrace>()
        var places = emptyList<VisitedPlace>()
        val routes = mutableListOf<WalkRoute>()
        fun map() = MindMapBuilder.build(authored, routes, places)
        fun keepCues(g: RideGuideAgent) {
            val cues = g.changedCues()
            authored = authored.upsert(cues, cues.map { Edge(it.id, MindMapBuilder.placeId(key), EdgeKind.ON_RIDE) })
        }
        fun keepTrace(g: RideGuideAgent, now: Long) {
            val tr = g.trace(now) ?: return
            traces += tr
            val areas = tr.areas.distinct().map { Node("area:" + it.lowercase().replace(' ', '-'), NodeKind.AREA, it, createdAt = now) }
            authored = authored.upsert(areas, areas.map { Edge(it.id, MindMapBuilder.placeId(key), EdgeKind.ON_RIDE) } +
                Edge(MindMapBuilder.placeId(key), areas.last().id, EdgeKind.IN_AREA))
        }

        // ---------------------------------------------------------------- day 1
        scene("Day 1 · first ride to KG Clinic")
        rider("Take me to KG Clinic.")
        note("first ride ever: the guide introduces itself once")
        app("On the way, tap the screen to hear where we are. Hold it and tell me what you notice, like speed bump or flyover, and I'll remind you next time.")
        val d1 = ride(t0, traces, emptyList(), words = mapOf(
            10 to "we just went over a speed bump",
            20 to "that was the flyover",
            21 to "I can hear the temple bells",
            22 to "undo",
            26 to "where am I"
        ))
        keepCues(d1)
        keepTrace(d1, t0 + 30 * 60_000)
        app("You've arrived. 3.6 kilometres, 92 rupees.")

        scene("Day 1 · after paying and feedback: a place the rider has never been to")
        val newHere = NearbySuggestionAgent.isNew(key, places, hasWalk = false, name = "KG Clinic", countsThisVisit = false)
        assertTrue(newHere)
        val osm = if (live) liveGet(String.format(java.util.Locale.ROOT, "/me/places/nearby?lat=%.5f&lng=%.5f&radius=600", clinicLat, clinicLng))
            ?.let { com.cabeye.rider.mindmap.NearbyJson.osm(it) }.orEmpty() else emptyList()
        val fromOsm = NearbySuggestionAgent.pick(clinicLat, clinicLng, osm, "KG Clinic")
        val picks = fromOsm.ifEmpty {
            note("no Google key: real places from the offline Coimbatore atlas bundled in the app")
            NearbySuggestionAgent.pick(clinicLat, clinicLng, atlas.nearby(clinicLat, clinicLng, 600.0), "KG Clinic")
        }
        if (fromOsm.isNotEmpty()) note("no Google key: live OpenStreetMap data through the backend")
        assertTrue(picks.isNotEmpty())
        app(NearbySuggestionAgent.offer(picks, clinicLat, clinicLng, newHere = true))
        rider("Tell me more.")
        app(NearbySuggestionAgent.more(picks, clinicLat, clinicLng))
        rider("Remember them.")
        places = listOf(VisitedPlace(placeKey = key, name = "KG Clinic", latitude = clinicLat, longitude = clinicLng,
            visitCount = 1, lastVisitedAt = t0))
        val clinicNode = map().node(MindMapBuilder.placeId(key))!!
        val (kept, keptEdges) = NearbySuggestionAgent.remember(picks, clinicNode, t0)
        authored = authored.upsert(kept, keptEdges)
        app("Saved ${MindMapAgent.spokenList(picks.map { it.first.name })} to your mind map. Ask what's around KG Clinic to hear them again.")

        scene("Day 1 · the walk to the clinic door, and what places mean")
        note("the walk was recorded with the existing walking agent")
        routes += clinicWalk
        rider("The bakery smell is where I buy bread for Amma.")
        val bread = (MindMapCommands.parse("the bakery smell is where I buy bread for Amma") as MindMapCommands.Command.Remember).anchor
        val r1 = MindMapAgent.remember(map(), bread, map().node(MindMapBuilder.placeId(key)), null, null, t0, "n1")
        authored = authored.upsert(r1.nodes, r1.edges)
        app(r1.spoken)

        rider("Remember this as the place I meet Priya.")
        note("said 80 m from the clinic: the phone's position is kept")
        val priya = (MindMapCommands.parse("remember this as the place I meet Priya") as MindMapCommands.Command.Remember).anchor
        val r2 = MindMapAgent.remember(map(), priya, null, 10.9992, 76.9645, t0, "n2")
        authored = authored.upsert(r2.nodes, r2.edges)
        app(r2.spoken + " To come back by cab, say: take me to ${AnchorParser.firstPerson(r2.nodes.single().label)}.")

        rider("The red sign means turn left.")
        assertTrue(MindMapCommands.parse("the red sign means turn left") is MindMapCommands.Command.VisualOnly)
        app("I'll only keep what you can feel, hear or smell, or what a place means to you. A sign or a colour won't help you find it.")

        // ---------------------------------------------------------------- day 2
        scene("Day 2 · the same ride, with a detour")
        rider("Take me to KG Clinic.")
        val cues2 = map().nodes.filter { it.kind == NodeKind.RIDE_CUE }
        assertEquals(setOf("speed bump", "flyover"), cues2.map { it.label }.toSet())
        val d2 = ride(t0 + day, traces, cues2, detour = true, tapAt = 24, walk = clinicWalk)
        keepCues(d2)
        keepTrace(d2, t0 + day + 30 * 60_000)

        scene("Day 2 · on foot at the clinic")
        rider("What's around KG Clinic?")
        val around = MindMapAgent.around(map(), map().node(MindMapBuilder.placeId(key))!!, t0 + day)
        app(around)
        assertTrue(around, around.contains("you buy bread for amma"))

        rider("Guide me to the clinic door.")
        app(WalkNarrator.narrate(clinicWalk, DetailLevel.BRIEF, t0 + day, MindMapAgent.notesFor(map(), clinicWalk.id)))

        rider("What's around here?  (later, 300 m away, near Town Hall)")
        app(MindMapAgent.nearPoint(map(), 10.9975, 76.9615, areaAt(10.9975, 76.9615), t0 + day) ?: "(nothing known: well-known places offered)")

        // ---------------------------------------------------------------- day 3
        scene("Day 3 · booking a spot that is on no map")
        rider("Take me to where I meet Priya.")
        val spot = MindMapAgent.bookable(map(), "where I meet Priya")
        assertNotNull(spot)
        app("Booking auto to ${spot!!.label}, the place you told me about. Say cancel to stop.")
        note("cab goes to ${"%.4f".format(java.util.Locale.ROOT, spot.lat)}, ${"%.4f".format(java.util.Locale.ROOT, spot.lng)}: the exact spot")
        rider("Take me to the tea shop.")
        assertEquals(null, MindMapAgent.bookable(map(), "the tea shop"))
        note("nothing the rider taught matches: the normal Google / gazetteer search runs")

        // ---------------------------------------------------------------- day 5
        scene("Day 5 · the third ride the same way: familiar, so less is said")
        traces += traces.last().copy(recordedAt = t0 + 3 * day)
        rider("Take me to KG Clinic.")
        val d5 = ride(t0 + 4 * day, traces, map().nodes.filter { it.kind == NodeKind.RIDE_CUE }, walk = clinicWalk)
        assertTrue(d5.familiar)

        scene("Without a Grok key")
        rider("The shop with the noisy fan is my stop.")
        note("the rules don't understand it; with no key the server answers 404, so the app just answers as before")
        assertEquals(null, MindMapCommands.parse("the shop with the noisy fan is my stop"))

        scene("Mind map")
        rider("Read my mind map.")
        app(MindMapAgent.summary(map()))

        File(if (live) "build/mind-map-live.txt" else "build/mind-map-simulation.txt").writeText(out.toString())
    }
}
