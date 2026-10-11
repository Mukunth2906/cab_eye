package com.cabeye.backend.mindmap;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The optional free-form interpreter for the rider's mind map.
 *
 * The phone understands most of what a rider says about places by rules ("Ravi's tea shop,
 * where I stop on Fridays"). When a sentence is said some other way, and the rider has agreed,
 * the phone sends that one sentence here and Grok (xAI) reads it into the same structure the
 * rules produce. Only the sentence and the names of a few nearby places the rider already
 * knows are sent — never a position, a route or a history.
 *
 * Off unless {@code cabeye.grok.api-key} is set: the endpoint then answers 404 and the
 * app stays rules-only. The phone checks the answer again with its own rules (no visual-only
 * cues, never "the path is clear"), so this is a reader, not a decision-maker.
 */
@Service
public class InterpretService {

    private static final Logger log = LoggerFactory.getLogger(InterpretService.class);

    public static final int MAX_UTTERANCE = 300;
    public static final int MAX_KNOWN = 20;
    public static final int PER_HOUR = 30;

    private static final Set<String> INTENTS = Set.of("REMEMBER", "RIDE_CUE", "NONE");
    private static final Set<String> SENSES = Set.of("SOUND", "SMELL", "TEXTURE", "KERB", "STEPS", "RAMP", "DOOR", "CROSSING", "OTHER", "NONE");
    private static final Set<String> SIDES = Set.of("LEFT", "RIGHT", "AHEAD", "NONE");
    private static final Pattern CLEAR = Pattern.compile("\\b(clear|safe to (cross|walk|go)|no obstacles?)\\b", Pattern.CASE_INSENSITIVE);

    static final String SYSTEM = """
            You read one sentence a blind traveller said to their navigation app, and decide whether it is a \
            personal note about a place (REMEMBER), something they sense on a cab ride (RIDE_CUE), or neither (NONE).
            Keep the traveller's own words. Write habit and meaning in the second person ("you stop on fridays", \
            "you're near amma's house"). Drop colours and anything only seen (signs, boards, colours): a blind \
            traveller cannot use them; if that leaves nothing, answer NONE. Never state that a way is clear or safe.
            If the subject matches one of the known places, use that name exactly. Lower case except names.
            A question, a booking request ("take me to …") or a command is NONE.""";

    /** The model call, separate so tests never reach the network. */
    public interface Model {
        Interpretation read(String utterance, List<String> known) throws Exception;
    }

    private final Model model;
    private final boolean enabled;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    @Autowired
    public InterpretService(@Value("${cabeye.grok.api-key:}") String apiKey,
                            @Value("${cabeye.grok.model:grok-4.7}") String modelId,
                            @Value("${cabeye.grok.url:https://api.x.ai/v1/chat/completions}") String url,
                            @Value("${cabeye.grok.timeout-ms:4000}") long timeoutMs) {
        this.enabled = apiKey != null && !apiKey.isBlank();
        this.model = enabled ? grok(apiKey.trim(), modelId, url, timeoutMs) : null;
        log.info(enabled ? "MINDMAP_INTERPRET enabled model={}" : "MINDMAP_INTERPRET disabled (no cabeye.grok.api-key)", modelId);
    }

    /** For tests: any model, always enabled. */
    public InterpretService(Model model) {
        this.model = model;
        this.enabled = true;
    }

    public boolean enabled() {
        return enabled;
    }

    public sealed interface Result permits Result.Ok, Result.Limited, Result.Failed {
        record Ok(Interpretation value) implements Result {}
        record Limited() implements Result {}
        record Failed(String reason) implements Result {}
    }

    public Result interpret(String riderId, String utterance, List<String> known) {
        String said = utterance == null ? "" : utterance.strip();
        if (said.isEmpty() || said.length() > MAX_UTTERANCE) return new Result.Failed("utterance must be 1-" + MAX_UTTERANCE + " characters");
        if (!allow(riderId, System.currentTimeMillis())) return new Result.Limited();
        List<String> names = known == null ? List.of() : known.stream()
                .filter(s -> s != null && !s.isBlank()).map(s -> s.strip().length() > 80 ? s.strip().substring(0, 80) : s.strip())
                .limit(MAX_KNOWN).toList();
        try {
            return new Result.Ok(validate(model.read(said, names)));
        } catch (Exception e) {
            log.warn("MINDMAP_INTERPRET failure type={}", e.getClass().getSimpleName());
            return new Result.Failed("interpreter unavailable");
        }
    }

    /** Whatever the model said, only well-formed answers leave this service. */
    static Interpretation validate(Interpretation i) {
        if (i == null || !INTENTS.contains(up(i.intent())) || "NONE".equals(up(i.intent()))) return Interpretation.NONE;
        String subject = clip(i.subject());
        if (subject.isEmpty()) return Interpretation.NONE;
        String meaning = clip(i.meaning());
        String habit = clip(i.habit());
        // Never pass on a claim that a way is clear: the app cannot know, and neither can the model.
        if (CLEAR.matcher(meaning).find()) meaning = "";
        if (CLEAR.matcher(habit).find()) habit = "";
        double c = Double.isNaN(i.confidence()) ? 0 : Math.max(0, Math.min(1, i.confidence()));
        return new Interpretation(
                up(i.intent()), subject, clip(i.person()), habit, meaning, clip(i.relatesTo()),
                SENSES.contains(up(i.sense())) ? up(i.sense()) : "NONE",
                SIDES.contains(up(i.side())) ? up(i.side()) : "NONE",
                c);
    }

    /** At most {@link #PER_HOUR} sentences per rider per hour. */
    boolean allow(String riderId, long now) {
        Deque<Long> q = recent.computeIfAbsent(riderId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) q.pollFirst();
            if (q.size() >= PER_HOUR) return false;
            q.addLast(now);
            return true;
        }
    }

    private static Model grok(String apiKey, String modelId, String url, long timeoutMs) {
        ObjectMapper json = new ObjectMapper();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        ObjectNode format = json.createObjectNode().put("type", "json_schema");
        try {
            format.putObject("json_schema").put("name", "interpretation").put("strict", true).set("schema", schema(json));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
        return (utterance, known) -> {
            String user = "Known places: " + (known.isEmpty() ? "(none)" : String.join("; ", known)) +
                    "\nSentence: " + utterance;
            ObjectNode body = json.createObjectNode().put("model", modelId).put("max_tokens", 2048).put("temperature", 0);
            body.putArray("messages")
                    .add(json.createObjectNode().put("role", "system").put("content", SYSTEM))
                    .add(json.createObjectNode().put("role", "user").put("content", user));
            body.set("response_format", format);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    // The rider is waiting to hear an answer: one try, no retries, the rules take over instead.
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
            JsonNode message = json.readTree(response.body()).path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            if (message.hasNonNull("refusal") || content.isBlank()) return Interpretation.NONE;
            return json.readValue(content, Interpretation.class);
        };
    }

    /** The JSON schema the model must answer in, derived from {@link Interpretation}: every field required. */
    static ObjectNode schema(ObjectMapper json) throws NoSuchFieldException {
        ObjectNode schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        var required = schema.putArray("required");
        for (RecordComponent c : Interpretation.class.getRecordComponents()) {
            ObjectNode p = props.putObject(c.getName()).put("type", c.getType() == double.class ? "number" : "string");
            // The annotation can't target a record component, so it lands on the backing field.
            JsonPropertyDescription d = Interpretation.class.getDeclaredField(c.getName()).getAnnotation(JsonPropertyDescription.class);
            if (d != null) p.put("description", d.value());
            required.add(c.getName());
        }
        return schema;
    }

    private static String up(String s) {
        return s == null ? "" : s.strip().toUpperCase();
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }
}
