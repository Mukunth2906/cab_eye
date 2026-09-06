package com.cabeye.rider

import com.cabeye.rider.audio.NarrationTier
import com.cabeye.rider.net.BackendUrl
import com.cabeye.rider.net.Reconciler
import com.cabeye.rider.net.RideEventType
import com.cabeye.rider.net.RidePhase
import com.cabeye.rider.net.RideSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the networking logic that has no Android dependency.
 *
 * These three pieces are tested here rather than on a device for one reason: each of them
 * fails *invisibly*. A URL with a trailing newline produces a socket that never connects; a
 * reconciliation that announces the wrong thing produces an app that sounds fine and says
 * something false. Neither shows up as a crash, and reproducing either on a phone means
 * physically killing wifi at the right moment. Here they run in milliseconds.
 */
class NetworkLogicTest {

    // =================================================================================
    //  BackendUrl — the pasted-ngrok-URL problem
    // =================================================================================

    @Test
    fun `trailing slash is stripped`() {
        assertEquals(
            "https://abc123.ngrok-free.app",
            BackendUrl.normaliseBase("https://abc123.ngrok-free.app/")
        )
    }

    @Test
    fun `multiple trailing slashes are stripped`() {
        assertEquals(
            "https://abc123.ngrok-free.app",
            BackendUrl.normaliseBase("https://abc123.ngrok-free.app///")
        )
    }

    /** The realistic case: copied out of a terminal, so it arrives with whitespace attached. */
    @Test
    fun `surrounding whitespace and newlines are stripped`() {
        assertEquals(
            "https://abc123.ngrok-free.app",
            BackendUrl.normaliseBase("  https://abc123.ngrok-free.app/ \n")
        )
    }

    @Test
    fun `missing scheme defaults to https`() {
        assertEquals(
            "https://abc123.ngrok-free.app",
            BackendUrl.normaliseBase("abc123.ngrok-free.app")
        )
    }

    /** A socket URL pasted into the base field is accepted rather than refused. */
    @Test
    fun `wss scheme is converted back to https`() {
        assertEquals(
            "https://abc123.ngrok-free.app",
            BackendUrl.normaliseBase("wss://abc123.ngrok-free.app/")
        )
    }

    @Test
    fun `blank input yields null`() {
        assertNull(BackendUrl.normaliseBase("   "))
        assertNull(BackendUrl.normaliseBase(null))
    }

    @Test
    fun `local http address survives untouched`() {
        assertEquals("http://10.0.2.2:8080", BackendUrl.normaliseBase("http://10.0.2.2:8080/"))
    }

    // ---- Socket derivation ----------------------------------------------------------

    @Test
    fun `https derives wss`() {
        assertEquals(
            "wss://abc123.ngrok-free.app",
            BackendUrl.toSocketBase("https://abc123.ngrok-free.app")
        )
    }

    @Test
    fun `http derives ws`() {
        assertEquals("ws://10.0.2.2:8080", BackendUrl.toSocketBase("http://10.0.2.2:8080"))
    }

    /**
     * The acceptance test, end to end: paste an ngrok https URL with a trailing slash and
     * whitespace, and the socket URL comes out correct without anyone typing it.
     */
    @Test
    fun `pasted ngrok url produces a correct socket url`() {
        val base = BackendUrl.normaliseBase(" https://abc123.ngrok-free.app/ ")
        assertNotNull(base)

        val socket = BackendUrl.rideSocketUrl(
            normalisedBase = base!!,
            rideId = "ride-1001",
            userId = "user-abc",
            role = "RIDER",
            lastSeq = 7
        )

        assertEquals(
            "wss://abc123.ngrok-free.app/ws/ride?rideId=ride-1001&userId=user-abc&role=RIDER&lastSeq=7",
            socket
        )
    }

    @Test
    fun `health url is appended without doubling the slash`() {
        assertEquals(
            "https://abc123.ngrok-free.app/health",
            BackendUrl.healthUrl(BackendUrl.normaliseBase("https://abc123.ngrok-free.app/")!!)
        )
    }

    @Test
    fun `spoken host drops the scheme`() {
        assertEquals(
            "abc123.ngrok-free.app",
            BackendUrl.spokenHost("https://abc123.ngrok-free.app")
        )
    }

    // =================================================================================
    //  Reconciler — announce the delta, not the backlog
    // =================================================================================

