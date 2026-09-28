package com.cabeye.backend.controller;

import com.cabeye.backend.payment.MockPaymentGateway;
import com.cabeye.backend.payment.MockPaymentGateway.PaymentException;
import com.cabeye.backend.payment.CheckoutPage;
import com.cabeye.backend.payment.PaymentOrder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The payment surface: a JSON API for the apps, and the sandbox checkout page for humans.
 *
 * <pre>
 *   POST /rides/{rideId}/payment/order   create (or reuse) the order for a finished ride's fare
 *   GET  /rides/{rideId}/payment/order   latest order for a ride
 *   GET  /payments/{orderId}             poll an order
 *   POST /payments/{orderId}/simulate    {"outcome":"SUCCESS"|"FAILURE","method":"UPI"} — tests/demos
 *   GET  /pay/{orderId}                  sandbox checkout page (what the app redirects to / the QR opens)
 *   POST /pay/{orderId}                  checkout form submit (action=pay|fail, method=UPI|CARD|NETBANKING)
 * </pre>
 *
 * The older {@code /rides/{id}/payment} endpoints in {@link RideController} are unchanged.
 */
@RestController
@CrossOrigin(originPatterns = "*")
public class PaymentController {

    private final MockPaymentGateway gateway;

    public PaymentController(MockPaymentGateway gateway) {
        this.gateway = gateway;
    }

    // ===================================================================================
    //  JSON API
    // ===================================================================================

    @PostMapping("/rides/{rideId}/payment/order")
    public ResponseEntity<?> createOrder(@PathVariable String rideId, HttpServletRequest request) {
        return json(() -> gateway.view(gateway.createOrder(rideId), baseUrl(request)));
    }

    @GetMapping("/rides/{rideId}/payment/order")
    public ResponseEntity<?> latestOrder(@PathVariable String rideId, HttpServletRequest request) {
        return gateway.latestForRide(rideId)
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(gateway.view(o, baseUrl(request))))
                .orElseGet(() -> error(404, "No payment has been started for this ride."));
    }

    @GetMapping("/payments/{orderId}")
    public ResponseEntity<?> order(@PathVariable String orderId, HttpServletRequest request) {
        return gateway.find(orderId)
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(gateway.view(o, baseUrl(request))))
                .orElseGet(() -> error(404, "That payment could not be found."));
    }

    @PostMapping("/payments/{orderId}/simulate")
    public ResponseEntity<?> simulate(@PathVariable String orderId,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String outcome = String.valueOf(b.getOrDefault("outcome", "SUCCESS")).trim().toUpperCase();
        String method = String.valueOf(b.getOrDefault("method", "UPI"));
        String reason = String.valueOf(b.getOrDefault("reason", "Declined in simulation"));
        return json(() -> gateway.view(
                outcome.startsWith("FAIL") ? gateway.fail(orderId, method, reason)
                                           : gateway.pay(orderId, method),
                baseUrl(request)));
    }

    // ===================================================================================
    //  Sandbox checkout page
    // ===================================================================================

    @GetMapping(value = "/pay/{orderId}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> checkoutPage(@PathVariable String orderId) {
        try {
            return html(200, CheckoutPage.checkout(gateway.open(orderId)));
        } catch (PaymentException e) {
            return html(404, CheckoutPage.notFound(e.getMessage()));
        }
    }

    @PostMapping(value = "/pay/{orderId}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> submitCheckout(@PathVariable String orderId,
                                                 @RequestParam(defaultValue = "pay") String action,
                                                 @RequestParam(defaultValue = "UPI") String method) {
        try {
            PaymentOrder order = "fail".equalsIgnoreCase(action)
                    ? gateway.fail(orderId, method, "You declined the payment")
                    : gateway.pay(orderId, method);
            return html(200, CheckoutPage.result(order));
        } catch (PaymentException e) {
            // A final order re-renders its own outcome rather than a bare error page, so a
            // back-button resubmit shows the receipt instead of alarming the payer.
            return gateway.find(orderId)
                    .map(o -> html(409, CheckoutPage.result(o)))
                    .orElseGet(() -> html(404, CheckoutPage.notFound(e.getMessage())));
        }
    }

    // ===================================================================================
    //  Helpers
    // ===================================================================================

    private ResponseEntity<?> json(Supplier<PaymentOrder.View> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (PaymentException e) {
            return error(e.kind() == PaymentException.Kind.NOT_FOUND ? 404 : 409, e.getMessage());
        }
    }

    private static ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private static ResponseEntity<String> html(int status, String body) {
        // Explicit UTF-8: without it Spring writes Latin-1 and the rupee sign reaches the page as "?".
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }

    /**
     * scheme://host:port exactly as the caller reached us. The phone reached the server at its
     * LAN address, so that is the address the checkout link and QR code must carry.
     */
    static String baseUrl(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return request.getScheme() + "://" + host;
    }
}
