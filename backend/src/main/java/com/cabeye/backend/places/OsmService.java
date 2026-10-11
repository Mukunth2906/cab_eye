package com.cabeye.backend.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Real map data with no API key, from OpenStreetMap: the name of the area a point is in
 * (Nominatim) and the well-known places around it (Overpass).
 *
 * <p>The rider's mind map uses these when the phone has no Google key, and when the phone's own
 * geocoder can't name an area. Requests go through this server, not from each phone, because the
 * OpenStreetMap services ask for exactly what only one place can guarantee: an identifying
 * User-Agent, caching, and for Nominatim at most one request a second across all users. It also
 * keeps the rider's IP address away from third parties.
 *
 * <p>OpenStreetMap has no ratings. "Well known" is read from the data itself: a place linked to
 * Wikipedia or Wikidata, a tourist attraction, a monument, a railway station or a mall ranks
 * above an ordinary temple or clinic, which only fill the list when nothing better is near.
 *
 * <p>Data © OpenStreetMap contributors, ODbL.
 */
@Service
public class OsmService {

    private static final Logger log = LoggerFactory.getLogger(OsmService.class);

    /** The HTTP calls, separate so tests use recorded answers and never the network. */
    public interface Http {
        String get(String url) throws Exception;
        String post(String url, String form) throws Exception;
    }

    /** A place near the rider. [types] use Google's names, so the phone describes both alike. */
    public record Place(String name, double lat, double lng, List<String> types, boolean notable, String wikidata) {}

    public static final int MAX_PLACES = 10;
    public static final int MAX_RADIUS_M = 1_500;
    static final long AREA_TTL_MS = 7L * 24 * 3_600_000;
    static final long NEARBY_TTL_MS = 24L * 3_600_000;
    /** Nominatim's policy: at most one request a second. */
    static final long NOMINATIM_GAP_MS = 1_100;
    /** A caller waits at most this long for its turn; after that the answer is "not known". */
    static final long NOMINATIM_MAX_WAIT_MS = 2_500;
    private static final int CACHE_SIZE = 5_000;

    /** "Ward 85", "Zone 3": administrative numbers nobody says out loud. */
    private static final Pattern ADMIN = Pattern.compile("^(ward|zone|division|sector)\\s*(no\\.?\\s*)?\\d+\\b.*|.*\\bzone$",
            Pattern.CASE_INSENSITIVE);

    private final Http http;
    private final boolean enabled;
    private final String nominatim;
    private final String overpass;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached<Optional<String>>> areas = lru();
    private final Map<String, Cached<List<Place>>> nearby = lru();
    private final Object nominatimTurn = new Object();
    private long lastNominatimAt = 0;

    private record Cached<T>(T value, long at) {}

    @Autowired
    public OsmService(@Value("${cabeye.osm.enabled:true}") boolean enabled,
                      @Value("${cabeye.osm.user-agent:CabEye/0.1 (accessible ride-hailing for blind riders; https://github.com/Mukunth2906/cab_eye)}") String userAgent,
                      @Value("${cabeye.osm.nominatim-url:https://nominatim.openstreetmap.org}") String nominatim,
                      @Value("${cabeye.osm.overpass-url:https://overpass-api.de/api/interpreter,https://z.overpass-api.de/api/interpreter}") String overpass) {
        this(enabled, javaHttp(userAgent), nominatim, overpass);
    }

    public OsmService(boolean enabled, Http http, String nominatim, String overpass) {
        this.enabled = enabled;
        this.http = http;
        this.nominatim = nominatim;
        this.overpass = overpass;
        log.info(enabled ? "OSM places enabled (Nominatim + Overpass, cached)" : "OSM places disabled");
    }

    public boolean enabled() {
        return enabled;
    }

    // ---- area names ------------------------------------------------------------------------

