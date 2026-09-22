package com.cabeye.backend.payment;

/**
 * The sandbox checkout, rendered as one self-contained HTML page.
 *
 * <p>Plain server-rendered HTML with no scripts and no external assets: it has to open on any
 * phone on the LAN (including one that just scanned the QR code), and it has to work with a
 * screen reader — native form controls, a real {@code <h1>}, large targets, and an
 * {@code aria-live} status. It is labelled TEST MODE everywhere, and it imitates no real bank
 * or payment app.
 */
public final class CheckoutPage {

    private CheckoutPage() {}

    public static String checkout(PaymentOrder order) {
        String body = switch (order.status()) {
            case CREATED, PENDING -> payForm(order);
            case PAID -> paidBlock(order);
            case FAILED, EXPIRED -> failedBlock(order);
        };
        return page("Pay ₹" + order.amountRupees() + " — Cab Eye (test)", body);
    }

    public static String result(PaymentOrder order) {
        return page(order.status() == PaymentOrder.Status.PAID
                ? "Payment successful — Cab Eye (test)"
                : "Payment not completed — Cab Eye (test)", switch (order.status()) {
            case PAID -> paidBlock(order);
            case FAILED, EXPIRED -> failedBlock(order);
            default -> payForm(order);
        });
    }

    public static String notFound(String message) {
        return page("Payment not found — Cab Eye (test)",
                "<h1>Payment not found</h1><p role=\"status\">" + esc(message) + "</p>");
    }

    // ---------------------------------------------------------------------------------

    private static String payForm(PaymentOrder order) {
        String id = esc(order.orderId());
        return """
                <p class="label">Amount to pay</p>
                <h1 class="amount" aria-label="%1$d rupees">₹%1$d</h1>
                <p class="note">%2$s</p>
                <dl>
                  <dt>Paying</dt><dd>%3$s</dd>
                  <dt>Order</dt><dd class="mono">%4$s</dd>
                </dl>
                <form method="post" action="/pay/%4$s">
                  <fieldset>
                    <legend>Choose how to pay (simulated)</legend>
                    <label><input type="radio" name="method" value="UPI" checked> UPI</label>
                    <label><input type="radio" name="method" value="CARD"> Debit or credit card</label>
                    <label><input type="radio" name="method" value="NETBANKING"> Net banking</label>
                  </fieldset>
                  <button type="submit" name="action" value="pay" class="pay">Pay ₹%1$d</button>
                  <button type="submit" name="action" value="fail" class="fail">Decline payment</button>
                </form>
                <p class="small">This is a sandbox. No money moves and no bank is contacted.</p>
                """.formatted(order.amountRupees(), esc(order.note()), esc(order.payeeName()), id);
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

    private static String failedBlock(PaymentOrder order) {
        return """
                <div role="status" aria-live="polite">
                  <p class="cross" aria-hidden="true">✕</p>
                  <h1>Payment not completed</h1>
                  <p>%1$s</p>
                </div>
                <p>No money was taken. Return to the Cab Eye app and tap Try again.</p>
                <p class="small mono">Order %2$s</p>
                """.formatted(esc(order.failureReason()), esc(order.orderId()));
    }

    private static String page(String title, String body) {
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
                  .banner { background:#facc15; color:#111; font-weight:700; text-align:center;
                            padding:10px; letter-spacing:.08em; }
                  main { max-width:480px; margin:0 auto; padding:20px 16px 40px; }
                  .brand { color:#a1a1aa; margin:0 0 16px; }
                  .label { color:#a1a1aa; margin:8px 0 0; }
                  .amount { font-size:64px; margin:0; line-height:1.1; }
                  .small-amount { font-size:36px; }
                  .note { color:#d4d4d8; margin:4px 0 16px; }
                  dl { display:grid; grid-template-columns:auto 1fr; gap:6px 16px; margin:16px 0; }
                  dt { color:#a1a1aa; } dd { margin:0; word-break:break-all; }
                  .mono { font-family: ui-monospace, monospace; font-size:17px; }
                  fieldset { border:2px solid #3f3f46; border-radius:14px; margin:16px 0; padding:12px 16px; }
                  legend { padding:0 6px; color:#a1a1aa; }
                  label { display:flex; align-items:center; gap:12px; min-height:48px; }
                  input[type=radio] { width:24px; height:24px; }
                  button { display:block; width:100%%; min-height:64px; border:0; border-radius:16px;
                           font-size:24px; font-weight:700; margin-top:14px; cursor:pointer; }
                  button:focus-visible { outline:4px solid #facc15; outline-offset:3px; }
                  .pay { background:#22c55e; color:#04130a; }
                  .fail { background:transparent; color:#f87171; border:3px solid #f87171; }
                  .tick { font-size:72px; color:#22c55e; margin:0; }
                  .cross { font-size:72px; color:#f87171; margin:0; }
                  .small { color:#a1a1aa; font-size:16px; }
                </style>
                </head>
                <body>
                <div class="banner" role="note">TEST MODE · NO REAL MONEY</div>
                <main>
                <p class="brand">Cab Eye Pay · sandbox</p>
                %2$s
                </main>
                </body>
                </html>
                """.formatted(esc(title), body);
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
