package com.cabeye.backend;

import com.cabeye.backend.admin.AdminCase;
import com.cabeye.backend.admin.CaseService;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideEvent;
import com.cabeye.backend.model.RideRecord;
import com.cabeye.backend.model.RideStop;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Multi-stop rides, Uber/Rapido style: up to three stops, visited in order, editable by the
 * rider — and the Cab Eye rule on top: at a WAIT stop the car cannot leave until the rider's
 * phone has heard the boarding code again.
 */
@SpringBootTest(properties = {
        "cabeye.data.dir=build/test-data/stops-${random.uuid}",
        "cabeye.stops.wait-minutes=10"
})
@AutoConfigureMockMvc
class MultiStopEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RideService rides;
    @Autowired CaseService cases;

    private static final String TWO_STOPS = """
            {"destination":"PSG College of Technology","rideType":"AUTO",
             "stops":[
               {"name":"Apollo Pharmacy","kind":"WAIT","latitude":11.02,"longitude":76.96,"spokenAs":"the pharmacy"},
               {"name":"Gandhipuram","kind":"DROP","note":"my friend Priya"}
             ]}""";

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode book(String request) throws Exception {
        return body(mvc.perform(post("/rides").header("X-User-Id", "rider-stops")
                .contentType("application/json").content(request)).andExpect(status().isOk()));
    }

    /** Booked, driver assigned, code confirmed, seated and trip started. */
    private JsonNode bookAndStart(String request) throws Exception {
        JsonNode ride = book(request);
        String id = ride.get("rideId").asText();
        rides.assign(id, "driver-stops", "Ravi", "Wagon R", "TN 38 DF 8314", "", 3);
        rides.arrived(id, "driver-stops");
        rides.confirmCode(id, "rider-stops", true);
        rides.passengerSeated(id, "driver-stops");
        rides.startTrip(id, "driver-stops", 20);
        return ride;
    }

    private ResultActions stopCall(String rideId, String stopId, String action) throws Exception {
        return mvc.perform(post("/rides/" + rideId + "/stops/" + stopId + "/" + action)
                .header("X-User-Id", action.equals("skip") ? "rider-stops" : "driver-stops"));
    }

    @Test
    @DisplayName("booking with two stops: kept in order, each with an id, the first is current")
    void bookWithStops() throws Exception {
        JsonNode ride = book(TWO_STOPS);
        JsonNode stops = ride.get("stops");
        assertEquals(2, stops.size());
        assertEquals("Apollo Pharmacy", stops.get(0).get("name").asText());
        assertEquals("WAIT", stops.get(0).get("kind").asText());
        assertEquals("PENDING", stops.get(0).get("status").asText());
        assertEquals(600, stops.get(0).get("waitLimitSeconds").asInt());
        assertEquals("DROP", stops.get(1).get("kind").asText());
        assertEquals("my friend Priya", stops.get(1).get("note").asText());
        assertTrue(stops.get(0).get("stopId").asText().startsWith("stop-"));
        assertEquals(1, ride.get("currentStop").asInt());
        // An ordinary ride is unchanged: no stops, no current stop.
        JsonNode plain = book("{\"destination\":\"Ukkadam\",\"rideType\":\"AUTO\"}");
        assertEquals(0, plain.get("stops").size());
        assertTrue(plain.get("currentStop") == null || plain.get("currentStop").isNull());
    }

    @Test
    @DisplayName("more than three stops is refused with a sentence the phone can say")
    void tooManyStops() throws Exception {
        mvc.perform(post("/rides").contentType("application/json").content("""
                        {"destination":"Home","stops":[{"name":"A"},{"name":"B"},{"name":"C"},{"name":"D"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A ride can have up to 3 stops before the destination."));
    }

    @Test
    @DisplayName("WAIT stop: the car cannot leave until the rider's phone hears the code again")
    void waitStopNeedsCodeAgain() throws Exception {
        JsonNode ride = bookAndStart(TWO_STOPS);
        String id = ride.get("rideId").asText();
        String pharmacy = ride.get("stops").get(0).get("stopId").asText();
        String friend = ride.get("stops").get(1).get("stopId").asText();

        // Out of order is refused: the pharmacy comes first.
        stopCall(id, friend, "arrived").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("That isn't the next stop. Stops are visited in order."));

        stopCall(id, pharmacy, "arrived").andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].status").value("WAITING"));

        // The rider is still in the shop.
        stopCall(id, pharmacy, "done").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Your passenger is not back yet. Wait for their phone to confirm the code."));
        mvc.perform(post("/rides/" + id + "/complete").contentType("application/json").content("{\"fareRupees\":180}"))
                .andExpect(status().isConflict());

        // A wrong code (someone else getting in) does not count.
        mvc.perform(post("/rides/" + id + "/code").header("X-User-Id", "rider-stops")
                        .contentType("application/json").content("{\"matched\":false}"))
                .andExpect(jsonPath("$.stops[0].riderBack").value(false));
        stopCall(id, pharmacy, "done").andExpect(status().isConflict());

        // The real rider is back: their phone heard the right code.
        mvc.perform(post("/rides/" + id + "/code").header("X-User-Id", "rider-stops")
                        .contentType("application/json").content("{\"matched\":true}"))
                .andExpect(jsonPath("$.stops[0].riderBack").value(true))
                .andExpect(jsonPath("$.codeConfirmed").value(true));
        stopCall(id, pharmacy, "done").andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].status").value("DONE"))
                .andExpect(jsonPath("$.currentStop").value(2));

        // DROP stop: no code needed — the rider never left the car.
        stopCall(id, friend, "arrived").andExpect(jsonPath("$.stops[1].status").value("ARRIVED"));
        stopCall(id, friend, "done").andExpect(status().isOk());

        mvc.perform(post("/rides/" + id + "/complete").contentType("application/json")
                        .content("{\"fareRupees\":180,\"durationMinutes\":34}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("COMPLETED"));

        List<String> types = rides.find(id).orElseThrow().eventsAfter(0).stream().map(RideEvent::type).toList();
        assertTrue(types.containsAll(List.of("STOP_ARRIVED", "RIDER_RETURNED", "STOP_DONE", "TRIP_COMPLETED")), types.toString());
    }

    @Test
    @DisplayName("the rider can skip a stop and change the stops still ahead; finished stops stay put")
    void skipAndEdit() throws Exception {
        JsonNode ride = bookAndStart(TWO_STOPS);
        String id = ride.get("rideId").asText();
        String pharmacy = ride.get("stops").get(0).get("stopId").asText();
        String friend = ride.get("stops").get(1).get("stopId").asText();

        // Skipping the stop you are standing at with the car gone would strand you: refused.
        stopCall(id, pharmacy, "arrived");
        stopCall(id, pharmacy, "skip").andExpect(status().isConflict());
        mvc.perform(post("/rides/" + id + "/code").contentType("application/json").content("{\"matched\":true}"));
        stopCall(id, pharmacy, "done").andExpect(status().isOk());

        // Replace what is left: Gandhipuram is dropped, two new stops added (1 done + 2 = 3, the max).
        mvc.perform(put("/rides/" + id + "/stops").header("X-User-Id", "rider-stops").contentType("application/json")
                        .content("{\"stops\":[{\"name\":\"Brookefields Mall\",\"kind\":\"WAIT\"},{\"name\":\"Race Course\",\"kind\":\"PICKUP\",\"note\":\"mother\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.stops[0].status").value("DONE"))
                .andExpect(jsonPath("$.stops[1].name").value("Brookefields Mall"))
                .andExpect(jsonPath("$.stops[2].kind").value("PICKUP"))
                .andExpect(jsonPath("$.currentStop").value(2));

        // A fourth would exceed the limit, counting the finished one.
        mvc.perform(put("/rides/" + id + "/stops").contentType("application/json")
                        .content("{\"stops\":[{\"name\":\"A\"},{\"name\":\"B\"},{\"name\":\"C\"}]}"))
                .andExpect(status().isBadRequest());

        String mall = rides.find(id).orElseThrow().copyStops().get(1).stopId;
        String race = rides.find(id).orElseThrow().copyStops().get(2).stopId;
        stopCall(id, mall, "skip").andExpect(status().isOk()).andExpect(jsonPath("$.stops[1].status").value("SKIPPED"));
        stopCall(id, race, "skip").andExpect(status().isOk());
        assertFalse(rides.find(id).orElseThrow().hasOpenStops());
        mvc.perform(post("/rides/" + id + "/complete").contentType("application/json").content("{\"fareRupees\":150}"))
                .andExpect(status().isOk());
        // The old Gandhipuram stop is gone entirely, not merely skipped.
        assertTrue(rides.find(id).orElseThrow().copyStops().stream().noneMatch(s -> s.stopId.equals(friend)));
    }

    @Test
    @DisplayName("waiting too long: reminders, then an urgent admin case with the stop's location")
    void overdueOpensAdminCase() throws Exception {
        JsonNode ride = bookAndStart(TWO_STOPS);
        String id = ride.get("rideId").asText();
        String pharmacy = ride.get("stops").get(0).get("stopId").asText();
        stopCall(id, pharmacy, "arrived");
        long start = rides.find(id).orElseThrow().copyStops().get(0).waitStartedAt;

        rides.checkWaits(start + 301_000);   // half the 10 minutes used
        rides.checkWaits(start + 302_000);   // no repeat
        rides.checkWaits(start + 545_000);   // 55 s left
        rides.checkWaits(start + 601_000);   // over the limit
        rides.checkWaits(start + 700_000);   // no repeat

        List<RideEvent> events = rides.find(id).orElseThrow().eventsAfter(0);
        assertEquals(2, events.stream().filter(e -> e.type().equals("WAIT_WARNING")).count());
        assertEquals(1, events.stream().filter(e -> e.type().equals("WAIT_OVERDUE")).count());

        AdminCase c = cases.get("stop-" + id + "-" + pharmacy).orElseThrow();
        assertEquals(AdminCase.Kind.STOP_OVERDUE, c.kind);
        assertTrue(c.urgent);
        assertEquals(11.02, c.latitude, 1e-9);
        assertTrue(c.text.contains("Apollo Pharmacy"), c.text);
    }

    @Test
    @DisplayName("stops survive a restart with their status")
    void stopsPersist() throws Exception {
        JsonNode ride = bookAndStart(TWO_STOPS);
        String id = ride.get("rideId").asText();
        stopCall(id, ride.get("stops").get(0).get("stopId").asText(), "arrived");

        RideRecord saved = json.readValue(json.writeValueAsString(rides.find(id).orElseThrow().toRecord()), RideRecord.class);
        Ride back = Ride.fromRecord(saved);
        List<RideStop> stops = back.copyStops();
        assertEquals(2, stops.size());
        assertEquals(RideStop.Status.WAITING, stops.get(0).status);
        assertTrue(stops.get(0).waitStartedAt > 0);
        assertEquals(1, back.currentStopIndex());
    }
}