    /** The neighbourhood a point is in ("RS Puram"), or empty when it can't be named. */
    public Optional<String> area(double lat, double lng) {
        if (!enabled) return Optional.empty();
        String key = String.format(Locale.ROOT, "%.3f,%.3f", lat, lng);
        Cached<Optional<String>> hit = get(areas, key, AREA_TTL_MS);
        if (hit != null) return hit.value();
        if (!takeNominatimTurn()) return Optional.empty(); // busy: not cached, asked again later
        try {
            String url = nominatim + "/reverse?format=jsonv2&zoom=16&addressdetails=1&accept-language=en" +
                    String.format(Locale.ROOT, "&lat=%.4f&lon=%.4f", lat, lng);
            String name = areaFrom(json.readTree(http.get(url)).path("address"));
            if (name == null) {
                // Only a ward number here: the nearest named neighbourhood on the map will do.
                JsonNode places = overpass("[out:json][timeout:15];node(" +
                        String.format(Locale.ROOT, "around:%d,%.4f,%.4f", NEAREST_AREA_M, lat, lng) +
                        ")[\"place\"~\"^(suburb|neighbourhood|quarter)$\"][\"name\"];out 30;");
                if (places == null) return Optional.empty(); // busy: not cached, asked again later
                name = nearestPlaceName(places, lat, lng, NEAREST_AREA_M);
            }
            Optional<String> found = Optional.ofNullable(name);
            put(areas, key, found);
            return found;
        } catch (Exception e) {
            log.warn("OSM area failure type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static final int NEAREST_AREA_M = 1_000;

    /** The nearest named place node within [maxM], or null. */
    static String nearestPlaceName(JsonNode root, double lat, double lng, int maxM) {
        String best = null;
        double bestM = maxM;
        for (JsonNode e : root.path("elements")) {
            JsonNode tags = e.path("tags");
            String name = tags.path("name:en").asText(tags.path("name").asText("")).trim();
            if (name.isEmpty() || ADMIN.matcher(name).matches() || !e.has("lat")) continue;
            double m = metres(lat, lng, e.path("lat").asDouble(), e.path("lon").asDouble());
            if (m <= bestM) {
                bestM = m;
                best = name;
            }
        }
        return best;
    }

    /** One Overpass query, tried on each configured server; null when none gave a real answer. */
    private JsonNode overpass(String query) {
        String form = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        // Public Overpass servers are often busy — and then may answer 200 with an HTML error page.
        for (String server : overpass.split(",")) {
            try {
                JsonNode root = json.readTree(http.post(server.trim(), form));
                if (root.has("elements")) return root;
            } catch (Exception e) {
                log.warn("OSM overpass failure server={} type={} detail={}", server.trim(), e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return null;
    }

    /** The name a person would use: the neighbourhood, else the suburb — never a ward number or a zone. */
    static String areaFrom(JsonNode address) {
        for (String field : List.of("neighbourhood", "quarter", "suburb", "residential", "village", "hamlet", "town")) {
            String v = address.path(field).asText("").trim();
            if (!v.isEmpty() && !ADMIN.matcher(v).matches()) return v;
        }
        return null;
    }

    private boolean takeNominatimTurn() {
        long deadline = System.currentTimeMillis() + NOMINATIM_MAX_WAIT_MS;
        synchronized (nominatimTurn) {
            while (true) {
                long now = System.currentTimeMillis();
                long wait = lastNominatimAt + NOMINATIM_GAP_MS - now;
                if (wait <= 0) {
                    lastNominatimAt = now;
                    return true;
                }
                if (now + wait > deadline) return false;
                try {
                    nominatimTurn.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    // ---- well-known places -------------------------------------------------------------------

    /** The best-known places within [radiusM], best first, at most {@link #MAX_PLACES}. */
    public List<Place> nearby(double lat, double lng, int radiusM) {
        if (!enabled) return List.of();
        int r = Math.max(100, Math.min(radiusM, MAX_RADIUS_M));
        String key = String.format(Locale.ROOT, "%.3f,%.3f,%d", lat, lng, r);
        Cached<List<Place>> hit = get(nearby, key, NEARBY_TTL_MS);
        if (hit != null) return hit.value();
        JsonNode root = overpass(overpassQuery(lat, lng, r));
        if (root == null) return List.of(); // only a real answer is cached
        List<Place> places = placesFrom(root, lat, lng);
        put(nearby, key, places);
        return places;
    }

    /**
     * One spatial search for named things, then filters on that set. Measured on a busy public
     * server: six separate "around" searches timed out (504) where this answered in 8 s.
     */
    static String overpassQuery(double lat, double lng, int r) {
        String at = String.format(Locale.ROOT, "around:%d,%.4f,%.4f", r, lat, lng);
        return "[out:json][timeout:15];nwr(" + at + ")[\"name\"]->.n;(" +
                "nwr.n[\"tourism\"~\"^(attraction|museum|viewpoint|zoo|theme_park|gallery)$\"];" +
                "nwr.n[\"historic\"];" +
                "nwr.n[\"amenity\"~\"^(place_of_worship|hospital|marketplace|bus_station|university|college)$\"];" +
                "nwr.n[\"railway\"=\"station\"];" +
                "nwr.n[\"leisure\"~\"^(park|stadium)$\"];" +
                "nwr.n[\"shop\"=\"mall\"];" +
                ");out center tags 80;";
    }

    /** Overpass elements → places, ranked by how well known they are, then by distance. */
    static List<Place> placesFrom(JsonNode root, double lat, double lng) {
        record Scored(Place place, int score, double metres) {}
        List<Scored> all = new ArrayList<>();
        for (JsonNode e : root.path("elements")) {
            JsonNode tags = e.path("tags");
            String name = tags.path("name:en").asText(tags.path("name").asText("")).trim();
            if (name.isEmpty()) continue;
            JsonNode at = e.has("center") ? e.path("center") : e;
            if (!at.has("lat") || !at.has("lon")) continue;
            double plat = at.path("lat").asDouble();
            double plng = at.path("lon").asDouble();
            String wikidata = tags.path("wikidata").asText("");
            boolean wiki = !wikidata.isEmpty() || tags.has("wikipedia");
            List<String> types = typesOf(tags);
            int score = (wiki ? 100 : 0) + kindScore(tags);
            all.add(new Scored(new Place(name, plat, plng, types, wiki || kindScore(tags) >= 30, wikidata), score,
                    metres(lat, lng, plat, plng)));
        }
        all.sort(Comparator.comparingInt((Scored s) -> -s.score()).thenComparingDouble(Scored::metres));
        List<Place> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Scored s : all) {
            String k = s.place().name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (!seen.add(k)) continue;
            // Ordinary places only fill the list when nothing better is near.
            if (!s.place().notable() && out.size() >= 3) continue;
            out.add(s.place());
            if (out.size() >= MAX_PLACES) break;
        }
        return out;
    }

    private static int kindScore(JsonNode t) {
        String tourism = t.path("tourism").asText("");
        String amenity = t.path("amenity").asText("");
        if (!tourism.isEmpty()) return 60;
        if (t.has("historic")) return 55;
        if ("station".equals(t.path("railway").asText(""))) return 50;
        if ("mall".equals(t.path("shop").asText(""))) return 40;
        if ("stadium".equals(t.path("leisure").asText(""))) return 40;
        if ("park".equals(t.path("leisure").asText(""))) return 35;
        if ("university".equals(amenity) || "college".equals(amenity)) return 35;
        if ("bus_station".equals(amenity)) return 30;
        if ("marketplace".equals(amenity)) return 25;
        if ("place_of_worship".equals(amenity)) return 20;
        if ("hospital".equals(amenity)) return 15;
        return 0;
    }

    /** OSM tags → the Google type names the phone already knows how to say. */
    static List<String> typesOf(JsonNode t) {
        List<String> types = new ArrayList<>();
        String amenity = t.path("amenity").asText("");
        if ("place_of_worship".equals(amenity)) {
            switch (t.path("religion").asText("")) {
                case "hindu" -> types.add("hindu_temple");
                case "christian" -> types.add("church");
                case "muslim" -> types.add("mosque");
                default -> { }
            }
            types.add("place_of_worship");
        }
        switch (amenity) {
            case "hospital" -> types.add("hospital");
            case "marketplace" -> types.add("market");
            case "bus_station" -> types.add("bus_station");
            case "university", "college" -> types.add("university");
            default -> { }
        }
        if ("station".equals(t.path("railway").asText(""))) types.add("train_station");
        if ("park".equals(t.path("leisure").asText(""))) types.add("park");
        if ("stadium".equals(t.path("leisure").asText(""))) types.add("stadium");
        if ("mall".equals(t.path("shop").asText(""))) types.add("shopping_mall");
        if ("museum".equals(t.path("tourism").asText(""))) types.add("museum");
        if (t.has("tourism") || t.has("historic")) types.add("tourist_attraction");
        return types;
    }

    static double metres(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6_371_000 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private static <T> Map<String, Cached<T>> lru() {
        return new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached<T>> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    private static <T> Cached<T> get(Map<String, Cached<T>> cache, String key, long ttl) {
        synchronized (cache) {
            Cached<T> c = cache.get(key);
            return c != null && System.currentTimeMillis() - c.at() < ttl ? c : null;
        }
    }

    private static <T> void put(Map<String, Cached<T>> cache, String key, T value) {
        synchronized (cache) {
            cache.put(key, new Cached<>(value, System.currentTimeMillis()));
        }
    }

    private static Http javaHttp(String userAgent) {
        // HTTP/1.1: the public OSM servers are plain HTTP/1.1 services behind simple proxies.
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        return new Http() {
            @Override
            public String get(String url) throws Exception {
                return send(HttpRequest.newBuilder(URI.create(url)).GET());
            }

            @Override
            public String post(String url, String form) throws Exception {
                return send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)));
            }

            private String send(HttpRequest.Builder b) throws Exception {
                HttpResponse<String> r = client.send(
                        // Overpass's own query limit is 15 s; a little over that before giving up.
                        b.header("User-Agent", userAgent).timeout(Duration.ofSeconds(18)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
                return r.body();
            }
        };
    }
}
