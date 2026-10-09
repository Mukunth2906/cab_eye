package com.cabeye.rider

import com.cabeye.rider.walk.DetailLevel
import com.cabeye.rider.walk.Landmark
import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.LandmarkParser
import com.cabeye.rider.walk.LandmarkParser.Result
import com.cabeye.rider.walk.RouteBuilder
import com.cabeye.rider.walk.RouteMemory
import com.cabeye.rider.walk.Segment
import com.cabeye.rider.walk.Side
import com.cabeye.rider.walk.StepDetector
import com.cabeye.rider.walk.TurnDirection
import com.cabeye.rider.walk.WalkCommands
import com.cabeye.rider.walk.WalkCommands.Command
import com.cabeye.rider.walk.WalkCommands.Guide
import com.cabeye.rider.walk.WalkEvent
import com.cabeye.rider.walk.WalkLogEntry
import com.cabeye.rider.walk.WalkNarrator
import com.cabeye.rider.walk.WalkProfile
import com.cabeye.rider.walk.WalkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The walking-route memory, end to end on synthetic walks: the rider's words become
 * landmarks (never visual ones), a recorded walk becomes O&M-style stretches, the narration
 * shrinks with familiarity and never claims the way is clear, and the stored route is checked,
 * corrected and updated when it changes.
 */
class WalkRouteTest {

    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    // ---------------------------------------------------------------- landmarks from words

    @Test
    fun sensedLandmarksAreKeptWithTheirSide() {
        val bakery = LandmarkParser.parse("I can smell the bakery on my left") as Result.Found
        assertEquals(LandmarkKind.SMELL, bakery.kind)
        assertEquals(Side.LEFT, bakery.side)
        assertEquals("bakery smell", bakery.text)

        val kerb = LandmarkParser.parse("there's a kerb here") as Result.Found
        assertEquals(LandmarkKind.KERB, kerb.kind)
        assertEquals("kerb", kerb.text)

        assertEquals(LandmarkKind.TACTILE, (LandmarkParser.parse("tactile tiles under my feet") as Result.Found).kind)
        assertEquals(LandmarkKind.TEXTURE, (LandmarkParser.parse("the floor changes to tiles") as Result.Found).kind)
        assertEquals(LandmarkKind.SOUND, (LandmarkParser.parse("fountain sound on the right") as Result.Found).kind)
        assertEquals(Side.RIGHT, (LandmarkParser.parse("fountain sound on the right") as Result.Found).side)
        val steps = LandmarkParser.parse("three steps up") as Result.Found
        assertEquals(LandmarkKind.STEPS, steps.kind)
        assertEquals("three steps up", steps.text)
        val door = LandmarkParser.parse("the red glass door straight ahead") as Result.Found
        assertEquals(LandmarkKind.DOOR, door.kind)
        assertEquals("glass door", door.text)          // the colour is no use to the rider: dropped
        assertEquals(Side.AHEAD, door.side)
    }

    @Test
    fun visualOnlyCuesAreRefusedAndTurnsAreTurns() {
        assertTrue(LandmarkParser.parse("the big red sign board") is Result.VisualOnly)
        assertTrue(LandmarkParser.parse("a blue poster") is Result.VisualOnly)
        assertEquals(Result.TurnHere(TurnDirection.LEFT), LandmarkParser.parse("turning left here"))
        assertEquals(Result.TurnHere(TurnDirection.RIGHT), LandmarkParser.parse("right turn"))
        assertEquals(Result.TurnHere(TurnDirection.SLIGHT_LEFT), LandmarkParser.parse("bear left"))
        assertNull(LandmarkParser.parse("hmm okay"))

        val cue = LandmarkParser.parse("the bakery smell is the cue for the left turn") as Result.Found
        assertEquals(LandmarkKind.SMELL, cue.kind)
        assertEquals(TurnDirection.LEFT, cue.cueFor)
        assertEquals("bakery smell", cue.text)
        assertEquals(Side.NONE, cue.side)                // "left" here is the turn, not a side
    }

    // ---------------------------------------------------------------- a recorded walk

