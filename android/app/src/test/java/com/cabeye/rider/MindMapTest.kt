package com.cabeye.rider

import com.cabeye.rider.memory.VisitedPlace
import com.cabeye.rider.mindmap.AnchorParser
import com.cabeye.rider.mindmap.EdgeKind
import com.cabeye.rider.mindmap.LandmarkLinker
import com.cabeye.rider.mindmap.MindMap
import com.cabeye.rider.mindmap.MindMapAgent
import com.cabeye.rider.mindmap.MindMapBuilder
import com.cabeye.rider.mindmap.MindMapCommands
import com.cabeye.rider.mindmap.MindMapCommands.Command
import com.cabeye.rider.mindmap.MindMapJson
import com.cabeye.rider.mindmap.NodeKind
import com.cabeye.rider.walk.DetailLevel
import com.cabeye.rider.walk.Landmark
import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.Segment
import com.cabeye.rider.walk.Side
import com.cabeye.rider.walk.TurnDirection
import com.cabeye.rider.walk.WalkCommands
import com.cabeye.rider.walk.WalkNarrator
import com.cabeye.rider.walk.WalkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The personal mind map: walks and rides joined into one memory, the rider's own anchors parsed
 * and read back in the second person, the same landmark recognised across walks (and two
 * different ones kept apart), and the spoken "what's around" answer — never claiming a way is
 * clear, never keeping a visual-only cue.
 */
class MindMapTest {

    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    // ---- fixtures: two drop-offs 200 m apart in Coimbatore, each with a walk ----------------

    private val clinic = VisitedPlace(placeKey = "p-clinic", name = "KG Clinic", latitude = 11.0168, longitude = 76.9558, visitCount = 4, lastVisitedAt = now - 2 * day)
    private val busStop = VisitedPlace(placeKey = "p-bus", name = "Town Hall bus stop", latitude = 11.0186, longitude = 76.9558, visitCount = 2, lastVisitedAt = now - 5 * day)
    private val farMall = VisitedPlace(placeKey = "p-mall", name = "Brookefields Mall", latitude = 11.0060, longitude = 76.9650, visitCount = 1, lastVisitedAt = now - day)

    private fun lm(id: String, kind: LandmarkKind, text: String, side: Side = Side.NONE, cue: Boolean = false, at: Long = now - 3 * day) =
        Landmark(id = id, kind = kind, text = text, side = side, isTurnCue = cue, recordedAt = at, lastConfirmedAt = at)

    private val clinicWalk = WalkRoute(
        id = "w-clinic", name = "clinic door", placeKey = "p-clinic", placeName = "KG Clinic",
        segments = listOf(
            Segment(20.0, 30, TurnDirection.LEFT, listOf(lm("l1", LandmarkKind.SMELL, "bakery smell", Side.LEFT, cue = true))),
            Segment(15.0, 22, TurnDirection.NONE, listOf(lm("l2", LandmarkKind.KERB, "kerb"), lm("l3", LandmarkKind.DOOR, "glass door")))
        ),
        createdAt = now - 10 * day, updatedAt = now - 3 * day, walks = 3
    )
    private val busWalk = WalkRoute(
        id = "w-bus", name = "bus shelter", placeKey = "p-bus", placeName = "Town Hall bus stop",
        segments = listOf(Segment(30.0, 45, TurnDirection.NONE, listOf(lm("b1", LandmarkKind.SMELL, "the bakery smell"), lm("b2", LandmarkKind.KERB, "kerb")))),
        createdAt = now - 8 * day, updatedAt = now - 5 * day, walks = 1
    )
    private val mallWalk = WalkRoute(
        id = "w-mall", name = "mall entrance", placeKey = "p-mall", placeName = "Brookefields Mall",
        segments = listOf(Segment(25.0, 35, TurnDirection.NONE, listOf(lm("m1", LandmarkKind.SMELL, "bakery smell")))),
        createdAt = now - 4 * day, updatedAt = now - day, walks = 1
    )

    private fun built(authored: MindMap = MindMap()) =
        MindMapBuilder.build(authored, listOf(clinicWalk, busWalk, mallWalk), listOf(clinic, busStop, farMall))

    // ---- building ----------------------------------------------------------------------------

