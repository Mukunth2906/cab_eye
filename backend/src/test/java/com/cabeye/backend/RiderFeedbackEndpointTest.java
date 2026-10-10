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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feedback enhancements: (a) the rider's feedback carries the trip's measured km, fare and
 * minutes, so a complaint can be checked; (b) the driver rates the passenger.
 */
@SpringBootTest(properties = {
        "cabeye.data.dir=build/test-data/rider-feedback-${random.uuid}",
        "cabeye.admin.email=admin@test.local",
        "cabeye.admin.password=testpass123"
})
@AutoConfigureMockMvc
class RiderFeedbackEndpointTest {

    private static final AtomicInteger NEXT = new AtomicInteger(4000);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** @return {token, accountId} */
    private String[] signIn(String role) throws Exception {
        String phone = "95000" + String.format("%05d", NEXT.incrementAndGet());
        String code = body(mvc.perform(post("/auth/otp").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\"}"))).get("devCode").asText();
        JsonNode v = body(mvc.perform(post("/auth/verify").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\",\"code\":\"" + code
                        + "\",\"name\":\"T\"}")));
        return new String[]{v.get("token").asText(), v.get("account").get("id").asText()};
    }

    private String admin() throws Exception {
        return body(mvc.perform(post("/admin/api/login").contentType("application/json")
                .content("{\"email\":\"admin@test.local\",\"password\":\"testpass123\"}"))
                .andExpect(status().isOk())).get("token").asText();
    }

    /** A completed ride with about 3.3 km measured by GPS. */
    private JsonNode finishedRide(String rider, String driver) throws Exception {
        String id = body(mvc.perform(post("/rides").header("Authorization", "Bearer " + rider)
                .contentType("application/json").content("{\"destination\":\"Gandhipuram\"}"))).get("rideId").asText();
        String auth = "Bearer " + driver;
        mvc.perform(post("/rides/" + id + "/accept").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(post("/rides/" + id + "/arrived").header("Authorization", auth));
        mvc.perform(post("/rides/" + id + "/code").header("Authorization", "Bearer " + rider)
                .contentType("application/json").content("{\"matched\":true}"));
        mvc.perform(post("/rides/" + id + "/seated").header("Authorization", auth));
        mvc.perform(post("/rides/" + id + "/start").header("Authorization", auth)).andExpect(status().isOk());
        long t0 = System.currentTimeMillis() - 4 * 60_000L;
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/rides/" + id + "/trip-location").header("Authorization", auth)
                    .contentType("application/json")
                    .content("{\"lat\":" + (11.0247 + 0.01 * i) + ",\"lng\":77.0027,\"at\":" + (t0 + i * 60_000L) + "}"));
        }
        return body(mvc.perform(post("/rides/" + id + "/complete").header("Authorization", auth))
                .andExpect(status().isOk()));
    }

    private JsonNode find(JsonNode list, String field, String value) {
        for (JsonNode n : list) if (value.equals(n.path(field).asText())) return n;
        return null;
    }

    @Test
    @DisplayName("(a) the rider's feedback and its admin case carry the trip's km, fare and minutes")
    void feedbackCarriesTheTrip() throws Exception {
        String[] rider = signIn("RIDER");
        String[] driver = signIn("DRIVER");
        JsonNode ride = finishedRide(rider[0], driver[0]);
        String id = ride.get("rideId").asText();

        mvc.perform(post("/rides/" + id + "/feedback").header("Authorization", "Bearer " + rider[0])
                        .contentType("application/json")
                        .content("{\"rating\":2,\"category\":\"PAYMENT\",\"text\":\"I was overcharged\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceMeters").value(ride.get("tripDistanceMeters").asInt()))
                .andExpect(jsonPath("$.distanceSource").value("GPS"))
                .andExpect(jsonPath("$.fareRupees").value(ride.get("fareRupees").asInt()))
                .andExpect(jsonPath("$.durationMinutes").value(ride.get("durationMinutes").asInt()));

        JsonNode c = find(body(mvc.perform(get("/admin/api/cases").header("Authorization", "Bearer " + admin()))), "id", "fb-" + id);
        assertEquals(ride.get("tripDistanceMeters").asInt(), c.get("distanceMeters").asInt());
        assertEquals(ride.get("fareRupees").asInt(), c.get("fareRupees").asInt());
    }

    @Test
    @DisplayName("(b) the driver rates the passenger: once per ride, counted in the rider's average")
    void driverRatesRider() throws Exception {
        String[] rider = signIn("RIDER");
        String[] driver = signIn("DRIVER");
        String id = finishedRide(rider[0], driver[0]).get("rideId").asText();
        String auth = "Bearer " + driver[0];

        mvc.perform(post("/rides/" + id + "/rider-feedback").header("Authorization", auth)
                        .contentType("application/json").content("{\"rating\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.urgent").value(false));
        // A second rating on the same ride does not count twice.
        mvc.perform(post("/rides/" + id + "/rider-feedback").header("Authorization", auth)
                        .contentType("application/json").content("{\"rating\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5));

        String adminToken = admin();
        JsonNode me = find(body(mvc.perform(get("/admin/api/riders").header("Authorization", "Bearer " + adminToken))), "id", rider[1]);
        assertEquals(1, me.get("ratingCount").asInt());
        assertEquals(5.0, me.get("ratingAverage").asDouble(), 0.001);
        // A plain good rating is not something support needs to read.
        JsonNode cases = body(mvc.perform(get("/admin/api/cases").header("Authorization", "Bearer " + adminToken)));
        assertEquals(null, find(cases, "id", "dr-" + id));
    }

    @Test
    @DisplayName("(b) a driver's safety report is urgent, reaches the inbox, and is not a complaint against that driver")
    void driverSafetyReport() throws Exception {
        String[] rider = signIn("RIDER");
        String[] driver = signIn("DRIVER");
        String id = finishedRide(rider[0], driver[0]).get("rideId").asText();

        mvc.perform(post("/rides/" + id + "/rider-feedback").header("Authorization", "Bearer " + driver[0])
                        .contentType("application/json")
                        .content("{\"rating\":1,\"category\":\"BEHAVIOUR\",\"text\":\"passenger threatened me\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urgent").value(true))
                .andExpect(jsonPath("$.category").value("SAFETY"));

        String adminToken = admin();
        JsonNode c = find(body(mvc.perform(get("/admin/api/cases").header("Authorization", "Bearer " + adminToken))), "id", "dr-" + id);
        assertEquals("DRIVER_REPORT", c.get("kind").asText());
        assertTrue(c.get("urgent").asBoolean());
        assertTrue(c.get("fareRupees").asInt() > 0, "the case shows the trip's fare");

        JsonNode d = find(body(mvc.perform(get("/admin/api/drivers").header("Authorization", "Bearer " + adminToken))), "id", driver[1]);
        assertEquals(0, d.get("complaints").asInt());
        JsonNode r = find(body(mvc.perform(get("/admin/api/riders").header("Authorization", "Bearer " + adminToken))), "id", rider[1]);
        assertEquals(1, r.get("driverReports").asInt());
    }

    @Test
    @DisplayName("(b) refused: before the trip ends, by another driver, out of range, or empty")
    void driverFeedbackRefusals() throws Exception {
        String[] rider = signIn("RIDER");
        String[] driver = signIn("DRIVER");
        String[] stranger = signIn("DRIVER");

        String early = body(mvc.perform(post("/rides").header("Authorization", "Bearer " + rider[0])
                .contentType("application/json").content("{\"destination\":\"Ukkadam\"}"))).get("rideId").asText();
        mvc.perform(post("/rides/" + early + "/rider-feedback").header("Authorization", "Bearer " + driver[0])
                        .contentType("application/json").content("{\"rating\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("You can rate your passenger once the trip has finished."));

        String id = finishedRide(rider[0], driver[0]).get("rideId").asText();
        mvc.perform(post("/rides/" + id + "/rider-feedback").header("Authorization", "Bearer " + stranger[0])
                .contentType("application/json").content("{\"rating\":4}")).andExpect(status().isBadRequest());
        mvc.perform(post("/rides/" + id + "/rider-feedback").header("Authorization", "Bearer " + driver[0])
                .contentType("application/json").content("{\"rating\":9}")).andExpect(status().isBadRequest());
        mvc.perform(post("/rides/" + id + "/rider-feedback").header("Authorization", "Bearer " + driver[0])
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());

        // A note without a rating is fine.
        JsonNode saved = body(mvc.perform(post("/rides/" + id + "/rider-feedback").header("Authorization", "Bearer " + driver[0])
                        .contentType("application/json").content("{\"category\":\"PICKUP\",\"text\":\"was not at the pickup point\"}"))
                .andExpect(status().isOk()));
        assertEquals("PICKUP", saved.get("category").asText());
        assertFalse(saved.has("rating"), "no rating was given");
    }
}