    /** 30 steps north, kerb, compass turn right, 20 steps east past the bakery, "turning left", 14 steps to the glass door. */
    private fun walk(firstStretchSteps: Int = 30, withGps: Boolean = true): List<WalkEvent> {
        val ev = mutableListOf<WalkEvent>()
        var t = now
        var lat = 11.0168
        var lng = 76.9665
        val stride = 0.75
        fun stretch(steps: Int, heading: Double) {
            repeat(steps) {
                // heading samples every 200 ms, one step every 600 ms, a fix every second
                for (k in 0 until 3) {
                    ev += WalkEvent.Heading(t, heading + (if (k == 1) 4.0 else -3.0))
                    t += 200
                }
                ev += WalkEvent.Step(t)
                lat += stride * cos(Math.toRadians(heading)) / 111_320.0
                lng += stride * sin(Math.toRadians(heading)) / (111_320.0 * cos(Math.toRadians(lat)))
                if (withGps && it % 2 == 0) ev += WalkEvent.Fix(t, lat, lng, 6f)
            }
        }
        stretch(firstStretchSteps, 0.0)
        ev += WalkEvent.Mark(t, "kerb here")
        // turning right at the kerb: heading swings over a second, then holds
        for (h in listOf(20.0, 45.0, 70.0, 88.0)) { t += 250; ev += WalkEvent.Heading(t, h) }
        stretch(8, 90.0)
        ev += WalkEvent.Mark(t, "bakery smell on my left")
        stretch(12, 90.0)
        ev += WalkEvent.Mark(t, "turning left here")
        stretch(14, 0.0)
        ev += WalkEvent.Mark(t, "glass door ahead")
        ev += WalkEvent.Mark(t, "the yellow sign board")
        return ev
    }

    @Test
    fun aWalkBecomesStretchesTurnsAndLandmarks() {
        val built = RouteBuilder.build(walk(), now)
        assertFalse(built.indoor)
        assertEquals(0.75, built.strideMetres, 0.05)        // calibrated from GPS, not the 0.7 default
        assertEquals(listOf("yellow sign board"), built.refused)

        val s = built.segments
        assertEquals(3, s.size)
        assertEquals(TurnDirection.RIGHT, s[0].turn)
        assertEquals(TurnDirection.LEFT, s[1].turn)
        assertEquals(TurnDirection.NONE, s[2].turn)
        assertEquals(22.5, s[0].metres, 2.5)
        assertEquals(15.0, s[1].metres, 2.5)
        assertEquals(10.5, s[2].metres, 2.0)

        val kerb = s[0].landmarks.single()
        assertEquals(LandmarkKind.KERB, kerb.kind)
        assertTrue("a landmark right at a turn is that turn's cue", kerb.isTurnCue)
        val bakery = s[1].landmarks.single()
        assertEquals(LandmarkKind.SMELL, bakery.kind)
        assertEquals(Side.LEFT, bakery.side)
        assertFalse(bakery.isTurnCue)
        assertEquals(LandmarkKind.DOOR, s[2].landmarks.single().kind)
    }

    @Test
    fun indoorsTheStepsStillGiveTheRoute() {
        val built = RouteBuilder.build(walk(withGps = false), now)
        assertTrue(built.indoor)
        assertEquals(RouteBuilder.DEFAULT_STRIDE_M, built.strideMetres, 0.001)
        assertEquals(listOf(TurnDirection.RIGHT, TurnDirection.LEFT, TurnDirection.NONE), built.segments.map { it.turn })
        assertEquals(21.0, built.segments[0].metres, 1.0)    // 30 steps × 0.7 m
    }

    @Test
    fun turningThePhoneWhileStandingStillIsNotATurn() {
        val ev = mutableListOf<WalkEvent>()
        var t = now
        repeat(10) { ev += WalkEvent.Heading(t, 0.0); t += 200; ev += WalkEvent.Heading(t, 2.0); t += 200; ev += WalkEvent.Step(t); t += 200 }
        repeat(30) { ev += WalkEvent.Heading(t, 120.0); t += 200 }       // spun the phone, no steps
        repeat(10) { ev += WalkEvent.Heading(t, 0.0); t += 200; ev += WalkEvent.Step(t); t += 400 }
        val segs = RouteBuilder.build(ev, now).segments
        assertEquals(1, segs.size)
        assertEquals(TurnDirection.NONE, segs[0].turn)
    }

    // ---------------------------------------------------------------- narration

    private fun route(walks: Int = 0, help: Int = 0, landmarkAge: Long = 0L): WalkRoute {
        val built = RouteBuilder.build(walk(), now - landmarkAge)
        return WalkRoute(id = "w1", name = "clinic door", placeName = "Gandhipuram Clinic", start = "where the auto drops me",
            segments = built.segments, createdAt = now - landmarkAge, updatedAt = now - landmarkAge, walks = walks, helpRequests = help)
    }

    @Test
    fun fullNarrationReadsLikeAnOAndMSpecialist() {
        val text = WalkNarrator.narrate(route(), DetailLevel.FULL, now)
        assertTrue(text, text.startsWith("Clinic door, from where the auto drops me: about 45 metres, 2 turns."))
        assertTrue(text, text.contains("Part 1. Walk straight about 20 metres, about 30 of your steps. Then turn right at the kerb."))
        assertTrue(text, text.contains("You'll pass the bakery smell on your left after about 6 metres."))
        assertTrue(text, text.contains("Then turn left."))
        assertTrue(text, text.contains("You'll reach the glass door ahead. That's the end of the route."))
        assertTrue(text, text.endsWith(WalkNarrator.SAFETY_NOTE))
    }

