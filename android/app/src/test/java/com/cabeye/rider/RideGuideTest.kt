package com.cabeye.rider

import com.cabeye.rider.mindmap.MindMapJson
import com.cabeye.rider.mindmap.Node
import com.cabeye.rider.mindmap.RideCommands
import com.cabeye.rider.mindmap.RideCueParser
import com.cabeye.rider.mindmap.RideGuideAgent
import com.cabeye.rider.mindmap.RideGuideAgent.Kind
import com.cabeye.rider.mindmap.RideTrace
import com.cabeye.rider.walk.LandmarkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ride mode on synthetic rides:
 * - a cue marked on one ride is said once, just before the place, on the next ride the same way
 *   (with the usual time left);
 * - cues close together are said together, others at most once a minute;
 * - a cab off the usual way is noticed once and calmly, and coming back is said;
 * - areas are named on a change that holds, and not on a familiar ride;
 * - "almost there" comes before arrival;
 * - "undo" takes back a wrong cue.
 */
class RideGuideTest {

    private val t0 = 1_790_000_000_000L
    private val home = "home"
    private val step = 100.0 // metres per fix
    private val every = 15_000L // ms per fix

    private fun north(m: Double) = 11.0 + m / 110_540.0
    private fun east(m: Double) = 76.95 + m / (111_320.0 * Math.cos(Math.toRadians(11.0)))

    /** Points heading north from a start, [n] fixes, optionally pushed [eastM] east between two distances. */
    private fun fixes(n: Int, eastM: Double = 0.0, from: Double = Double.MAX_VALUE, to: Double = Double.MAX_VALUE): List<Triple<Double, Double, Long>> =
        (0 until n).map { i ->
            val d = i * step
            val e = if (d >= from && d <= to) eastM else 0.0
            Triple(north(d), east(e), t0 + i * every)
        }

    private fun guide(usual: List<RideTrace> = emptyList(), cues: List<Node> = emptyList(), quiet: Boolean = false,
                      destM: Double? = null) =
        RideGuideAgent("p-home", home, usual, cues, quiet = quiet, destLat = destM?.let(::north), destLng = destM?.let { 76.95 })

    private fun ride(g: RideGuideAgent, pts: List<Triple<Double, Double, Long>>, mark: Map<Int, String> = emptyMap()): List<Pair<Int, RideGuideAgent.Say>> {
        val said = mutableListOf<Pair<Int, RideGuideAgent.Say>>()
        pts.forEachIndexed { i, (lat, lng, t) ->
            g.onFix(lat, lng, t).forEach { said += i to it }
            mark[i]?.let { g.mark(it, t, "c$i") }
        }
        return said
    }

    /** A first ride home with cues marked, returned as (trace, cues). */
    private fun firstRide(n: Int = 50, mark: Map<Int, String> = emptyMap()): Pair<RideTrace, List<Node>> {
        val g = guide()
        ride(g, fixes(n), mark)
        return g.trace(t0 + n * every)!!.copy(recordedAt = t0) to g.changedCues()
    }

    // ---- cues ------------------------------------------------------------------------------

    @Test
    fun aCueMarkedOnceIsSaidBeforeItNextTime() {
        val (trace, cues) = firstRide(mark = mapOf(20 to "that was the flyover"))
        assertEquals("flyover", cues.single().label)
        assertEquals(LandmarkKind.RAMP, cues.single().sense)

        val second = guide(listOf(trace), cues)
        val said = ride(second, fixes(50)).filter { it.second.kind == Kind.CUE }
        assertEquals(1, said.size)
        assertEquals(19, said.single().first) // 100 m before the place it was marked
        // 30 fixes × 15 s = 7.5 minutes left on the earlier ride
        assertEquals("The flyover coming up. Usually about 8 minutes from here to home.", said.single().second.text)
        assertTrue(second.preview()!!.contains("Your cues on the way: the flyover."))
    }

    @Test
    fun cuesCloseTogetherAreSaidTogether() {
        val (trace, cues) = firstRide(40, mark = mapOf(10 to "speed bump", 11 to "market noise"))
        val said = ride(guide(listOf(trace), cues), fixes(40)).filter { it.second.kind == Kind.CUE }
        assertEquals(1, said.size)
        assertTrue(said.single().second.text, said.single().second.text.startsWith("The speed bump, then the market noise coming up."))
    }

    @Test
    fun cuesAMinuteApartAtMost() {
        val (trace, cues) = firstRide(40, mark = mapOf(10 to "speed bump", 14 to "flyover"))
        val at = ride(guide(listOf(trace), cues), fixes(40)).filter { it.second.kind == Kind.CUE }.map { it.first }
        assertEquals(listOf(9, 13), at)
    }

    @Test
    fun markingTheSameCueAgainConfirmsIt() {
        val (trace, cues) = firstRide(40, mark = mapOf(20 to "speed bump"))
        val g = guide(listOf(trace), cues)
        ride(g, fixes(21))
        val again = g.mark("speed bump", t0 + 999, "new") as RideGuideAgent.Marked.Cue
        assertEquals(cues.single().id, again.node.id)
        assertEquals(2, again.node.confirmations)
        assertFalse(again.isNew)
    }

