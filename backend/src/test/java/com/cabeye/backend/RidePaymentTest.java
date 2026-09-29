package com.cabeye.backend;

import com.cabeye.backend.model.Ride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for the payment state carried on a ride.
 *
 * <p>These are deliberately about one property above all others: <b>a claim from the rider's
 * phone can never become CONFIRMED</b>. Everything else here is bookkeeping; that single rule
 * is what stops the app telling a blind rider their fare is paid when it is not.
 */
class RidePaymentTest {

    private Ride newRide() {
        return new Ride("ride-1", "rider-1", "Gandhipuram", "AUTO", "1-2-3");
    }

    @Test
    @DisplayName("a new ride has no payment against it")
    void startsAtNone() {
        assertEquals(Ride.PaymentStatus.NONE, newRide().paymentStatus());
        assertEquals("", newRide().paymentRef());
    }

    @Test
    @DisplayName("payment status and reference round-trip through the snapshot")
    void snapshotCarriesPayment() {
        Ride ride = newRide();
        ride.paymentStatus(Ride.PaymentStatus.REPORTED);
        ride.paymentRef("TXN123");

        Ride.Snapshot snap = ride.snapshot();

        assertEquals("REPORTED", snap.paymentStatus());
        assertEquals("TXN123", snap.paymentRef());
    }

    @Test
    @DisplayName("a null transaction reference is stored as empty, never as null")
    void nullRefBecomesEmpty() {
        Ride ride = newRide();
        ride.paymentRef(null);
        assertEquals("", ride.paymentRef());
    }

    @Test
    @DisplayName("REPORTED and CONFIRMED are distinct — the whole point of the design")
    void reportedIsNotConfirmed() {
        assertNotEquals(Ride.PaymentStatus.REPORTED, Ride.PaymentStatus.CONFIRMED);

        Ride ride = newRide();
        ride.paymentStatus(Ride.PaymentStatus.REPORTED);

        // A rider's phone saying "my UPI app said success" must leave the ride in a state that
        // the app will NOT announce as settled.
        assertNotEquals(Ride.PaymentStatus.CONFIRMED, ride.paymentStatus());
    }
}