    private fun snapshot(
        phase: RidePhase,
        driverName: String = "Karthik",
        vehicleModel: String = "Bajaj auto, yellow",
        etaMinutes: Int = 4,
        fareRupees: Int = 0
    ) = RideSnapshot(
        rideId = "ride-1001",
        phase = phase,
        destination = "Anna Nagar East",
        rideType = "AUTO",
        boardingCode = "4-7-2",
        driverName = driverName,
        vehicleModel = vehicleModel,
        vehiclePlate = "TN 37 BX 4412",
        driverPhone = "",
        etaMinutes = etaMinutes,
        distanceMeters = 40,
        bearingDeg = 45f,
        codeConfirmed = false,
        fareRupees = fareRupees,
        durationMinutes = 12,
        lastSeq = 9
    )

    /**
     * The headline acceptance test.
     *
     * The app last said "still finding a driver". While it was offline the driver was
     * assigned, drove over and arrived. On reconnect the rider must hear ONE sentence about
     * the car being here — not three sentences narrating how it got there.
     */
    @Test
    fun `driver arrived while offline announces arrival only`() {
        val result = Reconciler.reconcile(RidePhase.REQUESTED, snapshot(RidePhase.ARRIVED))

        assertNotNull(result.spoken)
        assertTrue(
            "Expected an arrival sentence, got: ${result.spoken}",
            result.spoken!!.startsWith("Your car has arrived")
        )
        // Tier 0: an arrival that queues behind another sentence has already lost its value.
        assertEquals(NarrationTier.INTERRUPT, result.tier)

        // Explicitly NOT a backlog. None of the intermediate phases may be mentioned.
        assertFalse(result.spoken.contains("on the way"))
        assertFalse(result.spoken.contains("is coming"))
    }

    @Test
    fun `nothing changed means nothing is spoken`() {
        val result = Reconciler.reconcile(RidePhase.ASSIGNED, snapshot(RidePhase.ASSIGNED))
        assertNull("A short blip with no state change must stay silent", result.spoken)
    }

    @Test
    fun `cancelled while offline interrupts`() {
        val result = Reconciler.reconcile(RidePhase.ENROUTE, snapshot(RidePhase.CANCELLED))
        assertEquals("Your ride was cancelled.", result.spoken)
        assertEquals(NarrationTier.INTERRUPT, result.tier)
    }

    @Test
    fun `assignment while offline names the driver and the eta`() {
        val result = Reconciler.reconcile(RidePhase.REQUESTED, snapshot(RidePhase.ASSIGNED))
        assertEquals("Karthik is coming. 4 minutes.", result.spoken)
        assertEquals(NarrationTier.QUEUED, result.tier)
    }

    @Test
    fun `one minute is spoken as words not as a numeral`() {
        val result = Reconciler.reconcile(
            RidePhase.REQUESTED, snapshot(RidePhase.ASSIGNED, etaMinutes = 1)
        )
        assertEquals("Karthik is coming. one minute.", result.spoken)
    }

    /**
     * The plate number is deliberately absent from the arrival sentence: it is a long string
     * of characters that costs seconds to speak and helps a blind rider not at all.
     */
    @Test
    fun `arrival does not read out the number plate`() {
        val result = Reconciler.reconcile(RidePhase.ENROUTE, snapshot(RidePhase.ARRIVED))
        assertFalse(result.spoken!!.contains("TN 37"))
        assertTrue(result.spoken.contains("Bajaj auto, yellow"))
    }

    @Test
    fun `approach is reconciled without a distance`() {
        val result = Reconciler.reconcile(RidePhase.ASSIGNED, snapshot(RidePhase.ENROUTE))
        // The approach phase is wordless by design; a reconnect is not a reason to abandon it.
        assertFalse(result.spoken!!.contains("metres"))
        assertFalse(result.spoken.contains("40"))
    }

    @Test
    fun `completion mentions the fare`() {
        val result = Reconciler.reconcile(
            RidePhase.IN_TRIP, snapshot(RidePhase.COMPLETED, fareRupees = 148)
        )
        assertTrue(result.spoken!!.contains("148"))
    }

    /** A ride nobody has been told about yet, still being searched for, needs no announcement. */
    @Test
    fun `requested with nothing previously spoken stays silent`() {
        assertNull(Reconciler.reconcile(null, snapshot(RidePhase.REQUESTED)).spoken)
    }

    // =================================================================================
    //  Event tiers — the policy, asserted rather than assumed
    // =================================================================================