    @Test
    fun detailShrinksWithFamiliarityAndGrowsWithHelp() {
        val p = WalkProfile()
        assertEquals(DetailLevel.FULL, WalkNarrator.levelFor(route(walks = 0), p))
        assertEquals(DetailLevel.BRIEF, WalkNarrator.levelFor(route(walks = 3), p))
        assertEquals(DetailLevel.LANDMARKS, WalkNarrator.levelFor(route(walks = 6), p))
        assertEquals(DetailLevel.BRIEF, WalkNarrator.levelFor(route(walks = 6, help = 1), p))
        assertEquals(DetailLevel.FULL, WalkNarrator.levelFor(route(walks = 6), WalkProfile(detailBias = 2)))
        assertEquals(DetailLevel.LANDMARKS, WalkNarrator.levelFor(route(walks = 3), WalkProfile(detailBias = -1)))

        assertEquals("Right at the kerb, left after about 15 metres, then the glass door.",
            WalkNarrator.narrate(route(walks = 6), DetailLevel.LANDMARKS, now))
        val brief = WalkNarrator.narrate(route(walks = 3), DetailLevel.BRIEF, now)
        assertTrue(brief, brief.contains("about 20 metres, then right at the kerb; about 15 metres, then left; about 10 metres to the glass door ahead."))
    }

    @Test
    fun neverClaimsTheWayIsClearAndFlagsOldLandmarks() {
        val old = route(landmarkAge = 45 * day)
        for (level in DetailLevel.entries) {
            val text = WalkNarrator.narrate(old, level, now).lowercase()
            assertFalse(text, Regex("(?<!can't tell whether )the (way|path|road) is (clear|safe)").containsMatchIn(text))
            assertFalse(text, text.contains("no obstacles") || text.contains("safe to cross"))
            assertTrue(text, text.contains("was last confirmed 6 weeks ago, so check for it"))
        }
    }

    // ---------------------------------------------------------------- memory over time

    @Test
    fun theAgentChecksTheStalestLandmarkAndDropsOneMissingTwice() {
        val r = route(walks = 2, landmarkAge = 20 * day)
        val ask = RouteMemory.toVerify(r, now)
        assertNotNull(ask)
        val (once, removed1) = RouteMemory.miss(r, ask!!.id, now)
        assertFalse(removed1)
        assertTrue(once.landmarks.first { it.id == ask.id }.confidence < 0.6)
        assertTrue(WalkNarrator.narrate(once, DetailLevel.FULL, now).contains("wasn't there last time, so don't rely on it"))
        val (twice, removed2) = RouteMemory.miss(once, ask.id, now)
        assertTrue(removed2)
        assertTrue(twice.landmarks.none { it.id == ask.id })

        val confirmed = RouteMemory.confirm(r, ask.id, now)
        assertEquals(now, confirmed.landmarks.first { it.id == ask.id }.lastConfirmedAt)
        assertNull("a fresh route has nothing to ask", RouteMemory.toVerify(route(), now))
    }

    @Test
    fun theRiderCanSayWhichLandmarkMarksATurn() {
        val r = route()
        val (fixed, said) = RouteMemory.correct(r, "the bakery smell is the cue for the left turn", null, now, "lm-x")!!
        assertEquals("Got it. The bakery smell marks the left turn in part 2.", said)
        val cue = fixed.segments[1].landmarks.last()
        assertTrue(cue.isTurnCue)
        assertEquals(fixed.segments[1].metres, cue.atMetres, 0.01)
        assertEquals(1, fixed.landmarks.count { it.kind == LandmarkKind.SMELL })   // moved, not duplicated
        assertTrue(WalkNarrator.narrate(fixed, DetailLevel.LANDMARKS, now).contains("left after the bakery smell"))
    }

    @Test
    fun aChangedRouteIsNoticedAndASameRouteIsConfirmed() {
        val stored = route(walks = 3, landmarkAge = 20 * day)
        val again = RouteBuilder.build(walk(), now).segments
        assertEquals(RouteMemory.Comparison.Same, RouteMemory.compare(stored, again))
        val rewalked = RouteMemory.rewalked(stored, again, now)
        assertEquals(4, rewalked.walks)
        assertTrue(rewalked.landmarks.all { it.lastConfirmedAt == now })

        // Construction: the first stretch is now much longer.
        val detour = RouteBuilder.build(walk(firstStretchSteps = 60), now).segments
        val changed = RouteMemory.compare(stored, detour) as RouteMemory.Comparison.Changed
        assertEquals(0, changed.part)
        assertEquals("Part 1 was about 45 metres this time; I had about 20.", changed.sentence)
        val updated = RouteMemory.replaced(stored, detour, now)
        assertEquals(1, updated.walks)      // a new route to learn again
        assertEquals(45.0, updated.segments[0].metres, 3.0)
        assertTrue(updated.landmarks.any { it.kind == LandmarkKind.KERB })

        val turnsChanged = listOf(Segment(20.0, turn = TurnDirection.LEFT), Segment(30.0))
        assertTrue(RouteMemory.compare(stored, turnsChanged) is RouteMemory.Comparison.Changed)
    }

