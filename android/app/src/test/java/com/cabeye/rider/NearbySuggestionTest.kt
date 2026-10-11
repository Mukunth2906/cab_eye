package com.cabeye.rider

import com.cabeye.rider.memory.VisitedPlace
import com.cabeye.rider.mindmap.EdgeKind
import com.cabeye.rider.mindmap.NearbyPlace
import com.cabeye.rider.mindmap.NearbySuggestionAgent
import com.cabeye.rider.mindmap.Node
import com.cabeye.rider.mindmap.NodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arriving somewhere new: only for a first visit, only well-known places (rated, enough
 * ratings), at most three, with straight-line distance and direction; the rider can pick one by
 * name or order; two declines and it stops offering by itself.
 */
class NearbySuggestionTest {

    private val lat = 11.0168
    private val lng = 76.9558
    private fun north(m: Double) = lat + m / 110_540.0
    private fun east(m: Double) = lng + m / (111_320.0 * Math.cos(Math.toRadians(lat)))

    private val temple = NearbyPlace("Sri Ranganathar Temple", north(200.0), lng, listOf("hindu_temple", "place_of_worship"), 4.7, 2_300, "t1")
    private val mall = NearbyPlace("Brookefields Mall", lat, east(500.0), listOf("shopping_mall"), 4.4, 45_000, "m1")
    private val park = NearbyPlace("VOC Park", north(-300.0), lng, listOf("park"), 4.2, 800, "p1")
    private val cafe = NearbyPlace("Corner Cafe", north(100.0), lng, listOf("cafe"), 4.6, 40, "c1") // too few ratings
    private val dump = NearbyPlace("Old Market", north(150.0), lng, listOf("market"), 3.1, 900, "x1") // rated low
    private val far = NearbyPlace("Marudhamalai Temple", north(5_000.0), lng, listOf("hindu_temple"), 4.8, 30_000, "f1")
    private val here = NearbyPlace("KG Clinic", north(10.0), lng, listOf("hospital"), 4.5, 3_000, "h1")

    @Test
    fun onlyFirstVisitsWithoutAWalkAreNew() {
        val once = VisitedPlace(placeKey = "k", name = "KG Clinic", visitCount = 1)
        val often = once.copy(visitCount = 5)
        assertTrue(NearbySuggestionAgent.isNew("k", listOf(once), hasWalk = false))
        assertTrue(NearbySuggestionAgent.isNew("k", emptyList(), hasWalk = false))
        assertFalse(NearbySuggestionAgent.isNew("k", listOf(often), hasWalk = false))
        assertFalse(NearbySuggestionAgent.isNew("k", listOf(once), hasWalk = true))
    }

    @Test
    fun picksWellKnownPlacesCloseByAtMostThree() {
        val picks = NearbySuggestionAgent.pick(lat, lng, listOf(here, cafe, temple, dump, mall, far, park), hereName = "KG Clinic")
        assertEquals(listOf("Sri Ranganathar Temple", "Brookefields Mall", "VOC Park"), picks.map { it.first.name })
    }

    @Test
    fun openStreetMapPlacesKeepTheirOrderAndCredit() {
        // The backend's answer for the clinic area, from real OpenStreetMap data.
        val body = """{"places":[
            {"name":"Coimbatore Junction","lat":10.9975,"lng":76.9665,"types":["train_station"],"notable":true,"wikidata":"Q3532594"},
            {"name":"Koniamman Temple","lat":10.9937,"lng":76.9637,"types":["hindu_temple","place_of_worship"],"notable":true,"wikidata":"Q19806101"},
            {"name":"Clock Tower","lat":10.9940,"lng":76.9629,"types":["tourist_attraction"],"notable":true,"wikidata":""},
            {"name":"","lat":1,"lng":2}],
            "attribution":"Data © OpenStreetMap contributors, ODbL"}"""
        val osm = com.cabeye.rider.mindmap.NearbyJson.osm(body)
        assertEquals(listOf("Coimbatore Junction", "Koniamman Temple", "Clock Tower"), osm.map { it.name })
        assertTrue(osm.all { it.ranked })
        assertEquals("osm:Q3532594", osm[0].placeId)
        val picks = NearbySuggestionAgent.pick(10.9985, 76.9640, osm, "KG Clinic")
        // Kept in the map's order of how well known they are, not re-sorted by distance.
        assertEquals(listOf("Coimbatore Junction", "Koniamman Temple", "Clock Tower"), picks.map { it.first.name })
        val more = NearbySuggestionAgent.more(picks.take(2), 10.9985, 76.9640)
        assertTrue(more, more.startsWith("Coimbatore Junction, a railway station, about 300 metres east."))
        assertTrue(more, more.contains("Koniamman Temple, a temple, about 550 metres south."))
        assertTrue(more, more.endsWith("Place information from OpenStreetMap."))
        assertEquals(emptyList<NearbyPlace>(), com.cabeye.rider.mindmap.NearbyJson.osm("<html>busy</html>"))
    }