    @Test
    fun `arrival and deviation are the only tier zero ride events`() {
        val interrupting = RideEventType.entries
            .filter { it.isRideLifecycle && it.tier == NarrationTier.INTERRUPT }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("DRIVER_ARRIVED", "ROUTE_DEVIATION", "RIDE_CANCELLED"),
            interrupting
        )
    }

    @Test
    fun `driver location speaks nothing and fires an earcon`() {
        val type = RideEventType.DRIVER_LOCATION
        assertEquals(NarrationTier.EARCON_ONLY, type.tier)
        assertNotNull("A tier-2 event with no earcon would be pure silence", type.earcon)
    }

    @Test
    fun `beacon speaks nothing`() {
        assertEquals(NarrationTier.EARCON_ONLY, RideEventType.BEACON.tier)
    }

    @Test
    fun `transport notices are not ride lifecycle events`() {
        // This is what stops a debug caller forging a CONNECTED notice: the backend's scope
        // check accepts only lifecycle types, and this asserts the two sides agree on which
        // ones those are.
        assertFalse(RideEventType.CONNECTED.isRideLifecycle)
        assertFalse(RideEventType.REPLAY_COMPLETE.isRideLifecycle)
        assertFalse(RideEventType.PONG.isRideLifecycle)
        assertTrue(RideEventType.DRIVER_ARRIVED.isRideLifecycle)
    }

    @Test
    fun `an unknown event type parses instead of throwing`() {
        // A malformed or future event must never crash the app: a crash is indistinguishable,
        // to a rider who cannot see the screen, from the app simply going quiet.
        assertEquals(RideEventType.UNKNOWN, RideEventType.parse("SOMETHING_NEW"))
        assertEquals(RideEventType.UNKNOWN, RideEventType.parse(null))
    }

    // =================================================================================
    //  Event parsing and de-duplication
    // =================================================================================

    @Test
    fun `event parses with its eventId preserved`() {
        val json = """
            {"eventId":"abc-123","seq":7,"type":"DRIVER_ARRIVED","rideId":"ride-1001",
             "senderId":"driver-1","senderRole":"DRIVER","payload":{"boardingCode":"4-7-2"}}
        """.trimIndent()

        val event = com.cabeye.rider.net.RideEvent.parse(json)
        assertNotNull(event)
        // The id must survive verbatim — it is the de-duplication key, and a replayed copy
        // carrying a different id would be announced a second time.
        assertEquals("abc-123", event!!.eventId)
        assertEquals(7L, event.seq)
        assertEquals(RideEventType.DRIVER_ARRIVED, event.type)
        assertEquals("4-7-2", event.string("boardingCode"))
    }

    @Test
    fun `the same event parsed twice yields the same id`() {
        val json = """{"eventId":"abc-123","seq":7,"type":"DRIVER_ARRIVED","rideId":"r1"}"""
        val first = com.cabeye.rider.net.RideEvent.parse(json)
        val second = com.cabeye.rider.net.RideEvent.parse(json)
        assertEquals(first!!.eventId, second!!.eventId)
    }

    /** An id-less event still has to be de-duplicable, so one is derived deterministically. */
    @Test
    fun `an event with no id gets a stable synthesised one`() {
        val json = """{"seq":3,"type":"DRIVER_LOCATION","rideId":"ride-1001"}"""
        val first = com.cabeye.rider.net.RideEvent.parse(json)!!
        val second = com.cabeye.rider.net.RideEvent.parse(json)!!
        assertEquals(first.eventId, second.eventId)
        assertTrue(first.eventId.isNotEmpty())
    }

    @Test
    fun `malformed json returns null rather than throwing`() {
        assertNull(com.cabeye.rider.net.RideEvent.parse("not json at all"))
    }

    @Test
    fun `snapshot parses a full ride`() {
        val json = """
            {"rideId":"ride-1001","phase":"ARRIVED","destination":"Adyar","rideType":"AUTO",
             "boardingCode":"4-7-2","driverName":"Karthik","vehicleModel":"Bajaj auto",
             "etaMinutes":0,"distanceMeters":5,"bearingDeg":90.0,"lastSeq":12}
        """.trimIndent()

        val parsed = RideSnapshot.parse(json)
        assertNotNull(parsed)
        assertEquals(RidePhase.ARRIVED, parsed!!.phase)
        assertEquals("4-7-2", parsed.boardingCode)
        assertEquals(12L, parsed.lastSeq)
    }
}
