package com.cabeye.backend;

import com.cabeye.backend.account.Phone;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in, profiles and identity stamping, over the real endpoints.
 *
 * <p>Each test uses its own phone number so they cannot interfere through the OTP cooldown.
 */
@SpringBootTest(properties = "cabeye.data.dir=build/test-data/auth-${random.uuid}")
@AutoConfigureMockMvc
class AuthEndpointTest {

    private static final AtomicInteger NEXT = new AtomicInteger(1000);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static String freshPhone() {
        return "98765" + String.format("%05d", NEXT.incrementAndGet());
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** Full OTP round trip; returns the token. */
    private String signIn(String phone, String role, String name) throws Exception {
        JsonNode sent = body(mvc.perform(post("/auth/otp").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk()));
        String code = sent.get("devCode").asText();
        JsonNode verified = body(mvc.perform(post("/auth/verify").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"" + role + "\",\"code\":\"" + code
                        + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()));
        return verified.get("token").asText();
    }

    @Test
    @DisplayName("spoken number forms all normalise to the same ten digits")
    void phoneNormalisation() {
        assertEquals("9876543210", Phone.normalize("+91 98765 43210").orElseThrow());
        assertEquals("9876543210", Phone.normalize("098765 43210").orElseThrow());
        assertEquals("9876543210", Phone.normalize("9 8 7 6 5 4 3 2 1 0").orElseThrow());
        assertFalse(Phone.normalize("12345").isPresent());
        assertFalse(Phone.normalize("1234567890").isPresent(), "Indian mobiles start 6-9");
    }

    @Test
    @DisplayName("rider signs in with OTP, is created once, and reads their profile")
    void riderSignIn() throws Exception {
        String phone = freshPhone();
        String token = signIn(phone, "RIDER", "Harshini");

        mvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.role").value("RIDER"))
                .andExpect(jsonPath("$.account.name").value("Harshini"))
                .andExpect(jsonPath("$.account.rider.speechRate").value(1.0))
                .andExpect(jsonPath("$.profileComplete").value(true));

        // Second sign-in with the same number is the same account, not a new one.
        JsonNode sent = body(mvc.perform(post("/auth/otp").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"RIDER\"}")));
        assertTrue(sent.get("registered").asBoolean());
    }

    @Test
    @DisplayName("a wrong code is refused with a speakable sentence; five wrong burns the code")
    void wrongCode() throws Exception {
        String phone = freshPhone();
        mvc.perform(post("/auth/otp").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"RIDER\"}"));
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/auth/verify").contentType("application/json")
                            .content("{\"phone\":\"" + phone + "\",\"role\":\"RIDER\",\"code\":\"1\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.reason").value("WRONG"));
        }
        mvc.perform(post("/auth/verify").contentType("application/json")
                        .content("{\"phone\":\"" + phone + "\",\"role\":\"RIDER\",\"code\":\"1\"}"))
                .andExpect(jsonPath("$.reason").value("LOCKED"));
    }

    @Test
    @DisplayName("a second OTP within 30 seconds is refused")
    void resendCooldown() throws Exception {
        String phone = freshPhone();
        String req = "{\"phone\":\"" + phone + "\",\"role\":\"RIDER\"}";
        mvc.perform(post("/auth/otp").contentType("application/json").content(req)).andExpect(status().isOk());
        mvc.perform(post("/auth/otp").contentType("application/json").content(req))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("/me without a token is 401")
    void meNeedsToken() throws Exception {
        mvc.perform(get("/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("driver profile: incomplete until vehicle is set; plate is normalised")
    void driverProfile() throws Exception {
        String token = signIn(freshPhone(), "DRIVER", "Karthik");
        mvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.account.role").value("DRIVER"))
                .andExpect(jsonPath("$.profileComplete").value(false));

        mvc.perform(patch("/me/profile").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"vehicleModel\":\"Bajaj RE\",\"vehiclePlate\":\"tn38ab1234\",\"vehicleColour\":\"Yellow\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.driver.vehiclePlate").value("TN 38 AB 1234"))
                .andExpect(jsonPath("$.profileComplete").value(true));
    }

    @Test
    @DisplayName("rider profile rejects an out-of-range speech rate with a spoken reason")
    void riderProfileValidation() throws Exception {
        String token = signIn(freshPhone(), "RIDER", "Asha");
        mvc.perform(patch("/me/profile").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"speechRate\":9}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/me/profile").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"speechRate\":1.5}"))
                .andExpect(jsonPath("$.account.rider.speechRate").value(1.5));
    }

    @Test
    @DisplayName("booking and accepting with tokens stamps the real ids and the driver's own vehicle")
    void identityStamping() throws Exception {
        String rider = signIn(freshPhone(), "RIDER", "Harshini");
        String driver = signIn(freshPhone(), "DRIVER", "Karthik");
        mvc.perform(patch("/me/profile").header("Authorization", "Bearer " + driver)
                .contentType("application/json")
                .content("{\"vehicleModel\":\"Bajaj RE\",\"vehiclePlate\":\"TN38AB1234\",\"vehicleColour\":\"Yellow\"}"));

        JsonNode me = body(mvc.perform(get("/me").header("Authorization", "Bearer " + rider)));
        String riderId = me.get("account").get("id").asText();

        JsonNode ride = body(mvc.perform(post("/rides").header("Authorization", "Bearer " + rider)
                        .header("X-User-Id", "someone-else")
                        .contentType("application/json")
                        .content("{\"destination\":\"PSG College of Technology\"}"))
                .andExpect(status().isOk()));
        assertEquals(riderId, ride.get("riderId").asText(), "token wins over the header");

        mvc.perform(post("/rides/" + ride.get("rideId").asText() + "/accept")
                        .header("Authorization", "Bearer " + driver)
                        .contentType("application/json")
                        .content("{\"driverName\":\"Spoofed\",\"vehiclePlate\":\"XX\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverName").value("Karthik"))
                .andExpect(jsonPath("$.vehicleModel").value("Yellow Bajaj RE"))
                .andExpect(jsonPath("$.vehiclePlate").value("TN 38 AB 1234"));
    }

    @Test
    @DisplayName("logout revokes the token")
    void logout() throws Exception {
        String token = signIn(freshPhone(), "RIDER", "Asha");
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
}
