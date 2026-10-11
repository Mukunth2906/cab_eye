package com.cabeye.rider

import com.cabeye.rider.mindmap.Geo
import com.cabeye.rider.mindmap.NearbySuggestionAgent
import com.cabeye.rider.mindmap.RideGuideAgent
import com.cabeye.rider.mindmap.SyntheticRide
import com.cabeye.rider.places.Atlas
import com.cabeye.rider.places.AtlasStore
import com.cabeye.rider.places.MatchGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The offline Coimbatore atlas — the real OpenStreetMap data bundled in the app — answering with
 * no network: area names, well-known places, and place search that the confidence gate accepts.
 * Then a simulated ride across the city through the real ride guide, named by the atlas.
 */
class AtlasTest {

    private val atlas: Atlas = Atlas.parse(File("src/main/assets/atlas/coimbatore.json").readText(Charsets.UTF_8))!!
        .withAreas(AtlasStore.gazetteerAreas())

    @Test
    fun theBundledDataIsRealAndSmall() {
        assertEquals("Coimbatore", atlas.region)
        assertTrue(atlas.areas.size > 100)
        assertTrue(atlas.places.size > 1_000)
        assertTrue(atlas.attribution.contains("OpenStreetMap"))
        assertTrue(File("src/main/assets/atlas/coimbatore.json").length() < 300_000)
    }

    @Test
    fun areaNamesAreNeighbourhoods() {
        assertEquals("RS Puram", atlas.areaAt(11.0068, 76.9490))
        assertEquals("Saibaba Colony", atlas.areaAt(11.0244, 76.9450))
        assertEquals("Gandhipuram", atlas.areaAt(11.0177, 76.9660))
        // Not an OpenStreetMap area point: the app's own hand-checked list fills the gap.
        assertEquals("Race Course", atlas.areaAt(10.9975, 76.9705))
        // Outside the data (Chennai): nothing, rather than a guess.
        assertNull(atlas.areaAt(13.0827, 80.2707))
        assertFalse(atlas.covers(13.0827, 80.2707))
    }

    @Test
    fun wellKnownPlacesNearTheClinic() {
        val near = atlas.nearby(10.9985, 76.9640, 600.0)
        val names = near.map { it.name }
        assertTrue(names.toString(), "Coimbatore Junction" in names)
        assertTrue(names.toString(), "Koniamman Temple" in names)
        assertTrue(near.all { it.ranked })
        val picks = NearbySuggestionAgent.pick(10.9985, 76.9640, near, "KG Clinic")
        assertEquals(3, picks.size)
        assertTrue(NearbySuggestionAgent.more(picks, 10.9985, 76.9640).endsWith("Place information from OpenStreetMap."))
    }

    @Test
    fun placesByTheNamesPeopleSay() {
        fun top(q: String) = atlas.search(q, 11.0068, 76.9490).first()
        assertEquals("Arulmigu Subramaniyaswami Thirukovil", top("marudhamalai temple").name)
        assertEquals("Kovai Medical Centre and Hospital", top("KMCH").name)
        assertEquals("Coimbatore International Airport", top("airport").name)
        assertEquals("Coimbatore Junction", top("railway station").name)
        assertEquals("Brookefields Mall", top("brookefields mall").name)
        assertEquals("Tamil Nadu Agricultural University", top("TNAU").name)
        // The confidence gate accepts them as it does the built-in places.
        for (q in listOf("marudhamalai temple", "KMCH", "koniamman temple", "brookefields mall")) {
            val d = MatchGate.evaluate(q, atlas.search(q, 11.0068, 76.9490))
            assertTrue("$q -> $d", d is MatchGate.Decision.Proceed)
        }
        // An area is not an address: marked vague, so the app asks who is meeting the rider.
        val area = top("kalapatti")
        assertEquals("Kalapatti", area.name)
        assertTrue(area.isVague)
        assertFalse(top("koniamman temple").isVague)
        // Nonsense is not booked.
        val nonsense = atlas.search("zzqx")
        assertTrue(nonsense.take(3).toString(), MatchGate.evaluate("zzqx", nonsense) !is MatchGate.Decision.Proceed)
    }

    @Test
    fun aSimulatedRideAcrossTheCityIsNamedByTheAtlas() {
        val dest = atlas.search("Coimbatore Junction").first()
        val route = SyntheticRide.route(11.0244, 76.9450, dest.latitude, dest.longitude, 1_790_000_000_000L)
        // Endpoints are exact, distance close to the straight line, fixes ~100 m and 15 s apart.
        assertEquals(11.0244, route.first().lat, 1e-9)
        assertEquals(dest.latitude, route.last().lat, 1e-9)
        val straight = Geo.metres(11.0244, 76.9450, dest.latitude, dest.longitude)
        assertTrue(SyntheticRide.metres(route) in straight..straight * 1.1)
        assertEquals(route, SyntheticRide.route(11.0244, 76.9450, dest.latitude, dest.longitude, 1_790_000_000_000L))

        val g = RideGuideAgent("junction", dest.name, emptyList(), emptyList(), destLat = dest.latitude, destLng = dest.longitude)
        val said = mutableListOf<String>()
        route.forEachIndexed { i, f ->
            g.onFix(f.lat, f.lng, f.t).forEach { said += it.text }
            if (i % 4 == 0) atlas.areaAt(f.lat, f.lng)?.let { a -> g.onArea(a, f.t)?.let { said += it.text } }
        }
        assertTrue(said.toString(), said.any { it.startsWith("Now in ") })
        assertTrue(said.toString(), said.any { it.startsWith("Almost there: Coimbatore Junction") })
        assertNotNull(g.trace(route.last().t))
    }
}
