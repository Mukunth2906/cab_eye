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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The memory agent's raw material for multi-stop rides: every stop actually visited becomes a
 * visited place, the trip records the route in order (skipped stops left out), and the rider
 * can name a route to book it again.
 */
@SpringBootTest(properties = {"cabeye.data.dir=build/test-data/stopmemory-${random.uuid}",
        "cabeye.dev.memory-simulator=true"})
@AutoConfigureMockMvc
class MultiStopMemoryTest {

    private static final AtomicInteger NEXT = new AtomicInteger(4000);

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

    @Test
    @DisplayName("visited stops become places; the trip keeps the route in order; skipped stops are not remembered")
    void stopsBecomeMemory() throws Exception {
        String rider = "Bearer " + signIn("RIDER");
        String driver = "Bearer " + signIn("DRIVER");

        JsonNode ride = body(mvc.perform(post("/rides").header("Authorization", rider).contentType("application/json")
                .content("""
                        {"destination":"PSG College of Technology","destinationPlaceId":"ChIJ-psg",
                         "destinationLatitude":11.02,"destinationLongitude":77.0,"spokenAs":"college",
                         "stops":[
                           {"name":"Apollo Pharmacy","placeId":"ChIJ-apollo","kind":"WAIT","spokenAs":"the pharmacy"},
                           {"name":"Brookefields Mall","placeId":"ChIJ-brook","kind":"DROP"}
                         ]}""")).andExpect(status().isOk()));
        String id = ride.get("rideId").asText();
        String apollo = ride.get("stops").get(0).get("stopId").asText();
        String mall = ride.get("stops").get(1).get("stopId").asText();

        mvc.perform(post("/rides/" + id + "/accept").header("Authorization", driver)).andExpect(status().isOk());
        mvc.perform(post("/rides/" + id + "/arrived").header("Authorization", driver));
        mvc.perform(post("/rides/" + id + "/code").header("Authorization", rider).contentType("application/json").content("{\"matched\":true}"));
        mvc.perform(post("/rides/" + id + "/seated").header("Authorization", driver));
        mvc.perform(post("/rides/" + id + "/start").header("Authorization", driver)).andExpect(status().isOk());

        // Only this ride's driver may move its stops.
        String stranger = "Bearer " + signIn("DRIVER");
        mvc.perform(post("/rides/" + id + "/stops/" + apollo + "/arrived").header("Authorization", stranger))
                .andExpect(status().isForbidden());

        mvc.perform(post("/rides/" + id + "/stops/" + apollo + "/arrived").header("Authorization", driver)).andExpect(status().isOk());
        mvc.perform(post("/rides/" + id + "/code").header("Authorization", rider).contentType("application/json").content("{\"matched\":true}"));
        mvc.perform(post("/rides/" + id + "/stops/" + apollo + "/done").header("Authorization", driver)).andExpect(status().isOk());
        mvc.perform(post("/rides/" + id + "/stops/" + mall + "/skip").header("Authorization", rider)).andExpect(status().isOk());
        mvc.perform(post("/rides/" + id + "/complete").header("Authorization", driver).contentType("application/json")
                .content("{\"fareRupees\":190,\"durationMinutes\":31}")).andExpect(status().isOk());

        JsonNode memory = body(mvc.perform(get("/me/memory").header("Authorization", rider)).andExpect(status().isOk()));
        JsonNode trip = memory.get("trips").get(0);
        assertEquals("ChIJ-psg", trip.get("placeKey").asText());
        assertEquals(1, trip.get("stops").size());
        assertEquals("ChIJ-apollo", trip.get("stops").get(0).get("placeKey").asText());
        assertEquals("WAIT", trip.get("stops").get(0).get("kind").asText());

        String places = memory.get("places").toString();
        assertTrue(places.contains("Apollo Pharmacy"), places);
        assertTrue(places.contains("the pharmacy"), "the rider's words for the stop become an alias");
        assertTrue(places.contains("PSG College of Technology"));
        assertFalse(places.contains("Brookefields"), "a skipped stop was never visited");
    }

    @Test
    @DisplayName("a named route can be saved, listed, used and forgotten; forget-all clears routes too")
    void savedRoutes() throws Exception {
        String rider = "Bearer " + signIn("RIDER");
        String route = """
                {"name":"Monday errands","rideType":"AUTO",
                 "stops":[{"name":"Apollo Pharmacy","kind":"WAIT","latitude":11.0,"longitude":76.9}],
                 "destination":{"name":"PSG College of Technology","latitude":11.02,"longitude":77.0}}""";
        mvc.perform(post("/me/memory/routes").header("Authorization", rider).contentType("application/json").content(route))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("monday errands"));
        mvc.perform(post("/me/memory/routes").header("Authorization", rider).contentType("application/json")
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A saved route needs a destination."));

        mvc.perform(post("/me/memory/routes/used").param("name", "MONDAY errands!").header("Authorization", rider))
                .andExpect(jsonPath("$.found").value(true));
        JsonNode memory = body(mvc.perform(get("/me/memory").header("Authorization", rider)));
        assertEquals(1, memory.get("routes").size());
        assertEquals(1, memory.get("routes").get(0).get("useCount").asInt());
        assertEquals("WAIT", memory.get("routes").get(0).get("stops").get(0).get("kind").asText());

        mvc.perform(delete("/me/memory").header("Authorization", rider)).andExpect(status().isOk());
        assertEquals(0, body(mvc.perform(get("/me/memory").header("Authorization", rider))).get("routes").size());
    }

    @Test
    @DisplayName("be the user: a simulated month of Monday errands lands in memory like real rides")
    void simulatedHistory() throws Exception {
        String rider = "Bearer " + signIn("RIDER");
        long day = 86_400_000L;
        long now = System.currentTimeMillis();
        StringBuilder trips = new StringBuilder();
        for (int week = 1; week <= 4; week++) {
            if (week > 1) trips.append(',');
            trips.append("{\"at\":").append(now - week * 7 * day)
                 .append(",\"destination\":{\"name\":\"PSG College of Technology\",\"placeId\":\"ChIJ-psg\",\"latitude\":11.02,\"longitude\":77.0,\"spokenAs\":\"college\"}")
                 .append(",\"stops\":[{\"name\":\"Apollo Pharmacy\",\"placeId\":\"ChIJ-apollo\",\"kind\":\"WAIT\",\"spokenAs\":\"medical shop\"}]}");
        }
        mvc.perform(post("/me/memory/simulate").header("Authorization", rider).contentType("application/json")
                        .content("{\"trips\":[" + trips + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(4));

        JsonNode memory = body(mvc.perform(get("/me/memory").header("Authorization", rider)));
        assertEquals(4, memory.get("trips").size());
        JsonNode first = memory.get("trips").get(0);
        assertEquals("ChIJ-apollo", first.get("stops").get(0).get("placeKey").asText());
        assertEquals("WAIT", first.get("stops").get(0).get("kind").asText());
        boolean aliasLearned = false;
        for (JsonNode p : memory.get("places")) {
            assertEquals(4, p.get("visitCount").asInt());
            if (p.get("placeKey").asText().equals("ChIJ-apollo")) aliasLearned = p.get("aliases").toString().contains("medical shop");
        }
        assertTrue(aliasLearned);

        // Future, or too old: refused with a sentence.
        mvc.perform(post("/me/memory/simulate").header("Authorization", rider).contentType("application/json")
                        .content("{\"trips\":[{\"at\":" + (now + day) + ",\"destination\":{\"name\":\"Home\"}}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Trip times must be in the last 120 days."));
    }
}
