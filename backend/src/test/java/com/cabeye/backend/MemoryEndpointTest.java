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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Completed rides become visited places; confirmed suggestions teach aliases; forget works. */
@SpringBootTest(properties = "cabeye.data.dir=build/test-data/memory-${random.uuid}")
@AutoConfigureMockMvc
class MemoryEndpointTest {

    private static final AtomicInteger NEXT = new AtomicInteger(2000);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String signIn(String role) throws Exception {
        String phone = "97000" + String.format("%05d", NEXT.incrementAndGet());
        String code = body(mvc.perform(post("/auth/otp").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\"}"))).get("devCode").asText();
        return body(mvc.perform(post("/auth/verify").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\",\"code\":\"" + code
                        + "\",\"name\":\"T\"}"))).get("token").asText();
    }

    /** Books with the rider's token and drives the ride all the way to COMPLETED. */
    private void completeRide(String rider, String driver, String destination, String placeId, String spokenAs)
            throws Exception {
        String id = body(mvc.perform(post("/rides").header("Authorization", "Bearer " + rider)
                .contentType("application/json")
                .content("{\"destination\":\"" + destination + "\",\"destinationPlaceId\":\"" + placeId
                        + "\",\"destinationLatitude\":11.02,\"destinationLongitude\":77.0,\"spokenAs\":\""
                        + spokenAs + "\"}"))).get("rideId").asText();
        String auth = "Bearer " + driver;
        mvc.perform(post("/rides/" + id + "/accept").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(post("/rides/" + id + "/arrived").header("Authorization", auth));
        mvc.perform(post("/rides/" + id + "/code").header("Authorization", "Bearer " + rider)
                .contentType("application/json").content("{\"matched\":true}"));
        mvc.perform(post("/rides/" + id + "/seated").header("Authorization", auth));
        mvc.perform(post("/rides/" + id + "/start").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(post("/rides/" + id + "/complete").header("Authorization", auth)
                .contentType("application/json").content("{\"fareRupees\":120,\"durationMinutes\":14}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("two completed rides to the same place make one visited place with a count, hour and alias")
    void visitedPlaces() throws Exception {
        String rider = signIn("RIDER");
        String driver = signIn("DRIVER");
        completeRide(rider, driver, "PSG College of Technology", "ChIJ-psg", "piece g");
        completeRide(rider, driver, "PSG College of Technology", "ChIJ-psg", "psg tech");
        completeRide(rider, driver, "Gandhipuram", "ChIJ-gp", "gandhipuram");

        JsonNode memory = body(mvc.perform(get("/me/memory").header("Authorization", "Bearer " + rider))
                .andExpect(status().isOk()));
        JsonNode top = memory.get("places").get(0);
        assertEquals("PSG College of Technology", top.get("name").asText());
        assertEquals(2, top.get("visitCount").asInt());
        assertTrue(top.get("aliases").toString().contains("piece g"));
        int hourTotal = 0;
        for (JsonNode h : top.get("hourCounts")) hourTotal += h.asInt();
        assertEquals(2, hourTotal);
        assertEquals(3, memory.get("trips").size());
        assertTrue(memory.get("trips").toString().contains("\"spokenAs\":\"piece g\""));
    }

    @Test
    @DisplayName("an accepted repair suggestion learns the misheard words; outcomes feed the stats")
    void outcomes() throws Exception {
        String rider = signIn("RIDER");
        String driver = signIn("DRIVER");
        completeRide(rider, driver, "Brookefields Mall", "ChIJ-bf", "brookefields");

        mvc.perform(post("/me/memory/outcome").header("Authorization", "Bearer " + rider)
                        .contentType("application/json")
                        .content("{\"placeKey\":\"ChIJ-bf\",\"kind\":\"REPAIR\",\"accepted\":true,\"heard\":\"brook fields\"}"))
                .andExpect(status().isOk());
        JsonNode after = body(mvc.perform(post("/me/memory/outcome").header("Authorization", "Bearer " + rider)
                .contentType("application/json")
                .content("{\"placeKey\":\"ChIJ-bf\",\"kind\":\"PROACTIVE\",\"accepted\":false}")));

        assertTrue(after.get("place").get("aliases").toString().contains("brook fields"));
        assertEquals(1, after.get("stats").get("repairAccepted").asInt());
        assertEquals(1, after.get("stats").get("proactiveRejected").asInt());
    }

    @Test
    @DisplayName("forget my history removes everything")
    void forget() throws Exception {
        String rider = signIn("RIDER");
        String driver = signIn("DRIVER");
        completeRide(rider, driver, "Race Course", "ChIJ-rc", "race course");
        mvc.perform(delete("/me/memory").header("Authorization", "Bearer " + rider)).andExpect(status().isOk());
        JsonNode memory = body(mvc.perform(get("/me/memory").header("Authorization", "Bearer " + rider)));
        assertEquals(0, memory.get("places").size());
        assertEquals(0, memory.get("trips").size());
    }

    @Test
    @DisplayName("a ride booked without signing in leaves no memory")
    void guestsKeepNothing() throws Exception {
        String rider = signIn("RIDER");
        String driver = signIn("DRIVER");
        String id = body(mvc.perform(post("/rides").contentType("application/json")
                .content("{\"destination\":\"Ukkadam\"}"))).get("rideId").asText();
        String auth = "Bearer " + driver;
        mvc.perform(post("/rides/" + id + "/accept").header("Authorization", auth));
        mvc.perform(post("/rides/" + id + "/code").contentType("application/json").content("{\"matched\":true}"));
        mvc.perform(post("/rides/" + id + "/seated").header("Authorization", auth));
        mvc.perform(post("/rides/" + id + "/start").header("Authorization", auth));
        mvc.perform(post("/rides/" + id + "/complete").header("Authorization", auth));
        JsonNode memory = body(mvc.perform(get("/me/memory").header("Authorization", "Bearer " + rider)));
        assertEquals(0, memory.get("places").size());
    }
}
