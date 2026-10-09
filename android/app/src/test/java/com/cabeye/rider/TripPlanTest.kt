package com.cabeye.rider

import com.cabeye.rider.state.PlaceOption
import com.cabeye.rider.state.RideType
import com.cabeye.rider.trip.PlannedLeg
import com.cabeye.rider.trip.StopKind
import com.cabeye.rider.trip.StopPlanParser
import com.cabeye.rider.trip.StopPlanParser.PlanEdit
import com.cabeye.rider.trip.StopPlanParser.TripCommand
import com.cabeye.rider.trip.TripPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-stop speech: the route, the edits at the read-back, and what is said mid-ride. */
class TripPlanTest {

    private fun queries(text: String) = StopPlanParser.parseTrip(text)!!.legs.map { it.query }

    // ---------------------------------------------------------------- the route

    @Test
    fun thenSeparatesStopsInOrder() {
        assertEquals(listOf("apollo pharmacy", "gandhipuram", "home"),
            queries("Take me to Apollo pharmacy, then Gandhipuram, then home"))
        assertEquals(listOf("pharmacy", "college"), queries("first the pharmacy and then college"))
        assertEquals(listOf("bank", "psg college"), queries("go to the bank after that PSG college"))
    }

    @Test
    fun viaPutsTheStopBeforeTheDestination() {
        assertEquals(listOf("pharmacy", "psg college"), queries("take me to PSG college via the pharmacy"))
        assertEquals(listOf("atm", "home"), queries("home, stopping at the ATM"))
        assertEquals(listOf("bakery", "office"), queries("office with a quick stop at the bakery on the way"))
    }

    @Test
    fun singleDestinationsAreNeverRoutes() {
        assertNull(StopPlanParser.parseTrip("take me to Ukkadam bus stop"))
        assertNull(StopPlanParser.parseTrip("drop me at the bus stop at Gandhipuram"))
        assertNull(StopPlanParser.parseTrip("Fun Republic and back"))
        assertNull(StopPlanParser.parseTrip("book an auto to Race Course"))
    }

    @Test
    fun kindsAndWhoComeFromTheWords() {
        val legs = StopPlanParser.parseTrip(
            "wait for me at the pharmacy, then drop my friend at Gandhipuram, then pick up my mother from Race Course, then home"
        )!!.legs
        assertEquals(StopKind.WAIT, legs[0].kind)
        assertEquals("pharmacy", legs[0].query)
        assertEquals(StopKind.DROP, legs[1].kind)
        assertEquals("my friend", legs[1].note)
        assertEquals("gandhipuram", legs[1].query)
        assertEquals(StopKind.PICKUP, legs[2].kind)
        assertEquals("my mother", legs[2].note)
        assertEquals("race course", legs[2].query)
        assertNull(legs[3].kind)
        assertEquals(3, StopPlanParser.parseTrip("pharmacy then bank then mall then home")!!.toPlan().stops.size)
    }

    @Test
    fun rideTypeIsKeptAndTooManyStopsIsNoticed() {
        val p = StopPlanParser.parseTrip("book a cab to the pharmacy then home")!!
        assertEquals(RideType.CAB, p.rideType)
        assertTrue(p.rideTypeWasExplicit)
        assertTrue(StopPlanParser.tooManyStops("bank then atm then mall then bakery then home"))
        assertFalse(StopPlanParser.tooManyStops("bank then atm then home"))
    }

    @Test
    fun answersAboutAStop() {
        assertEquals(StopKind.WAIT, StopPlanParser.kindAnswer("wait for me, I'll come back"))
        assertEquals(StopKind.WAIT, StopPlanParser.kindAnswer("I'm getting out for a quick errand"))
        assertEquals(StopKind.DROP, StopPlanParser.kindAnswer("I'm dropping my friend"))
        assertEquals(StopKind.DROP, StopPlanParser.kindAnswer("someone gets off there"))
        assertEquals(StopKind.PICKUP, StopPlanParser.kindAnswer("picking up my brother"))
        assertNull(StopPlanParser.kindAnswer("hmm"))
        assertTrue(StopPlanParser.isPlanStart("I have a few stops"))
        assertTrue(StopPlanParser.isPlanStart("add a stop"))
        assertFalse(StopPlanParser.isPlanStart("take me to the bus stop"))
        assertTrue(StopPlanParser.isDoneAdding("that's all"))
        assertTrue(StopPlanParser.isDoneAdding("no more stops"))
    }

    // ---------------------------------------------------------------- edits