    @Test
    fun undoTakesBackANewCueAndRestoresAConfirmedOne() {
        val g = guide()
        ride(g, fixes(5))
        val m = g.mark("the door is open", t0, "x") as RideGuideAgent.Marked.Cue
        assertTrue(m.spoken.endsWith("If that's wrong, say undo."))
        val u = g.undo()!!
        assertEquals(m.node.id, u.removed!!.id)
        assertTrue(g.changedCues().isEmpty())
        assertNull(g.undo()) // nothing left to undo

        val (trace, cues) = firstRide(40, mark = mapOf(20 to "speed bump"))
        val h = guide(listOf(trace), cues)
        ride(h, fixes(21))
        h.mark("speed bump", t0, "y")
        val back = h.undo()!!
        assertNull(back.removed)
        assertEquals(1, back.restored!!.confirmations)
    }

    @Test
    fun withoutGpsCuesArePlacedByTheTripMeter() {
        val g = guide()
        g.onTripMeters(1_200.0, t0)
        val cue = (g.mark("railway crossing", t0, "c1") as RideGuideAgent.Marked.Cue).node
        assertEquals(1_200.0, cue.atMetres!!, 0.1)
        val next = guide(cues = listOf(cue))
        assertTrue(next.onTripMeters(1_000.0, t0).isEmpty())
        assertEquals("The railway crossing coming up.", next.onTripMeters(1_100.0, t0 + 1).single().text)
    }

    // ---- quiet, detours, areas, arrival --------------------------------------------------------

    @Test
    fun quietRideSaysNoCuesButStillSaysADetour() {
        val (trace, cues) = firstRide(mark = mapOf(20 to "flyover"))
        val said = ride(guide(listOf(trace), cues, quiet = true), fixes(50, eastM = 600.0, from = 1_000.0, to = 1_500.0)).map { it.second }
        assertTrue(said.none { it.kind == Kind.CUE })
        assertEquals(1, said.count { it.kind == Kind.DETOUR })
    }

    @Test
    fun aDetourIsSaidOnceAndComingBackIsSaid() {
        val (trace, _) = firstRide()
        val said = ride(guide(listOf(trace)), fixes(50, eastM = 600.0, from = 1_000.0, to = 2_500.0))
        val detours = said.filter { it.second.kind == Kind.DETOUR }
        assertEquals(1, detours.size)
        assertEquals(11, detours.single().first) // two fixes off before saying it
        assertTrue(detours.single().second.text.startsWith("This isn't your usual way to home."))
        assertTrue(detours.single().second.text.contains("S O S"))
        assertEquals(1, said.count { it.second.kind == Kind.BACK_ON_ROUTE })
    }

    @Test
    fun noDetourOnAFirstRideOrOnADifferentRoad() {
        assertTrue(ride(guide(), fixes(50, eastM = 600.0, from = 1_000.0, to = 2_500.0)).isEmpty())
        val (trace, _) = firstRide()
        // A ride home that starts 5 km away, nowhere near the usual road, is not "the same way".
        val far = fixes(50).map { (a, b, t) -> Triple(a + 0.05, b, t) }
        assertTrue(ride(guide(listOf(trace)), far).none { it.second.kind == Kind.DETOUR })
    }

    @Test
    fun theUsualWayIsRecognisedMidRoute() {
        // The app restarted 2 km into the ride: the rest of the way is still the usual way.
        val (trace, _) = firstRide()
        val g = guide(listOf(trace))
        val said = ride(g, fixes(50).drop(20).take(15))
        assertTrue(g.hasUsualWay)
        assertTrue(said.none { it.second.kind == Kind.DETOUR })
        assertTrue(g.whereAmI(null).contains("Usually about"))
    }

    @Test
    fun areasAreNamedWhenAChangeHolds() {
        val g = guide()
        assertNull(g.onArea("RS Puram", t0)) // where the ride starts: the rider knows
        assertNull(g.onArea("Gandhipuram", t0 + 200_000)) // once could be a boundary road
        assertEquals("Now in Gandhipuram.", g.onArea("Gandhipuram", t0 + 220_000)?.text)
        assertNull(g.onArea("Gandhipuram", t0 + 400_000))
        // Flapping at a boundary: never said.
        assertNull(g.onArea("Peelamedu", t0 + 500_000))
        assertNull(g.onArea("Gandhipuram", t0 + 520_000))
        assertEquals("Gandhipuram", g.currentArea)
        // Under two minutes since the last one: noted, not said.
        g.onArea("Peelamedu", t0 + 300_000)
        assertNull(g.onArea("Peelamedu", t0 + 310_000))
        assertEquals("Peelamedu", g.currentArea)
    }

