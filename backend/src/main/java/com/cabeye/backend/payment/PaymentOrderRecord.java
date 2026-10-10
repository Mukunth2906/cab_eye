package com.cabeye.backend.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** A payment order as saved in the database. See {@link PaymentOrder#toRecord()}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentOrderRecord {
    public String orderId;
    public String rideId;
    public int amountRupees;
    public String payeeVpa;
    public String payeeName;
    public String note;
    public Instant createdAt;
    public Instant expiresAt;
    public String status;
    public String method;
    public String gatewayTxnId;
    public String bankRef;
    public String failureReason;
    public Instant paidAt;
    /** Razorpay order ID (starts with order_), or empty when using the sandbox mock. */
    public String razorpayOrderId;

    public PaymentOrderRecord() {}
}
