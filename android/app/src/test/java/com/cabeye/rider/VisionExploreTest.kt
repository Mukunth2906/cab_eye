package com.cabeye.rider

import com.cabeye.rider.demo.DemoData
import com.cabeye.rider.explore.ExploreCommands
import com.cabeye.rider.explore.ExploreCommands.Command
import com.cabeye.rider.memory.VisitedPlace
import com.cabeye.rider.mindmap.Edge
import com.cabeye.rider.mindmap.EdgeKind
import com.cabeye.rider.mindmap.MindMap
import com.cabeye.rider.mindmap.MindMapAgent
import com.cabeye.rider.mindmap.MindMapBuilder
import com.cabeye.rider.mindmap.MindMapCatalog
import com.cabeye.rider.mindmap.MindModule
import com.cabeye.rider.mindmap.Node
import com.cabeye.rider.mindmap.NodeKind
import com.cabeye.rider.mindmap.Observations
import com.cabeye.rider.vision.Box
import com.cabeye.rider.vision.Scene
import com.cabeye.rider.vision.SceneObject
import com.cabeye.rider.vision.SceneSource
import com.cabeye.rider.vision.SceneSpeech
import com.cabeye.rider.walk.Side
import com.cabeye.rider.walk.WalkProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera modules (ride camera, explore) and the mind map's split storage and screen. */
class VisionExploreTest {

    private fun obj(label: String, cx: Float, h: Float = 0.3f, d: Double? = null) = SceneObject(
        label = label,
        box = Box(cx - 0.1f, 0.5f - h / 2, cx + 0.1f, 0.5f + h / 2),
        clock = SceneSpeech.clock(cx),
        distanceM = d
    )

    // ---------------------------------------------------------------------------------
    //  What the rider hears
    // ---------------------------------------------------------------------------------

    @Test
    fun `positions are a clock face`() {
        assertEquals("10 o'clock", SceneSpeech.clock(0.05f))
        assertEquals("11 o'clock", SceneSpeech.clock(0.3f))
        assertEquals("12 o'clock", SceneSpeech.clock(0.5f))
        assertEquals("1 o'clock", SceneSpeech.clock(0.7f))
        assertEquals("2 o'clock", SceneSpeech.clock(0.95f))
    }

    @Test
    fun `hazards come first, and nothing is ever called clear or safe`() {
        val said = SceneSpeech.describe(
            Scene(
                summary = "A quiet lane. The path is clear.",
                objects = listOf(obj("chair", 0.7f), obj("steps", 0.5f, d = 2.0)),
                source = SceneSource.GEMINI
            )
        )
        assertTrue(said, said.startsWith("Careful: steps at 12 o'clock, about 2 metres."))
        assertFalse(said, said.contains("clear", ignoreCase = true))
        assertTrue(said, said.contains("a chair at 1 o'clock"))
    }

    @Test
    fun `keep telling me says only what is new`() {
        val scene = Scene(objects = listOf(obj("bench", 0.2f), obj("dog", 0.5f)))
        assertEquals("Careful: a dog at 12 o'clock. A bench at 11 o'clock.", SceneSpeech.changes(scene, emptySet()))
        assertEquals("A bench at 11 o'clock.", SceneSpeech.changes(scene, setOf("dog")))
        assertNull(SceneSpeech.changes(scene, setOf("dog", "bench")))
    }

    @Test
    fun `find the door says where it is, or to keep turning`() {
        assertEquals("door", SceneSpeech.targetOf("the front door"))
        assertEquals("seat", SceneSpeech.targetOf("a bench to sit on"))
        val scene = Scene(objects = listOf(obj("chair", 0.2f), obj("Doorway", 0.75f, d = 4.0)))
        assertEquals("A doorway at 1 o'clock, about 4 metres.", SceneSpeech.find("door", scene))
        assertEquals("I can't see steps yet. Turn slowly.", SceneSpeech.find("steps", scene))
    }

    @Test
    fun `the server's boxes become fractions and keep their clock`() {
        val scene = Scene.fromServer(
            JSONObject(
                """{"summary":"A shop.","objects":[{"label":"door","box":[100,700,900,900],"clock":"1 o'clock","distanceM":3}],
                   "hazards":["two steps up"],"text":"OPEN","found":"","source":"gemini"}"""
            ), 0
        )
        val door = scene.objects.single()
        assertEquals(0.7f, door.box!!.left, 0.001f)
        assertEquals(0.9f, door.box!!.bottom, 0.001f)
        assertEquals("1 o'clock", door.clock)
        assertEquals(3.0, door.distanceM!!, 0.001)
        assertEquals(SceneSource.GEMINI, scene.source)
        assertTrue(SceneSpeech.describe(scene).startsWith("Careful: two steps up."))
    }

    // ---------------------------------------------------------------------------------
    //  Explore voice commands
    // ---------------------------------------------------------------------------------

