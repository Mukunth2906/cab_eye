package com.cabeye.rider.demo

import android.content.Context
import com.cabeye.rider.mindmap.Edge
import com.cabeye.rider.mindmap.EdgeKind
import com.cabeye.rider.mindmap.Geo
import com.cabeye.rider.mindmap.MindMap
import com.cabeye.rider.mindmap.MindMapBuilder
import com.cabeye.rider.mindmap.MindMapStore
import com.cabeye.rider.mindmap.Node
import com.cabeye.rider.mindmap.NodeKind
import com.cabeye.rider.mindmap.RideTrace
import com.cabeye.rider.mindmap.SyntheticRide
import com.cabeye.rider.mindmap.TracePoint
import com.cabeye.rider.walk.Landmark
import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.Segment
import com.cabeye.rider.walk.Side
import com.cabeye.rider.walk.TurnDirection
import com.cabeye.rider.walk.WalkProfile
import com.cabeye.rider.walk.WalkRoute
import com.cabeye.rider.walk.WalkStore

/**
 * Demo content for the parts of the rider's memory that live only on the phone: walking
 * routes, the personal mind map (notes, ride cues, areas) and past ride paths.
 *
 * It is the phone half of `scripts/seed_demo.py`, which creates the demo rider's ride history
 * on the backend. Both use the same place ids (`demo-kgh`, `demo-psg` …) so that, once the demo
 * rider is signed in, the cab destinations, the walks from them and the rider's own notes join
 * up into one mind map — exactly as months of real use would leave it.
 *
 * Real Coimbatore coordinates (from the offline atlas). Debug builds only: see Settings.
 */
object DemoData {

    /** Place ids shared with scripts/seed_demo.py. */
    const val HOME = "demo-home"
    const val PSG = "demo-psg"
    const val KGH = "demo-kgh"
    const val MALL = "demo-mall"
    const val RACE = "demo-race"

    private data class P(val key: String, val name: String, val lat: Double, val lng: Double)

    private val home = P(HOME, "Home", 11.0080, 76.9502)
    private val psg = P(PSG, "PSG College of Technology", 11.02481, 77.00292)
    private val kgh = P(KGH, "KG Hospital", 11.0005, 76.97164)
    private val mall = P(MALL, "Brookefields Mall", 11.00887, 76.9594)
    private val race = P(RACE, "Race Course", 11.0017, 76.9713)

    private const val DAY = 86_400_000L

    data class Content(val routes: List<WalkRoute>, val authored: MindMap, val traces: List<RideTrace>)

    /** Everything, timed relative to [now]. Pure: no Android, so it is covered by a JVM test. */
    fun build(now: Long): Content = Content(walks(now), mindMap(now), traces(now))

    /**
     * Writes the demo walks, mind map and ride paths to this phone, replacing any earlier demo
     * content but keeping everything the rider made themselves.
     *
     * @return a sentence saying what was loaded
     */
    fun load(context: Context, now: Long = System.currentTimeMillis()): String {
        val c = build(now)
        val walks = WalkStore(context)
        walks.routes().filter { it.id.contains("demo") }.forEach { walks.delete(it.id) }
        c.routes.forEach(walks::save)
        walks.saveProfile(walks.profile().copy(consentGiven = true))

        val store = MindMapStore(context)
        val mine = store.authored().let { m ->
            m.nodes.filter { it.id.contains("demo") }.fold(m) { acc, n -> acc.without(n.id) }
        }
        store.saveAuthored(mine.plus(c.authored))
        store.replaceTraces(store.traces().filterNot { it.placeKey.startsWith("demo-") } + c.traces)

        return "Loaded ${c.routes.size} walks (${c.routes.sumOf { it.landmarks.size }} landmarks), " +
            "${c.authored.nodes.count { it.kind == NodeKind.ANCHOR }} personal notes, " +
            "${c.authored.nodes.count { it.kind == NodeKind.RIDE_CUE }} ride cues, " +
            "${c.authored.nodes.count { it.kind == NodeKind.AREA }} areas, " +
            "${c.authored.nodes.count { it.kind == NodeKind.OBSERVED }} camera sightings and ${c.traces.size} past ride paths."
    }