    @Test
    fun aFamiliarRideSaysLess() {
        val traces = (0 until 3).map { firstRide().first.copy(recordedAt = t0 + it) }
        val g = guide(traces)
        assertTrue(g.familiar)
        assertEquals("About 12 minutes by your usual way.", g.preview())
        g.onArea("RS Puram", t0)
        g.onArea("Gandhipuram", t0 + 200_000)
        assertNull(g.onArea("Gandhipuram", t0 + 220_000)) // no area names on the daily commute…
        // …until the cab leaves the usual way, when the area matters again.
        ride(g, fixes(20, eastM = 600.0, from = 1_000.0, to = 2_000.0))
        g.onArea("Peelamedu", t0 + 500_000)
        assertEquals("Now in Peelamedu.", g.onArea("Peelamedu", t0 + 520_000)?.text)
        assertNull(guide().preview()) // a first ride has no preview
    }

    @Test
    fun almostThereComesOnceBeforeArrivalEvenOnAQuietRide() {
        val g = guide(quiet = true, destM = 3_000.0)
        val said = ride(g, fixes(31)).filter { it.second.kind == Kind.ARRIVING }
        assertEquals(1, said.size)
        assertEquals(27, said.single().first) // the first fix within 400 m
        assertEquals("Almost there: home is about 300 metres away.", said.single().second.text)
    }

    @Test
    fun whereAmIAndStatus() {
        val (trace, cues) = firstRide(mark = mapOf(30 to "railway crossing"))
        val g = guide(listOf(trace), cues)
        ride(g, fixes(11))
        g.onArea("Gandhipuram", t0)
        val s = g.whereAmI(tripMeters = 1_000.0)
        assertTrue(s, s.startsWith("You're in Gandhipuram, 1 kilometre into the ride."))
        assertTrue(s, s.contains("Usually about 10 minutes from here to home."))
        assertTrue(s, s.contains("Next you'll notice the railway crossing, in 2 kilometres."))
        assertEquals("You're in Gandhipuram. Usually about 10 minutes from here.", g.briefStatus())

        // A first ride with a known destination: how far, in a straight line.
        val first = guide(destM = 5_000.0)
        ride(first, fixes(11))
        assertTrue(first.whereAmI(null), first.whereAmI(null).contains("home is about 4 kilometres away in a straight line."))
        assertNull(guide().briefStatus())
    }

    // ---- words ---------------------------------------------------------------------------------

    @Test
    fun rideCommands() {
        assertEquals(RideCommands.Command.WHERE_AM_I, RideCommands.parse("Where are we?"))
        assertEquals(RideCommands.Command.WHERE_AM_I, RideCommands.parse("which area is this"))
        assertEquals(RideCommands.Command.WHERE_AM_I, RideCommands.parse("how much longer"))
        assertEquals(RideCommands.Command.QUIET, RideCommands.parse("quiet ride"))
        assertEquals(RideCommands.Command.TALK, RideCommands.parse("talk to me"))
        assertEquals(RideCommands.Command.PREVIEW, RideCommands.parse("what's on the way"))
        assertEquals(RideCommands.Command.UNDO, RideCommands.parse("undo that"))
        assertEquals(RideCommands.Command.UNDO, RideCommands.parse("no, that's wrong"))
        assertNull(RideCommands.parse("speed bump"))
    }

    @Test
    fun rideCueWords() {
        fun label(s: String) = (RideCueParser.parse(s) as RideCueParser.Result.Cue).label
        assertEquals("speed bump", label("we just went over a speed bump"))
        assertEquals(LandmarkKind.TEXTURE, (RideCueParser.parse("speed breaker") as RideCueParser.Result.Cue).kind)
        assertEquals(LandmarkKind.SOUND, (RideCueParser.parse("I can hear the market") as RideCueParser.Result.Cue).kind)
        assertEquals("market", label("I can hear the market"))
        assertEquals("left turn", label("turning left"))
        // Whole words only: "overpass" keeps its "over", "onion" keeps its "on".
        assertEquals("overpass", label("overpass"))
        assertEquals("overpass", label("that's the overpass"))
        assertEquals("onion smell", label("onion smell"))
        assertEquals("flyover", label("going up the flyover"))
        assertTrue(RideCueParser.parse("a big hoarding") is RideCueParser.Result.VisualOnly)
        assertNull(RideCueParser.parse("how long will it take"))
        assertNull(RideCueParser.parse("how far is the school"))
        assertNull(RideCueParser.parse("I can't hear you"))
        assertNull(RideCueParser.parse("the bump isn't there anymore"))
    }

    @Test
    fun tracesKeepTheLastFivePerPlaceAndSurviveJson() {
        val tr = firstRide(10).first
        val many = (0 until 8).map { tr.copy(recordedAt = t0 + it) } + tr.copy(placeKey = "other")
        val kept = MindMapJson.keepTraces(many)
        assertEquals(5, kept.count { it.placeKey == "p-home" })
        assertEquals(1, kept.count { it.placeKey == "other" })
        val back = MindMapJson.traces(MindMapJson.tracesJson(kept).toString())
        assertEquals(kept, back)
        assertNotNull(back.first().points.first())
    }
}