    @Test
    fun `explore understands what a rider says`() {
        assertEquals(Command.Describe, ExploreCommands.parse("what's in front of me"))
        assertEquals(Command.Read, ExploreCommands.parse("read this"))
        assertEquals(Command.KeepTelling, ExploreCommands.parse("keep telling me"))
        assertEquals(Command.Find("door"), ExploreCommands.parse("find the door"))
        assertEquals(Command.Find("bus stop"), ExploreCommands.parse("where is the bus stop"))
        assertEquals(Command.Name("Ravi's tea shop"), ExploreCommands.parse("this is Ravi's tea shop"))
        assertEquals(Command.Name("amma's gate"), ExploreCommands.parse("remember this as amma's gate"))
        assertEquals(Command.Finish, ExploreCommands.parse("I'm done"))
        assertEquals(Command.Stop, ExploreCommands.parse("stop"))
        assertEquals(Command.Discard, ExploreCommands.parse("don't save"))
        assertNull(ExploreCommands.parse("take me to the airport"))
    }

    @Test
    fun `the main question opens explore for camera words, not for bookings`() {
        assertTrue(ExploreCommands.opens("what's in front of me"))
        assertTrue(ExploreCommands.opens("I want to explore"))
        assertTrue(ExploreCommands.opens("open the camera"))
        assertFalse(ExploreCommands.opens("take me to PSG college"))
        assertFalse(ExploreCommands.opens("what's around KG Hospital"))
    }

    // ---------------------------------------------------------------------------------
    //  Separate storage per module
    // ---------------------------------------------------------------------------------

    @Test
    fun `ride things and explore things go to their own stores`() {
        val map = MindMap(
            listOf(
                Node("place:demo-kgh", NodeKind.PLACE, "KG Hospital"),
                Node("cue:1", NodeKind.RIDE_CUE, "the flyover"),
                Node("area:rs-puram", NodeKind.AREA, "RS Puram"),
                Node("a:1", NodeKind.ANCHOR, "Ravi's tea shop"),
                Node("obs:1", NodeKind.OBSERVED, "bench")
            ),
            listOf(
                Edge("cue:1", "place:demo-kgh", EdgeKind.ON_RIDE),
                Edge("a:1", "place:demo-kgh", EdgeKind.NEAR),
                Edge("a:1", "lm:x", EdgeKind.MEANS)
            )
        )
        val (rides, explore) = MindModule.split(map)
        assertEquals(setOf("place:demo-kgh", "cue:1", "area:rs-puram"), rides.nodes.map { it.id }.toSet())
        assertEquals(setOf("a:1", "obs:1"), explore.nodes.map { it.id }.toSet())
        assertEquals(1, rides.edges.size)
        assertEquals(2, explore.edges.size)
        assertEquals(map.nodes.size, rides.plus(explore).nodes.size)
    }

    @Test
    fun `the same thing seen again close by is one sighting, seen more times`() {
        val bench = Node("obs:a", NodeKind.OBSERVED, "bench", lat = 11.0, lng = 76.9, side = Side.LEFT)
        var map = Observations.merge(MindMap(), listOf(bench), now = 1)
        map = Observations.merge(map, listOf(bench.copy(id = "obs:b", lat = 11.00005)), now = 2)
        map = Observations.merge(map, listOf(bench.copy(id = "obs:c", lat = 11.01)), now = 3)
        val benches = map.nodes.filter { it.label == "bench" }
        assertEquals(2, benches.size)
        assertEquals(2, benches.first { it.id == "obs:a" }.confirmations)
    }

    // ---------------------------------------------------------------------------------
    //  The mind map screen
    // ---------------------------------------------------------------------------------

    @Test
    fun `the demo mind map shows rides, named spots, walks and camera sightings`() {
        val now = 1_790_000_000_000L
        val c = DemoData.build(now)
        val places = listOf(
            VisitedPlace(DemoData.KGH, "KG Hospital", latitude = 11.0005, longitude = 76.97164, placeId = DemoData.KGH, visitCount = 9, aliases = listOf("hospital")),
            VisitedPlace(DemoData.PSG, "PSG College of Technology", latitude = 11.02481, longitude = 77.00292, placeId = DemoData.PSG, visitCount = 21)
        )
        val map = MindMapBuilder.build(c.authored, c.routes, places)
        val home = 11.0080 to 76.9502
        val cat = MindMapCatalog.build(map, places, emptyList(), c.routes, WalkProfile(), home, now)

        assertEquals("PSG College of Technology", cat.ridePlaces.first().name)
        assertEquals(listOf("hospital"), cat.ridePlaces.first { it.name == "KG Hospital" }.aliases)
        assertTrue(cat.ridePlaces.first { it.name == "KG Hospital" }.walks.isNotEmpty())
        assertTrue(cat.ridePlaces.first { it.name == "KG Hospital" }.seenHere.contains("steps"))
        assertEquals(setOf("where you meet Priya", "Murugan medicals"), cat.spots.map { it.name }.toSet())
        assertEquals(4, cat.walks.size)
        assertTrue(cat.walks.any { it.explored })
        assertTrue(cat.walks.first { it.name.startsWith("KG") }.checkFor.contains("the security guard's whistle"))
        assertEquals(9, cat.sightings.size)
        assertTrue(cat.dots.isNotEmpty())
        assertTrue(cat.rideNotes.any { it.title == "the flyover" })

        val around = MindMapAgent.nearPoint(map, home.first, home.second, "RS Puram", now)!!
        assertTrue(around, around.contains("With your camera here you've seen"))
        assertTrue(around, around.contains("a gate"))
    }
}
