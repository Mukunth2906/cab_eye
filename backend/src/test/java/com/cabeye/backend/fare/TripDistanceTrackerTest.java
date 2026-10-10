package com.cabeye.backend.fare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripDistanceTrackerTest {

    /** 0.01 degrees of latitude is about 1,112 m anywhere on Earth. */
    private static final double STEP = 0.01;
    private static final double LAT = 11.0247;
    private static final double LNG = 77.0027;
    private static final long MINUTE = 60_000;

    @Test
    @DisplayName("the first fix only sets the starting point; it adds no distance")
    void firstFixAddsNothing() {
        TripDistanceTracker t = new TripDistanceTracker();
        assertFalse(t.add(LAT, LNG, 0));
        assertEquals(0, t.meters());
    }

    @Test
    @DisplayName("a steady drive adds up: three 1.1 km legs make about 3.3 km")
    void steadyDrive() {
        TripDistanceTracker t = new TripDistanceTracker();
        t.add(LAT, LNG, 0);
        assertTrue(t.add(LAT + STEP, LNG, MINUTE));
        assertTrue(t.add(LAT + 2 * STEP, LNG, 2 * MINUTE));
        assertTrue(t.add(LAT + 3 * STEP, LNG, 3 * MINUTE));
        assertEquals(3336, t.meters(), 3);
    }

    @Test
    @DisplayName("GPS jitter under 10 m is ignored, but real movement is still counted once it is far enough")
    void jitterIgnoredCreepCounted() {
        TripDistanceTracker t = new TripDistanceTracker();
        double tiny = 0.00004; // about 4.4 m
        t.add(LAT, LNG, 0);
        assertFalse(t.add(LAT + tiny, LNG, 1_000));
        assertFalse(t.add(LAT + 2 * tiny, LNG, 2_000));
        assertEquals(0, t.meters());
        assertTrue(t.add(LAT + 3 * tiny, LNG, 3_000)); // now 13 m from the anchor
        assertEquals(13, t.meters(), 1);
    }

    @Test
    @DisplayName("a 5 km teleport in one second is a glitch and adds nothing")
    void teleportIgnored() {
        TripDistanceTracker t = new TripDistanceTracker();
        t.add(LAT, LNG, 0);
        assertFalse(t.add(LAT + 0.05, LNG, 1_000));
        assertEquals(0, t.meters());
    }

    @Test
    @DisplayName("three glitches in a row mean the car really moved: the anchor jumps there without adding the gap")
    void persistentJumpReanchors() {
        TripDistanceTracker t = new TripDistanceTracker();
        t.add(LAT, LNG, 0);
        double far = LAT + 0.05;
        assertFalse(t.add(far, LNG, 1_000));
        assertFalse(t.add(far, LNG, 2_000));
        assertFalse(t.add(far, LNG, 3_000)); // third reject re-anchors here
        assertEquals(0, t.meters());
        assertTrue(t.add(far + STEP, LNG, 3_000 + MINUTE));
        assertEquals(1112, t.meters(), 2);
    }

    @Test
    @DisplayName("impossible or empty positions are ignored and never become the starting point")
    void invalidPositionsIgnored() {
        TripDistanceTracker t = new TripDistanceTracker();
        assertFalse(t.add(Double.NaN, LNG, 0));
        assertFalse(t.add(91, LNG, 0));
        assertFalse(t.add(LAT, 181, 0));
        assertFalse(t.add(0, 0, 0));
        t.add(LAT, LNG, 1_000); // the first real fix becomes the start
        assertTrue(t.add(LAT + STEP, LNG, 1_000 + MINUTE));
        assertEquals(1112, t.meters(), 2);
    }

    @Test
    @DisplayName("saved state resumes exactly where it left off, so a backend restart mid-trip loses nothing")
    void stateRoundTrip() {
        TripDistanceTracker continuous = new TripDistanceTracker();
        TripDistanceTracker first = new TripDistanceTracker();
        double[] lats = {LAT, LAT + STEP, LAT + 2 * STEP, LAT + 3 * STEP};
        for (int i = 0; i < 2; i++) {
            continuous.add(lats[i], LNG, i * MINUTE);
            first.add(lats[i], LNG, i * MINUTE);
        }
        TripDistanceTracker resumed = TripDistanceTracker.from(first.state());
        for (int i = 2; i < 4; i++) {
            continuous.add(lats[i], LNG, i * MINUTE);
            resumed.add(lats[i], LNG, i * MINUTE);
        }
        assertEquals(continuous.meters(), resumed.meters());
        assertEquals(3336, resumed.meters(), 3);
    }

    @Test
    @DisplayName("haversine: one degree of latitude is about 111.2 km")
    void haversineScale() {
        assertEquals(111_195, TripDistanceTracker.haversineMeters(0.5, 77, 1.5, 77), 5);
    }
}
