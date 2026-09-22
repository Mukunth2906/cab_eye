package com.cabeye.backend.payment;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One attempt to collect the fare for one ride, held in memory by {@link MockPaymentGateway}.
 *
 * <p>Modelled on how real Indian gateways shape an order (create an order for an amount,
 * hand the payer a checkout, learn the outcome from the gateway, never from the payer's
 * phone), so that swapping the mock for a real provider later changes the gateway class and
 * nothing that talks to it.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   CREATED ──(checkout page opened)──▶ PENDING ──▶ PAID
 *      │                                   │
 *      └───────────────┬───────────────────┴──▶ FAILED
 *                      └──(TTL elapsed)────────▶ EXPIRED
 * </pre>
 * PAID, FAILED and EXPIRED are final. A final order is never reopened; a retry is a new order,
 * which is what keeps "which attempt actually took the money" a question with one answer.
 */
public class PaymentOrder {

    public enum Status {
        CREATED, PENDING, PAID, FAILED, EXPIRED;

        public boolean isFinal() {
            return this == PAID || this == FAILED || this == EXPIRED;
        }
    }

    private final String orderId;
    private final String rideId;
    private final int amountRupees;
    private final String payeeVpa;
    private final String payeeName;
    private final String note;
    private final Instant createdAt;
    private final Instant expiresAt;

    private Status status = Status.CREATED;
    private String method = "";
    private String gatewayTxnId = "";
    /** 12-digit bank reference (the "UTR" a UPI receipt shows). Mock-generated. */
    private String bankRef = "";
    private String failureReason = "";
    private Instant paidAt;

    public PaymentOrder(String orderId, String rideId, int amountRupees, String payeeVpa,
                        String payeeName, String note, Instant createdAt, Instant expiresAt) {
        this.orderId = orderId;
        this.rideId = rideId;
        this.amountRupees = amountRupees;
        this.payeeVpa = payeeVpa;
        this.payeeName = payeeName;
        this.note = note;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    // -----------------------------------------------------------------------------------
    //  Transitions. Each returns false when the move is not legal from the current state,
    //  so the gateway decides what a refusal means rather than this class throwing.
    // -----------------------------------------------------------------------------------

    synchronized boolean markOpened() {
        if (status != Status.CREATED) return false;
        status = Status.PENDING;
        return true;
    }

    synchronized boolean markPaid(String method, String gatewayTxnId, String bankRef, Instant at) {
        if (status.isFinal()) return false;
        this.status = Status.PAID;
        this.method = method == null ? "" : method;
        this.gatewayTxnId = gatewayTxnId == null ? "" : gatewayTxnId;
        this.bankRef = bankRef == null ? "" : bankRef;
        this.paidAt = at;
        return true;
    }

    synchronized boolean markFailed(String method, String reason) {
        if (status.isFinal()) return false;
        this.status = Status.FAILED;
        this.method = method == null ? "" : method;
        this.failureReason = reason == null || reason.isBlank() ? "Payment declined" : reason;
        return true;
    }

    /** @return true when this call moved the order to EXPIRED */
    synchronized boolean expireIfDue(Instant now) {
        if (status.isFinal() || now.isBefore(expiresAt)) return false;
        status = Status.EXPIRED;
        failureReason = "Payment window expired";
        return true;
    }

    // -----------------------------------------------------------------------------------
    //  Accessors
    // -----------------------------------------------------------------------------------

    public String orderId()       { return orderId; }
    public String rideId()        { return rideId; }
    public int amountRupees()     { return amountRupees; }
    public String payeeVpa()      { return payeeVpa; }
    public String payeeName()     { return payeeName; }
    public String note()          { return note; }
    public Instant createdAt()    { return createdAt; }
    public Instant expiresAt()    { return expiresAt; }
    public synchronized Status status()        { return status; }
    public synchronized String method()        { return method; }
    public synchronized String gatewayTxnId()  { return gatewayTxnId; }
    public synchronized String bankRef()       { return bankRef; }
    public synchronized String failureReason() { return failureReason; }
    public synchronized Instant paidAt()       { return paidAt; }

    /**
     * What clients see. {@code checkoutUrl} is absolute and built from the address the caller
     * used to reach this server, so a phone on the LAN gets a URL it can open (and a QR code
     * another phone can scan) without the server having to know its own IP.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(
            String orderId,
            String rideId,
            int amountRupees,
            String currency,
            String status,
            String method,
            String gatewayTxnId,
            String bankRef,
            String failureReason,
            String payeeVpa,
            String payeeName,
            String upiUri,
            String checkoutUrl,
            Instant createdAt,
            Instant expiresAt,
            Instant paidAt,
            boolean testMode
    ) {}

    public synchronized View view(String upiUri, String checkoutUrl) {
        return new View(orderId, rideId, amountRupees, "INR", status.name(), method,
                gatewayTxnId, bankRef, failureReason, payeeVpa, payeeName, upiUri, checkoutUrl,
                createdAt, expiresAt, paidAt, true);
    }
}
