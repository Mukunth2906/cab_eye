package com.cabeye.rider

import com.cabeye.rider.auth.SignInCommands
import com.cabeye.rider.auth.SignInCommands.Kind
import com.cabeye.rider.auth.SpokenNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dictated phone numbers and one-time codes.
 *
 * Each test is one shape a recogniser has actually been seen to return for the same spoken
 * number. If any of these regresses, a blind rider cannot sign in without sighted help.
 */
class AuthLogicTest {

    @Test
    fun plainDigits() {
        assertEquals("9876543210", SpokenNumbers.mobileNumber("98765 43210"))
    }

    @Test
    fun countryCodeAndTrunkPrefixAreDropped() {
        assertEquals("9876543210", SpokenNumbers.mobileNumber("+91 98765 43210"))
        assertEquals("9876543210", SpokenNumbers.mobileNumber("0 98765 43210"))
    }

    @Test
    fun wordByWord() {
        assertEquals(
            "9876543210",
            SpokenNumbers.mobileNumber("nine eight seven six five four three two one zero")
        )
    }

    @Test
    fun doubleAndTriple() {
        assertEquals("9988877766", SpokenNumbers.mobileNumber("double nine triple eight seven seven seven six six"))
    }

    @Test
    fun tensAreTwoDigits() {
        assertEquals("9876543210", SpokenNumbers.mobileNumber("ninety eight seventy six fifty four thirty two ten"))
        assertEquals("90", SpokenNumbers.digits("ninety"))
    }

    @Test
    fun mixedWordsAndDigits() {
        assertEquals("9876543210", SpokenNumbers.mobileNumber("my number is nine eight 765 four three two one oh"))
    }

    @Test
    fun ambiguousWordsCountOnlyNextToNumbers() {
        // "for" beside digits is a four; in a sentence it is a preposition.
        assertEquals("94", SpokenNumbers.digits("nine for"))
        assertEquals("", SpokenNumbers.digits("this is for you"))
        // "twenty to" is not twenty-two.
        assertEquals("20", SpokenNumbers.digits("twenty to"))
    }

    @Test
    fun rejectsWrongLengthsAndLandlines() {
        assertNull(SpokenNumbers.mobileNumber("nine eight seven"))
        assertNull(SpokenNumbers.mobileNumber("12345 67890"))
    }

    @Test
    fun otpCode() {
        assertEquals("482913", SpokenNumbers.otp("four eight two nine one three"))
        assertEquals("482913", SpokenNumbers.otp("482 913"))
        assertNull(SpokenNumbers.otp("four eight two"))
    }

    @Test
    fun speakableGroupsForTheEar() {
        assertEquals("9 8 7 6 5. 4 3 2 1 0.", SpokenNumbers.speakable("9876543210"))
    }
}

class SignInCommandsTest {

    private fun k(text: String) = SignInCommands.classify(text)

    @Test
    fun yesAndNo() {
        assertEquals(Kind.YES, k("yes that's right"))
        assertEquals(Kind.NO, k("no"))
    }

    @Test
    fun skipAlwaysWins() {
        assertEquals(Kind.SKIP, k("skip"))
        assertEquals(Kind.SKIP, k("no, continue without signing in"))
    }

    @Test
    fun resendBeatsRepeat() {
        assertEquals(Kind.RESEND, k("send it again"))
        assertEquals(Kind.RESEND, k("I didn't get a code"))
        assertEquals(Kind.REPEAT, k("say that again"))
    }

    @Test
    fun backToChangeNumber() {
        assertEquals(Kind.BACK, k("no, wrong number"))
        assertEquals(Kind.BACK, k("go back"))
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("Harshini Sree", SignInCommands.name("my name is harshini sree"))
        assertEquals("Asha", SignInCommands.name("Asha"))
        assertNull(SignInCommands.name("i would like to go to the station please"))
    }
}
