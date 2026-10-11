package com.cabeye.backend.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Google Gemini, through its OpenAI-compatible chat endpoint, with several keys and several models.
 *
 * <h2>Why several of each</h2>
 * The free tier is generous but rate-limited per key and per model (about 15 requests a minute on
 * Flash-Lite). A rider pointing the camera around can ask several times a minute, so — as in the
 * AI-Server router this follows — keys are given comma-separated and tried in turn, and models fall
 * back from the fastest to the more capable: {@code gemini-3.5-flash-lite → gemini-3.5-flash →
 * gemini-2.5-flash}. A key that answers 429 rests for a minute; a key that answers 401/403 is
 * skipped for the rest of the process. The first good answer wins.
 *
 * <h2>Configuration</h2>
 * {@code cabeye.gemini.api-keys=key1,key2} in {@code cabeye-secrets.properties} (or
 * {@code CABEYE_GEMINI_API_KEYS}). No keys = {@link #enabled()} false, and callers fall back (the
 * phone has its own on-device detector). Keys are never logged.
 */
@Service
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    static final long RATE_LIMIT_REST_MS = 60_000L;

    /** Thrown when no key and model gave an answer. */
    public static class GeminiException extends Exception {
        public GeminiException(String message) {
            super(message);
        }
    }

    private final List<String> keys;
    private final List<String> models;
    private final String url;
    private final long timeoutMs;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Integer, Long> restingUntil = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> rejected = new ConcurrentHashMap<>();
    private final AtomicInteger nextKey = new AtomicInteger();

    public GeminiClient(@Value("${cabeye.gemini.api-keys:${CABEYE_GEMINI_API_KEYS:}}") String keys,
                        @Value("${cabeye.gemini.models:gemini-3.5-flash-lite,gemini-3.5-flash,gemini-2.5-flash}") String models,
                        @Value("${cabeye.gemini.url:https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}") String url,
                        @Value("${cabeye.gemini.timeout-ms:15000}") long timeoutMs) {
        this.keys = split(keys);
        this.models = split(models);
        this.url = url.trim();
        this.timeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 8_000))).build();
        log.info("GEMINI keys={} models={}", this.keys.size(), this.models);
    }

    private static List<String> split(String raw) {
        if (raw == null) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    public boolean enabled() {
        return !keys.isEmpty() && !models.isEmpty();
    }

    /**
     * One chat completion. {@code userParts} is the OpenAI "content" array (text and image parts).
     *
     * @param deadlineMs total time allowed across all keys and models
     * @return the model's text answer
     */
    public String chat(String system, ArrayNode userParts, int maxTokens, long deadlineMs) throws GeminiException {
        if (!enabled()) throw new GeminiException("no Gemini keys configured");
        long deadline = System.currentTimeMillis() + deadlineMs;
        List<String> failures = new ArrayList<>();

        for (String model : models) {
            int start = Math.floorMod(nextKey.getAndIncrement(), keys.size());
            for (int k = 0; k < keys.size(); k++) {
                int keyIndex = (start + k) % keys.size();
                long now = System.currentTimeMillis();
                if (now >= deadline) throw new GeminiException("out of time: " + String.join("; ", failures));
                if (rejected.containsKey(keyIndex)) continue;
                Long rest = restingUntil.get(keyIndex);
                if (rest != null && rest > now) continue;

                try {
                    HttpResponse<String> response = http.send(request(model, keys.get(keyIndex), system, userParts, maxTokens,
                            Math.min(timeoutMs, deadline - now)), HttpResponse.BodyHandlers.ofString());
                    int status = response.statusCode();
                    if (status / 100 == 2) {
                        String content = json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText("");
                        if (!content.isBlank()) {
                            log.info("GEMINI ok model={} key#{}", model, keyIndex + 1);
                            return content;
                        }
                        failures.add(model + " key#" + (keyIndex + 1) + " empty");
                        continue;
                    }
                    failures.add(model + " key#" + (keyIndex + 1) + " HTTP " + status + " " + errorMessage(response.body()));
                    if (status == 429) {
                        restingUntil.put(keyIndex, now + RATE_LIMIT_REST_MS);
                    } else if (status == 401 || status == 403) {
                        rejected.put(keyIndex, true);
                        log.warn("GEMINI key#{} refused (HTTP {}); skipping it from now on", keyIndex + 1, status);
                    } else if (status == 400 || status == 404) {
                        // This model can't take the request (or doesn't exist for this key): next model.
                        break;
                    }
                } catch (java.io.IOException e) {
                    failures.add(model + " key#" + (keyIndex + 1) + " " + e.getClass().getSimpleName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GeminiException("interrupted");
                }
            }
        }
        log.warn("GEMINI all keys/models failed: {}", failures);
        throw new GeminiException(failures.isEmpty() ? "every key is resting" : String.join("; ", failures));
    }

    /** Google's error message from a failed response (it never contains the key), cut short for the log. */
    private String errorMessage(String body) {
        String message;
        try {
            JsonNode tree = json.readTree(body);
            JsonNode error = tree.isArray() ? tree.path(0).path("error") : tree.path("error");
            message = error.path("message").asText(body);
        } catch (Exception e) {
            message = body;
        }
        message = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }

    private HttpRequest request(String model, String key, String system, ArrayNode userParts, int maxTokens, long timeout)
            throws java.io.IOException {
        ObjectNode body = json.createObjectNode().put("model", model).put("max_tokens", maxTokens).put("temperature", 0.2);
        ArrayNode messages = body.putArray("messages");
        messages.add(json.createObjectNode().put("role", "system").put("content", system));
        ObjectNode user = messages.addObject().put("role", "user");
        user.set("content", userParts);
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1_000, timeout)))
                // The OpenAI-compatible endpoint only takes Bearer auth; x-goog-api-key gets HTTP 400.
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
    }

    /** A text part for {@link #chat}. */
    public ObjectNode text(String text) {
        return json.createObjectNode().put("type", "text").put("text", text);
    }

    /** A JPEG image part for {@link #chat}. */
    public ObjectNode jpeg(String base64) {
        ObjectNode part = json.createObjectNode().put("type", "image_url");
        part.putObject("image_url").put("url", "data:image/jpeg;base64," + base64);
        return part;
    }

    public ArrayNode parts(JsonNode... parts) {
        ArrayNode a = json.createArrayNode();
        for (JsonNode p : parts) a.add(p);
        return a;
    }

    /** The JSON object inside a model answer, tolerating ```json fences and text around it. */
    public static String jsonIn(String answer) {
        if (answer == null) return "{}";
        int a = answer.indexOf('{');
        int b = answer.lastIndexOf('}');
        return a >= 0 && b > a ? answer.substring(a, b + 1) : "{}";
    }
}
