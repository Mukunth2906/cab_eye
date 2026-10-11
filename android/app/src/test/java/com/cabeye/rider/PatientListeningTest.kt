package com.cabeye.rider

import com.cabeye.rider.auth.PhoneEntry
import com.cabeye.rider.auth.SpokenNumbers
import com.cabeye.rider.auth.sixDigitCodeIn
import com.cabeye.rider.speech.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An answer said with pauses is one answer, and a number that comes through short is finished,
 * not restarted.
 */
class PatientListeningTest {

    // ---------------------------------------------------------------------------------
    //  Utterance: joining the recogniser's segments
    // ---------------------------------------------------------------------------------

    @Test
    fun `a number said in two breaths joins into one whole number`() {
        val u = Utterance()
        u.addSegment(listOf("98765", "9876 5"))
        u.addSegment(listOf("43210", "4321 0"))
        assertEquals("98765 43210", u.alternatives().first())
        assertEquals("9876543210", SpokenNumbers.mobileNumber(u.alternatives().first()))
        assertEquals(2, u.segmentCount)
    }

    @Test
    fun `lower guesses of each segment are combined, best first`() {
        val u = Utterance()
        u.addSegment(listOf("98765", "98767"))
        u.addSegment(listOf("for 3210", "43210"))
        val alts = u.alternatives()
        assertEquals("98765 for 3210", alts.first())
        assertTrue(alts.contains("98765 43210"))
        // The best guess for any one segment always comes before a lower guess for both.
        assertTrue(alts.indexOf("98765 43210") < alts.indexOf("98767 43210"))
    }

    @Test
    fun `any complete guess is found, not only the first`() {
        val u = Utterance()
        u.addSegment(listOf("nine eight seven six five"))
        u.addSegment(listOf("for three two one oh", "four three two one zero"))
        assertTrue(u.anyComplete { SpokenNumbers.mobileNumber(it) != null })
    }

    @Test
    fun `words shown as a partial are kept when the recogniser finalises nothing`() {
        val u = Utterance()
        u.addSegment(listOf("take me to"))
        u.onPartial("RS Puram")
        assertEquals("take me to RS Puram", u.bestText())
        u.commitPartial()
        assertEquals("take me to RS Puram", u.alternatives().first())
    }

    @Test
    fun `blank guesses are not a segment`() {
        val u = Utterance()
        u.addSegment(listOf("", "  "))
        assertTrue(u.isEmpty)
        assertTrue(u.alternatives().isEmpty())
    }

    @Test
    fun `many segments stay bounded`() {
        val u = Utterance()
        repeat(12) { u.addSegment(listOf("a$it", "b$it", "c$it")) }
        val alts = u.alternatives()
        assertTrue(alts.size <= 8)
        assertEquals((0 until 12).joinToString(" ") { "a$it" }, alts.first())
    }

    // ---------------------------------------------------------------------------------
    //  PhoneEntry: a number across answers
    // ---------------------------------------------------------------------------------

    @Test
    fun `a whole number completes at once`() {
        assertEquals(PhoneEntry.Step.Complete("9876543210"), PhoneEntry.next("", "my number is 98765 43210"))
        assertEquals(PhoneEntry.Step.Complete("9876543210"), PhoneEntry.next("", "plus 91 98765 43210"))
    }

    @Test
    fun `a short number is kept, and the rest completes it`() {
        val first = PhoneEntry.next("", "nine eight seven six five four three")
        assertEquals(PhoneEntry.Step.Partial("9876543"), first)
        assertEquals(PhoneEntry.Step.Complete("9876543210"), PhoneEntry.next("9876543", "two one zero"))
    }

    @Test
    fun `a whole number said again replaces the digits kept so far`() {
        assertEquals(PhoneEntry.Step.Complete("9123456789"), PhoneEntry.next("98765", "9123456789"))
    }

    @Test
    fun `too many digits, or not a mobile number, start again`() {
        assertEquals(PhoneEntry.Step.TooLong, PhoneEntry.next("98765", "432101234"))
        assertEquals(PhoneEntry.Step.NotMobile, PhoneEntry.next("", "1234567890"))
        assertEquals(PhoneEntry.Step.Nothing, PhoneEntry.next("98765", "um hello"))
    }

    @Test
    fun `the guess that completes the number is chosen over a higher-ranked one`() {
        val picked = PhoneEntry.bestGuess("98765", listOf("4321", "43210", "4 3 2 1"))
        assertEquals("43210", picked)
        assertNull(PhoneEntry.bestGuess("", listOf("hello there")))
    }

    // ---------------------------------------------------------------------------------
    //  The code inside the text message
    // ---------------------------------------------------------------------------------

    @Test
    fun `the six digit code is read out of the message`() {
        assertEquals("482913", sixDigitCodeIn("Your Cab Eye code is 482913. It expires in 5 minutes."))
        assertEquals("000123", sixDigitCodeIn("000123 is your code"))
        assertNull(sixDigitCodeIn("Call 9876543210 for help"))
        assertNull(sixDigitCodeIn(null))
        assertFalse(sixDigitCodeIn("code 12345") != null)
    }
}
