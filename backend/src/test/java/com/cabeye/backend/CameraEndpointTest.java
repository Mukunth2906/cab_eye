package com.cabeye.backend;

import com.cabeye.backend.camera.RideCameraService;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideEvent;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.websocket.RideSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The live camera: who may ask, consent before anything streams, frames only to the driver,
 * and every automatic stop.
 */
@SpringBootTest(properties = "cabeye.data.dir=build/test-data/camera-${random.uuid}")
@AutoConfigureMockMvc
class CameraEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired RideService rides;
    @Autowired RideCameraService camera;
    @Autowired RideSessionManager sessions;

    private static final String RIDER = "rider-cam";
    private static final String DRIVER = "driver-cam";

    /** A ride with a driver assigned, plus fake rider and driver sockets on its topic. */
    private record Fixture(String rideId, WebSocketSession riderSocket, WebSocketSession driverSocket) {}

    private Fixture assignedRide() {
        Ride ride = rides.create(RIDER, "Gandhipuram", "AUTO");
        rides.assign(ride.rideId(), DRIVER, "Karthik", "Yellow Bajaj RE", "TN 38 AB 1234", "", 4);
        WebSocketSession r = socket(ride.rideId(), RIDER, "RIDER");
        WebSocketSession d = socket(ride.rideId(), DRIVER, "DRIVER");
        return new Fixture(ride.rideId(), r, d);
    }

    private WebSocketSession socket(String rideId, String userId, String role) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RideSessionManager.ATTR_RIDE_ID, rideId);
        attrs.put(RideSessionManager.ATTR_USER_ID, userId);
        attrs.put(RideSessionManager.ATTR_ROLE, role);
        when(s.getAttributes()).thenReturn(attrs);
        when(s.isOpen()).thenReturn(true);
        when(s.getId()).thenReturn(role + "-" + rideId);
        sessions.join(rideId, s);
        return s;
    }

    /** Everything a fake socket was sent, as JSON strings. */
    private static List<String> received(WebSocketSession s) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(s, atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(TextMessage::getPayload).toList();
    }

    private static boolean got(WebSocketSession s, String type) throws Exception {
        return received(s).stream().anyMatch(json -> json.contains("\"type\":\"" + type + "\""));
    }

    private void requestAsDriver(String rideId) throws Exception {
        mvc.perform(post("/rides/" + rideId + "/camera/request").header("X-User-Id", DRIVER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REQUESTED"));
    }

    private void acceptAsRider(String rideId) throws Exception {
        mvc.perform(post("/rides/" + rideId + "/camera/answer").header("X-User-Id", RIDER)
                        .contentType("application/json").content("{\"accept\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LIVE"));
    }

    private static Map<String, Object> frame() {
        return Map.of("jpeg", "/9j/4AAQSkZJRgABAQAAAQABAAD", "n", 1);
    }

    @Test
    @DisplayName("only the ride's own driver can ask to see the camera")
    void onlyTheDriverCanAsk() throws Exception {
        Fixture f = assignedRide();
        mvc.perform(post("/rides/" + f.rideId() + "/camera/request").header("X-User-Id", "someone-else"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/rides/ride-does-not-exist/camera/request").header("X-User-Id", DRIVER))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("nothing streams until the rider says yes; then frames reach the driver only")
    void consentThenFramesToDriverOnly() throws Exception {
        Fixture f = assignedRide();
        requestAsDriver(f.rideId());
        assertTrue(got(f.riderSocket(), "CAMERA_REQUESTED"), "the rider's phone is asked");

        assertFalse(camera.relayFrame(f.rideId(), RIDER, "RIDER", frame()), "no frames before consent");

        mvc.perform(post("/rides/" + f.rideId() + "/camera/answer").header("X-User-Id", "not-the-rider")
                        .contentType("application/json").content("{\"accept\":true}"))
                .andExpect(status().isForbidden());

        acceptAsRider(f.rideId());
        assertTrue(got(f.driverSocket(), "CAMERA_STARTED"));

        assertTrue(camera.relayFrame(f.rideId(), RIDER, "RIDER", frame()));
        assertTrue(got(f.driverSocket(), "CAMERA_FRAME"), "driver sees the picture");
        assertFalse(got(f.riderSocket(), "CAMERA_FRAME"), "frames are never echoed to the rider");

        assertFalse(camera.relayFrame(f.rideId(), DRIVER, "DRIVER", frame()), "the driver cannot send frames");
        assertFalse(camera.relayFrame(f.rideId(), "impostor", "RIDER", frame()), "only the ride's rider");
        assertFalse(camera.relayFrame(f.rideId(), RIDER, "RIDER",
                Map.of("jpeg", "x".repeat(RideCameraService.MAX_FRAME_CHARS + 1))), "oversized frames dropped");
    }

    @Test
    @DisplayName("confirming the boarding code switches the camera off and blocks a new request")
    void codeConfirmedStopsCamera() throws Exception {
        Fixture f = assignedRide();
        requestAsDriver(f.rideId());
        acceptAsRider(f.rideId());

        rides.confirmCode(f.rideId(), RIDER, true);

        assertEquals(RideCameraService.State.OFF, camera.state(f.rideId()));
        assertTrue(received(f.driverSocket()).stream()
                .anyMatch(j -> j.contains("CAMERA_STOPPED") && j.contains("CODE_CONFIRMED")));
        assertFalse(camera.relayFrame(f.rideId(), RIDER, "RIDER", frame()));

        mvc.perform(post("/rides/" + f.rideId() + "/camera/request").header("X-User-Id", DRIVER))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a wrong code does not stop the camera, and the passenger cannot be seated")
    void wrongCodeKeepsLooking() throws Exception {
        Fixture f = assignedRide();
        requestAsDriver(f.rideId());
        acceptAsRider(f.rideId());

        rides.confirmCode(f.rideId(), RIDER, false);
        assertEquals(RideCameraService.State.LIVE, camera.state(f.rideId()), "still looking for the right car");

        mvc.perform(post("/rides/" + f.rideId() + "/seated").header("X-User-Id", DRIVER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
        assertEquals(RideCameraService.State.LIVE, camera.state(f.rideId()));
    }

    @Test
    @DisplayName("the rider can say no, and either side can stop it")
    void declineAndStop() throws Exception {
        Fixture f = assignedRide();
        requestAsDriver(f.rideId());
        mvc.perform(post("/rides/" + f.rideId() + "/camera/answer").header("X-User-Id", RIDER)
                        .contentType("application/json").content("{\"accept\":false}"))
                .andExpect(jsonPath("$.state").value("OFF"));
        assertTrue(got(f.driverSocket(), "CAMERA_DECLINED"));

        requestAsDriver(f.rideId());
        acceptAsRider(f.rideId());
        mvc.perform(post("/rides/" + f.rideId() + "/camera/stop").header("X-User-Id", DRIVER)
                        .header("X-Role", "DRIVER").contentType("application/json").content("{\"reason\":\"FOUND\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OFF"));
        assertTrue(got(f.riderSocket(), "CAMERA_STOPPED"));

        // Stopping again is harmless.
        mvc.perform(post("/rides/" + f.rideId() + "/camera/stop").header("X-User-Id", RIDER)
                        .header("X-Role", "RIDER"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("camera events never enter the replayable ride log")
    void notReplayed() throws Exception {
        Fixture f = assignedRide();
        requestAsDriver(f.rideId());
        acceptAsRider(f.rideId());
        rides.cancel(f.rideId(), RIDER, "RIDER", "test");

        assertEquals(RideCameraService.State.OFF, camera.state(f.rideId()), "cancelling ends it");
        Ride ride = rides.find(f.rideId()).orElseThrow();
        for (RideEvent e : ride.eventsAfter(0)) {
            assertFalse(e.type().startsWith("CAMERA_"), "found " + e.type() + " in the log");
        }
    }
}
