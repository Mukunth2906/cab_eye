package com.cabeye.rider

import com.cabeye.rider.mindmap.MindMap
import com.cabeye.rider.mindmap.MindMapInterpreter
import com.cabeye.rider.mindmap.MindMapInterpreter.Reading
import com.cabeye.rider.mindmap.Node
import com.cabeye.rider.mindmap.NodeKind
import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's reading of a sentence is checked on the phone by the same rules as the rider's
 * own words: a sign or a colour is refused, "the way is clear" is dropped, junk is nothing —
 * and only the sentence and a few known names are ever sent.
 */
class MindMapInterpreterTest {

    @Test
    fun aNoteComesBackAsAnAnchor() {
        val r = MindMapInterpreter.read(
            """{"intent":"REMEMBER","subject":"Temple Bell","person":"Amma","habit":"","meaning":"you're near amma's house",
               "relatesTo":"amma's house","sense":"SOUND","side":"LEFT","confidence":0.9}""",
            "that bell, it tells me amma's place is close"
        ) as Reading.Note
        assertEquals("temple bell", r.anchor.subject)
        assertEquals(LandmarkKind.SOUND, r.anchor.sense)
        assertEquals(Side.LEFT, r.anchor.side)
        assertEquals("you're near amma's house", r.anchor.meaning)
        assertEquals("that bell, it tells me amma's place is close", r.anchor.words)
        assertEquals(0.9, r.confidence, 1e-9)
    }

    @Test
    fun theSameRefusalsAsTheRidersOwnWords() {
        assertEquals(Reading.Nothing, MindMapInterpreter.read("""{"intent":"REMEMBER","subject":"the red sign","confidence":1}""", "x"))
        val door = MindMapInterpreter.read(
            """{"intent":"REMEMBER","subject":"blue door","meaning":"the path is clear","habit":"you wait here","confidence":1}""", "x"
        ) as Reading.Note
        assertEquals("door", door.anchor.subject)
        assertEquals("", door.anchor.meaning)
        assertEquals(Reading.Nothing, MindMapInterpreter.read("""{"intent":"BOOK","subject":"home","confidence":1}""", "x"))
        assertEquals(Reading.Nothing, MindMapInterpreter.read("not json", "x"))
        assertEquals(Reading.Nothing, MindMapInterpreter.read("""{"intent":"REMEMBER","subject":"","confidence":1}""", "x"))
    }

    @Test
    fun aRideCue() {
        val c = MindMapInterpreter.read("""{"intent":"RIDE_CUE","subject":"the big climb","confidence":0.8}""", "x") as Reading.Cue
        assertEquals("big climb", c.words)
    }

    @Test
    fun onlyTheSentenceAndKnownNamesAreSent() {
        val here = Node("place:k", NodeKind.PLACE, "KG Clinic", 11.0, 76.9)
        val map = MindMap(listOf(here, Node("a:1", NodeKind.ANCHOR, "Ravi's tea shop")))
        val body = MindMapInterpreter.request("that bell tells me amma's place is close", map, here)
        assertEquals(setOf("utterance", "known"), body.keys().asSequence().toSet())
        val known = (0 until body.getJSONArray("known").length()).map { body.getJSONArray("known").getString(it) }
        assertEquals(listOf("KG Clinic", "Ravi's tea shop"), known)
        assertTrue(!body.toString().contains("11.0"))
    }
}
