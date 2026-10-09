package com.cabeye.rider

import com.cabeye.rider.memory.MemoryStats
import com.cabeye.rider.memory.PreferenceMemoryAgent
import com.cabeye.rider.memory.PreferenceMemoryAgent.Moment
import com.cabeye.rider.memory.PreferenceMemoryAgent.Thresholds
import com.cabeye.rider.memory.RiderMemory
import com.cabeye.rider.memory.RouteLeg
import com.cabeye.rider.memory.RoutineAgent
import com.cabeye.rider.memory.SavedRoute
import com.cabeye.rider.memory.TripRecord
import com.cabeye.rider.memory.TripStop
import com.cabeye.rider.memory.VisitedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * "Be the user": four weeks of one rider's real-looking diary, fed in exactly the way the
 * backend records it — a trip per completed ride, stops in visiting order, a visit per place —
 * then the agents are asked what they would suggest at given moments.
 *
 * The diary (Coimbatore, home in RS Puram):
 *  - Monday 8:30   pharmacy (driver waits), then PSG College
 *  - Tue–Fri 8:35  PSG College directly
 *  - Wednesday 18:00  pick up mother at Race Course, then home
 *  - Sunday 16:00  Brookefields Mall
 */
class RoutineAgentTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val home = 11.0050 to 76.9500

    private val psg = place("ChIJ-psg", "PSG College of Technology", 11.0247, 77.0028)
    private val apollo = place("ChIJ-apollo", "Apollo Pharmacy", 11.0120, 76.9550)
    private val race = place("ChIJ-race", "Race Course", 11.0005, 76.9760)
    private val homePlace = place("ChIJ-home", "Home", home.first, home.second)
    private val mall = place("ChIJ-mall", "Brookefields Mall", 11.0089, 76.9606)

    private fun place(key: String, name: String, lat: Double, lng: Double) =
        VisitedPlace(placeKey = key, name = name, latitude = lat, longitude = lng)

    private fun at(day: LocalDate, h: Int, m: Int) = LocalDateTime.of(day, java.time.LocalTime.of(h, m))
        .atZone(zone).toInstant().toEpochMilli()

    /** Simulates the rider's four weeks, ending Sunday 27 September 2026. */
    private fun diary(): RiderMemory {
        val trips = mutableListOf<TripRecord>()
        var n = 0
        val start = LocalDate.of(2026, 8, 31) // a Monday
        for (week in 0 until 4) {
            for (d in 0 until 7) {
                val day = start.plusDays((week * 7 + d).toLong())
                when (day.dayOfWeek.value) {
                    1 -> trips += TripRecord("r${n++}", psg.placeKey, psg.name, pickupLatitude = home.first,
                        pickupLongitude = home.second, bookedAt = at(day, 8, 30 + week),
                        stops = listOf(TripStop(apollo.placeKey, apollo.name, "WAIT")))
                    in 2..5 -> trips += TripRecord("r${n++}", psg.placeKey, psg.name, pickupLatitude = home.first,
                        pickupLongitude = home.second, bookedAt = at(day, 8, 35))
                    7 -> trips += TripRecord("r${n++}", mall.placeKey, mall.name, bookedAt = at(day, 16, 0))
                }
                if (day.dayOfWeek.value == 3) {
                    trips += TripRecord("r${n++}", homePlace.placeKey, homePlace.name, bookedAt = at(day, 18, 0),
                        stops = listOf(TripStop(race.placeKey, race.name, "PICKUP")))
                }
            }
        }
        return RiderMemory(places = listOf(psg, apollo, race, homePlace, mall), trips = trips)
    }

    private val defaults = Thresholds(Thresholds.BASE_PROACTIVE, Thresholds.BASE_REPAIR)
    private fun moment(y: Int, mo: Int, d: Int, h: Int, mi: Int, from: Pair<Double, Double>? = home) =
        Moment(LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli(), zone, from?.first, from?.second)

    @Test
    fun mondayMorningSuggestsThePharmacyThenCollegeRoute() {
        val memory = diary()
        val now = moment(2026, 9, 28, 8, 40)                   // the next Monday
        val routine = RoutineAgent.proactive(memory, now, defaults)
        assertNotNull(routine)
        assertEquals("Apollo Pharmacy, then PSG College of Technology", routine!!.spokenRoute())
        assertEquals("like most Monday mornings", routine.reason)
        assertEquals("WAIT", routine.stops.single().kind)
        assertEquals(4, routine.support)

        // The single-place agent also has an answer (college) — the route refines it, so it wins.
        val single = PreferenceMemoryAgent.proactive(memory, now, defaults)
        assertEquals("ChIJ-psg", single!!.place.placeKey)
        assertTrue(RoutineAgent.preferRoutine(routine, single))
    }

    @Test
    fun tuesdayMorningIsJustCollege() {
        val memory = diary()
        val now = moment(2026, 9, 29, 8, 40)
        assertNull("a Monday-only route must not be offered on Tuesday", RoutineAgent.proactive(memory, now, defaults))
        assertEquals("ChIJ-psg", PreferenceMemoryAgent.proactive(memory, now, defaults)!!.place.placeKey)
    }

    @Test
    fun wednesdayEveningSuggestsPickingUpMother() {
        // Asked from college at the end of the day — not from home, where "home" is never suggested.
        val atCollege = psg.latitude!! to psg.longitude!!
        val routine = RoutineAgent.proactive(diary(), moment(2026, 9, 30, 17, 55, from = atCollege), defaults)
        assertNotNull(routine)
        assertEquals("Race Course, then Home", routine!!.spokenRoute())
        assertEquals("like most Wednesday evenings", routine.reason)
        assertNull(RoutineAgent.proactive(diary(), moment(2026, 9, 30, 17, 55), defaults))
        assertEquals("PICKUP", routine.stops.single().kind)
    }

    @Test
    fun theRulesStillHold() {
        val memory = diary()
        // Unusual time: nothing.
        assertNull(RoutineAgent.proactive(memory, moment(2026, 9, 28, 14, 0), defaults))
        // Already at the first stop: never suggest going there.
        assertNull(RoutineAgent.proactive(memory, moment(2026, 9, 28, 8, 40, from = apollo.latitude!! to apollo.longitude!!), defaults))
        // Turned down in this session: not asked again.
        val first = RoutineAgent.proactive(memory, moment(2026, 9, 28, 8, 40), defaults)!!
        assertNull(RoutineAgent.proactive(memory, moment(2026, 9, 28, 8, 40), defaults, setOf(first.signature)))
        // A rider who keeps saying no raises the bar until the agent stays quiet.
        val strict = Thresholds.from(MemoryStats(proactiveAccepted = 0, proactiveRejected = 12))
        assertTrue(strict.proactive > defaults.proactive)
        // Only one Monday of history is a coincidence, not a habit.
        val oneWeek = memory.copy(trips = memory.trips.filter { it.bookedAt < at(LocalDate.of(2026, 9, 7), 0, 0) })
        assertNull(RoutineAgent.proactive(oneWeek, moment(2026, 9, 7, 8, 40), defaults))
        // "Forget my history" leaves nothing to reason over.
        assertNull(RoutineAgent.proactive(RiderMemory.EMPTY, moment(2026, 9, 28, 8, 40), defaults))
    }

    @Test
    fun namedRoutesAreFoundByTheRidersOwnWords() {
        val routes = listOf(
            SavedRoute("Monday errands", "monday errands",
                listOf(RouteLeg("Apollo Pharmacy", kind = "WAIT")), RouteLeg("PSG College of Technology")),
            SavedRoute("Amma pickup", "amma pickup",
                listOf(RouteLeg("Race Course", kind = "PICKUP", note = "Amma")), RouteLeg("Home"))
        )
        assertEquals("Monday errands", RoutineAgent.named("book Monday errands", routes)?.name)
        assertEquals("Monday errands", RoutineAgent.named("my monday errands route", routes)?.name)
        assertEquals("Amma pickup", RoutineAgent.named("amma pick up", routes)?.name)
        assertNull(RoutineAgent.named("take me to the airport", routes))
        assertNull(RoutineAgent.named("monday errands", emptyList()))
        assertFalse(RoutineAgent.preferRoutine(null, null))
    }
}