    @Test
    fun ridesAndWalksBecomeOneJourney() {
        val m = built()
        val clinicNode = m.node("place:p-clinic")!!
        assertEquals(NodeKind.PLACE, clinicNode.kind)
        // drop-off → walk end → landmarks on it
        val walkEnd = m.neighbours(clinicNode.id, EdgeKind.LEADS_TO).single()
        assertEquals("clinic door", walkEnd.label)
        val onWalk = m.neighbours(walkEnd.id, EdgeKind.ON_WALK).map { it.label }.toSet()
        assertEquals(setOf("bakery smell", "kerb", "glass door"), onWalk)
        // landmarks are placed at the drop-off, so "near" works for them
        assertEquals(clinic.latitude!!, m.node("lm:l1")!!.lat!!, 1e-9)
    }

    @Test
    fun theSameBakeryOnTwoNearbyWalksIsOneMemoryButTwoKerbsAreNot() {
        val m = built()
        val same = m.edges.filter { it.kind == EdgeKind.SAME_AS }
        // clinic bakery ↔ bus-stop bakery (200 m apart): joined
        assertTrue(same.any { setOf(it.from, it.to) == setOf("lm:l1", "lm:b1") })
        // "kerb" names only a kind: never joined, however close
        assertFalse(same.any { setOf(it.from, it.to) == setOf("lm:l2", "lm:b2") })
        // the mall bakery is ~1 km away: a different bakery
        assertFalse(same.any { "lm:m1" in setOf(it.from, it.to) })
    }

    @Test
    fun linkerNeedsDistinctiveWords() {
        val m = built()
        assertEquals("bakery", LandmarkLinker.distinctive(m.node("lm:l1")!!))
        assertEquals("", LandmarkLinker.distinctive(m.node("lm:l2")!!))
        assertEquals("clinic", LandmarkLinker.distinctive(m.node("lm:l3")!!.copy(label = "clinic door")))
    }

    // ---- anchors from the rider's words -------------------------------------------------------

    @Test
    fun possessiveAndHabitAreUnderstood() {
        val a = (AnchorParser.parse("Ravi's tea shop, where I stop on Fridays") as AnchorParser.Result.Found).anchor
        assertEquals("ravi's tea shop", a.subject)
        assertEquals("Ravi", a.person)
        assertEquals("you stop on fridays", a.habit)
        assertFalse(a.here)
    }

    @Test
    fun aSoundThatMeansYouAreNearAPlace() {
        val a = (AnchorParser.parse("the temple bell means I'm near Amma's house") as AnchorParser.Result.Found).anchor
        assertEquals("temple bell", a.subject)
        assertEquals(LandmarkKind.SOUND, a.sense)
        assertEquals("you're near amma's house", a.meaning)
        assertEquals("amma's house", a.relatesTo)
        assertEquals("Amma", a.person)

        val b = (AnchorParser.parse("when I smell the bakery I'm almost at the clinic") as AnchorParser.Result.Found).anchor
        assertEquals("bakery smell", b.subject)
        assertEquals(LandmarkKind.SMELL, b.sense)
        assertEquals("you're almost at the clinic", b.meaning)
        assertEquals("clinic", b.relatesTo)
    }

    @Test
    fun thisPlaceAnchors() {
        val a = (AnchorParser.parse("remember this as the place I meet Priya") as AnchorParser.Result.Found).anchor
        assertTrue(a.here)
        assertEquals("", a.subject)
        assertEquals("you meet priya", a.habit)
        assertEquals("Priya", a.person)

        val b = (AnchorParser.parse("remember this as my uncle's shop") as AnchorParser.Result.Found).anchor
        assertTrue(b.here)
        assertEquals("your uncle", b.person)
    }

    @Test
    fun visualOnlyAnchorsAreRefusedAndColoursDropped() {
        assertTrue(AnchorParser.parse("the red sign means turn left") is AnchorParser.Result.VisualOnly)
        val door = (AnchorParser.parse("the blue door is where I wait for the van") as AnchorParser.Result.Found).anchor
        assertEquals("door", door.subject)
    }

    @Test
    fun ordinarySentencesAreNotAnchors() {
        assertNull(AnchorParser.parse("take me to Ravi's tea shop"))
        assertNull(AnchorParser.parse("yes"))
        assertNull(AnchorParser.parse("remember this"))
        assertNull(AnchorParser.parse("how far is it"))
        assertNull(AnchorParser.parse("save this route as Monday errands"))
        assertNull(AnchorParser.parse("save this as Monday errands"))
        assertTrue(AnchorParser.parse("save this place as Ravi's tea shop") is AnchorParser.Result.Found)
        assertNull(AnchorParser.parse("remember this trip as Monday errands"))
        // At the booking question a destination with "where I" stays a destination…
        assertNull(AnchorParser.parse("the mall where I bought shoes", requireLead = true))
        // …unless the rider says "remember".
        assertNotNull(AnchorParser.parse("remember the mall where I bought shoes", requireLead = true))
    }

