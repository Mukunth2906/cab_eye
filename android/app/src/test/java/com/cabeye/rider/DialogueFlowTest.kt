package com.cabeye.rider

import com.cabeye.rider.dialogue.ClarifyAnswer
import com.cabeye.rider.dialogue.ClarifyAnswer.Kind
import com.cabeye.rider.intent.Classifier
import com.cabeye.rider.intent.RiderIntent
import com.cabeye.rider.intent.UtteranceClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "where does the conversation go next" rules: every answer to an A-or-B question lands
 * somewhere, and "go back" always means the previous step, never a booking.
 */
class DialogueFlowTest {

    private fun clarify(text: String) = ClarifyAnswer.interpret(text, "Anna Nagar", "Anna Salai")

    @Test
    fun namingAnOptionChoosesIt() {
        assertEquals(Kind.B, clarify("anna salai"))
        assertEquals(Kind.B, clarify("no, anna salai"))
        assertEquals(Kind.A, clarify("the nagar one"))
    }

    @Test
    fun ordinalsChoose() {
        assertEquals(Kind.A, clarify("the first one"))
        assertEquals(Kind.B, clarify("second"))
    }

    @Test
    fun noneIsNotOne() {
        // The old matcher read "none" as "one" and booked option A.
        assertEquals(Kind.NEITHER, clarify("none of them"))
        assertEquals(Kind.NEITHER, clarify("neither"))
        assertEquals(Kind.NEITHER, clarify("no"))
        assertEquals(Kind.NEITHER, clarify("go back"))
    }

    @Test
    fun bareYesAsksWhichOne() {
        assertEquals(Kind.WHICH, clarify("yes"))
    }

    @Test
    fun somethingElseIsUnclear() {
        assertEquals(Kind.UNCLEAR, clarify("gandhipuram"))
    }

    @Test
    fun goBackIsACommandNotABooking() {
        for (said in listOf("go back", "Go back.", "start over", "wrong place", "change destination")) {
            val c = Classifier.classify(said, rideActive = false)
            assertEquals(said, UtteranceClass.COMMAND, c.kind)
            assertTrue(said, c.intent is RiderIntent.Back)
        }
    }

    @Test
    fun goBackHomeIsStillABooking() {
        val c = Classifier.classify("go back home", rideActive = false)
        assertTrue("'go back home' must not be read as the back command", c.intent !is RiderIntent.Back)
    }

    @Test
    fun neitherIsANo() {
        assertTrue(Classifier.classify("neither", rideActive = false).intent is RiderIntent.No)
    }
}

class MemoryCommandsTest {

    @Test
    fun myPlacesAndForgetAreCommands() {
        assertTrue(Classifier.classify("what are my places", false).intent is RiderIntent.MyPlaces)
        assertTrue(Classifier.classify("where do I usually go", false).intent is RiderIntent.MyPlaces)
        assertTrue(Classifier.classify("forget my history", false).intent is RiderIntent.ForgetHistory)
        assertTrue(Classifier.classify("clear my places", false).intent is RiderIntent.ForgetHistory)
    }

    @Test
    fun forgetItIsStillCancel() {
        assertTrue(Classifier.classify("forget it", false).intent is RiderIntent.Cancel)
    }

    @Test
    fun listPlacesStillListsTheCity() {
        assertTrue(Classifier.classify("list places", false).intent is RiderIntent.ListPlaces)
    }
}

class CancelWordTest {
    private fun isCancel(text: String) =
        com.cabeye.rider.intent.Classifier.classify(text, rideActive = false).intent is RiderIntent.Cancel

    @Test
    fun theCommandStillCancels() {
        for (said in listOf("cancel", "stop", "please stop", "never mind", "forget it", "cancel it", "stop the booking")) {
            assertTrue(said, isCancel(said))
        }
    }

    @Test
    fun aBusStopIsAPlaceNotACancel() {
        assertTrue(!isCancel("take me to Ukkadam bus stop"))
        assertTrue(!isCancel("Perur bus stop"))
        assertTrue(!isCancel("he didn't stop at the gate"))
    }
}
