package com.cabeye.backend.controller;

import com.cabeye.backend.places.OsmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real map data for the rider's mind map, with no API key (OpenStreetMap, through
 * {@link OsmService}). Under /me so only signed-in riders can use this server as a proxy.
 *
 * <pre>
 *   GET /me/places/area?lat=…&amp;lng=…                → {"area":"RS Puram"} or {"area":null}
 *   GET /me/places/nearby?lat=…&amp;lng=…&amp;radius=600  → {"places":[{name,lat,lng,types,notable,wikidata}], "attribution":"…"}
 *   404 when disabled (cabeye.osm.enabled=false), 400 for coordinates off the map
 * </pre>
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/me/places")
public class PlacesController {

    private static final String ATTRIBUTION = "Data © OpenStreetMap contributors, ODbL";

    private final OsmService osm;

    public PlacesController(OsmService osm) {
        this.osm = osm;
    }

    @GetMapping("/area")
    public ResponseEntity<?> area(@RequestParam double lat, @RequestParam double lng) {
        if (!osm.enabled()) return ResponseEntity.notFound().build();
        if (!valid(lat, lng)) return ResponseEntity.badRequest().body(Map.of("error", "bad coordinates"));
        Map<String, Object> out = new HashMap<>();
        out.put("area", osm.area(lat, lng).orElse(null));
        out.put("attribution", ATTRIBUTION);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/nearby")
    public ResponseEntity<?> nearby(@RequestParam double lat, @RequestParam double lng,
                                    @RequestParam(defaultValue = "600") int radius) {
        if (!osm.enabled()) return ResponseEntity.notFound().build();
        if (!valid(lat, lng)) return ResponseEntity.badRequest().body(Map.of("error", "bad coordinates"));
        List<OsmService.Place> places = osm.nearby(lat, lng, radius);
        return ResponseEntity.ok(Map.of("places", places, "attribution", ATTRIBUTION));
    }

    private static boolean valid(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180 && !(lat == 0 && lng == 0);
    }
}