    // ---- commands -------------------------------------------------------------------------------

    @Test
    fun commandsAreRecognised() {
        assertEquals(Command.Around("clinic"), MindMapCommands.parse("What's around the clinic?"))
        assertEquals(Command.Around(""), MindMapCommands.parse("what's around here"))
        assertEquals(Command.About("ravi's shop"), MindMapCommands.parse("what do I know about Ravi's shop"))
        assertEquals(Command.Nearby, MindMapCommands.parse("what's famous around here"))
        assertEquals(Command.Nearby, MindMapCommands.parse("any good places nearby"))
        assertEquals(Command.Nearby, MindMapCommands.parse("suggest some places"))
        assertEquals(Command.Summary, MindMapCommands.parse("read my mind map"))
        assertEquals(Command.Forget("ravi's shop"), MindMapCommands.parse("forget the note about Ravi's shop"))
        assertEquals(Command.ForgetAll, MindMapCommands.parse("forget my mind map"))
        assertEquals(Command.Suggestions(false), MindMapCommands.parse("stop suggesting places"))
        assertTrue(MindMapCommands.parse("Ravi's tea shop where I stop on Fridays") is Command.Remember)
        assertTrue(MindMapCommands.parse("the red sign means turn left") is Command.VisualOnly)
        assertNull(MindMapCommands.parse("take me to the clinic"))
        assertNull(MindMapCommands.parse("cancel"))
        // The booking dialogue's own place list keeps its words.
        assertNull(MindMapCommands.parse("what places do you know"))
    }

    @Test
    fun walkCommandsStillWinForWalks() {
        // "remember my walk" records a walk; the mind map must not take it.
        assertTrue(WalkCommands.parse("remember my walk to the clinic") is WalkCommands.Command.Record)
    }

    // ---- remembering and reading back --------------------------------------------------------------

    @Test
    fun anAnchorAboutAKnownLandmarkIsJoinedToTheOneWhereTheRiderIs() {
        val m = built()
        val a = (AnchorParser.parse("the bakery smell is where I buy bread for Amma") as AnchorParser.Result.Found).anchor
        // Said at the clinic: the clinic walk's bakery, not the mall's.
        val r = MindMapAgent.remember(m, a, here = m.node("place:p-clinic"), lat = null, lng = null, now = now, newId = "x1")
        assertEquals("lm:l1", r.edges.single { it.kind == EdgeKind.MEANS }.to)
        assertTrue(r.spoken, r.spoken.startsWith("Got it."))
        assertTrue(r.spoken, r.spoken.contains("you buy bread for amma"))
        assertTrue(r.spoken, r.spoken.contains("on your walk to clinic door"))
    }

    @Test
    fun anAmbiguousLandmarkIsNotGuessed() {
        val m = built()
        val a = (AnchorParser.parse("the bakery smell is where I buy bread for Amma") as AnchorParser.Result.Found).anchor
        val r = MindMapAgent.remember(m, a, here = null, lat = null, lng = null, now = now, newId = "x1")
        assertTrue(r.edges.none { it.kind == EdgeKind.MEANS })
    }

    @Test
    fun anAnchorPointingAtAKnownPlaceIsLinkedNearIt() {
        val m = built()
        val a = (AnchorParser.parse("the temple bell means I'm near the bus stop") as AnchorParser.Result.Found).anchor
        val r = MindMapAgent.remember(m, a, here = null, lat = null, lng = null, now = now, newId = "x2")
        assertTrue(r.edges.any { it.kind == EdgeKind.NEAR && it.to == "place:p-bus" })
    }

    @Test
    fun sayingItAgainUpdatesTheSameNote() {
        var authored = MindMap()
        val first = MindMapAgent.remember(built(authored), (AnchorParser.parse("Ravi's tea shop, where I stop on Fridays") as AnchorParser.Result.Found).anchor,
            here = built().node("place:p-clinic"), lat = null, lng = null, now = now, newId = "a1")
        authored = authored.upsert(first.nodes, first.edges)
        val again = MindMapAgent.remember(built(authored), (AnchorParser.parse("Ravi's tea shop is where I meet Kumar") as AnchorParser.Result.Found).anchor,
            here = null, lat = null, lng = null, now = now + day, newId = "a2")
        assertEquals("a:a1", again.anchorId)
        assertEquals("you stop on fridays, and you meet kumar", again.nodes.single().habit)
        assertTrue(again.spoken.startsWith("Updated your note."))
    }

