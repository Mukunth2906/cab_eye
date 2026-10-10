package com.cabeye.backend.payment;

import com.razorpay.Order;
import com.razorpay.Payment;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * Thin wrapper around the Razorpay Java SDK. This service exists so that the rest of the
 * payment package never imports the SDK directly, which makes testing straightforward and
 * keeps the swap surface for a different provider to a single class.
 *
 * <p>The service is only active when both {@code cabeye.razorpay.key-id} and
 * {@code cabeye.razorpay.key-secret} are set in the configuration. When they are blank,
 * {@link #isEnabled()} returns false and the {@link MockPaymentGateway} falls back to
 * its built-in sandbox.
 *
 * <h2>What this class does</h2>
 * <ol>
 *   <li>Creates a Razorpay order (amount, currency, receipt) and returns the order ID
 *       that the checkout JS needs.</li>
 *   <li>Verifies the {@code razorpay_signature} that the checkout returns, using
 *       HMAC-SHA256 over {@code order_id|payment_id} — the only way to know the
 *       payment is genuine and not spoofed by a tampered checkout page.</li>
 *   <li>Fetches payment details from Razorpay to get the method, bank reference, etc.</li>
 * </ol>
 */
@Service
public class RazorpayService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayService.class);

    @Value("${cabeye.razorpay.key-id:}")
    private String keyId;

    @Value("${cabeye.razorpay.key-secret:}")
    private String keySecret;

    private RazorpayClient client;
    private boolean enabled;

    @PostConstruct
    void init() {
        if (keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank()) {
            try {
                client = new RazorpayClient(keyId, keySecret);
                enabled = true;
                boolean test = keyId.startsWith("rzp_test_");
                log.info("RAZORPAY enabled mode={}", test ? "TEST" : "LIVE");
            } catch (RazorpayException e) {
                log.error("RAZORPAY failed to initialise: {}", e.getMessage());
                enabled = false;
            }
        } else {
            log.info("RAZORPAY disabled (no keys configured) — using sandbox mock");
            enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String keyId() {
        return keyId;
    }

    public boolean isTestMode() {
        return keyId != null && keyId.startsWith("rzp_test_");
    }

    // ===================================================================================
    //  Order creation
    // ===================================================================================

    /**
     * Creates a Razorpay order for the given amount and receipt.
     *
     * @param amountRupees the fare in whole rupees (converted to paise internally)
     * @param receipt      a unique receipt string, e.g. the internal order ID
     * @param note         description shown on the Razorpay dashboard
     * @return the Razorpay order as a JSON object with {@code id}, {@code amount}, etc.
     * @throws RazorpayException if the API call fails
     */
    public JSONObject createOrder(int amountRupees, String receipt, String note) throws RazorpayException {
        JSONObject request = new JSONObject();
        request.put("amount", amountRupees * 100); // Razorpay works in paise
        request.put("currency", "INR");
        request.put("receipt", receipt);
        request.put("notes", new JSONObject().put("description", note));

        Order order = client.orders.create(request);
        String rzpOrderId = order.get("id");
        log.info("RAZORPAY_ORDER_CREATED rzp_order={} receipt={} amount=₹{}",
                rzpOrderId, receipt, amountRupees);
        return order.toJson();
    }

    // ===================================================================================
    //  Payment verification
    // ===================================================================================

    /**
     * Verifies the Razorpay payment signature. This is the ONLY way to trust that a
     * payment actually went through. The checkout page sends three values; we verify that
     * the signature matches {@code order_id|payment_id} signed with our secret.
     *
     * @return true if the signature is valid
     */
    public boolean verifySignature(String razorpayOrderId, String razorpayPaymentId,
                                   String razorpaySignature) {
        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_order_id", razorpayOrderId);
            attributes.put("razorpay_payment_id", razorpayPaymentId);
            attributes.put("razorpay_signature", razorpaySignature);
            return Utils.verifyPaymentSignature(attributes, keySecret);
        } catch (RazorpayException e) {
            log.warn("RAZORPAY_SIGNATURE_VERIFY_FAILED: {}", e.getMessage());
            return false;
        }
    }

    // ===================================================================================
    //  Payment details
    // ===================================================================================

    /**
     * Fetches payment details from Razorpay. Used after verification to get the method,
     * bank reference (acquirer data / UPI transaction ID), and other metadata.
     *
     * @return the payment JSON, or null if the fetch failed
     */
    public JSONObject fetchPayment(String razorpayPaymentId) {
        try {
            Payment payment = client.payments.fetch(razorpayPaymentId);
            return payment.toJson();
        } catch (RazorpayException e) {
            log.warn("RAZORPAY_PAYMENT_FETCH_FAILED payment={}: {}",
                    razorpayPaymentId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches order details from Razorpay to check its status.
     *
     * @return the order JSON, or null if the fetch failed
     */
    public JSONObject fetchOrder(String razorpayOrderId) {
        try {
            Order order = client.orders.fetch(razorpayOrderId);
            return order.toJson();
        } catch (RazorpayException e) {
            log.warn("RAZORPAY_ORDER_FETCH_FAILED order={}: {}",
                    razorpayOrderId, e.getMessage());
            return null;
        }
    }
}
