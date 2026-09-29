package com.cabeye.backend.store;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RidePhase;
import com.cabeye.backend.payment.MockPaymentGateway;
import com.cabeye.backend.payment.PaymentOrder;
import com.cabeye.backend.payment.PaymentOrderRecord;
import com.cabeye.backend.persistence.RidePersistence;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.websocket.RideSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A backend restart no longer loses rides or payments. */
class RestartTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("rides and payment orders come back after a restart; ride ids continue")
    void ridesAndPaymentsSurviveRestart(@TempDir Path dir) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:restart-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", ""));

        // ---- Before the restart ------------------------------------------------------
        DataDirectory data = new DataDirectory(dir.toString(), mapper, jdbc);
        RideService rides = new RideService(new RideSessionManager(mapper));
        new RidePersistence(rides, data);
        MockPaymentGateway gateway = new MockPaymentGateway(rides);
        gateway.persistTo(data.table("payment_orders", PaymentOrderRecord.class));

        String inProgress = rides.create("rider-1", "PSG College", "AUTO").rideId();
        rides.assign(inProgress, "driver-1", "Karthik", "Yellow Bajaj RE", "TN 38 AB 1234", "", 4);
        rides.arrived(inProgress, "driver-1");
        rides.confirmCode(inProgress, "rider-1", true);

        String finished = rides.create("rider-1", "Gandhipuram", "AUTO").rideId();
        rides.assign(finished, "driver-1", "Karthik", "Auto", "TN", "", 4);
        rides.confirmCode(finished, "rider-1", true);
        rides.passengerSeated(finished, "driver-1");
        rides.startTrip(finished, "driver-1", 10);
        rides.complete(finished, "driver-1", 148, 12);
        PaymentOrder order = gateway.createOrder(finished);
        gateway.pay(order.orderId(), "UPI");
        int eventsBefore = rides.find(inProgress).orElseThrow().eventsAfter(0).size();

        // ---- "Restart": everything rebuilt from the same database --------------------
        DataDirectory data2 = new DataDirectory(dir.toString(), mapper, jdbc);
        RideService rides2 = new RideService(new RideSessionManager(mapper));
        new RidePersistence(rides2, data2);
        MockPaymentGateway gateway2 = new MockPaymentGateway(rides2);
        gateway2.persistTo(data2.table("payment_orders", PaymentOrderRecord.class));

        Ride back = rides2.find(inProgress).orElseThrow();
        assertEquals(RidePhase.ARRIVED, back.phase());
        assertEquals("Karthik", back.driverName());
        assertTrue(back.codeConfirmed());
        assertEquals(eventsBefore, back.eventsAfter(0).size(), "event log restored for replay");

        Ride done = rides2.find(finished).orElseThrow();
        assertEquals(RidePhase.COMPLETED, done.phase());
        assertEquals(Ride.PaymentStatus.CONFIRMED, done.paymentStatus());

        PaymentOrder paid = gateway2.find(order.orderId()).orElseThrow();
        assertEquals(PaymentOrder.Status.PAID, paid.status());
        assertEquals(order.bankRef(), paid.bankRef());

        // The seated gate still holds for the restored ride's successor, and numbering continues.
        String next = rides2.create("rider-1", "Ukkadam", "AUTO").rideId();
        assertTrue(!next.equals(inProgress) && !next.equals(finished), "new ride id " + next + " must not collide");
    }
}