    @Test
    fun curatedPlacesNeedNoRating() {
        val curated = NearbyPlace("Town Hall", north(250.0), lng, curated = true)
        assertEquals(listOf("Town Hall"), NearbySuggestionAgent.pick(lat, lng, listOf(curated)).map { it.first.name })
    }

    @Test
    fun theOfferSaysDistanceAndDirection() {
        val picks = NearbySuggestionAgent.pick(lat, lng, listOf(temple, mall))
        val said = NearbySuggestionAgent.offer(picks, lat, lng, newHere = true)
        assertEquals(
            "This is new for you. Well-known places close by: Sri Ranganathar Temple, about 200 metres north; " +
                "Brookefields Mall, about 500 metres east. Say tell me more, take me to one of them, or remember them.",
            said
        )
        // facing east, north is at 9 o'clock
        assertTrue(NearbySuggestionAgent.offer(picks, lat, lng, newHere = false, heading = 90.0).contains("about 200 metres at 9 o'clock"))
        assertEquals("I couldn't find well-known places close by.", NearbySuggestionAgent.offer(emptyList(), lat, lng, true))
    }

    @Test
    fun moreDetailHonestAboutDistance() {
        val picks = NearbySuggestionAgent.pick(lat, lng, listOf(temple))
        val s = NearbySuggestionAgent.more(picks, lat, lng)
        assertEquals("Sri Ranganathar Temple, a temple, rated 4.7 by about 2 thousand people, about 200 metres north. " +
            "Distances are straight-line, not the walking route.", s)
    }

    @Test
    fun choosingByNameKindOrOrder() {
        val picks = NearbySuggestionAgent.pick(lat, lng, listOf(temple, mall, park))
        assertEquals("Brookefields Mall", NearbySuggestionAgent.choose(picks, "take me to brookefields")?.name)
        assertEquals("Sri Ranganathar Temple", NearbySuggestionAgent.choose(picks, "the temple")?.name)
        assertEquals("VOC Park", NearbySuggestionAgent.choose(picks, "the third one")?.name)
        assertEquals("VOC Park", NearbySuggestionAgent.choose(picks, "the last one")?.name)
        assertNull(NearbySuggestionAgent.choose(picks, "the zoo"))
    }

    @Test
    fun twoDeclinesAndItStopsOffering() {
        var g = NearbySuggestionAgent.Guard()
        assertTrue(g.offersProactively)
        g = g.copy(declines = 1); assertTrue(g.offersProactively)
        g = g.copy(declines = 2); assertFalse(g.offersProactively)
        assertFalse(NearbySuggestionAgent.Guard(off = true).offersProactively)
    }

    @Test
    fun rememberedPlacesJoinTheMindMapNearTheDropOff() {
        val drop = Node("place:k", NodeKind.PLACE, "KG Clinic", lat, lng)
        val (nodes, edges) = NearbySuggestionAgent.remember(NearbySuggestionAgent.pick(lat, lng, listOf(temple)), drop, 1L)
        assertEquals("near:t1", nodes.single().id)
        assertEquals(EdgeKind.NEAR, edges.single().kind)
        assertEquals("place:k", edges.single().to)
    }
}
