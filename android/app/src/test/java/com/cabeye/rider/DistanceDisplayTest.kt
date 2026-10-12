package com.cabeye.rider

import com.cabeye.rider.state.DriverInfo
import com.cabeye.rider.state.RiderState
import com.cabeye.rider.ui.components.formatDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rider once saw "-1 meters away": the wire's "distance not known yet" (-1) went straight
 * onto the screen. Unknown is now null in the state, and only real distances are formatted.
 */
class DistanceDisplayTest {

    private val driver = DriverInfo("Karthik", "Bajaj RE", "TN 37 BX 4412", "", 4.8f)

    @Test
    fun unknownDistanceFromTheWireIsNull() {
        val a = RiderState.Approaching.of(driver, -1, 0f)
        assertNull(a.distanceMeters)
        // No position, so no direction either (0 degrees would read as "straight ahead").
        assertNull(a.bearingDegrees)
    }

    @Test
    fun knownDistanceKeepsItsBearing() {
        val a = RiderState.Approaching.of(driver, 240, 90f)
        assertEquals(240, a.distanceMeters)
        assertEquals(90f, a.bearingDegrees)
    }

    @Test
    fun zeroMetresIsAKnownDistance() {
        assertEquals(0, RiderState.Approaching.of(driver, 0, 10f).distanceMeters)
    }

    @Test
    fun distancesReadNaturally() {
        assertEquals("under 10 m", formatDistance(0))
        assertEquals("under 10 m", formatDistance(9))
        assertEquals("40 m", formatDistance(37))
        assertEquals("250 m", formatDistance(250))
        assertEquals("1.0 km", formatDistance(995))
        assertEquals("1.0 km", formatDistance(1000))
        assertEquals("1.2 km", formatDistance(1234))
    }
}
