package com.cabeye.rider

import com.cabeye.rider.dialogue.FeedbackParser
import com.cabeye.rider.dialogue.FeedbackParser.Category
import com.cabeye.rider.dialogue.NextJourneyChoice
import com.cabeye.rider.dialogue.SpokenTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Feedback, the next-journey question and spoken times. */
class PostRideTest {

    // --------------------------------------------------------------- feedback

    @Test
    fun ratingsFromNumbersAndWords() {
        assertEquals(4, FeedbackParser.rating("four"))
        assertEquals(5, FeedbackParser.rating("5 stars"))
        assertEquals(5, FeedbackParser.rating("five out of five"))
        assertEquals(4, FeedbackParser.rating("it was good"))
        assertEquals(1, FeedbackParser.rating("very bad"))
        assertNull(FeedbackParser.rating("skip"))
        assertNull(FeedbackParser.rating("eight"))
    }

    @Test
    fun loneMisheardNumbersAreRatings() {
        // Asked for 1 to 5, a lone "won" or "for" is the recogniser mishearing the number.
        assertEquals(1, FeedbackParser.rating("won"))
        assertEquals(2, FeedbackParser.rating("to"))
        assertEquals(3, FeedbackParser.rating("free"))
        assertEquals(4, FeedbackParser.rating("For."))
        // ...but only when it is the whole answer.
        assertEquals(FeedbackParser.Intent.REPORT, FeedbackParser.intentOf("I want to report a problem"))
        assertNull(FeedbackParser.rating("he drove too fast"))
    }

    @Test
    fun skipAndReport() {
        assertEquals(FeedbackParser.Intent.SKIP, FeedbackParser.intentOf("skip"))
        assertEquals(FeedbackParser.Intent.SKIP, FeedbackParser.intentOf("no"))
        assertEquals(FeedbackParser.Intent.REPORT, FeedbackParser.intentOf("I want to report a problem"))
        assertEquals(FeedbackParser.Intent.RATING, FeedbackParser.intentOf("three"))
    }

    @Test
    fun safetyAlwaysWins() {
        assertEquals(Category.SAFETY, FeedbackParser.categorise("he was driving too fast and I felt unsafe"))
        assertEquals(Category.SAFETY, FeedbackParser.categorise("he asked for extra money and touched my arm"))
        assertEquals(Category.PAYMENT, FeedbackParser.categorise("he asked for extra money"))
        assertEquals(Category.PICKUP, FeedbackParser.categorise("he couldn't find me at the gate"))
        assertEquals(Category.ROUTE, FeedbackParser.categorise("he took a long way"))
        assertEquals(Category.DRIVER, FeedbackParser.categorise("the driver was rude"))
        assertEquals(Category.OTHER, FeedbackParser.categorise("the music was loud"))
    }

    // --------------------------------------------------------------- next journey

    @Test
    fun nextJourneyChoices() {
        assertEquals(NextJourneyChoice.Kind.NOW, NextJourneyChoice.classify("book another ride"))
        assertEquals(NextJourneyChoice.Kind.LATER, NextJourneyChoice.classify("schedule one for later"))
        assertEquals(NextJourneyChoice.Kind.LATER, NextJourneyChoice.classify("book one for tomorrow"))
        assertEquals(NextJourneyChoice.Kind.DONE, NextJourneyChoice.classify("no I'm done"))
        assertEquals(NextJourneyChoice.Kind.NONE, NextJourneyChoice.classify("hmm"))
    }

    // --------------------------------------------------------------- spoken time

    private val zone = ZoneId.of("Asia/Kolkata")
    /** Wednesday 23 Sept 2026, 4:20 PM. */
    private val now = ZonedDateTime.of(2026, 9, 23, 16, 20, 0, 0, zone)

    private fun at(text: String) = SpokenTime.parse(text, now)?.let { "${it.toLocalDate()} ${it.toLocalTime()}" }

    @Test
    fun relativeTimes() {
        assertEquals("2026-09-23 18:20", at("in two hours"))
        assertEquals("2026-09-23 16:50", at("in half an hour"))
        assertEquals("2026-09-23 16:35", at("in 15 minutes"))
    }

    @Test
    fun tomorrowMorning() {
        assertEquals("2026-09-24 08:30", at("tomorrow at 8 30"))
        assertEquals("2026-09-24 08:30", at("tomorrow at eight thirty"))
        assertEquals("2026-09-24 09:00", at("tomorrow morning 9"))
    }

