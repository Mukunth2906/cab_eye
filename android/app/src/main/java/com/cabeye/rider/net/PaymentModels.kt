package com.cabeye.rider.net

import org.json.JSONObject

/**
 * One payment order as the backend's sandbox gateway describes it.
 *
 * Mirrors `PaymentOrder.View` on the server. The app never decides that a payment succeeded:
 * it reads [status] from here, which only the gateway can move to PAID.
 *
 * @param checkoutUrl absolute URL of the sandbox checkout, built by the server from the address
 *   this phone used to reach it — so it opens from this phone and from any phone that scans
 *   the QR code on the same network
 * @param upiUri standard `upi://pay` link for the "pay with my UPI app" path
 * @param bankRef the 12-digit bank reference, present once PAID
 */
data class PaymentOrder(
    val orderId: String,
    val rideId: String,
    val amountRupees: Int,
    val status: PaymentOrderStatus,
    val method: String,
    val bankRef: String,
    val gatewayTxnId: String,
    val failureReason: String,
    val upiUri: String,
    val checkoutUrl: String,
    val testMode: Boolean,
    /** Razorpay order ID, present when the server uses Razorpay. Empty for sandbox mock. */
    val razorpayOrderId: String = ""
) {
    /** True when the server is using the real Razorpay gateway for this order. */
    val isRazorpay: Boolean get() = razorpayOrderId.isNotBlank()

    companion object {
        fun parse(json: String): PaymentOrder? = runCatching {
            val o = JSONObject(json)
            val id = o.optString("orderId")
            if (id.isBlank()) return@runCatching null
            PaymentOrder(
                orderId = id,
                rideId = o.optString("rideId"),
                amountRupees = o.optInt("amountRupees", 0),
                status = PaymentOrderStatus.parse(o.optString("status")),
                method = o.optString("method"),
                bankRef = o.optString("bankRef"),
                gatewayTxnId = o.optString("gatewayTxnId"),
                failureReason = o.optString("failureReason"),
                upiUri = o.optString("upiUri"),
                checkoutUrl = o.optString("checkoutUrl"),
                testMode = o.optBoolean("testMode", true),
                razorpayOrderId = o.optString("razorpayOrderId", "")
            )
        }.getOrNull()

        /** The server's `{"error": "..."}` body, already phrased to be spoken. */
        fun errorMessage(body: String): String? = runCatching {
            JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

enum class PaymentOrderStatus {
    CREATED, PENDING, PAID, FAILED, EXPIRED, UNKNOWN;

    val isFinal: Boolean get() = this == PAID || this == FAILED || this == EXPIRED

    companion object {
        fun parse(raw: String?): PaymentOrderStatus =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: UNKNOWN
    }
}

/**
 * What a UPI app returned in the `response` extra, e.g.
 * `txnId=AXI123&responseCode=00&Status=SUCCESS&txnRef=order_abc`.
 *
 * Keys are matched case-insensitively because UPI apps disagree on `Status` vs `status`.
 * This is a *claim* from another app on the same phone and is never treated as payment.
 */
data class UpiResponse(val status: String, val txnRef: String) {
    companion object {
        fun parse(response: String?): UpiResponse {
            val fields = response.orEmpty()
                .split('&')
                .mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { it[0].trim().lowercase() to it[1].trim() }
            return UpiResponse(
                status = fields["status"]?.uppercase().orEmpty(),
                txnRef = fields["txnref"] ?: fields["txnid"] ?: ""
            )
        }
    }
}

/** Last four characters, for speaking a reference without reading out twelve digits. */
fun String.lastFourSpoken(): String =
    takeLast(4).toCharArray().joinToString(" ")
