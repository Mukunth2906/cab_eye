package com.cabeye.backend.payment;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideEvent;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.websocket.RideSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the sandbox gateway. No Spring context: the gateway, the ride state machine
 * and a socket manager with no sockets are wired by hand, so each test runs in milliseconds.
 */
class MockPaymentGatewayTest {

    /** A clock the test can move forward, so expiry is tested without waiting ten minutes. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-22T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private RideService rides;
    private MutableClock clock;
    private MockPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        rides = new RideService(new RideSessionManager(new ObjectMapper()));
        clock = new MutableClock();
        gateway = new MockPaymentGateway(rides, clock);
    }

    /** A ride taken all the way to COMPLETED with the given fare. */
    private String completedRide(int fare) {
        String id = rides.create("rider-test", "Gandhipuram", "AUTO").rideId();
        rides.assign(id, "driver-test", "Karthik", "Auto", "TN 37 BX 4412", "+910000000000", 5);
        rides.passengerSeated(id, "driver-test");
        rides.startTrip(id, "driver-test", 12);
        rides.complete(id, "driver-test", fare, 12);
        return id;
    }

    private Ride ride(String id) {
        return rides.find(id).orElseThrow();
    }

    private boolean loggedPaymentEvent(String rideId) {
        for (RideEvent e : ride(rideId).eventsAfter(0)) {
            if ("PAYMENT_UPDATED".equals(e.type())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------

    @Test
    void refusesOrderBeforeTheRideHasFinished() {
        String id = rides.create("rider-test", "Gandhipuram", "AUTO").rideId();
        MockPaymentGateway.PaymentException e =
                assertThrows(MockPaymentGateway.PaymentException.class, () -> gateway.createOrder(id));
        assertEquals(MockPaymentGateway.PaymentException.Kind.CONFLICT, e.kind());
    }

    @Test
    void refusesOrderForUnknownRide() {
        MockPaymentGateway.PaymentException e =
                assertThrows(MockPaymentGateway.PaymentException.class, () -> gateway.createOrder("ride-nope"));
        assertEquals(MockPaymentGateway.PaymentException.Kind.NOT_FOUND, e.kind());
    }

    @Test
    void refusesOrderWhenThereIsNoFare() {
        String id = completedRide(0);
        assertThrows(MockPaymentGateway.PaymentException.class, () -> gateway.createOrder(id));
    }

    @Test
    void createsOrderForTheFareWithAUpiLinkAndCheckoutUrl() {
        String id = completedRide(148);
        PaymentOrder order = gateway.createOrder(id);

        assertEquals(148, order.amountRupees());
        assertEquals(PaymentOrder.Status.CREATED, order.status());
        assertTrue(order.orderId().startsWith("order_"));

        PaymentOrder.View view = gateway.view(order, "http://192.168.1.10:8080");
        assertTrue(view.upiUri().startsWith("upi://pay?pa="));
        assertTrue(view.upiUri().contains("&am=148.00"));
        assertTrue(view.upiUri().contains("&cu=INR"));
        assertTrue(view.upiUri().contains("&tr=" + order.orderId()));
        assertEquals("http://192.168.1.10:8080/pay/" + order.orderId(), view.checkoutUrl());
        assertTrue(view.testMode());
    }

    @Test
    void createOrderIsIdempotentWhileTheOrderIsOpen() {
        String id = completedRide(148);
        PaymentOrder first = gateway.createOrder(id);
        gateway.open(first.orderId());
        PaymentOrder second = gateway.createOrder(id);
        assertEquals(first.orderId(), second.orderId());
        assertEquals(PaymentOrder.Status.PENDING, second.status());
    }

    @Test
    void payingConfirmsTheRideAndTellsTheRideTopic() {
        String id = completedRide(148);
        PaymentOrder order = gateway.pay(gateway.createOrder(id).orderId(), "card");

        assertEquals(PaymentOrder.Status.PAID, order.status());
        assertEquals("CARD", order.method());
        assertTrue(order.bankRef().matches("[1-9][0-9]{11}"), "12-digit bank reference");
        assertTrue(order.gatewayTxnId().startsWith("pay_"));
        assertEquals(Ride.PaymentStatus.CONFIRMED, ride(id).paymentStatus());
        assertEquals(order.bankRef(), ride(id).paymentRef());
        assertTrue(loggedPaymentEvent(id));
    }

    @Test
    void payingTwiceIsIdempotent() {
        String id = completedRide(148);
        String orderId = gateway.createOrder(id).orderId();
        String ref = gateway.pay(orderId, "UPI").bankRef();
        assertEquals(ref, gateway.pay(orderId, "UPI").bankRef());
    }

    @Test
    void aPaidRideCannotBeChargedAgain() {
        String id = completedRide(148);
        gateway.pay(gateway.createOrder(id).orderId(), "UPI");
        MockPaymentGateway.PaymentException e =
                assertThrows(MockPaymentGateway.PaymentException.class, () -> gateway.createOrder(id));
        assertEquals(MockPaymentGateway.PaymentException.Kind.CONFLICT, e.kind());
    }

    @Test
    void failureMarksRideFailedAndARetryGetsAFreshOrder() {
        String id = completedRide(148);
        PaymentOrder first = gateway.createOrder(id);
        gateway.fail(first.orderId(), "UPI", "You declined the payment");

        assertEquals(PaymentOrder.Status.FAILED, first.status());
        assertEquals(Ride.PaymentStatus.FAILED, ride(id).paymentStatus());

        PaymentOrder retry = gateway.createOrder(id);
        assertNotEquals(first.orderId(), retry.orderId());
        gateway.pay(retry.orderId(), "UPI");
        assertEquals(Ride.PaymentStatus.CONFIRMED, ride(id).paymentStatus());
    }

    @Test
    void aPaidOrderCannotBeFailed() {
        String id = completedRide(148);
        String orderId = gateway.createOrder(id).orderId();
        gateway.pay(orderId, "UPI");
        assertThrows(MockPaymentGateway.PaymentException.class, () -> gateway.fail(orderId, "UPI", "late"));
        assertEquals(Ride.PaymentStatus.CONFIRMED, ride(id).paymentStatus());
    }

    @Test
    void ordersExpireAndCannotThenBePaid() {
        String id = completedRide(148);
        String orderId = gateway.createOrder(id).orderId();

        clock.advance(MockPaymentGateway.ORDER_TTL.plusSeconds(1));

        assertEquals(PaymentOrder.Status.EXPIRED, gateway.find(orderId).orElseThrow().status());
        assertThrows(MockPaymentGateway.PaymentException.class, () -> gateway.pay(orderId, "UPI"));
        assertFalse(ride(id).paymentStatus() == Ride.PaymentStatus.CONFIRMED);
        // ...and a new attempt is possible.
        assertNotEquals(orderId, gateway.createOrder(id).orderId());
    }

    @Test
    void externalConfirmationIsReflectedInTheOrder() {
        String id = completedRide(148);
        String orderId = gateway.createOrder(id).orderId();
        rides.confirmPayment(id);
        PaymentOrder order = gateway.find(orderId).orElseThrow();
        assertEquals(PaymentOrder.Status.PAID, order.status());
        assertEquals("EXTERNAL", order.method());
    }

    @Test
    void aLateFailureClaimFromThePhoneNeverUnpaysARide() {
        String id = completedRide(148);
        gateway.pay(gateway.createOrder(id).orderId(), "UPI");
        rides.reportPayment(id, "FAILURE", "late-ref");
        assertEquals(Ride.PaymentStatus.CONFIRMED, ride(id).paymentStatus());
    }

    @Test
    void unknownOrderIsNotFound() {
        assertFalse(gateway.find("order_missing").isPresent());
        MockPaymentGateway.PaymentException e =
                assertThrows(MockPaymentGateway.PaymentException.class, () -> gateway.pay("order_missing", "UPI"));
        assertEquals(MockPaymentGateway.PaymentException.Kind.NOT_FOUND, e.kind());
    }

    @Test
    void checkoutPageShowsAmountAndTestModeAndEscapesText() {
        String id = rides.create("rider-test", "<script>x</script>", "AUTO").rideId();
        rides.assign(id, "d", "K", "Auto", "P", "+91", 5);
        rides.passengerSeated(id, "d");
        rides.startTrip(id, "d", 1);
        rides.complete(id, "d", 148, 1);

        String html = CheckoutPage.checkout(gateway.createOrder(id));
        assertTrue(html.contains("TEST MODE"));
        assertTrue(html.contains("₹148"));
        assertTrue(html.contains("name=\"action\" value=\"pay\""));
        assertFalse(html.contains("<script>"), "destination text must be escaped");
    }
}