    // ---------------------------------------------------------------------------------
    //  Walks: from the cab's drop-off to the door
    // ---------------------------------------------------------------------------------

    private fun lm(
        id: String, kind: LandmarkKind, text: String, side: Side, at: Double, now: Long,
        turnCue: Boolean = false, ageDays: Int = 3, confirmations: Int = 3, misses: Int = 0
    ) = Landmark(
        id = "demo-lm-$id", kind = kind, text = text, side = side, atMetres = at, isTurnCue = turnCue,
        recordedAt = now - 40 * DAY, lastConfirmedAt = now - ageDays * DAY,
        confirmations = confirmations, misses = misses
    )

    private fun walks(now: Long) = listOf(
        // Walked four times: brief directions. One landmark is stale, so it is said as "check for it"
        // and the agent asks about it after the walk.
        WalkRoute(
            id = "demo-walk-kgh",
            name = "KG Hospital outpatient entrance",
            aliases = listOf("hospital entrance", "OP entrance", "outpatient", "clinic"),
            placeKey = kgh.key, placeName = kgh.name, start = "the cab drop-off at the main road",
            segments = listOf(
                Segment(
                    metres = 30.0, steps = 41, turn = TurnDirection.RIGHT,
                    landmarks = listOf(
                        lm("kerb", LandmarkKind.KERB, "the kerb", Side.AHEAD, 2.0, now),
                        lm("tea", LandmarkKind.SMELL, "the tea shop smell", Side.LEFT, 16.0, now),
                        lm("bakery1", LandmarkKind.SMELL, "the bakery smell", Side.LEFT, 24.0, now),
                        lm("gate", LandmarkKind.GATE, "the hospital gate", Side.RIGHT, 30.0, now, turnCue = true)
                    )
                ),
                Segment(
                    metres = 45.0, steps = 61, turn = TurnDirection.LEFT,
                    landmarks = listOf(
                        lm("tactile", LandmarkKind.TACTILE, "tactile paving", Side.AHEAD, 12.0, now),
                        lm("whistle", LandmarkKind.SOUND, "the security guard's whistle", Side.RIGHT, 38.0, now,
                            ageDays = 70, confirmations = 1, misses = 1),
                        lm("pillar", LandmarkKind.POLE, "the round pillar", Side.LEFT, 45.0, now, turnCue = true)
                    )
                ),
                Segment(
                    metres = 12.0, steps = 16,
                    landmarks = listOf(
                        lm("steps", LandmarkKind.STEPS, "three steps up", Side.AHEAD, 8.0, now),
                        lm("door", LandmarkKind.DOOR, "the sliding glass door", Side.AHEAD, 12.0, now)
                    )
                )
            ),
            createdAt = now - 40 * DAY, updatedAt = now - 6 * DAY, lastWalkedAt = now - 6 * DAY, walks = 4
        ),
        // Walked seven times: landmarks only. Shares "the bakery smell" with the hospital walk
        // (the two start 150 m apart), so the mind map joins them as the same bakery.
        WalkRoute(
            id = "demo-walk-amma",
            name = "Amma's house",
            aliases = listOf("amma's door", "mother's house", "amma"),
            placeKey = race.key, placeName = race.name, start = "the Race Course walking track gate",
            segments = listOf(
                Segment(
                    metres = 60.0, steps = 82, turn = TurnDirection.LEFT,
                    landmarks = listOf(
                        lm("bakery2", LandmarkKind.SMELL, "the bakery smell", Side.RIGHT, 10.0, now),
                        lm("bell", LandmarkKind.SOUND, "the temple bell", Side.RIGHT, 30.0, now, confirmations = 6),
                        lm("lamppost", LandmarkKind.POLE, "the lamp post", Side.LEFT, 60.0, now, turnCue = true)
                    )
                ),
                Segment(
                    metres = 25.0, steps = 34, turn = TurnDirection.RIGHT,
                    landmarks = listOf(
                        lm("gravel", LandmarkKind.TEXTURE, "gravel underfoot", Side.NONE, 5.0, now),
                        lm("irongate", LandmarkKind.GATE, "the iron gate that squeaks", Side.RIGHT, 25.0, now, turnCue = true)
                    )
                ),
                Segment(
                    metres = 8.0, steps = 11,
                    landmarks = listOf(
                        lm("ammadoor", LandmarkKind.DOOR, "Amma's door, with the bell on the right", Side.AHEAD, 8.0, now)
                    )
                )
            ),
            createdAt = now - 60 * DAY, updatedAt = now - 2 * DAY, lastWalkedAt = now - 2 * DAY, walks = 7
        ),
        // Walked once, indoors: full step-by-step detail.
        WalkRoute(
            id = "demo-walk-library",
            name = "PSG library",
            aliases = listOf("library", "college library"),
            placeKey = psg.key, placeName = psg.name, start = "the college main gate", indoor = true,
            segments = listOf(
                Segment(
                    metres = 50.0, steps = 68, turn = TurnDirection.RIGHT,
                    landmarks = listOf(
                        lm("railing", LandmarkKind.RAILING, "the railing", Side.LEFT, 5.0, now),
                        lm("fountain", LandmarkKind.SOUND, "the water fountain", Side.RIGHT, 35.0, now),
                        lm("ramp", LandmarkKind.RAMP, "the ramp", Side.AHEAD, 50.0, now, turnCue = true)
                    )
                ),
                Segment(
                    metres = 20.0, steps = 27,
                    landmarks = listOf(
                        lm("hum", LandmarkKind.SOUND, "the photocopier hum", Side.LEFT, 12.0, now),
                        lm("libdoor", LandmarkKind.DOOR, "the library door", Side.AHEAD, 20.0, now)
                    )
                )
            ),
            createdAt = now - 20 * DAY, updatedAt = now - 9 * DAY, lastWalkedAt = now - 9 * DAY, walks = 1
        ),
        // Explore module: a walk round the street at home with the camera; its landmarks are
        // things the camera saw.
        WalkRoute(
            id = "walk-xdemo-street",
            name = "around my street",
            aliases = listOf("my street", "street walk"),
            placeKey = home.key, placeName = home.name, start = "your front gate",
            segments = listOf(
                Segment(
                    metres = 40.0, steps = 55, turn = TurnDirection.LEFT,
                    landmarks = listOf(
                        lm("x-gate", LandmarkKind.GATE, "gate ahead", Side.AHEAD, 2.0, now),
                        lm("x-car", LandmarkKind.OTHER, "parked car on your right", Side.RIGHT, 18.0, now),
                        lm("x-pole", LandmarkKind.POLE, "pole on your left", Side.LEFT, 40.0, now, turnCue = true)
                    )
                ),
                Segment(
                    metres = 30.0, steps = 41,
                    landmarks = listOf(
                        lm("x-bench", LandmarkKind.OTHER, "bench on your left", Side.LEFT, 20.0, now),
                        lm("x-shop", LandmarkKind.DOOR, "shop entrance ahead", Side.AHEAD, 30.0, now)
                    )
                )
            ),
            createdAt = now - 5 * DAY, updatedAt = now - 5 * DAY, lastWalkedAt = now - 5 * DAY, walks = 1
        )
    )

