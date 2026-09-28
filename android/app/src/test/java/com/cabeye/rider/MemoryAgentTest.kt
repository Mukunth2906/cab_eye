package com.cabeye.rider

import com.cabeye.rider.memory.MemoryStats
import com.cabeye.rider.memory.PreferenceMemoryAgent
import com.cabeye.rider.memory.PreferenceMemoryAgent.Moment
import com.cabeye.rider.memory.PreferenceMemoryAgent.Thresholds
import com.cabeye.rider.memory.RiderMemory
import com.cabeye.rider.memory.TextSimilarity
import com.cabeye.rider.memory.TripRecord
import com.cabeye.rider.memory.VisitedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Preference-Memory Agent against one rider's realistic week:
 * college every weekday morning from home, the bus stand now and then in the evening.
 */
class MemoryAgentTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val home = 10.9950 to 76.9600
    private val psg = VisitedPlace(
        placeKey = "ChIJ-psg", name = "PSG College of Technology", latitude = 11.0247, longitude = 77.0028,
        visitCount = 4, aliases = listOf("piece g")
    )
    private val gandhipuram = VisitedPlace(
        placeKey = "ChIJ-gp", name = "Gandhipuram Central Bus Stand", latitude = 11.0183, longitude = 76.9660,
        visitCount = 1
    )
    private val brookefields = VisitedPlace(
        placeKey = "ChIJ-bf", name = "Brookefields Mall", latitude = 11.0089, longitude = 76.9606,
        visitCount = 2
    )

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun trip(id: String, place: VisitedPlace, time: Long, from: Pair<Double, Double> = home) =
        TripRecord(id, place.placeKey, place.name, pickupLatitude = from.first, pickupLongitude = from.second, bookedAt = time)

    /** Mon–Thu last week to college around 8:35; one Friday-evening trip to the bus stand. */
    private val memory = RiderMemory(
        places = listOf(psg, gandhipuram, brookefields),
        trips = listOf(
            trip("t1", psg, at(2026, 9, 14, 8, 30)),
            trip("t2", psg, at(2026, 9, 15, 8, 45)),
            trip("t3", psg, at(2026, 9, 16, 8, 35)),
            trip("t4", psg, at(2026, 9, 17, 8, 50)),
            trip("t5", gandhipuram, at(2026, 9, 18, 18, 10)),
            trip("t6", brookefields, at(2026, 9, 13, 16, 0)),  // Sunday afternoon
            trip("t7", brookefields, at(2026, 9, 6, 16, 30))   // Sunday afternoon
        )
    )

    private val defaults = Thresholds(Thresholds.BASE_PROACTIVE, Thresholds.BASE_REPAIR)
    private fun moment(y: Int, mo: Int, d: Int, h: Int, mi: Int, from: Pair<Double, Double>? = home) =
        Moment(at(y, mo, d, h, mi), zone, from?.first, from?.second)

    // --------------------------------------------------------------- proactive

    @Test
    fun mondayMorningFromHomeSuggestsCollege() {
        val s = PreferenceMemoryAgent.proactive(memory, moment(2026, 9, 21, 8, 40), defaults)
        assertNotNull(s)
        assertEquals("ChIJ-psg", s!!.place.placeKey)
        assertEquals("like most weekday mornings", s.reason)
    }

    @Test
    fun noSuggestionAtAnUnusualTime() {
        assertNull(PreferenceMemoryAgent.proactive(memory, moment(2026, 9, 21, 14, 0), defaults))
    }

    @Test
    fun weekendsAreTheirOwnPattern() {
        // Sunday afternoon: the mall, not college.
        val s = PreferenceMemoryAgent.proactive(memory, moment(2026, 9, 20, 16, 15), defaults)
        assertEquals("ChIJ-bf", s?.place?.placeKey)
        assertNull(PreferenceMemoryAgent.proactive(memory, moment(2026, 9, 20, 8, 40), defaults))
    }

    @Test
    fun neverSuggestsWhereYouAlreadyAre() {
        val atCollege = psg.latitude!! to psg.longitude!!
        assertNull(PreferenceMemoryAgent.proactive(memory, moment(2026, 9, 21, 8, 40, atCollege), defaults))
    }

    @Test
    fun aPlaceTurnedDownRepeatedlyIsDropped() {
        val tired = memory.copy(places = listOf(psg.copy(rejected = 3), gandhipuram, brookefields))
        assertNull(PreferenceMemoryAgent.proactive(tired, moment(2026, 9, 21, 8, 40), defaults))
    }

    // --------------------------------------------------------------- recalibration

    @Test
    fun rejectionsRaiseTheBarAndAcceptancesLowerIt() {
        val base = Thresholds.from(MemoryStats())
        val rejecting = Thresholds.from(MemoryStats(proactiveRejected = 8))
        val accepting = Thresholds.from(MemoryStats(proactiveAccepted = 8))
        assertEquals(Thresholds.BASE_PROACTIVE, base.proactive, 0.001f)
        assertTrue(rejecting.proactive > base.proactive)
        assertTrue(accepting.proactive < base.proactive)
        assertTrue("never silent", rejecting.proactive <= 0.85f)
        assertTrue("never reckless", accepting.proactive >= 0.40f)
    }

    @Test
    fun aRiderWhoAlwaysSaysNoStopsGettingWeakSuggestions() {
        // Two college trips out of four morning trips: a weak habit (share 0.5).
        val weak = memory.copy(trips = memory.trips.take(2) + listOf(
            trip("x1", gandhipuram, at(2026, 9, 16, 8, 35)),
            trip("x2", gandhipuram, at(2026, 9, 17, 8, 40))
        ))
        val lenient = Thresholds(0.30f, 0.62f)
        assertNotNull(PreferenceMemoryAgent.proactive(weak, moment(2026, 9, 21, 8, 40), lenient))
        val recalibrated = Thresholds.from(MemoryStats(proactiveRejected = 6))
        assertNull(PreferenceMemoryAgent.proactive(weak, moment(2026, 9, 21, 8, 40), recalibrated))
    }

    // --------------------------------------------------------------- repair

    @Test
    fun spokenAcronymIsRecognised() {
        assertEquals("psg", TextSimilarity.spokenAcronym("piece g"))
        assertEquals("psg", TextSimilarity.spokenAcronym("pee ess gee"))
        assertTrue(TextSimilarity.similarity("pee ess gee", "PSG College of Technology") >= 0.9f)
    }

    @Test
    fun misheardNamesRepairToVisitedPlaces() {
        val m = moment(2026, 9, 21, 12, 0)
        assertEquals("ChIJ-bf", PreferenceMemoryAgent.repair("brook fields", memory, m, defaults)?.place?.placeKey)
        assertEquals("ChIJ-gp", PreferenceMemoryAgent.repair("gandipuram", memory, m, defaults)?.place?.placeKey)
        assertEquals("ChIJ-psg", PreferenceMemoryAgent.repair("psg", memory, m, defaults)?.place?.placeKey)
    }

    @Test
    fun genericWordsAloneAreNotEnough() {
        assertNull(PreferenceMemoryAgent.repair("mall", memory, moment(2026, 9, 21, 12, 0), defaults))
    }

    @Test
    fun unrelatedWordsRepairToNothing() {
        assertNull(PreferenceMemoryAgent.repair("airport", memory, moment(2026, 9, 21, 12, 0), defaults))
    }

    @Test
    fun theTimeOfTravelBreaksATie() {
        // Two colleges the rider has visited; at 8:40 on a Monday "college" means theirs.
        val kg = VisitedPlace(placeKey = "ChIJ-kg", name = "KG College", latitude = 11.05, longitude = 77.03, visitCount = 1)
        val both = memory.copy(places = memory.places + kg)
        val ranked = PreferenceMemoryAgent.rankByWords("college", both, moment(2026, 9, 21, 8, 40), emptySet())
        assertEquals("ChIJ-psg", ranked.first().place.placeKey)
        val s = PreferenceMemoryAgent.repair("college", both, moment(2026, 9, 21, 8, 40), defaults)
        assertEquals("ChIJ-psg", s?.place?.placeKey)
        assertEquals("like most weekday mornings", s?.reason)
        // At noon there is no habit to lean on, so "college" alone suggests nothing.
        assertNull(PreferenceMemoryAgent.repair("college", both, moment(2026, 9, 21, 12, 0), defaults))
    }

    @Test
    fun rejectedCandidatesAreExcluded() {
        val m = moment(2026, 9, 21, 12, 0)
        assertNull(PreferenceMemoryAgent.repair("brook fields", memory, m, defaults, exclude = setOf("ChIJ-bf")))
    }

    @Test
    fun aLearnedAliasIsADirectHit() {
        val s = PreferenceMemoryAgent.direct("piece g", memory, moment(2026, 9, 21, 12, 0))
        assertEquals("ChIJ-psg", s?.place?.placeKey)
        assertNull(PreferenceMemoryAgent.direct("brook", memory, moment(2026, 9, 21, 12, 0)))
        assertEquals("ChIJ-psg", PreferenceMemoryAgent.direct("psg", memory, moment(2026, 9, 21, 12, 0))?.place?.placeKey)
        // One shared word is a question, not a booking: the rider may mean the area.
        assertNull(PreferenceMemoryAgent.direct("gandhipuram", memory, moment(2026, 9, 21, 12, 0)))
        assertEquals("ChIJ-gp", PreferenceMemoryAgent.repair("gandhipuram", memory, moment(2026, 9, 21, 12, 0),
            Thresholds(Thresholds.BASE_PROACTIVE, Thresholds.BASE_REPAIR))?.place?.placeKey)
    }

    @Test
    fun unheardSpeechFallsBackToTheTimeBasedGuess() {
        val s = PreferenceMemoryAgent.guessWhenUnheard(memory, moment(2026, 9, 21, 8, 40), defaults, emptySet())
        assertEquals("ChIJ-psg", s?.place?.placeKey)
    }

    @Test
    fun spokenTimeReadsNaturally() {
        assertEquals("8 40 AM", PreferenceMemoryAgent.spokenTime(moment(2026, 9, 21, 8, 40)))
        assertEquals("12 PM", PreferenceMemoryAgent.spokenTime(moment(2026, 9, 21, 12, 0)))
    }
}
