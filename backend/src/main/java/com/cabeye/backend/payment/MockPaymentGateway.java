package com.cabeye.backend.payment;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RidePhase;
import com.cabeye.backend.service.RideService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.razorpay.RazorpayException;

/**
 * A sandbox payment gateway. No bank, no money, no credentials — but the same shape as a real
 * one, so the rest of the app is built against the real contract:
 *
 * <ol>
 *   <li>the rider's app asks the server to <b>create an order</b> for the ride's fare;</li>
 *   <li>the rider is sent to a <b>checkout</b> (a page this server hosts at {@code /pay/{id}},
 *       reachable by redirect from the app or by scanning the QR code the app shows);</li>
 *   <li>the <b>gateway</b> — never the rider's phone — decides the outcome, and on success
 *       marks the ride's payment CONFIRMED and tells rider and driver over the ride topic.</li>
 * </ol>
 *
 * <p>Replacing this with a live provider means: create the provider's order in
 * {@link #createOrder}, and call {@link #pay}/{@link #fail} from its webhook instead of from the
 * sandbox page. Nothing on either phone has to change.
 */
@Service
public class MockPaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    /** How long a checkout stays payable. Long enough for a slow screen-reader flow. */
    public static final Duration ORDER_TTL = Duration.ofMinutes(10);

    /** Sandbox payee. Deliberately not a real handle: the UPI-app path is best-effort only. */
    public static final String PAYEE_VPA = "cabeye@sandbox";
    public static final String PAYEE_NAME = "Cab Eye";

    private static final String ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final RideService rides;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final RazorpayService razorpay;

    private final Map<String, PaymentOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, String> latestOrderByRide = new ConcurrentHashMap<>();

    /** Where orders are saved; null in plain unit tests, which run without a database. */
    private volatile com.cabeye.backend.store.Table<PaymentOrderRecord> store;

    /**
     * Saves every order to [table] from now on, after loading the ones already there — so an
     * open checkout, or a paid receipt, survives a backend restart.
     */
    public synchronized void persistTo(com.cabeye.backend.store.Table<PaymentOrderRecord> table) {
        for (PaymentOrderRecord r : table.all()) {
            try {
                PaymentOrder order = PaymentOrder.fromRecord(r);
                orders.put(order.orderId(), order);
                String latest = latestOrderByRide.get(order.rideId());
                if (latest == null || orders.get(latest).createdAt().isBefore(order.createdAt())) {
                    latestOrderByRide.put(order.rideId(), order.orderId());
                }
            } catch (RuntimeException e) {
                log.warn("PAYMENT_ORDER_RESTORE_FAILED order={} : {}", r.orderId, e.toString());
            }
        }
        this.store = table;
        log.info("PAYMENT orders restored {} from the database", orders.size());
    }

    private void save(PaymentOrder order) {
        var table = store;
        if (table == null) return;
        try {
            table.put(order.orderId(), order.toRecord());
        } catch (RuntimeException e) {
            log.warn("PAYMENT_ORDER_SAVE_FAILED order={} : {}", order.orderId(), e.toString());
        }
    }

    @Autowired
    public MockPaymentGateway(RideService rides, RazorpayService razorpay) {
        this(rides, Clock.systemUTC(), razorpay);
    }

    /** Test seam: lets expiry be tested without sleeping for ten minutes. */
    public MockPaymentGateway(RideService rides, Clock clock) {
        this(rides, clock, null);
    }

    public MockPaymentGateway(RideService rides, Clock clock, RazorpayService razorpay) {
        this.rides = rides;
        this.clock = clock;
        this.razorpay = razorpay;
    }

    /** True when real Razorpay keys are configured and the SDK initialised. */
    public boolean isRazorpayEnabled() { return razorpay != null && razorpay.isEnabled(); }

    public RazorpayService razorpay() { return razorpay; }

    /** Why a request was refused, in words the rider's app can speak. */
    public static final class PaymentException extends RuntimeException {
        public enum Kind { NOT_FOUND, CONFLICT }
        private final Kind kind;

        PaymentException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() { return kind; }
    }

    // ===================================================================================
    //  Orders
    // ===================================================================================

    /**
     * Creates (or returns the still-open) order for a ride's fare.
     *
     * <p>Idempotent on purpose. A rider who taps Pay twice, or whose app retried after a
     * timeout, must land on the same checkout — two live orders for one fare is how people get
     * charged twice.
     */
    public synchronized PaymentOrder createOrder(String rideId) {
        Ride ride = rides.find(rideId)
                .orElseThrow(() -> new PaymentException(PaymentException.Kind.NOT_FOUND,
                        "That ride no longer exists."));

        if (ride.phase() != RidePhase.COMPLETED) {
            throw new PaymentException(PaymentException.Kind.CONFLICT,
                    "The ride has not finished yet.");
        }
        if (ride.fareRupees() <= 0) {
            throw new PaymentException(PaymentException.Kind.CONFLICT,
                    "There is no fare to pay for this ride.");
        }
        if (ride.paymentStatus() == Ride.PaymentStatus.CONFIRMED) {
            throw new PaymentException(PaymentException.Kind.CONFLICT,
                    "This ride is already paid.");
        }

        PaymentOrder open = latestForRide(rideId).orElse(null);
        if (open != null && !open.status().isFinal()) {
            return open;
        }

        Instant now = clock.instant();
        PaymentOrder order = new PaymentOrder(
                "order_" + randomToken(14), rideId, ride.fareRupees(), PAYEE_VPA, PAYEE_NAME,
                "Cab Eye ride to " + ride.destination(), now, now.plus(ORDER_TTL));

        // When Razorpay is enabled, create a real order on Razorpay's side.
        if (isRazorpayEnabled()) {
            try {
                org.json.JSONObject rzpOrder = razorpay.createOrder(
                        order.amountRupees(), order.orderId(), order.note());
                order.setRazorpayOrderId(rzpOrder.getString("id"));
                log.info("PAYMENT_RAZORPAY_ORDER order={} rzp_order={}",
                        order.orderId(), order.razorpayOrderId());
            } catch (RazorpayException e) {
                log.error("PAYMENT_RAZORPAY_ORDER_FAILED order={}: {}",
                        order.orderId(), e.getMessage());
                throw new PaymentException(PaymentException.Kind.CONFLICT,
                        "Could not create payment. Please try again.");
            }
        }

        orders.put(order.orderId(), order);
        latestOrderByRide.put(rideId, order.orderId());
        save(order);

        log.info("PAYMENT_ORDER_CREATED order={} ride={} amount=₹{} razorpay={}",
                order.orderId(), rideId, order.amountRupees(), isRazorpayEnabled());
        return order;
    }

    /**
     * Looks an order up, first bringing it up to date: it may have expired, or the ride may
     * have been confirmed paid by another route (the manual confirm endpoint, standing in for
     * a provider webhook). One order, one truth.
     */
    public Optional<PaymentOrder> find(String orderId) {
        PaymentOrder order = orderId == null ? null : orders.get(orderId);
        if (order == null) return Optional.empty();
        refresh(order);
        return Optional.of(order);
    }

    public Optional<PaymentOrder> latestForRide(String rideId) {
        String id = rideId == null ? null : latestOrderByRide.get(rideId);
        return id == null ? Optional.empty() : find(id);
    }

    /** The checkout page was opened. CREATED → PENDING; anything else is left alone. */
    public PaymentOrder open(String orderId) {
        PaymentOrder order = require(orderId);
        if (order.markOpened()) {
            log.info("PAYMENT_CHECKOUT_OPENED order={}", orderId);
            save(order);
        }
        return order;
    }

    // ===================================================================================
    //  Outcomes — the gateway's decision, never the payer's phone's
    // ===================================================================================

    /**
     * Settles the order. Idempotent: paying an already-paid order returns it unchanged, so a
     * double-tap on the checkout page cannot produce two receipts.
     */
    public PaymentOrder pay(String orderId, String method) {
        PaymentOrder order = require(orderId);

        String txnId = "pay_" + randomToken(14);
        String bankRef = randomDigits(12);
        String how = normaliseMethod(method);

        if (!order.markPaid(how, txnId, bankRef, clock.instant())) {
            if (order.status() == PaymentOrder.Status.PAID) return order;
            throw new PaymentException(PaymentException.Kind.CONFLICT, conflictFor(order));
        }

        save(order);
        rides.confirmPayment(order.rideId(), bankRef);
        log.info("PAYMENT_PAID order={} ride={} amount=₹{} method={} utr={}",
                orderId, order.rideId(), order.amountRupees(), how, bankRef);
        return order;
    }

    /** The payer declined, or the (mock) bank refused. */
    public PaymentOrder fail(String orderId, String method, String reason) {
        PaymentOrder order = require(orderId);

        if (!order.markFailed(normaliseMethod(method), reason)) {
            if (order.status() == PaymentOrder.Status.FAILED) return order;
            throw new PaymentException(PaymentException.Kind.CONFLICT, conflictFor(order));
        }

        save(order);
        rides.failPayment(order.rideId(), order.failureReason());
        log.info("PAYMENT_FAILED order={} ride={} reason=\"{}\"",
                orderId, order.rideId(), order.failureReason());
        return order;
    }

    // ===================================================================================
    //  Razorpay callback — verify signature and settle
    // ===================================================================================

    /**
     * Called by the Razorpay checkout callback after the rider pays through the Razorpay
     * modal. Verifies the signature, then marks the order PAID.
     *
     * @return the order, now PAID, or throws on bad signature / missing order
     */
    public PaymentOrder verifyAndPay(String orderId, String razorpayPaymentId,
                                     String razorpayOrderId, String razorpaySignature) {
        PaymentOrder order = require(orderId);

        if (order.status() == PaymentOrder.Status.PAID) return order;

        if (!isRazorpayEnabled()) {
            throw new PaymentException(PaymentException.Kind.CONFLICT,
                    "Razorpay is not enabled on this server.");
        }

        // Verify the signature: the ONLY way to trust that the payment is genuine.
        if (!razorpay.verifySignature(razorpayOrderId, razorpayPaymentId, razorpaySignature)) {
            log.warn("PAYMENT_RAZORPAY_SIGNATURE_INVALID order={} rzp_payment={}",
                    orderId, razorpayPaymentId);
            throw new PaymentException(PaymentException.Kind.CONFLICT,
                    "Payment verification failed. Please try again.");
        }

        // Fetch payment details from Razorpay to get method, bank reference, etc.
        String method = "UPI";
        String bankRef = razorpayPaymentId; // fallback
        org.json.JSONObject paymentDetails = razorpay.fetchPayment(razorpayPaymentId);
        if (paymentDetails != null) {
            method = paymentDetails.optString("method", "UPI").toUpperCase();
            // Try acquirer_data.upi_transaction_id for UPI, else rrn, else payment ID
            org.json.JSONObject acq = paymentDetails.optJSONObject("acquirer_data");
            if (acq != null) {
                String upiTxnId = acq.optString("upi_transaction_id", "");
                String rrn = acq.optString("rrn", "");
                bankRef = !upiTxnId.isEmpty() ? upiTxnId : !rrn.isEmpty() ? rrn : razorpayPaymentId;
            }
        }

        if (!order.markPaid(normaliseMethod(method), razorpayPaymentId, bankRef, clock.instant())) {
            if (order.status() == PaymentOrder.Status.PAID) return order;
            throw new PaymentException(PaymentException.Kind.CONFLICT, conflictFor(order));
        }

        save(order);
        rides.confirmPayment(order.rideId(), bankRef);
        log.info("PAYMENT_RAZORPAY_PAID order={} ride={} amount=₹{} method={} rzp_payment={} ref={}",
                orderId, order.rideId(), order.amountRupees(), method,
                razorpayPaymentId, bankRef);
        return order;
    }

    // ===================================================================================
    //  Presentation helpers
    // ===================================================================================

    /**
     * The standard UPI deep link for this order, for the "pay with my UPI app" path.
     * {@code tr} carries the order id so a real collection could be matched back to it.
     */
    public String upiUri(PaymentOrder order) {
        return "upi://pay"
                + "?pa=" + enc(order.payeeVpa())
                + "&pn=" + enc(order.payeeName())
                + "&am=" + order.amountRupees() + ".00"
                + "&cu=INR"
                + "&tn=" + enc(order.note())
                + "&tr=" + enc(order.orderId());
    }

    /** @param baseUrl scheme://host:port the caller used, no trailing slash */
    public static String checkoutUrl(String baseUrl, PaymentOrder order) {
        return baseUrl + "/pay/" + order.orderId();
    }

    public PaymentOrder.View view(PaymentOrder order, String baseUrl) {
        return order.view(upiUri(order), checkoutUrl(baseUrl, order));
    }

    // ===================================================================================
    //  Internals
    // ===================================================================================

    private PaymentOrder require(String orderId) {
        return find(orderId).orElseThrow(() -> new PaymentException(
                PaymentException.Kind.NOT_FOUND, "That payment could not be found."));
    }

    private void refresh(PaymentOrder order) {
        if (order.expireIfDue(clock.instant())) {
            log.info("PAYMENT_EXPIRED order={} ride={}", order.orderId(), order.rideId());
            save(order);
        }
        rides.find(order.rideId()).ifPresent(ride -> {
            if (ride.paymentStatus() == Ride.PaymentStatus.CONFIRMED
                    && order.status() != PaymentOrder.Status.PAID) {
                if (order.markPaid("EXTERNAL", "", ride.paymentRef(), clock.instant())) save(order);
            }
        });
    }

    private static String conflictFor(PaymentOrder order) {
        return switch (order.status()) {
            case PAID -> "This payment is already complete.";
            case FAILED -> "This payment failed. Start a new payment to try again.";
            case EXPIRED -> "This payment expired. Start a new payment to try again.";
            default -> "That payment can't be changed right now.";
        };
    }

    private static String normaliseMethod(String method) {
        if (method == null || method.isBlank()) return "UPI";
        String m = method.trim().toUpperCase();
        return switch (m) {
            case "UPI", "CARD", "NETBANKING", "WALLET", "EXTERNAL" -> m;
            default -> "UPI";
        };
    }

    private String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUM.charAt(random.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }

    private String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        sb.append(1 + random.nextInt(9)); // no leading zero, like a real UTR
        for (int i = 1; i < length; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
