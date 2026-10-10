package com.cabeye.backend;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.service.RideService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests over the real payment endpoints.
 *
 * <p>The important assertion is in {@link #successClaimDoesNotConfirm()}: posting
 * {@code Status=SUCCESS} — exactly what a UPI app returns — must NOT produce CONFIRMED. If that
 * test ever goes green with "CONFIRMED", the app has started trusting the client about money.
 */
@SpringBootTest(properties = "cabeye.data.dir=build/test-data/payment-${random.uuid}")
@AutoConfigureMockMvc
class PaymentEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired RideService rides;

    private String bookRide() {
        Ride ride = rides.create("rider-test", "Gandhipuram", "AUTO");
        return ride.rideId();
    }

    @Test
    @DisplayName("a fresh ride reports NONE")
    void freshRideHasNoPayment() throws Exception {
        String id = bookRide();
        mvc.perform(get("/rides/" + id + "/payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("NONE"));
    }

    @Test
    @DisplayName("a SUCCESS claim from the phone becomes REPORTED, never CONFIRMED")
    void successClaimDoesNotConfirm() throws Exception {
        String id = bookRide();

        mvc.perform(post("/rides/" + id + "/payment")
                        .contentType("application/json")
                        .content("{\"status\":\"SUCCESS\",\"txnRef\":\"TXN999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("REPORTED"))
                .andExpect(jsonPath("$.paymentRef").value("TXN999"));
    }

    @Test
    @DisplayName("SUBMITTED is also only REPORTED")
    void submittedIsReported() throws Exception {
        String id = bookRide();

        mvc.perform(post("/rides/" + id + "/payment")
                        .contentType("application/json")
                        .content("{\"status\":\"SUBMITTED\"}"))
                .andExpect(jsonPath("$.paymentStatus").value("REPORTED"));
    }

    @Test
    @DisplayName("a FAILURE claim is trusted immediately")
    void failureIsTrusted() throws Exception {
        String id = bookRide();

        mvc.perform(post("/rides/" + id + "/payment")
                        .contentType("application/json")
                        .content("{\"status\":\"FAILURE\"}"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));
    }

    @Test
    @DisplayName("only the confirm endpoint — standing in for the PSP webhook — reaches CONFIRMED")
    void onlyWebhookConfirms() throws Exception {
        String id = bookRide();

        mvc.perform(post("/rides/" + id + "/payment")
                        .contentType("application/json")
                        .content("{\"status\":\"SUCCESS\"}"))
                .andExpect(jsonPath("$.paymentStatus").value("REPORTED"));

        mvc.perform(post("/rides/" + id + "/payment/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("CONFIRMED"));
    }

    @Test
    @DisplayName("an unknown ride is a 404, not a silent success")
    void unknownRideIs404() throws Exception {
        mvc.perform(get("/rides/ride-does-not-exist/payment"))
                .andExpect(status().isNotFound());
    }
}
