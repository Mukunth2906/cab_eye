package com.cabeye.backend;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.service.RideService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SOS button reaches support: an urgent case in the admin inbox, with the ride, the
 * driver and the rider's position. It used to only change the rider's screen.
 */
@SpringBootTest(properties = {
        "cabeye.data.dir=build/test-data/sos-${random.uuid}",
        "cabeye.admin.email=admin@cabeye.test",
        "cabeye.admin.password=correct-horse-battery"
})
@AutoConfigureMockMvc
class SosEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RideService rides;

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String adminToken() throws Exception {
        return body(mvc.perform(post("/admin/api/login").contentType("application/json")
                .content("{\"email\":\"admin@cabeye.test\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk())).get("token").asText();
    }

    private JsonNode adminCase(String id) throws Exception {
        return body(mvc.perform(get("/admin/api/cases/" + id).header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk()));
    }

    @Test
    @DisplayName("SOS during a ride opens one urgent case with the driver and the position; a second press updates it")
    void duringARide() throws Exception {
        Ride ride = rides.create("rider-sos-test", "Town Hall", "AUTO");
        rides.assign(ride.rideId(), "driver-sos-test", "Ravi", "brown Wagon R", "TN 38 DF 8314", "9894552378", 3);

        JsonNode first = body(mvc.perform(post("/sos").contentType("application/json")
                        .content("{\"rideId\":\"" + ride.rideId() + "\",\"lat\":11.0168,\"lng\":76.9558}"))
                .andExpect(status().isOk()));
        String id = first.get("caseId").asText();
        assertTrue(id.startsWith("sos-"));
        assertNotNull(first.get("alerted"));

        JsonNode k = adminCase(id);
        assertEquals("SOS", k.get("kind").asText());
        assertTrue(k.get("urgent").asBoolean());
        assertEquals("Ravi", k.get("driverName").asText());
        assertEquals("TN 38 DF 8314", k.get("vehiclePlate").asText());
        assertEquals(11.0168, k.get("latitude").asDouble(), 1e-6);

        // Pressed again a moment later, from a few metres on: the same case, the new position.
        JsonNode again = body(mvc.perform(post("/sos").contentType("application/json")
                        .content("{\"rideId\":\"" + ride.rideId() + "\",\"lat\":11.0170,\"lng\":76.9560}"))
                .andExpect(status().isOk()));
        assertEquals(id, again.get("caseId").asText());
        JsonNode k2 = adminCase(id);
        assertEquals(11.0170, k2.get("latitude").asDouble(), 1e-6);
    }

    @Test
    @DisplayName("a guest with no ride and no location still gets through")
    void guest() throws Exception {
        JsonNode r = body(mvc.perform(post("/sos").contentType("application/json").content("{}"))
                .andExpect(status().isOk()));
        JsonNode k = adminCase(r.get("caseId").asText());
        assertTrue(k.get("text").asText().contains("Location not available"));
    }

    @Test
    @DisplayName("impossible coordinates are dropped, not stored")
    void badCoordinates() throws Exception {
        JsonNode r = body(mvc.perform(post("/sos").contentType("application/json").content("{\"lat\":999,\"lng\":76.9}"))
                .andExpect(status().isOk()));
        JsonNode k = adminCase(r.get("caseId").asText());
        assertTrue(k.get("latitude") == null || k.get("latitude").isNull());
    }
}
