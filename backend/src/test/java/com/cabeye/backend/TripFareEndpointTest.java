package com.cabeye.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-time km and a server-computed fare: GPS fixes during the trip move the meter, and
 * {@code /complete} prices the trip from what was measured — never from what the phone says.
 * Uses the default PLACEHOLDER rates (AUTO: 40 + 15/km + 1/min, minimum 50; CAB: 60 + 18/km +
 * 1.5/min, minimum 80).
 */
@SpringBootTest(properties = "cabeye.data.dir=build/test-data/fare-${random.uuid}")
@AutoConfigureMockMvc
class TripFareEndpointTest {

    private static final AtomicInteger NEXT = new AtomicInteger(3000);
    /** 0.01 degrees of latitude is about 1,112 m. */
    private static final double STEP = 0.01;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String signIn(String role) throws Exception {
        String phone = "96000" + String.format("%05d", NEXT.incrementAndGet());
        String code = body(mvc.perform(post("/auth/otp").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\"}"))).get("devCode").asText();
        return body(mvc.perform(post("/auth/verify").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\",\"code\":\"" + code
                        + "\",\"name\":\"T\"}"))).get("token").asText();
    }

    /** Books (with pickup and destination about 5.2 km apart) and drives the ride into IN_TRIP. */
    private String rideInTrip(String rider, String driver, String rideType) throws Exception {
        String id = body(mvc.perform(post("/rides").header("Authorization", "Bearer " + rider)
                .contentType("application/json")
                .content("{\"destination\":\"Gandhipuram\",\"rideType\":\"" + rideType + "\","
                        + "\"pickupLatitude\":11.0247,\"pickupLongitude\":77.0027,"
                        + "\"destinationLatitude\":11.0168,\"destinationLongitude\":76.9558}")))
                .get("rideId").asText();
        String auth = "Bearer " + driver;
        mvc.perform(post("/rides/" + id + "/accept").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(post("/rides/" + id + "/arrived").header("Authorization", auth));
        mvc.perform(post("/rides/" + id + "/code").header("Authorization", "Bearer " + rider)
                .contentType("application/json").content("{\"matched\":true}"));
        mvc.perform(post("/rides/" + id + "/seated").header("Authorization", auth));
        mvc.perform(post("/rides/" + id + "/start").header("Authorization", auth)).andExpect(status().isOk());
        return id;
    }

    /** Sends {@code legs + 1} fixes, one minute apart, each 0.01 degrees further north. */
    private JsonNode drive(String id, String driver, int legs) throws Exception {
        long t0 = System.currentTimeMillis() - (legs + 1) * 60_000L;
        JsonNode last = null;
        for (int i = 0; i <= legs; i++) {
            last = body(mvc.perform(post("/rides/" + id + "/trip-location").header("Authorization", "Bearer " + driver)
                            .contentType("application/json")
                            .content("{\"lat\":" + (11.0247 + STEP * i) + ",\"lng\":77.0027,\"at\":" + (t0 + i * 60_000L) + "}"))
                    .andExpect(status().isOk()));
        }
        return last;
    }

    @Test
    @DisplayName("GPS fixes during the trip move the live km and live fare")
    void liveMeter() throws Exception {
        String rider = signIn("RIDER");
        String driver = signIn("DRIVER");
        String id = rideInTrip(rider, driver, "AUTO");

        JsonNode snap = drive(id, driver, 5);
        assertEquals(5560, snap.get("tripDistanceMeters").asInt(), 10.0);
        assertEquals("GPS", snap.get("distanceSource").asText());
        assertTrue(snap.get("liveFareRupees").asInt() > 50, "a live fare is shown");

        JsonNode events = body(mvc.perform(get("/rides/" + id + "/events")));
        int progress = 0;
        for (JsonNode e : events) if ("TRIP_PROGRESS".equals(e.get("type").asText())) progress++;
        assertEquals(5, progress, "one TRIP_PROGRESS per leg (each leg is more than 100 m)");
    }

    @Test
    @DisplayName("the server prices the trip; a fare sent by the phone is ignored")
    void serverPricesTheTrip() throws Exception {
        String rider = signIn("RIDER");
        String driver = signIn("DRIVER");
        String id = rideInTrip(rider, driver, "AUTO");
        drive(id, driver, 5);

        JsonNode done = body(mvc.perform(post("/rides/" + id + "/complete").header("Authorization", "Bearer " + driver)
                        .contentType("application/json").content("{\"fareRupees\":148,\"durationMinutes\":12}"))
                .andExpect(status().isOk()));
        int metres = done.get("tripDistanceMeters").asInt();
        int minutes = done.get("durationMinutes").asInt();
        int expected = Math.max(50, (int) Math.round(40 + 15 * metres / 1000.0 + minutes));
        assertEquals("COMPLETED", done.get("phase").asText());
        assertEquals(expected, done.get("fareRupees").asInt());
        assertNotEquals(148, done.get("fareRupees").asInt());
        assertTrue(minutes >= 1, "the trip's minutes are measured");

        // The payment order charges exactly that fare.
        JsonNode order = body(mvc.perform(post("/rides/" + id + "/payment/order").header("Authorization", "Bearer " + rider))
                .andExpect(status().isOk()));
        assertEquals(expected, order.get("amountRupees").asInt());
    }

    @Test
    @DisplayName("with no GPS at all the distance is the straight line from pickup to destination, marked ESTIMATE")
    void estimateWithoutGps() throws Exception {
        String rider = signIn("RIDER");
        String driver = signIn("DRIVER");
        String id = rideInTrip(rider, driver, "AUTO");
        JsonNode done = body(mvc.perform(post("/rides/" + id + "/complete").header("Authorization", "Bearer " + driver))
                .andExpect(status().isOk()));
        assertEquals("ESTIMATE", done.get("distanceSource").asText());
        int metres = done.get("tripDistanceMeters").asInt();
        assertTrue(metres > 5000 && metres < 5300, "straight line is about 5.2 km, was " + metres);
    }

    @Test
    @DisplayName("a CAB is priced with the cab rates")
    void cabRates() throws Exception {
        String rider = signIn("RIDER");
        String driver = signIn("DRIVER");
        String id = rideInTrip(rider, driver, "CAB");
        JsonNode done = body(mvc.perform(post("/rides/" + id + "/complete").header("Authorization", "Bearer " + driver))
                .andExpect(status().isOk()));
        int metres = done.get("tripDistanceMeters").asInt();
        int minutes = done.get("durationMinutes").asInt();
        assertEquals(Math.max(80, (int) Math.round(60 + 18 * metres / 1000.0 + 1.5 * minutes)), done.get("fareRupees").asInt());
    }

    @Test
    @DisplayName("GPS is only counted during the trip, and only from this ride's driver")
    void meterGuards() throws Exception {
        String rider = signIn("RIDER");
        String driver = signIn("DRIVER");
        String stranger = signIn("DRIVER");

        String waiting = body(mvc.perform(post("/rides").header("Authorization", "Bearer " + rider)
                .contentType("application/json").content("{\"destination\":\"Ukkadam\"}"))).get("rideId").asText();
        mvc.perform(post("/rides/" + waiting + "/trip-location").contentType("application/json")
                .content("{\"lat\":11.0,\"lng\":77.0}")).andExpect(status().isConflict());

        String id = rideInTrip(rider, driver, "AUTO");
        mvc.perform(post("/rides/" + id + "/trip-location").header("Authorization", "Bearer " + stranger)
                .contentType("application/json").content("{\"lat\":11.0,\"lng\":77.0}")).andExpect(status().isForbidden());
        mvc.perform(post("/rides/" + id + "/trip-location").header("Authorization", "Bearer " + driver)
                .contentType("application/json").content("{\"lat\":11.0}")).andExpect(status().isBadRequest());

        mvc.perform(post("/rides/" + id + "/complete").header("Authorization", "Bearer " + driver)).andExpect(status().isOk());
        mvc.perform(post("/rides/" + id + "/trip-location").header("Authorization", "Bearer " + driver)
                .contentType("application/json").content("{\"lat\":11.0,\"lng\":77.0}")).andExpect(status().isConflict());
    }
}
