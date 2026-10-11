package com.cabeye.backend;

import com.cabeye.backend.mindmap.InterpretService;
import com.cabeye.backend.vision.GeminiClient;
import com.cabeye.backend.vision.VisionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "What's in front of me" through Gemini, against a stand-in Gemini on this machine: keys
 * rotate on rate limits, models fall back, and the answer is checked before the rider hears it.
 */
class VisionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    /** (key, model) → (status, content) */
    private volatile BiFunction<String, String, Map.Entry<Integer, String>> reply;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    private static final String SCENE = """
            ```json
            {"summary": "A shop doorway with two steps. The way looks clear.",
             "objects": [
               {"label": "Door", "box": [100, 420, 900, 580], "distance_m": 3},
               {"label": "steps", "box": [700, 50, 1000, 180], "distance_m": 1.5},
               {"label": "auto rickshaw", "box": [300, 820, 700, 1100], "distance_m": 6},
               {"label": "", "box": [1, 2, 3, 4]}
             ],
             "hazards": ["Two steps up at the door", "The road is safe to cross"],
             "text": "OPEN", "found": ""}
            ```""";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            String key = exchange.getRequestHeaders().getFirst("Authorization").replaceFirst("^Bearer ", "");
            String model = body.path("model").asText();
            calls.add(key + "/" + model);
            Map.Entry<Integer, String> r = reply.apply(key, model);
            String out = r.getKey() / 100 == 2
                    ? JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", r.getValue())))))
                    : "{\"error\":\"nope\"}";
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(r.getKey(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiClient client(String keys, String models) {
        return new GeminiClient(keys, models, "http://127.0.0.1:" + server.getAddress().getPort() + "/chat", 5_000);
    }

    @Test
    @DisplayName("a rate-limited key rests and the next key answers")
    void keyRotation() {
        reply = (key, model) -> key.equals("k1") ? Map.entry(429, "") : Map.entry(200, SCENE);
        VisionService vision = new VisionService(client("k1,k2", "m1"));

        assertInstanceOf(VisionService.Result.Ok.class, vision.describe("r1", "aGVsbG8=", VisionService.Mode.DESCRIBE, null));
        assertTrue(calls.contains("k1/m1") && calls.contains("k2/m1"), calls.toString());

        // k1 is resting now: the next picture goes straight to k2.
        calls.clear();
        assertInstanceOf(VisionService.Result.Ok.class, vision.describe("r1", "aGVsbG8=", VisionService.Mode.DESCRIBE, null));
        assertEquals(List.of("k2/m1"), calls);
    }

    @Test
    @DisplayName("a model that can't take the request falls back to the next model")
    void modelFallback() {
        reply = (key, model) -> model.equals("lite") ? Map.entry(404, "") : Map.entry(200, SCENE);
        VisionService vision = new VisionService(client("k1", "lite,flash"));
        assertInstanceOf(VisionService.Result.Ok.class, vision.describe("r2", "aGVsbG8=", VisionService.Mode.DESCRIBE, null));
        assertEquals(List.of("k1/lite", "k1/flash"), calls);
    }

    @Test
    @DisplayName("the answer is checked: boxes clamped, clock from the box, no 'clear' or 'safe' claims")
    void answerIsChecked() {
        reply = (key, model) -> Map.entry(200, SCENE);
        VisionService.Result r = new VisionService(client("k1", "m1")).describe("r3", "aGVsbG8=", VisionService.Mode.DESCRIBE, null);
        VisionService.Scene s = ((VisionService.Result.Ok) r).scene();

        assertFalse(s.summary().toLowerCase().contains("clear"), s.summary());
        assertTrue(s.summary().contains("doorway"));
        assertEquals(List.of("Two steps up at the door"), s.hazards());
        assertEquals(3, s.objects().size(), "a nameless object is dropped");
        assertEquals("door", s.objects().get(0).label());
        assertEquals("12 o'clock", s.objects().get(0).clock());
        assertEquals("10 o'clock", s.objects().get(1).clock());
        assertEquals(1000, s.objects().get(2).box()[3], "boxes are clamped to 0-1000");
        assertEquals("2 o'clock", s.objects().get(2).clock());
        assertEquals("OPEN", s.text());
    }

    @Test
    @DisplayName("with no keys the service is off, and every key failing is a failure, not a guess")
    void offAndFailing() {
        assertInstanceOf(VisionService.Result.Off.class,
                new VisionService(client("", "m1")).describe("r4", "aGVsbG8=", VisionService.Mode.DESCRIBE, null));
        reply = (key, model) -> Map.entry(500, "");
        assertInstanceOf(VisionService.Result.Failed.class,
                new VisionService(client("k1", "m1")).describe("r4", "aGVsbG8=", VisionService.Mode.DESCRIBE, null));
    }

    @Test
    @DisplayName("too many pictures a minute are refused")
    void rateLimited() {
        reply = (key, model) -> Map.entry(200, SCENE);
        VisionService vision = new VisionService(client("k1", "m1"));
        for (int i = 0; i < VisionService.PER_MINUTE; i++) vision.describe("r5", "aGVsbG8=", VisionService.Mode.DESCRIBE, null);
        assertInstanceOf(VisionService.Result.Limited.class, vision.describe("r5", "aGVsbG8=", VisionService.Mode.DESCRIBE, null));
    }

    @Test
    @DisplayName("the mind map interpreter can run on Gemini")
    void interpreterOnGemini() {
        reply = (key, model) -> Map.entry(200, "{\"intent\":\"REMEMBER\",\"subject\":\"Ravi's tea shop\",\"person\":\"Ravi\"," +
                "\"habit\":\"you stop on fridays\",\"meaning\":\"\",\"relatesTo\":\"\",\"sense\":\"SMELL\",\"side\":\"LEFT\",\"confidence\":0.9}");
        InterpretService s = new InterpretService("", "grok", "http://unused", 1000, client("k1", "m1"));
        assertTrue(s.enabled());
        InterpretService.Result r = s.interpret("r6", "ravi's tea shop where i stop on fridays", List.of());
        assertEquals("Ravi's tea shop", ((InterpretService.Result.Ok) r).value().subject());
    }
}