    @Test
    fun routesAreFoundByTheRidersWords() {
        val clinic = route()
        val work = clinic.copy(id = "w2", name = "office entrance", placeName = "Tidel Park", aliases = listOf("work"))
        val routes = listOf(clinic, work)
        assertEquals("w1", RouteMemory.find(routes, "the clinic door")?.id)
        assertEquals("w1", RouteMemory.find(routes, "clinic")?.id)
        assertEquals("w2", RouteMemory.find(routes, "work")?.id)
        assertEquals("w2", RouteMemory.find(routes, "tidel park")?.id)
        assertNull(RouteMemory.find(routes, "airport"))
        assertEquals("w1", RouteMemory.forPlace(listOf(clinic.copy(placeKey = "ChIJ-c")), "ChIJ-c")?.id)
    }

    @Test
    fun walkingCommandsAreNotCabBookings() {
        assertEquals(Command.Record("clinic door"), WalkCommands.parse("record my walk to the clinic door"))
        assertEquals(Command.Record(""), WalkCommands.parse("start recording"))
        assertEquals(Command.Recall("clinic", guided = false), WalkCommands.parse("How do I get to the clinic?"))
        assertEquals(Command.Recall("work", guided = false), WalkCommands.parse("remind me of the route to work"))
        assertEquals(Command.Recall("clinic", guided = true), WalkCommands.parse("guide me to the clinic"))
        assertEquals(Command.Recall("", guided = true), WalkCommands.parse("guide me"))
        assertEquals(Command.Recall("", guided = false), WalkCommands.parse("tell me the walk"))
        assertEquals(Command.List, WalkCommands.parse("my walking routes"))
        assertEquals(Command.Forget("clinic"), WalkCommands.parse("forget the route to the clinic"))
        assertEquals(Command.ForgetAll, WalkCommands.parse("forget my walking routes"))
        assertEquals(Command.Export, WalkCommands.parse("export my walking log"))
        assertNull(WalkCommands.parse("take me to the clinic"))
        assertNull(WalkCommands.parse("book an auto to Gandhipuram"))

        assertEquals(Guide.NEXT, WalkCommands.guide("next"))
        assertEquals(Guide.MORE_DETAIL, WalkCommands.guide("more detail"))
        assertEquals(Guide.LESS_DETAIL, WalkCommands.guide("just the landmarks"))
        assertEquals(Guide.ARRIVED, WalkCommands.guide("I'm there"))
        assertEquals(WalkCommands.RecordInput.Finish, WalkCommands.record("I've arrived"))
        assertEquals(WalkCommands.RecordInput.Turn(TurnDirection.RIGHT), WalkCommands.record("turning right"))
        assertTrue(WalkCommands.record("kerb on my left") is WalkCommands.RecordInput.Mark)
        assertTrue(WalkCommands.record("a green sign") is WalkCommands.RecordInput.Visual)
    }

    @Test
    fun stepsComeFromTheAccelerometer() {
        val d = StepDetector()
        var steps = 0
        var t = 0L
        // 20 s of walking at ~1.8 steps/s: vertical bounce on top of gravity, plus sensor jitter
        while (t < 20_000) {
            val bounce = 2.5 * sin(2 * Math.PI * 1.8 * t / 1000.0)
            val jitter = ((t * 7919) % 13 - 6) / 30.0
            if (d.onSample(t, 0.3, 0.2, 9.81 + bounce + jitter)) steps++
            t += 20
        }
        assertEquals(36.0, steps.toDouble(), 3.0)
        // Standing still: nothing.
        val still = StepDetector()
        var none = 0
        for (i in 0 until 500) if (still.onSample(i * 20L, 0.1, 0.1, 9.81 + ((i * 31) % 7 - 3) / 50.0)) none++
        assertEquals(0, none)
    }

    @Test
    fun theStudyLogExportsAsCsv() {
        val csv = RouteMemory.csv(listOf(WalkLogEntry(1L, "w1", "GUIDE_START", "level=FULL"), WalkLogEntry(2L, "w1", "CORRECTION", "bakery, left")))
        assertEquals("time_utc_ms,route_id,event,detail\n1,w1,GUIDE_START,level=FULL\n2,w1,CORRECTION,\"bakery, left\"\n", csv)
    }

    @Suppress("unused")
    private fun landmark(kind: LandmarkKind) = Landmark("x", kind, kind.spoken)
}