    // ---------------------------------------------------------------------------------
    //  The rider's own notes, ride cues and areas
    // ---------------------------------------------------------------------------------

    private fun placeNode(p: P, now: Long) = Node(
        id = MindMapBuilder.placeId(p.key), kind = NodeKind.PLACE, label = p.name,
        lat = p.lat, lng = p.lng, source = p.key, createdAt = now - 30 * DAY, lastConfirmedAt = now - 2 * DAY
    )

    private fun mindMap(now: Long): MindMap {
        // Fallback place nodes: the derived ones (from ride memory) win once it has loaded.
        val places = listOf(home, psg, kgh, mall, race).map { placeNode(it, now) }

        val anchors = listOf(
            Node(
                id = "a:demo-ravi", kind = NodeKind.ANCHOR, label = "Ravi's tea shop",
                lat = 11.0008, lng = 76.9713, sense = LandmarkKind.SMELL, person = "Ravi",
                habit = "you stop for tea on Fridays", words = "Ravi's tea shop, where I stop for tea on Fridays",
                createdAt = now - 25 * DAY, lastConfirmedAt = now - 4 * DAY, confirmations = 3
            ),
            Node(
                id = "a:demo-bell", kind = NodeKind.ANCHOR, label = "the temple bell",
                sense = LandmarkKind.SOUND, meaning = "you're near Amma's house",
                words = "the temple bell means I'm near Amma's house",
                createdAt = now - 50 * DAY, lastConfirmedAt = now - 2 * DAY, confirmations = 5
            ),
            Node(
                id = "a:demo-priya", kind = NodeKind.ANCHOR, label = "where you meet Priya",
                lat = 11.0091, lng = 76.9597, person = "Priya", habit = "you meet Priya on Sundays",
                words = "remember this as the place I meet Priya",
                createdAt = now - 21 * DAY, lastConfirmedAt = now - 7 * DAY, confirmations = 2
            ),
            Node(
                id = "a:demo-chemist", kind = NodeKind.ANCHOR, label = "Murugan medicals",
                lat = 11.0062, lng = 76.9528, person = "Murugan",
                habit = "you pick up Amma's tablets there", words = "Murugan medicals, where I pick up Amma's tablets",
                createdAt = now - 35 * DAY, lastConfirmedAt = now - 90 * DAY, confirmations = 1
            )
        )

        val trace = traceTo(psg, now - 3 * DAY)
        fun pointAt(m: Double) = trace.points.minByOrNull { kotlin.math.abs(it.metres - m) }!!
        val bump = pointAt(1_400.0)
        val flyover = pointAt(trace.totalMetres * 0.6)
        val kghTrace = traceTo(kgh, now - 6 * DAY)
        val pothole = kghTrace.points.minByOrNull { kotlin.math.abs(it.metres - kghTrace.totalMetres * 0.5) }!!
        val cues = listOf(
            Node(
                id = "cue:demo-bump", kind = NodeKind.RIDE_CUE, label = "the speed bump near the school",
                lat = bump.lat, lng = bump.lng, sense = LandmarkKind.TEXTURE, source = psg.key,
                atMetres = bump.metres, words = "speed bump near the school",
                createdAt = now - 18 * DAY, lastConfirmedAt = now - 3 * DAY, confirmations = 4
            ),
            Node(
                id = "cue:demo-flyover", kind = NodeKind.RIDE_CUE, label = "the flyover",
                lat = flyover.lat, lng = flyover.lng, sense = LandmarkKind.OTHER, source = psg.key,
                atMetres = flyover.metres, words = "that was the flyover",
                createdAt = now - 18 * DAY, lastConfirmedAt = now - 3 * DAY, confirmations = 3
            ),
            Node(
                id = "cue:demo-pothole", kind = NodeKind.RIDE_CUE, label = "the big pothole by the bus stop",
                lat = pothole.lat, lng = pothole.lng, sense = LandmarkKind.TEXTURE, source = kgh.key,
                atMetres = pothole.metres, words = "big pothole by the bus stop",
                createdAt = now - 12 * DAY, lastConfirmedAt = now - 6 * DAY, confirmations = 2
            )
        )

        fun area(name: String) = Node(
            id = "area:demo-" + name.lowercase().replace(' ', '-'), kind = NodeKind.AREA, label = name,
            createdAt = now - 28 * DAY, lastConfirmedAt = now - 3 * DAY
        )
        val rsPuram = area("RS Puram")
        val gandhipuram = area("Gandhipuram")
        val peelamedu = area("Peelamedu")
        val raceCourse = area("Race Course")

        val pid = { p: P -> MindMapBuilder.placeId(p.key) }
        val edges = listOf(
            // Notes give meaning to landmarks on the walks, and places.
            Edge("a:demo-ravi", MindMapBuilder.landmarkId("demo-lm-tea"), EdgeKind.MEANS),
            Edge("a:demo-bell", MindMapBuilder.landmarkId("demo-lm-bell"), EdgeKind.MEANS),
            Edge("a:demo-bell", MindMapBuilder.walkId("demo-walk-amma"), EdgeKind.NEAR),
            Edge("a:demo-priya", pid(mall), EdgeKind.NEAR),
            Edge("a:demo-chemist", pid(home), EdgeKind.NEAR),
            // Cues felt on the way to a destination.
            Edge("cue:demo-bump", pid(psg), EdgeKind.ON_RIDE),
            Edge("cue:demo-flyover", pid(psg), EdgeKind.ON_RIDE),
            Edge("cue:demo-pothole", pid(kgh), EdgeKind.ON_RIDE),
            // Areas the cab passes through, and the area each place is in.
            Edge(rsPuram.id, pid(psg), EdgeKind.ON_RIDE),
            Edge(gandhipuram.id, pid(psg), EdgeKind.ON_RIDE),
            Edge(peelamedu.id, pid(psg), EdgeKind.ON_RIDE),
            Edge(rsPuram.id, pid(kgh), EdgeKind.ON_RIDE),
            Edge(raceCourse.id, pid(kgh), EdgeKind.ON_RIDE),
            Edge(pid(psg), peelamedu.id, EdgeKind.IN_AREA),
            Edge(pid(kgh), raceCourse.id, EdgeKind.IN_AREA),
            Edge(pid(race), raceCourse.id, EdgeKind.IN_AREA),
            Edge(pid(home), rsPuram.id, EdgeKind.IN_AREA),
            Edge(pid(mall), rsPuram.id, EdgeKind.IN_AREA)
        )
        fun seen(id: String, label: String, lat: Double, lng: Double, side: Side, times: Int, daysAgo: Int, sense: LandmarkKind = LandmarkKind.OTHER) = Node(
            id = "obs:demo-$id", kind = NodeKind.OBSERVED, label = label, lat = lat, lng = lng, side = side, sense = sense,
            source = "ON_DEVICE", words = "$label ${side.spoken}".trim(),
            createdAt = now - (daysAgo + 10) * DAY, lastConfirmedAt = now - daysAgo * DAY, confirmations = times
        )
        val sightings = listOf(
            seen("kgh-steps", "steps", 11.0006, 76.9716, Side.AHEAD, 3, 6, LandmarkKind.STEPS),
            seen("kgh-gate", "gate", 11.0004, 76.9718, Side.RIGHT, 4, 6, LandmarkKind.GATE),
            seen("kgh-bench", "bench", 11.0007, 76.9714, Side.LEFT, 2, 6),
            seen("race-bench", "bench", 11.0018, 76.9712, Side.LEFT, 5, 2),
            seen("race-pole", "pole", 11.0019, 76.9714, Side.RIGHT, 2, 2, LandmarkKind.POLE),
            seen("race-dog", "dog", 11.0016, 76.9711, Side.AHEAD, 1, 2),
            seen("home-gate", "gate", 11.0081, 76.9503, Side.AHEAD, 6, 1, LandmarkKind.GATE),
            seen("home-car", "car", 11.0082, 76.9505, Side.RIGHT, 3, 1),
            seen("home-plant", "potted plant", 11.0080, 76.9501, Side.LEFT, 2, 1)
        )

        return MindMap(places + anchors + cues + listOf(rsPuram, gandhipuram, peelamedu, raceCourse) + sightings, edges)
    }

    // ---------------------------------------------------------------------------------
    //  Past ride paths: "your usual way", the repeat-ride preview, "not the usual way"
    // ---------------------------------------------------------------------------------

    private fun traceTo(dest: P, at: Long): RideTrace {
        val fixes = SyntheticRide.route(home.lat, home.lng, dest.lat, dest.lng, at)
        var m = 0.0
        val points = fixes.mapIndexed { i, f ->
            if (i > 0) m += Geo.metres(fixes[i - 1].lat, fixes[i - 1].lng, f.lat, f.lng)
            TracePoint(f.lat, f.lng, m, f.t)
        }
        val areas = when (dest.key) {
            PSG -> listOf("RS Puram", "Gandhipuram", "Peelamedu")
            KGH -> listOf("RS Puram", "Race Course")
            else -> listOf("RS Puram")
        }
        return RideTrace(dest.key, dest.name, points, areas, recordedAt = points.last().t)
    }

    private fun traces(now: Long) = listOf(
        traceTo(psg, now - 10 * DAY),
        traceTo(psg, now - 7 * DAY),
        traceTo(psg, now - 3 * DAY),
        traceTo(kgh, now - 13 * DAY),
        traceTo(kgh, now - 6 * DAY)
    )
}
