package com.cabeye.backend.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * "What's in front of me": one camera picture in, a short spoken-ready description out.
 *
 * The rider's phone sends a JPEG only when the rider asks (or, in "keep telling me", every few
 * seconds while they walk). Gemini returns the objects that matter for walking with their boxes,
 * hazards first, and any readable text. Everything is re-checked here before it reaches the rider:
 * boxes are clamped, clock positions are worked out from the boxes (not trusted from the model),
 * lists are capped, and any claim that the way is "clear" or "safe" is removed — the app cannot
 * know that, and neither can the model.
 *
 * Off when no Gemini key is configured: the endpoint answers 404 and the phone uses its own
 * on-device detector instead.
 */
@Service
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);

    public static final int PER_MINUTE = 20;
    public static final int MAX_IMAGE_BASE64 = 2_000_000;
    static final int MAX_OBJECTS = 8;

    private static final Pattern CLEAR = Pattern.compile(
            "[^.]*\\b(path|way|road|route|area)\\b[^.]*\\b(is|looks|seems)\\s+(clear|safe|free)[^.]*\\.?|" +
                    "[^.]*\\b(clear|safe) to (cross|walk|go|proceed)[^.]*\\.?|[^.]*\\bno obstacles?\\b[^.]*\\.?",
            Pattern.CASE_INSENSITIVE);

    public enum Mode { DESCRIBE, FIND, READ }

    public record SceneObject(String label, int[] box, String clock, Double distanceM) {}

    /**
     * @param found for FIND: a sentence saying whether and where the target is
     * @param source "gemini"
     */
    public record Scene(String summary, List<SceneObject> objects, List<String> hazards, String text,
                        String found, String source) {}

    public sealed interface Result permits Result.Ok, Result.Off, Result.Limited, Result.Failed {
        record Ok(Scene scene) implements Result {}
        record Off() implements Result {}
        record Limited() implements Result {}
        record Failed(String reason) implements Result {}
    }

    static final String SYSTEM = """
            You are the eyes of a blind pedestrian in India. The picture is from their phone camera, held at chest \
            height and pointing the way they face. Answer with ONE JSON object and nothing else:
            {"summary": "one or two short spoken sentences", \
            "objects": [{"label": "door", "box": [ymin, xmin, ymax, xmax], "distance_m": 3}], \
            "hazards": ["steps going down, ahead"], "text": "readable text, or empty", "found": ""}
            Rules:
            - box: the object's bounding box, coordinates normalised 0-1000 (like box_2d).
            - Up to 8 objects that matter for walking or orientation: doors, gates, steps, stairs, kerbs, ramps, \
            poles, walls, vehicles, autos, two-wheelers, people, animals, chairs, benches, tables, counters, \
            bins, potholes, puddles, shops, bus stops, trees.
            - hazards: anything that could trip, hit or hurt them (steps, kerbs, holes, moving vehicles, \
            obstacles at head height, wet floor), most urgent first. Empty list if none.
            - distance_m: a rough estimate in metres, or null.
            - Never say a path, road or way is clear or safe; you cannot know that.
            - No colours unless the user asks; plain words; no markdown.""";

    private final GeminiClient gemini;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public VisionService(GeminiClient gemini) {
        this.gemini = gemini;
    }

    public boolean enabled() {
        return gemini.enabled();
    }

    public Result describe(String riderId, String imageBase64, Mode mode, String target) {
        if (!gemini.enabled()) return new Result.Off();
        if (imageBase64 == null || imageBase64.isBlank() || imageBase64.length() > MAX_IMAGE_BASE64) {
            return new Result.Failed("image must be a JPEG under 1.5 MB");
        }
        if (!allow(riderId, System.currentTimeMillis())) return new Result.Limited();

        String ask = switch (mode) {
            case DESCRIBE -> "Describe what is in front of me.";
            case READ -> "Read me any text in the picture (signs, labels, boards, papers) in reading order, " +
                    "into \"text\". The summary says what kind of text it is.";
            case FIND -> "I am looking for: " + clean(target, 60) + ". In \"found\", say in one sentence whether you " +
                    "can see it, and where as a clock position (12 = straight ahead, 9 = left, 3 = right) and " +
                    "roughly how far. Still list the other objects.";
        };
        String data = imageBase64.replaceFirst("^data:image/[a-z]+;base64,", "");
        try {
            String answer = gemini.chat(SYSTEM, gemini.parts(gemini.text(ask), gemini.jpeg(data)), 700, 20_000);
            Scene scene = parse(answer, mode);
            log.info("VISION rider={} mode={} objects={} hazards={}", riderId, mode, scene.objects().size(), scene.hazards().size());
            return new Result.Ok(scene);
        } catch (GeminiClient.GeminiException e) {
            return new Result.Failed("vision unavailable");
        } catch (Exception e) {
            log.warn("VISION could not read the answer: {}", e.getClass().getSimpleName());
            return new Result.Failed("vision unavailable");
        }
    }

    /** The model's answer, checked. Package-private for tests. */
    Scene parse(String answer, Mode mode) throws Exception {
        JsonNode o = json.readTree(GeminiClient.jsonIn(answer));
        List<SceneObject> objects = new ArrayList<>();
        for (JsonNode n : o.path("objects")) {
            String label = clean(n.path("label").asText(""), 40).toLowerCase(Locale.ROOT);
            if (label.isEmpty()) continue;
            int[] box = box(n.path("box"));
            Double d = n.path("distance_m").isNumber() ? Math.max(0.2, Math.min(200, n.path("distance_m").asDouble())) : null;
            objects.add(new SceneObject(label, box, box == null ? "" : clock(box), d));
            if (objects.size() >= MAX_OBJECTS) break;
        }
        List<String> hazards = new ArrayList<>();
        for (JsonNode h : o.path("hazards")) {
            String s = noClearClaims(clean(h.asText(""), 80));
            if (!s.isEmpty()) hazards.add(s);
            if (hazards.size() >= 4) break;
        }
        return new Scene(
                noClearClaims(clean(o.path("summary").asText(""), 300)),
                objects, hazards,
                mode == Mode.READ || mode == Mode.DESCRIBE ? clean(o.path("text").asText(""), 600) : "",
                mode == Mode.FIND ? noClearClaims(clean(o.path("found").asText(""), 200)) : "",
                "gemini");
    }

    private static int[] box(JsonNode b) {
        if (!b.isArray() || b.size() != 4) return null;
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!b.get(i).isNumber()) return null;
            v[i] = (int) Math.max(0, Math.min(1000, Math.round(b.get(i).asDouble())));
        }
        if (v[2] <= v[0] || v[3] <= v[1]) return null;
        return v;
    }

    /**
     * Clock position from the box's horizontal centre, for a phone camera's ~65° view:
     * left edge ≈ 10 o'clock, centre 12, right edge ≈ 2.
     */
    static String clock(int[] box) {
        double x = (box[1] + box[3]) / 2.0;
        if (x < 200) return "10 o'clock";
        if (x < 400) return "11 o'clock";
        if (x <= 600) return "12 o'clock";
        if (x <= 800) return "1 o'clock";
        return "2 o'clock";
    }

    static String noClearClaims(String s) {
        return CLEAR.matcher(s).replaceAll("").replaceAll("\\s{2,}", " ").trim();
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[*_#`]", "").strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** At most {@link #PER_MINUTE} pictures per rider per minute. */
    boolean allow(String riderId, long now) {
        Deque<Long> q = recent.computeIfAbsent(riderId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000L) q.pollFirst();
            if (q.size() >= PER_MINUTE) return false;
            q.addLast(now);
            return true;
        }
    }
}