    @Test
    fun aroundTheClinicIsASpokenMentalMap() {
        var authored = MindMap()
        val r = MindMapAgent.remember(built(), (AnchorParser.parse("the bakery smell is where I buy bread for Amma") as AnchorParser.Result.Found).anchor,
            here = built().node("place:p-clinic"), lat = null, lng = null, now = now, newId = "a1")
        authored = authored.upsert(r.nodes, r.edges)
        val m = built(authored)
        val said = MindMapAgent.around(m, m.node("place:p-clinic")!!, now)
        assertTrue(said, said.startsWith("Around KG Clinic."))
        assertTrue(said, said.contains("you have a walk to clinic door"))
        assertTrue(said, said.contains("the bakery smell on your left"))
        // the bus stop is ~200 m north of the clinic
        assertTrue(said, said.contains("Town Hall bus stop, about 200 metres north"))
        assertFalse(said, said.contains("Brookefields")) // ~1 km: too far for "nearby"
        assertFalse(said.lowercase().contains("clear"))
    }

    @Test
    fun aroundSomewhereUnknownSaysHowToTeachIt() {
        val m = built()
        val said = MindMapAgent.around(m, m.node("place:p-mall")!!.copy(id = "place:new", lat = null, lng = null), now)
        assertTrue(said, said.startsWith("I don't know much around"))
    }

    @Test
    fun oldNotesAreSaidAsOld() {
        val r = MindMapAgent.remember(built(), (AnchorParser.parse("Ravi's tea shop, where I stop on Fridays") as AnchorParser.Result.Found).anchor,
            here = null, lat = null, lng = null, now = now - 120 * day, newId = "a1")
        val text = MindMapAgent.anchor(r.nodes.single(), now)
        assertTrue(text, text.contains("check it still holds"))
    }

    @Test
    fun findPicksThePlaceAndRefusesATie() {
        val m = built()
        assertEquals("place:p-clinic", MindMapAgent.find(m, "kg clinic")?.id)
        assertEquals("walk:w-clinic", MindMapAgent.find(m, "clinic door")?.id)
        // "bakery smell" is on three walks: two are one memory, the mall one is different → tie
        assertNull(MindMapAgent.find(m, "bakery smell", setOf(NodeKind.LANDMARK)))
    }

    @Test
    fun walkNarrationCarriesTheRidersOwnWords() {
        val r = MindMapAgent.remember(built(), (AnchorParser.parse("the bakery smell is where I buy bread for Amma") as AnchorParser.Result.Found).anchor,
            here = built().node("place:p-clinic"), lat = null, lng = null, now = now, newId = "a1")
        val m = built(MindMap().upsert(r.nodes, r.edges))
        val notes = MindMapAgent.notesFor(m, "w-clinic")
        assertEquals("where you buy bread for amma", notes["l1"])
        val said = WalkNarrator.narrate(clinicWalk, DetailLevel.BRIEF, now, notes)
        assertTrue(said, said.contains("left after the bakery smell, where you buy bread for amma"))
        // The bus-stop bakery is the same one: its walk says so.
        assertEquals("the one on your walk to clinic door", MindMapAgent.notesFor(m, "w-bus")["b1"])
    }

    @Test
    fun summaryCountsWhatIsKnown() {
        val s = MindMapAgent.summary(built())
        assertTrue(s, s.startsWith("Your mind map has 3 places, 3 walks, 6 landmarks"))
        assertTrue(s, s.contains("1 landmark is shared between walks"))
        assertEquals("Your mind map is empty so far. It grows as you ride, walk, and tell me what places mean to you.", MindMapAgent.summary(MindMap()))
    }

    @Test
    fun authoredContentSurvivesJson() {
        val r = MindMapAgent.remember(built(), (AnchorParser.parse("the temple bell means I'm near the bus stop") as AnchorParser.Result.Found).anchor,
            here = null, lat = 11.0, lng = 76.9, now = now, newId = "a1")
        val m = MindMap().upsert(r.nodes, r.edges)
        val back = MindMapJson.map(MindMapJson.json(m).toString())
        assertEquals(m, back)
        assertEquals(MindMap(), MindMapJson.map("not json"))
    }

