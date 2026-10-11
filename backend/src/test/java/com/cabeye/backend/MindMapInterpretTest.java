package com.cabeye.backend;

import com.cabeye.backend.mindmap.InterpretService;
import com.cabeye.backend.mindmap.Interpretation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The optional interpreter: off (404) without a key, never reached without sign-in, and when on
 * it passes only well-formed answers — no unknown intents, no "the way is clear" — within a
 * per-rider hourly limit. The model is a stub: tests never call Grok.
 */
@SpringBootTest(properties = "cabeye.data.dir=build/test-data/mindmap-${random.uuid}")
@AutoConfigureMockMvc
class MindMapInterpretTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String signIn() throws Exception {
        String phone = "9710" + String.format("%06d", (int) (Math.random() * 1_000_000));
        String code = json.readTree(mvc.perform(post("/auth/otp").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"RIDER\"}")).andReturn().getResponse().getContentAsString())
                .get("devCode").asText();
        return json.readTree(mvc.perform(post("/auth/verify").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"RIDER\",\"code\":\"" + code + "\",\"name\":\"T\"}"))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    @DisplayName("without a key the endpoint is off (404), and it needs a signed-in rider (401)")
    void offWithoutKey() throws Exception {
        String body = "{\"utterance\":\"the bakery smell is how I know the clinic is close\",\"known\":[]}";
        mvc.perform(post("/me/mindmap/interpret").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/me/mindmap/interpret").header("Authorization", "Bearer " + signIn())
                .contentType("application/json").content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("answers are cleaned: intent and sense checked, 'clear' claims dropped, confidence bounded")
    void validated() {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        InterpretService s = new InterpretService((u, known) -> {
            seen.set(known);
            return new Interpretation("remember", "temple bell", "Amma", "", "the way is clear, you're near amma's house",
                    "amma's house", "sound", "sideways", 3.0);
        });
        var r = (InterpretService.Result.Ok) s.interpret("r1", "  that bell, it tells me amma's place is close  ", List.of("Amma's house", " "));
        Interpretation i = r.value();
        assertEquals("REMEMBER", i.intent());
        assertEquals("SOUND", i.sense());
        assertEquals("NONE", i.side());
        assertEquals("", i.meaning());
        assertEquals(1.0, i.confidence());
        assertEquals(List.of("Amma's house"), seen.get());

        InterpretService junk = new InterpretService((u, k) -> new Interpretation("BOOK", "x", "", "", "", "", "", "", 1));
        assertEquals(Interpretation.NONE, ((InterpretService.Result.Ok) junk.interpret("r1", "take me home", List.of())).value());
    }

    @Test
    @DisplayName("a failing or slow model is a failure the phone can fall back from; input and rate are bounded")
    void failuresAndLimits() {
        InterpretService broken = new InterpretService((u, k) -> { throw new java.util.concurrent.TimeoutException(); });
        assertInstanceOf(InterpretService.Result.Failed.class, broken.interpret("r1", "something", List.of()));

        InterpretService ok = new InterpretService((u, k) -> Interpretation.NONE);
        assertInstanceOf(InterpretService.Result.Failed.class, ok.interpret("r2", "", List.of()));
        assertInstanceOf(InterpretService.Result.Failed.class, ok.interpret("r2", "x".repeat(400), List.of()));
        for (int n = 0; n < InterpretService.PER_HOUR; n++) {
            assertInstanceOf(InterpretService.Result.Ok.class, ok.interpret("r3", "hello", List.of()));
        }
        assertInstanceOf(InterpretService.Result.Limited.class, ok.interpret("r3", "hello", List.of()));
        assertInstanceOf(InterpretService.Result.Ok.class, ok.interpret("r4", "hello", List.of()));
    }

    @Test
    @DisplayName("the Grok call: bearer key, strict schema with every field, answer read from the first choice")
    void grokOverHttp() throws Exception {
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            sent.set(json.readTree(ex.getRequestBody()));
            String answer = json.writeValueAsString(new Interpretation("REMEMBER", "ravi's tea shop", "Ravi",
                    "you stop on fridays", "", "", "NONE", "NONE", 0.9));
            byte[] out = json.writeValueAsBytes(java.util.Map.of("choices",
                    List.of(java.util.Map.of("message", java.util.Map.of("role", "assistant", "content", answer)))));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
            InterpretService s = new InterpretService(" test-key ", "grok-test", url, 4000);
            var r = (InterpretService.Result.Ok) s.interpret("r5", "ravi's tea shop, where I stop on fridays", List.of("Amma's house"));
            assertEquals("ravi's tea shop", r.value().subject());
            assertEquals("you stop on fridays", r.value().habit());
            assertEquals("Bearer test-key", auth.get());
            JsonNode body = sent.get();
            assertEquals("grok-test", body.path("model").asText());
            assertTrue(body.path("messages").path(1).path("content").asText().contains("Amma's house"));
            JsonNode schema = body.path("response_format").path("json_schema").path("schema");
            assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
            assertEquals(9, schema.path("required").size());
            assertEquals("number", schema.path("properties").path("confidence").path("type").asText());
            assertTrue(schema.path("properties").path("intent").path("description").asText().startsWith("REMEMBER"));
        } finally {
            server.stop(0);
        }
    }
}
