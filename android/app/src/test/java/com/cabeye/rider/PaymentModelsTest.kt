package com.cabeye.rider

import com.cabeye.rider.net.PaymentOrder
import com.cabeye.rider.net.PaymentOrderStatus
import com.cabeye.rider.net.RideEventType
import com.cabeye.rider.net.UpiResponse
import com.cabeye.rider.net.lastFourSpoken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payment wire format and the UPI-response parser. Runs on the JVM with the real org.json
 * (see build.gradle), so these are the same parses the device performs.
 */
class PaymentModelsTest {

    private val orderJson = """
        {"orderId":"order_abc123","rideId":"ride-1001","amountRupees":148,"currency":"INR",
         "status":"PENDING","method":"","gatewayTxnId":"","bankRef":"","failureReason":"",
         "payeeVpa":"cabeye@sandbox","payeeName":"Cab Eye",
         "upiUri":"upi://pay?pa=cabeye%40sandbox&am=148.00&cu=INR&tr=order_abc123",
         "checkoutUrl":"http://192.168.1.10:8080/pay/order_abc123",
         "createdAt":"2026-09-22T10:00:00Z","expiresAt":"2026-09-22T10:10:00Z","testMode":true}
    """.trimIndent()

    @Test
    fun parsesAnOpenOrder() {
        val order = PaymentOrder.parse(orderJson)!!
        assertEquals("order_abc123", order.orderId)
        assertEquals(148, order.amountRupees)
        assertEquals(PaymentOrderStatus.PENDING, order.status)
        assertEquals("http://192.168.1.10:8080/pay/order_abc123", order.checkoutUrl)
        assertTrue(order.upiUri.startsWith("upi://pay"))
        assertTrue(order.testMode)
        assertFalse(order.status.isFinal)
    }

    @Test
    fun parsesAPaidOrder() {
        val paid = orderJson
            .replace("\"status\":\"PENDING\"", "\"status\":\"PAID\"")
            .replace("\"bankRef\":\"\"", "\"bankRef\":\"412345678901\"")
        val order = PaymentOrder.parse(paid)!!
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals("412345678901", order.bankRef)
        assertTrue(order.status.isFinal)
    }

    @Test
    fun rejectsGarbageAndOrdersWithoutAnId() {
        assertNull(PaymentOrder.parse("not json"))
        assertNull(PaymentOrder.parse("{\"status\":\"PAID\"}"))
    }

    @Test
    fun unknownStatusIsUnknownNotPaid() {
        assertEquals(PaymentOrderStatus.UNKNOWN, PaymentOrderStatus.parse("SETTLED"))
        assertEquals(PaymentOrderStatus.UNKNOWN, PaymentOrderStatus.parse(null))
        assertEquals(PaymentOrderStatus.PAID, PaymentOrderStatus.parse(" paid "))
    }

    @Test
    fun readsTheServersSpokenError() {
        assertEquals("This ride is already paid.",
            PaymentOrder.errorMessage("{\"error\":\"This ride is already paid.\"}"))
        assertNull(PaymentOrder.errorMessage(""))
        assertNull(PaymentOrder.errorMessage("{\"other\":1}"))
    }

    @Test
    fun parsesUpiResponsesWhateverTheKeyCase() {
        val a = UpiResponse.parse("txnId=AXI1&responseCode=00&Status=SUCCESS&txnRef=order_abc")
        assertEquals("SUCCESS", a.status)
        assertEquals("order_abc", a.txnRef)

        val b = UpiResponse.parse("status=failure&txnid=T9")
        assertEquals("FAILURE", b.status)
        assertEquals("T9", b.txnRef)

        val none = UpiResponse.parse(null)
        assertEquals("", none.status)
        assertEquals("", none.txnRef)
    }

    @Test
    fun speaksOnlyTheLastFourOfAReference() {
        assertEquals("8 9 0 1", "412345678901".lastFourSpoken())
    }

    @Test
    fun paymentEventIsKnownButNotALifecyclePhase() {
        assertEquals(RideEventType.PAYMENT_UPDATED, RideEventType.parse("PAYMENT_UPDATED"))
        assertFalse(RideEventType.PAYMENT_UPDATED.isRideLifecycle)
        assertTrue(RideEventType.RIDE_CANCELLED.isRideLifecycle)
    }
}
