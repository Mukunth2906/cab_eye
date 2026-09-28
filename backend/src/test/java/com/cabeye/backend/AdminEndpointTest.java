package com.cabeye.backend;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.service.RideService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin website's API: sign-in, the inbox fed by rider feedback, driver suspension and
 * Telegram alerts — the last against a fake Telegram server, so no real message is sent.
 */
@SpringBootTest(properties = {
        "cabeye.data.dir=build/test-data/admin-${random.uuid}",
        "cabeye.admin.email=admin@cabeye.test",
        "cabeye.admin.password=correct-horse-battery",
        "cabeye.telegram.bot-token=TESTTOKEN"
})
@AutoConfigureMockMvc
class AdminEndpointTest {

    // ---- A fake Telegram ----------------------------------------------------------------
    static final HttpServer TELEGRAM;
    static final BlockingQueue<String> SENT = new LinkedBlockingQueue<>();
    static volatile String startMessage = "";

    static {
        try {
            TELEGRAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TELEGRAM.createContext("/botTESTTOKEN/", ex -> {
                String path = ex.getRequestURI().getPath();
                String reply;
                if (path.endsWith("/getMe")) {
                    reply = "{\"ok\":true,\"result\":{\"username\":\"cabeye_test_bot\"}}";
                } else if (path.endsWith("/getUpdates")) {
                    reply = "{\"ok\":true,\"result\":[{\"update_id\":7,\"message\":{\"text\":\"" + startMessage
                            + "\",\"chat\":{\"id\":424242,\"type\":\"private\",\"first_name\":\"Harshu\"}}}]}";
                } else if (path.endsWith("/sendMessage")) {
                    SENT.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    reply = "{\"ok\":true,\"result\":{}}";
                } else {
                    reply = "{\"ok\":false,\"description\":\"unknown\"}";
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            });
            TELEGRAM.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void telegramBase(DynamicPropertyRegistry registry) {
        registry.add("cabeye.telegram.api-base", () -> "http://127.0.0.1:" + TELEGRAM.getAddress().getPort());
    }

    @AfterAll
    static void stopTelegram() {
        TELEGRAM.stop(0);
    }

    // -------------------------------------------------------------------------------------

    private static final AtomicInteger NEXT = new AtomicInteger(3000);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RideService rides;

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String adminToken() throws Exception {
        return body(mvc.perform(post("/admin/api/login").contentType("application/json")
                        .content("{\"email\":\"Admin@CabEye.test\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk())).get("token").asText();
    }

    private String finishedRide(String driverId) {
        Ride ride = rides.create("rider-admin-test", "Town Hall", "AUTO");
        rides.assign(ride.rideId(), driverId, "Ravi", "brown Wagon R", "TN 38 DF 8314", "9894552378", 3);
        rides.confirmCode(ride.rideId(), "rider-admin-test", true);
        rides.passengerSeated(ride.rideId(), driverId);
        rides.startTrip(ride.rideId(), driverId, 10);
        rides.complete(ride.rideId(), driverId, 148, 12);
        return ride.rideId();
    }

    private String signIn(String role, String name) throws Exception {
        String phone = "98766" + String.format("%05d", NEXT.incrementAndGet());
        JsonNode sent = body(mvc.perform(post("/auth/otp").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\"}")));
        return body(mvc.perform(post("/auth/verify").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\",\"code\":\""
                        + sent.get("devCode").asText() + "\",\"name\":\"" + name + "\"}"))).get("token").asText();
    }

    @Test
    @DisplayName("the admin API is locked without a token, and a wrong password is refused")
    void locked() throws Exception {
        mvc.perform(get("/admin/api/state")).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true));
        mvc.perform(get("/admin/api/cases")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/api/cases").header("Authorization", "Bearer made-up")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/api/login").contentType("application/json")
                        .content("{\"email\":\"admin@cabeye.test\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        // A rider's app token is not an admin token.
        String rider = signIn("RIDER", "Asha");
        mvc.perform(get("/admin/api/cases").header("Authorization", "Bearer " + rider)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("five wrong passwords from one address lock it out")
    void lockout() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/admin/api/login").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
                    .contentType("application/json").content("{\"email\":\"admin@cabeye.test\",\"password\":\"nope-nope\"}"));
        }
        mvc.perform(post("/admin/api/login").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
                        .contentType("application/json")
                        .content("{\"email\":\"admin@cabeye.test\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("the demo complaint reaches the inbox word for word, re-filed as urgent safety")
    void feedbackBecomesUrgentCase() throws Exception {
        String admin = adminToken();
        String rideId = finishedRide("driver-inbox-test");
        String words = "there was no OTP verification done";
        mvc.perform(post("/rides/" + rideId + "/feedback").contentType("application/json")
                        .content("{\"rating\":1,\"category\":\"OTHER\",\"text\":\"" + words + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("SAFETY"))
                .andExpect(jsonPath("$.urgent").value(true));

        mvc.perform(get("/admin/api/cases/fb-" + rideId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(words))
                .andExpect(jsonPath("$.urgent").value(true))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.vehiclePlate").value("TN 38 DF 8314"))
                .andExpect(jsonPath("$.rating").value(1));

        mvc.perform(post("/admin/api/cases/fb-" + rideId + "/notes").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"text\":\"Called the rider, spoke to driver.\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/api/cases/fb-" + rideId + "/status").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.notes.length()").value(2));

        mvc.perform(get("/admin/api/rides/" + rideId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ride.codeConfirmed").value(true));
    }

    @Test
    @DisplayName("a suspended driver cannot accept a ride, and gets told why")
    void suspension() throws Exception {
        String admin = adminToken();
        String driver = signIn("DRIVER", "Ravi");
        mvc.perform(patch("/me/profile").header("Authorization", "Bearer " + driver).contentType("application/json")
                .content("{\"vehicleModel\":\"Wagon R\",\"vehiclePlate\":\"TN38DF8314\"}"));
        String driverId = body(mvc.perform(get("/me").header("Authorization", "Bearer " + driver)))
                .get("account").get("id").asText();

        mvc.perform(post("/admin/api/drivers/" + driverId + "/suspend").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"suspended\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/api/drivers/" + driverId + "/suspend").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"suspended\":true,\"reason\":\"Skipped the boarding code\"}"))
                .andExpect(status().isOk());

        String rideId = rides.create("rider-x", "Gandhipuram", "AUTO").rideId();
        mvc.perform(post("/rides/" + rideId + "/accept").header("Authorization", "Bearer " + driver)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(post("/admin/api/drivers/" + driverId + "/suspend").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"suspended\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/rides/" + rideId + "/accept").header("Authorization", "Bearer " + driver)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Telegram: link the admin's chat with a one-time Start link, then urgent feedback is sent there")
    void telegram() throws Exception {
        String admin = adminToken();
        JsonNode link = body(mvc.perform(post("/admin/api/telegram/link").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()));
        String url = link.get("link").asText();
        assertTrue(url.startsWith("https://t.me/cabeye_test_bot?start=link"), url);

        startMessage = "/start wrongcode";
        mvc.perform(post("/admin/api/telegram/check").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.linked").value(false));

        startMessage = "/start " + url.substring(url.indexOf("start=") + 6);
        mvc.perform(post("/admin/api/telegram/check").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.alerts.telegramChat").value("Harshu"));
        assertNotNull(SENT.poll(5, TimeUnit.SECONDS), "confirmation message");
        SENT.clear();

        String rideId = finishedRide("driver-telegram-test");
        mvc.perform(post("/rides/" + rideId + "/feedback").contentType("application/json")
                        .content("{\"rating\":1,\"category\":\"SAFETY\",\"text\":\"driver was drunk\"}"))
                .andExpect(status().isOk());
        String alert = SENT.poll(10, TimeUnit.SECONDS);
        assertNotNull(alert, "urgent feedback alert");
        JsonNode msg = json.readTree(alert);
        assertEquals("424242", msg.get("chat_id").asText());
        assertTrue(msg.get("text").asText().contains("driver was drunk"));
        assertTrue(msg.get("text").asText().contains("TN 38 DF 8314"));

        // Ordinary (non-urgent) feedback does not ping the phone.
        String calm = finishedRide("driver-telegram-test");
        mvc.perform(post("/rides/" + calm + "/feedback").contentType("application/json").content("{\"rating\":5}"))
                .andExpect(status().isOk());
        assertEquals(null, SENT.poll(2, TimeUnit.SECONDS));
    }
}
