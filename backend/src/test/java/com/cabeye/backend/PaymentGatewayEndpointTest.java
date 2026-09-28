package com.cabeye.backend;

import com.cabeye.backend.service.RideService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end over HTTP: finished ride → order → checkout page → pay → ride CONFIRMED.
 * Exactly the path the rider's app, the QR code and the sandbox page take on a real device.
 */
@SpringBootTest(properties = "cabeye.data.dir=build/test-data/gateway-${random.uuid}")
@AutoConfigureMockMvc
class PaymentGatewayEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired RideService rides;

    private String completedRide(int fare) {
        String id = rides.create("rider-it", "Gandhipuram", "AUTO").rideId();
        rides.assign(id, "driver-it", "Karthik", "Auto", "TN 37 BX 4412", "+910000000000", 5);
        rides.confirmCode(id, "rider", true);
        rides.passengerSeated(id, "driver-it");
        rides.startTrip(id, "driver-it", 12);
        rides.complete(id, "driver-it", fare, 12);
        return id;
    }

    private String createOrder(String rideId) throws Exception {
        String json = mvc.perform(post("/rides/" + rideId + "/payment/order")
                        .header("Host", "192.168.1.10:8080"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.orderId");
    }

    @Test
    @DisplayName("order carries amount, UPI link and a checkout URL on the caller's own address")
    void createOrder() throws Exception {
        String rideId = completedRide(148);
        mvc.perform(post("/rides/" + rideId + "/payment/order").header("Host", "192.168.1.10:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountRupees").value(148))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.testMode").value(true))
                .andExpect(jsonPath("$.upiUri", containsString("am=148.00")))
                .andExpect(jsonPath("$.checkoutUrl", startsWith("http://192.168.1.10:8080/pay/order_")));
    }

    @Test
    @DisplayName("an unfinished ride cannot be paid for yet (409)")
    void unfinishedRide() throws Exception {
        String rideId = rides.create("rider-it", "Gandhipuram", "AUTO").rideId();
        mvc.perform(post("/rides/" + rideId + "/payment/order"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("checkout page renders, marks the order PENDING, and paying confirms the ride")
    void checkoutPageFlow() throws Exception {
        String rideId = completedRide(210);
        String orderId = createOrder(rideId);

        mvc.perform(get("/pay/" + orderId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TEST MODE")))
                .andExpect(content().string(containsString("₹210")));

        mvc.perform(get("/payments/" + orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(post("/pay/" + orderId).param("action", "pay").param("method", "CARD"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment successful")));

        mvc.perform(get("/payments/" + orderId))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.method").value("CARD"))
                .andExpect(jsonPath("$.bankRef", matchesPattern("[1-9][0-9]{11}")));

        mvc.perform(get("/rides/" + rideId + "/payment"))
                .andExpect(jsonPath("$.paymentStatus").value("CONFIRMED"));
    }

    @Test
    @DisplayName("declining on the checkout page fails the ride's payment; a retry gets a new order")
    void declineThenRetry() throws Exception {
        String rideId = completedRide(90);
        String first = createOrder(rideId);

        mvc.perform(post("/pay/" + first).param("action", "fail"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment not completed")));
        mvc.perform(get("/rides/" + rideId + "/payment"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));

        String second = createOrder(rideId);
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);

        mvc.perform(post("/payments/" + second + "/simulate")
                        .contentType("application/json").content("{\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(get("/rides/" + rideId + "/payment"))
                .andExpect(jsonPath("$.paymentStatus").value("CONFIRMED"));
    }

    @Test
    @DisplayName("a paid ride refuses a second order (409)")
    void noDoubleCharge() throws Exception {
        String rideId = completedRide(148);
        String orderId = createOrder(rideId);
        mvc.perform(post("/payments/" + orderId + "/simulate")
                .contentType("application/json").content("{\"outcome\":\"SUCCESS\"}"));
        mvc.perform(post("/rides/" + rideId + "/payment/order"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("unknown order: JSON 404 and an HTML not-found page")
    void unknownOrder() throws Exception {
        mvc.perform(get("/payments/order_nope")).andExpect(status().isNotFound());
        mvc.perform(get("/pay/order_nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Payment not found")));
    }
}