    @Test
    fun editsAtTheReadBack() {
        assertEquals(PlanEdit.Remove(2), StopPlanParser.planEdit("remove stop two"))
        assertEquals(PlanEdit.Remove(1), StopPlanParser.planEdit("delete the first stop"))
        assertEquals(PlanEdit.Remove(null, "pharmacy"), StopPlanParser.planEdit("remove the pharmacy"))
        assertEquals(PlanEdit.Swap(1, 2), StopPlanParser.planEdit("swap stops one and two"))
        val change = StopPlanParser.planEdit("change stop one to MedPlus") as PlanEdit.Change
        assertEquals(1, change.stop)
        assertEquals("medplus", change.leg.query)
        val add = StopPlanParser.planEdit("add a stop at the bank after stop one") as PlanEdit.Add
        assertEquals("bank", add.leg.query)
        assertEquals(1, add.afterStop)
        assertNull((StopPlanParser.planEdit("add the ATM") as PlanEdit.Add).afterStop)
        assertEquals(PlanEdit.SetKind(2, StopKind.WAIT), StopPlanParser.planEdit("make stop two a wait"))
        assertEquals("home", (StopPlanParser.planEdit("change the destination to home") as PlanEdit.ChangeDestination).leg.query)
        assertEquals(PlanEdit.SaveAs("Monday errands"), StopPlanParser.planEdit("save this as Monday errands"))
        assertEquals(PlanEdit.ReadAgain, StopPlanParser.planEdit("read it again"))
        assertNull(StopPlanParser.planEdit("yes"))
    }

    private fun placed(name: String, kind: StopKind? = null) =
        PlannedLeg(name.lowercase(), kind = kind, place = PlaceOption(name, 11.0, 77.0, 1f))

    private val plan = TripPlan(
        stops = listOf(placed("Apollo Pharmacy", StopKind.WAIT), placed("Gandhipuram", StopKind.DROP).copy(note = "my friend")),
        destination = placed("PSG College")
    )

    @Test
    fun readBackIsNumberedAndSaysWhatHappens() {
        assertEquals(
            "Stop one, Apollo Pharmacy, the driver waits for you. Stop two, Gandhipuram, dropping someone off, my friend. Then PSG College.",
            plan.readBack()
        )
        assertTrue(plan.isComplete)
    }

    @Test
    fun planEditsKeepTheRules() {
        val swapped = (plan.swap(1, 2) as TripPlan.Edit.Ok).plan
        assertEquals("Gandhipuram", swapped.stops[0].name)
        val removed = (plan.remove(1) as TripPlan.Edit.Ok).plan
        assertEquals(listOf("Gandhipuram"), removed.stops.map { it.name })
        assertTrue(plan.remove(3) is TripPlan.Edit.Refused)
        val three = (plan.add(PlannedLeg("bank"), afterStop = 0) as TripPlan.Edit.Ok).plan
        assertEquals("bank", three.stops[0].name)
        assertFalse(three.isComplete)
        assertEquals(0, three.nextUnresolved)
        assertEquals(0, three.nextWithoutKind)
        val refused = three.add(PlannedLeg("atm"))
        assertTrue(refused is TripPlan.Edit.Refused)
        assertEquals("You already have 3 stops, which is the most a ride can have.", (refused as TripPlan.Edit.Refused).sentence)
        assertEquals(1, plan.stopNamed("the pharmacy"))
        assertEquals(2, plan.stopNamed("gandhipuram"))
        assertNull(plan.stopNamed("airport"))
    }

    // ---------------------------------------------------------------- during the ride

    @Test
    fun whatIsSaidDuringTheRide() {
        assertEquals(TripCommand.ImBack, StopPlanParser.tripCommand("I'm back"))
        assertEquals(TripCommand.ImBack, StopPlanParser.tripCommand("ok I am back in the car"))
        assertEquals(TripCommand.Skip(null), StopPlanParser.tripCommand("skip the next stop"))
        assertEquals(TripCommand.Skip(2), StopPlanParser.tripCommand("skip stop two"))
        assertEquals(TripCommand.ReadStops, StopPlanParser.tripCommand("what are my stops"))
        assertEquals(TripCommand.ReadStops, StopPlanParser.tripCommand("where's my next stop"))
        val add = StopPlanParser.tripCommand("add a stop at the ATM") as TripCommand.AddStop
        assertEquals("atm", add.leg.query)
        assertNull(StopPlanParser.tripCommand("how long will it take"))
        assertNotNull(StopPlanParser.number("second"))
    }
}
