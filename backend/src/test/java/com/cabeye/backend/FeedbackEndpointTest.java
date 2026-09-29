package com.cabeye.backend;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.service.RideService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cabeye.data.dir=build/test-data/feedback-${random.uuid}")
@AutoConfigureMockMvc
class FeedbackEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired RideService rides;

    private String finishedRide() {
        Ride ride = rides.create("rider-test", "Gandhipuram", "AUTO");
        rides.assign(ride.rideId(), "driver-test", "D", "Auto", "TN 00", "", 3);
        rides.confirmCode(ride.rideId(), "rider", true);
        rides.passengerSeated(ride.rideId(), "driver-test");
        rides.startTrip(ride.rideId(), "driver-test", 10);
        rides.complete(ride.rideId(), "driver-test", 100, 10);
        return ride.rideId();
    }

    @Test
    @DisplayName("a rating is accepted after the ride")
    void rating() throws Exception {
        mvc.perform(post("/rides/" + finishedRide() + "/feedback").contentType("application/json")
                        .content("{\"rating\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.urgent").value(false));
    }

    @Test
    @DisplayName("a safety report is marked urgent")
    void safety() throws Exception {
        mvc.perform(post("/rides/" + finishedRide() + "/feedback").contentType("application/json")
                        .content("{\"rating\":1,\"category\":\"SAFETY\",\"text\":\"driving too fast\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urgent").value(true))
                .andExpect(jsonPath("$.category").value("SAFETY"));
    }

    @Test
    @DisplayName("feedback before the ride ends is refused with a speakable reason")
    void tooEarly() throws Exception {
        Ride ride = rides.create("rider-test", "Ukkadam", "AUTO");
        mvc.perform(post("/rides/" + ride.rideId() + "/feedback").contentType("application/json")
                        .content("{\"rating\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Feedback can be given once the ride has finished."));
    }

    @Test
    @DisplayName("an out-of-range rating is refused")
    void badRating() throws Exception {
        mvc.perform(post("/rides/" + finishedRide() + "/feedback").contentType("application/json")
                        .content("{\"rating\":9}"))
                .andExpect(status().isBadRequest());
    }
}