    @Test
    fun todayPicksTheNextFutureTime() {
        assertEquals("2026-09-23 17:00", at("at 5"))           // 5 AM has passed → 5 PM
        assertEquals("2026-09-23 18:30", at("6 30 pm"))
        assertEquals("2026-09-23 21:00", at("tonight at 9"))
        assertEquals("2026-09-24 12:00", at("tomorrow noon"))
    }

    @Test
    fun weekdayNames() {
        assertEquals("2026-09-28 09:00", at("on Monday at 9 am"))
    }

    @Test
    fun rejectsNonsenseAndThePast() {
        assertNull(at("whenever"))
        assertNull(at("today at 10 am"))    // already past
        assertNull(at("at 45"))
    }

    @Test
    fun readsBackNaturally() {
        val t = SpokenTime.parse("tomorrow at 8 30", now)!!
        assertEquals("tomorrow at 8 30 AM", SpokenTime.spoken(t, now))
    }
}

class PostRideCommandsTest {

    private fun intent(text: String, rideActive: Boolean = false) =
        com.cabeye.rider.intent.Classifier.classify(text, rideActive).intent

    @Test
    fun cancelScheduledIsNotARideCancel() {
        org.junit.Assert.assertTrue(intent("cancel my scheduled ride") is com.cabeye.rider.intent.RiderIntent.CancelScheduled)
        org.junit.Assert.assertTrue(intent("cancel", rideActive = true) is com.cabeye.rider.intent.RiderIntent.Cancel)
    }

    @Test
    fun schedulingAndFeedbackCommands() {
        org.junit.Assert.assertTrue(intent("schedule a ride") is com.cabeye.rider.intent.RiderIntent.ScheduleRide)
        org.junit.Assert.assertTrue(intent("book a ride for tomorrow") is com.cabeye.rider.intent.RiderIntent.ScheduleRide)
        org.junit.Assert.assertTrue(intent("my scheduled rides") is com.cabeye.rider.intent.RiderIntent.ListScheduled)
        org.junit.Assert.assertTrue(intent("I want to give feedback") is com.cabeye.rider.intent.RiderIntent.GiveFeedback)
    }

    @Test
    fun ordinaryBookingsAreUntouched() {
        org.junit.Assert.assertTrue(intent("take me to Gandhipuram") is com.cabeye.rider.intent.RiderIntent.Book)
    }

    // ---- The demo feedback, kept to what the rider meant ------------------------------

    @Test
    fun demoFeedbackIsCleanedAndFiledAsSafety() {
        val said = "one there was no OTP verification done. need of sending now send this as feedback"
        val report = FeedbackParser.cleanReport(said, rating = 1)
        assertEquals("there was no OTP verification done", report)
        assertEquals(Category.SAFETY, FeedbackParser.categorise(report))
    }

    @Test
    fun sendItIsAnInstructionNotPartOfTheReport() {
        org.junit.Assert.assertTrue(FeedbackParser.isSendCommand("send this as feedback"))
        org.junit.Assert.assertTrue(FeedbackParser.isSendCommand("okay send it"))
        assertEquals("", FeedbackParser.cleanReport("send this as feedback"))
        assertEquals("", FeedbackParser.cleanReport("please send it now"))
        assertEquals("he was on the phone", FeedbackParser.cleanReport("he was on the phone, that's all"))
    }

    @Test
    fun wordsInsideTheComplaintAreKept() {
        assertEquals("he asked me to send money on gpay", FeedbackParser.cleanReport("he asked me to send money on gpay"))
        assertEquals("the verification was done", FeedbackParser.cleanReport("the verification was done"))
    }

    @Test
    fun leadingRatingIsOnlyDroppedWhenItIsTheRating() {
        assertEquals("the driver was rude", FeedbackParser.cleanReport("two, the driver was rude", rating = 2))
        assertEquals("the driver was rude", FeedbackParser.cleanReport("2 out of 5 the driver was rude", rating = 2))
        assertEquals("one wheel was flat", FeedbackParser.cleanReport("one wheel was flat", rating = 3))
    }

    @Test
    fun wrongCarAndCodeProblemsAreSafety() {
        assertEquals(Category.SAFETY, FeedbackParser.categorise("it was a different car"))
        assertEquals(Category.SAFETY, FeedbackParser.categorise("the code was never checked"))
        assertEquals(Category.SAFETY, FeedbackParser.categorise("wrong driver came"))
    }

    @Test
    fun yesAndMore() {
        assertEquals("he was also on his phone", FeedbackParser.afterYes("yes, and he was also on his phone"))
        assertEquals("", FeedbackParser.afterYes("yes send it"))
    }
}
