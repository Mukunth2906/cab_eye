package com.cabeye.backend.payment;

/**
 * The Razorpay checkout, rendered as one self-contained HTML page that loads the
 * Razorpay Standard Checkout JS.
 *
 * <p>When Razorpay keys are configured, the {@code /pay/{orderId}} page renders this
 * instead of the sandbox {@link CheckoutPage}. The page:
 * <ol>
 *   <li>shows the fare, payee name and order details;</li>
 *   <li>opens Razorpay's checkout modal (which handles UPI, cards, net banking, wallets);</li>
 *   <li>on success, posts {@code razorpay_payment_id}, {@code razorpay_order_id} and
 *       {@code razorpay_signature} back to this server at
 *       {@code /pay/{orderId}/razorpay-callback};</li>
 *   <li>the server verifies the signature and marks the internal order PAID.</li>
 * </ol>
 *
 * <p>This is the real NPCI UPI flow: the rider chooses UPI in the Razorpay modal, the
 * phone's biometric or UPI PIN confirms, money moves through the banks, and the receipt
 * appears — exactly how a real payment app works.
 *
 * <p>The page works on any phone on the LAN (same as the sandbox checkout) and is
 * labelled TEST MODE when using {@code rzp_test_} keys.
 */
public final class RazorpayCheckoutPage {

    private RazorpayCheckoutPage() {}

    /**
     * The checkout page that opens the Razorpay modal.
     *
     * @param order      the internal payment order
     * @param rzpOrderId the Razorpay order ID (starts with {@code order_})
     * @param rzpKeyId   the Razorpay key ID (starts with {@code rzp_test_} or {@code rzp_live_})
     * @param baseUrl    scheme://host:port as the caller reached this server
     * @param testMode   true when using test keys
     */
    public static String checkout(PaymentOrder order, String rzpOrderId, String rzpKeyId,
                                  String baseUrl, boolean testMode) {
        String body = checkoutBody(order, rzpOrderId, rzpKeyId, baseUrl, testMode);
        return page("Pay ₹" + order.amountRupees() + " — Cab Eye", body, testMode);
    }

    /** After Razorpay confirms: shows the receipt. */
    public static String paid(PaymentOrder order, boolean testMode) {
        return page("Payment successful — Cab Eye", paidBlock(order), testMode);
    }

    /** When the payment failed or was cancelled in the Razorpay modal. */
    public static String failed(PaymentOrder order, String reason, boolean testMode) {
        return page("Payment not completed — Cab Eye", failedBlock(order, reason), testMode);
    }

    public static String notFound(String message, boolean testMode) {
        return page("Payment not found — Cab Eye",
                "<h1>Payment not found</h1><p role=\"status\">" + esc(message) + "</p>", testMode);
    }

    // ---------------------------------------------------------------------------------

    private static String checkoutBody(PaymentOrder order, String rzpOrderId, String rzpKeyId,
                                       String baseUrl, boolean testMode) {
        return """
                <p class="label">Amount to pay</p>
                <h1 class="amount" aria-label="%1$d rupees">₹%1$d</h1>
                <p class="note">%2$s</p>
                <dl>
                  <dt>Paying</dt><dd>%3$s</dd>
                  <dt>Order</dt><dd class="mono">%4$s</dd>
                </dl>

                <div id="status" role="status" aria-live="polite" class="status-msg"></div>

                <button id="pay-btn" class="pay" onclick="openRazorpay()">
                  Pay ₹%1$d
                </button>

                <p class="small">
                  %5$s
                  Powered by Razorpay. Your payment is secured with bank-grade encryption.
                </p>

                <script src="https://checkout.razorpay.com/v1/checkout.js"></script>
                <script>
                  var payBtn = document.getElementById('pay-btn');
                  var statusEl = document.getElementById('status');

                  function openRazorpay() {
                    payBtn.disabled = true;
                    payBtn.textContent = 'Opening payment…';
                    statusEl.textContent = 'Razorpay checkout is loading…';

                    var options = {
                      key: '%6$s',
                      amount: %7$d,
                      currency: 'INR',
                      order_id: '%8$s',
                      name: 'Cab Eye',
                      description: '%9$s',
                      handler: function(response) {
                        payBtn.textContent = 'Verifying…';
                        statusEl.textContent = 'Payment received. Verifying with the server…';

                        var form = document.createElement('form');
                        form.method = 'POST';
                        form.action = '%10$s/pay/%4$s/razorpay-callback';

                        var fields = {
                          'razorpay_payment_id': response.razorpay_payment_id,
                          'razorpay_order_id': response.razorpay_order_id,
                          'razorpay_signature': response.razorpay_signature
                        };

                        for (var key in fields) {
                          var input = document.createElement('input');
                          input.type = 'hidden';
                          input.name = key;
                          input.value = fields[key];
                          form.appendChild(input);
                        }
                        document.body.appendChild(form);
                        form.submit();
                      },
                      modal: {
                        ondismiss: function() {
                          payBtn.disabled = false;
                          payBtn.textContent = 'Pay ₹%1$d';
                          statusEl.textContent = 'Payment cancelled. Tap Pay to try again.';
                        },
                        escape: true,
                        confirm_close: true
                      },
                      theme: {
                        color: '#22c55e',
                        backdrop_color: 'rgba(0,0,0,0.85)'
                      }
                    };

                    try {
                      var rzp = new Razorpay(options);
                      rzp.on('payment.failed', function(response) {
                        payBtn.disabled = false;
                        payBtn.textContent = 'Try Again ₹%1$d';
                        statusEl.textContent = response.error.description || 'Payment failed. Please try again.';
                        statusEl.className = 'status-msg error';
                      });
                      rzp.open();
                    } catch (e) {
                      payBtn.disabled = false;
                      payBtn.textContent = 'Pay ₹%1$d';
                      statusEl.textContent = 'Could not open payment. Check your connection and try again.';
                      statusEl.className = 'status-msg error';
                    }
                  }
                </script>
                """.formatted(
                order.amountRupees(),                            // 1 — amount
                esc(order.note()),                               // 2 — note
                esc(order.payeeName()),                          // 3 — payee name
                esc(order.orderId()),                            // 4 — internal order ID
                testMode ? "This is a Razorpay TEST mode transaction. " : "",  // 5 — test label
                esc(rzpKeyId),                                   // 6 — Razorpay key ID
                order.amountRupees() * 100,                      // 7 — amount in paise
                esc(rzpOrderId),                                 // 8 — Razorpay order ID
                esc(order.note()),                               // 9 — description
                esc(baseUrl)                                     // 10 — base URL
        );
    }

