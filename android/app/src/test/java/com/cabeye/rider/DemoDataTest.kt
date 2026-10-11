package com.cabeye.rider

import com.cabeye.rider.demo.DemoData
import com.cabeye.rider.memory.VisitedPlace
import com.cabeye.rider.mindmap.EdgeKind
import com.cabeye.rider.mindmap.MindMapAgent
import com.cabeye.rider.mindmap.MindMapBuilder
import com.cabeye.rider.mindmap.NodeKind
import com.cabeye.rider.walk.DetailLevel
import com.cabeye.rider.walk.WalkNarrator
import com.cabeye.rider.walk.WalkProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo content joins up into one mind map the way real use would leave it. */
class DemoDataTest {

    private val now = 1_790_000_000_000L
    private val content = DemoData.build(now)

    /** What the backend's seed_demo.py leaves in ride memory, as the phone receives it. */
    private val places = listOf(
        VisitedPlace(DemoData.KGH, "KG Hospital", latitude = 11.0005, longitude = 76.97164, placeId = DemoData.KGH, visitCount = 5),
        VisitedPlace(DemoData.PSG, "PSG College of Technology", latitude = 11.02481, longitude = 77.00292, placeId = DemoData.PSG, visitCount = 16),
        VisitedPlace(DemoData.RACE, "Race Course", latitude = 11.0017, longitude = 76.9713, placeId = DemoData.RACE, visitCount = 4),
        VisitedPlace(DemoData.MALL, "Brookefields Mall", latitude = 11.00887, longitude = 76.9594, placeId = DemoData.MALL, visitCount = 4)
    )
    private val map = MindMapBuilder.build(content.authored, content.routes, places)

    @Test
    fun `each walk starts at its cab drop-off`() {
        for (r in content.routes) {
            val leads = map.edges.filter { it.kind == EdgeKind.LEADS_TO && it.to == MindMapBuilder.walkId(r.id) }
            assertEquals("walk ${r.name}", listOf(MindMapBuilder.placeId(r.placeKey)), leads.map { it.from })
        }
    }

    @Test
    fun `the bakery on two walks is one bakery`() {
        assertTrue(map.edges.any {
            it.kind == EdgeKind.SAME_AS && setOf(it.from, it.to) == setOf("lm:demo-lm-bakery1", "lm:demo-lm-bakery2")
        })
    }

    @Test
    fun `notes give meaning to walk landmarks, and no edge is left dangling`() {
        assertEquals("Ravi's tea shop", map.anchorsFor("lm:demo-lm-tea").single().label)
        assertEquals("the temple bell", map.anchorsFor("lm:demo-lm-bell").single().label)
        assertTrue(map.edges.all { map.node(it.from) != null && map.node(it.to) != null })
        assertTrue(map.nodes.count { it.kind == NodeKind.RIDE_CUE } == 3)
        assertTrue(map.nodes.count { it.kind == NodeKind.AREA } == 4)
    }

    @Test
    fun `what's around the hospital mentions the walk, the tea shop note and the ride cue`() {
        val hospital = map.node(MindMapBuilder.placeId(DemoData.KGH))!!
        val said = MindMapAgent.around(map, hospital, now)
        assertTrue(said, said.contains("outpatient", ignoreCase = true))
        assertTrue(said, said.contains("where you stop for tea on Fridays"))
        assertTrue(said, said.contains("pothole"))
        assertTrue(MindMapAgent.summary(map).isNotBlank())
    }

    @Test
    fun `walks are at three different detail levels and narrate`() {
        val levels = content.routes.map { WalkNarrator.levelFor(it, WalkProfile(consentGiven = true)) }.toSet()
        assertEquals(setOf(DetailLevel.FULL, DetailLevel.BRIEF, DetailLevel.LANDMARKS), levels)
        content.routes.forEach { r ->
            assertTrue(WalkNarrator.narrate(r, DetailLevel.FULL, now).isNotBlank())
        }
    }

    @Test
    fun `past rides lead from home to the right places`() {
        assertEquals(5, content.traces.size)
        assertTrue(content.traces.all { it.points.size > 5 && it.totalMetres > 1_000 })
    }
}
