package com.cabeye.rider

import com.cabeye.rider.dialogue.CameraConsent
import com.cabeye.rider.dialogue.CameraConsent.Answer
import com.cabeye.rider.intent.Classifier
import com.cabeye.rider.intent.RiderIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera question ("can your driver see your camera?") and "stop camera". */
class CameraConsentTest {

    @Test
    fun yes() {
        for (said in listOf("yes", "Yeah sure", "okay share it", "go ahead", "haan", "yes please do")) {
            assertEquals(said, Answer.YES, CameraConsent.answer(said))
        }
    }

    @Test
    fun no() {
        for (said in listOf("no", "No thanks", "don't", "not now", "nahi", "never", "keep it off")) {
            assertEquals(said, Answer.NO, CameraConsent.answer(said))
        }
    }

    @Test
    fun noWinsOverYes() {
        // A camera switched on by a muddled answer is worse than one left off.
        assertEquals(Answer.NO, CameraConsent.answer("yes no no"))
        assertEquals(Answer.NO, CameraConsent.answer("okay don't"))
    }

    @Test
    fun always() {
        assertEquals(Answer.ALWAYS, CameraConsent.answer("always"))
        assertEquals(Answer.ALWAYS, CameraConsent.answer("yes always"))
        assertEquals(Answer.ALWAYS, CameraConsent.answer("yes and don't ask me again"))
        // "Always ask me" is not permission to stop asking.
        assertNull(CameraConsent.answer("always ask me"))
    }

    @Test
    fun theDriverSayingTheCodeIsNotAnAnswer() {
        // At the kerb the same listen hears the boarding code; it must fall through to the code.
        assertNull(CameraConsent.answer("four seven two"))
        assertNull(CameraConsent.answer("4 7 2"))
        assertNull(CameraConsent.answer(""))
    }

    @Test
    fun stopCamera() {
        for (said in listOf("stop camera", "Stop the camera", "turn off the camera", "camera off",
            "switch off my camera", "no more camera", "please stop camera")) {
            assertTrue(said, CameraConsent.isStopCamera(said))
        }
        for (said in listOf("stop", "cancel", "take me to ukkadam bus stop", "camera", "yes")) {
            assertFalse(said, CameraConsent.isStopCamera(said))
        }
    }

    @Test
    fun stopCameraWouldOtherwiseCancelTheRide() {
        // Why the camera check runs before the classifier: on its own, a leading "stop" is the
        // command that cancels the whole ride.
        assertTrue(Classifier.classify("stop camera", rideActive = true).intent is RiderIntent.Cancel)
        assertTrue(CameraConsent.isStopCamera("stop camera"))
    }
}
