package com.cabeye.backend.persistence;

import com.cabeye.backend.payment.MockPaymentGateway;
import com.cabeye.backend.payment.PaymentOrderRecord;
import com.cabeye.backend.store.DataDirectory;
import org.springframework.stereotype.Component;

/** Connects the payment gateway to the database, so orders and receipts survive a restart. */
@Component
public class PaymentPersistence {

    public PaymentPersistence(MockPaymentGateway gateway, DataDirectory data) {
        gateway.persistTo(data.table("payment_orders", PaymentOrderRecord.class));
    }
}
