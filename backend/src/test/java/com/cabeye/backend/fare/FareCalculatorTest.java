package com.cabeye.backend.fare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FareCalculatorTest {

    private final FareCalculator calc = new FareCalculator();

    @Test
    @DisplayName("an auto fare is base + per-km + per-minute: 40 + 5 km x 15 + 15 min x 1 = 130")
    void autoFare() {
        assertEquals(130, calc.fareRupees("AUTO", 5000, 15));
    }

    @Test
    @DisplayName("a cab uses its own, higher tariff: 60 + 5 km x 18 + 14 min x 1.5 = 171")
    void cabFare() {
        assertEquals(171, calc.fareRupees("CAB", 5000, 14));
        assertEquals(171, calc.fareRupees("cab", 5000, 14));
    }

    @Test
    @DisplayName("a very short ride is lifted to the minimum fare")
    void minimumFare() {
        assertEquals(50, calc.fareRupees("AUTO", 200, 1));
        assertEquals(80, calc.fareRupees("CAB", 200, 1));
    }

    @Test
    @DisplayName("a longer ride never costs less than a shorter one")
    void longerCostsMore() {
        int previous = 0;
        for (int metres = 0; metres <= 30_000; metres += 500) {
            int fare = calc.fareRupees("AUTO", metres, metres / 400);
            assertTrue(fare >= previous, "fare fell at " + metres + " m");
            previous = fare;
        }
    }

    @Test
    @DisplayName("an unknown or missing ride type is charged as an auto")
    void unknownTypeIsAuto() {
        assertEquals(130, calc.fareRupees(null, 5000, 15));
        assertEquals(130, calc.fareRupees("SPACESHIP", 5000, 15));
    }

    @Test
    @DisplayName("negative inputs count as zero, so the result is the minimum, never negative")
    void negativeInputs() {
        assertEquals(50, calc.fareRupees("AUTO", -5000, -15));
    }

    @Test
    @DisplayName("custom rates are honoured")
    void customRates() {
        FareCalculator c = new FareCalculator(
                new FareCalculator.Rates(10, 10.0, 0.0, 20),
                new FareCalculator.Rates(10, 20.0, 0.0, 30));
        assertEquals(60, c.fareRupees("AUTO", 5000, 99));
        assertEquals(110, c.fareRupees("CAB", 5000, 99));
    }
}