    private static String paidBlock(PaymentOrder order) {
        return """
                <div role="status" aria-live="polite">
                  <p class="tick" aria-hidden="true">✓</p>
                  <h1>Payment successful</h1>
                  <p class="amount small-amount">₹%1$d paid</p>
                </div>
                <dl>
                  <dt>Paid to</dt><dd>%2$s</dd>
                  <dt>Method</dt><dd>%3$s</dd>
                  <dt>Bank reference</dt><dd class="mono">%4$s</dd>
                  <dt>Transaction</dt><dd class="mono">%5$s</dd>
                  <dt>Order</dt><dd class="mono">%6$s</dd>
                </dl>
                <p>You can return to the Cab Eye app. It will announce the payment.</p>
                """.formatted(order.amountRupees(), esc(order.payeeName()), esc(order.method()),
                esc(order.bankRef()), esc(order.gatewayTxnId()), esc(order.orderId()));
    }

    private static String failedBlock(PaymentOrder order, String reason) {
        String msg = reason != null && !reason.isBlank() ? reason : order.failureReason();
        return """
                <div role="status" aria-live="polite">
                  <p class="cross" aria-hidden="true">✕</p>
                  <h1>Payment not completed</h1>
                  <p>%1$s</p>
                </div>
                <p>No money was taken. Return to the Cab Eye app and tap Try again.</p>
                <p class="small mono">Order %2$s</p>
                """.formatted(esc(msg.isBlank() ? "The payment was not completed." : msg),
                esc(order.orderId()));
    }

    private static String page(String title, String body, boolean testMode) {
        String banner = testMode
                ? "<div class=\"banner test\" role=\"note\">RAZORPAY TEST MODE</div>"
                : "<div class=\"banner live\" role=\"note\">SECURE PAYMENT</div>";
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%1$s</title>
                <style>
                  :root { color-scheme: dark; }
                  body { margin:0; font-family: system-ui, sans-serif; background:#0b0b0f; color:#f4f4f5;
                         font-size:20px; line-height:1.4; }
                  .banner { font-weight:700; text-align:center; padding:10px; letter-spacing:.08em; }
                  .banner.test { background:#facc15; color:#111; }
                  .banner.live { background:#22c55e; color:#04130a; }
                  main { max-width:480px; margin:0 auto; padding:20px 16px 40px; }
                  .brand { color:#a1a1aa; margin:0 0 16px; }
                  .label { color:#a1a1aa; margin:8px 0 0; }
                  .amount { font-size:64px; margin:0; line-height:1.1; }
                  .small-amount { font-size:36px; }
                  .note { color:#d4d4d8; margin:4px 0 16px; }
                  dl { display:grid; grid-template-columns:auto 1fr; gap:6px 16px; margin:16px 0; }
                  dt { color:#a1a1aa; } dd { margin:0; word-break:break-all; }
                  .mono { font-family: ui-monospace, monospace; font-size:17px; }
                  button { display:block; width:100%%; min-height:64px; border:0; border-radius:16px;
                           font-size:24px; font-weight:700; margin-top:14px; cursor:pointer;
                           transition: opacity 0.2s; }
                  button:disabled { opacity:0.5; cursor:wait; }
                  button:focus-visible { outline:4px solid #facc15; outline-offset:3px; }
                  .pay { background:#22c55e; color:#04130a; }
                  .tick { font-size:72px; color:#22c55e; margin:0; }
                  .cross { font-size:72px; color:#f87171; margin:0; }
                  .small { color:#a1a1aa; font-size:16px; }
                  .status-msg { color:#a1a1aa; font-size:16px; min-height:24px; margin:8px 0; }
                  .status-msg.error { color:#f87171; }
                </style>
                </head>
                <body>
                %2$s
                <main>
                <p class="brand">Cab Eye Pay</p>
                %3$s
                </main>
                </body>
                </html>
                """.formatted(esc(title), banner, body);
    }

    /** HTML entity escaping — same as {@link CheckoutPage#esc}. */
    static String esc(String s) {
        return CheckoutPage.esc(s);
    }
}