    @Test
    fun aSpotNamedOnTheSpotCanBeBookedByName() {
        // "Remember this as the place I meet Priya", away from any known place: kept at the phone's position.
        val a = (AnchorParser.parse("remember this as the place I meet Priya") as AnchorParser.Result.Found).anchor
        val r = MindMapAgent.remember(built(), a, here = null, lat = 11.03, lng = 76.97, now = now, newId = "p1")
        val spot = r.nodes.single()
        assertEquals("where you meet Priya", spot.label)
        assertEquals(11.03, spot.lat!!, 1e-9)
        assertEquals("where I meet Priya", AnchorParser.firstPerson(spot.label))
        val tea = MindMapAgent.remember(built(), (AnchorParser.parse("remember this as Ravi's tea shop") as AnchorParser.Result.Found).anchor,
            here = null, lat = 11.04, lng = 76.98, now = now, newId = "p2").nodes.single()
        val m = built(MindMap().upsert(listOf(spot, tea)))

        // "take me to …" finds them, by the rider's own words.
        assertEquals(spot.id, MindMapAgent.bookable(m, "where I meet Priya")?.id)
        assertEquals(tea.id, MindMapAgent.bookable(m, "ravi's tea shop")?.id)
        assertEquals(tea.id, MindMapAgent.bookable(m, "Ravis tea shop")?.id)
        // Never a loose match, a bare kind of place, a place in ride memory, or a sound.
        assertNull(MindMapAgent.bookable(m, "tea shop"))
        assertNull(MindMapAgent.bookable(m, "kg clinic"))
        assertNull(MindMapAgent.bookable(m, "shop"))
        val bell = MindMapAgent.remember(m, (AnchorParser.parse("remember this as the temple bell") as AnchorParser.Result.Found).anchor,
            here = null, lat = 11.05, lng = 76.99, now = now, newId = "p3").nodes.single()
        assertNull(MindMapAgent.bookable(built(MindMap().upsert(listOf(bell))), "temple bell"))
    }

    @Test
    fun aNoteNotAboutHereTakesNoPosition() {
        // "Ravi's tea shop, where I stop on Fridays" said at home must not put the shop at home:
        // the caller passes a position only for "this place", and nothing else locates it.
        val a = (AnchorParser.parse("Ravi's tea shop, where I stop on Fridays") as AnchorParser.Result.Found).anchor
        val r = MindMapAgent.remember(built(), a, here = null, lat = null, lng = null, now = now, newId = "q1")
        assertFalse(r.nodes.single().located)
        assertNull(MindMapAgent.bookable(built(MindMap().upsert(r.nodes)), "ravi's tea shop"))
    }

    @Test
    fun aroundAPointAwayFromKnownPlaces() {
        val m = built()
        // 100 m north of the bus stop
        val said = MindMapAgent.nearPoint(m, 11.0195, 76.9558, "Town Hall", now)!!
        assertTrue(said, said.startsWith("You're in Town Hall. Places you know nearby: Town Hall bus stop, about 100 metres south"))
        assertTrue(said, said.contains("KG Clinic, about 300 metres south"))
        // Nowhere known and no area name: nothing to say (the caller offers well-known places).
        assertNull(MindMapAgent.nearPoint(m, 12.5, 78.0, null, now))
        assertEquals("You're in Ooty.", MindMapAgent.nearPoint(m, 12.5, 78.0, "Ooty", now))
    }

    @Test
    fun habitsThatStartWithWhere() {
        val a = (AnchorParser.parse("remember this as where I wait for my uncle") as AnchorParser.Result.Found).anchor
        assertTrue(a.here)
        assertEquals("", a.subject)
        assertEquals("you wait for your uncle", a.habit)
        // "says" is ordinary speech, not a meaning.
        assertNull(AnchorParser.parse("my friend says the bus is late"))
    }

    @Test
    fun forgettingAWalkDropsTheNotesEdgesNotTheNote() {
        val r = MindMapAgent.remember(built(), (AnchorParser.parse("the glass door is where I wait for the van") as AnchorParser.Result.Found).anchor,
            here = null, lat = null, lng = null, now = now, newId = "a1")
        val authored = MindMap().upsert(r.nodes, r.edges)
        val withoutWalk = MindMapBuilder.build(authored, listOf(busWalk), listOf(busStop))
        assertNotNull(withoutWalk.node("a:a1"))
        assertTrue(withoutWalk.edges.none { it.from == "a:a1" && it.kind == EdgeKind.MEANS })
    }
}
