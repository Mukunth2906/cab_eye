package com.cabeye.backend;

import com.cabeye.backend.places.OsmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real map data with no key, checked against answers recorded from OpenStreetMap for
 * Coimbatore (src/test/resources/osm): area names a person would say, never a ward number;
 * well-known places ranked by what the data says about them; caching; the busy-server page;
 * and the endpoints' guards. Tests never reach the network.
 */
@SpringBootTest(properties = {
        "cabeye.data.dir=build/test-data/osm-${random.uuid}",
        "cabeye.osm.enabled=false"
})
@AutoConfigureMockMvc
class OsmPlacesTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static String fixture(String name) {
        try (InputStream in = OsmPlacesTest.class.getResourceAsStream("/osm/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Answers by what is asked: the recorded Nominatim reply for the nearest fixture point, the recorded Overpass reply. */
    private static class Recorded implements OsmService.Http {
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger posts = new AtomicInteger();
        String overpass = fixture("overpass-town-hall.json");

        @Override
        public String get(String url) {
            gets.incrementAndGet();
            // A point OpenStreetMap only knows as "Ward 23" (recorded live, trimmed to the address).
            if (url.contains("lat=11.0068&lon=76.9560")) {
                return "{\"address\":{\"suburb\":\"Ward 23\",\"city_district\":\"West Zone\",\"city\":\"Coimbatore\"}}";
            }
            if (url.contains("lat=11.0068")) return fixture("nominatim-rs-puram.json");
            if (url.contains("lat=10.9860")) return fixture("nominatim-ward.json");
            return fixture("nominatim-five-corner.json");
        }

        @Override
        public String post(String url, String form) {
            posts.incrementAndGet();
            if (url.contains("busy")) return "<html><p>Error: runtime error: the server is probably too busy</p></html>";
            if (java.net.URLDecoder.decode(form, StandardCharsets.UTF_8).contains("[\"place\"")) {
                return fixture("overpass-places-near-rs-puram.json");
            }
            return overpass;
        }
    }

    @Test
    @DisplayName("area names are what a person says: the neighbourhood, never 'Ward 85' or a zone")
    void areaNames() {
        Recorded http = new Recorded();
        OsmService osm = new OsmService(true, http, "https://nominatim.test", "https://overpass.test");
        assertEquals(Optional.of("RS Puram"), osm.area(11.0068, 76.9490));
        assertEquals(Optional.of("Five Corner"), osm.area(10.9985, 76.9640));
        // suburb "Ward 85" is skipped for the real locality
        assertEquals(Optional.of("Podanur"), osm.area(10.9860, 76.9600));
        // Only a ward number in the address: the nearest named neighbourhood (RS Puram, 650 m).
        assertEquals(Optional.of("RS Puram"), osm.area(11.0068, 76.9560));
        // same spot (within ~100 m) again: from the cache, not the server
        int before = http.gets.get();
        assertEquals(Optional.of("RS Puram"), osm.area(11.0069, 76.9491));
        assertEquals(before, http.gets.get());
    }

    @Test
    @DisplayName("Nominatim is asked at most once a second")
    void nominatimRate() {
        Recorded http = new Recorded();
        OsmService osm = new OsmService(true, http, "https://nominatim.test", "https://overpass.test");
        long start = System.currentTimeMillis();
        osm.area(11.0068, 76.9490);
        osm.area(10.9860, 76.9600);
        assertTrue(System.currentTimeMillis() - start >= 1_000, "second lookup waited for its turn");
    }

    @Test
    @DisplayName("well-known places: Wikipedia-linked, attractions and stations first; ordinary clinics only as filler")
    void wellKnownPlaces() {
        Recorded http = new Recorded();
        OsmService osm = new OsmService(true, http, "https://nominatim.test", "https://overpass.test");
        List<OsmService.Place> places = osm.nearby(10.9985, 76.9640, 600);
        List<String> names = places.stream().map(OsmService.Place::name).toList();
        assertEquals(List.of("Coimbatore Junction", "Koniamman Temple", "Coimbatore Medical College Hospital"), names.subList(0, 3));
        assertTrue(names.containsAll(List.of("Clock Tower", "Tamil Nadu Police Museum", "Ashok Pillar")), names.toString());
        // Plain hospitals and small temples are not "well known" when better places are near.
        assertFalse(names.contains("Moses Eye Hospital"), names.toString());
        assertFalse(names.contains("Amman temple"), names.toString());
        assertTrue(places.stream().allMatch(OsmService.Place::notable));
        OsmService.Place temple = places.get(1);
        assertEquals(List.of("hindu_temple", "place_of_worship"), temple.types());
        assertEquals("Q19806101", temple.wikidata());
        assertEquals(List.of("train_station"), places.get(0).types());
        // cached for the day
        osm.nearby(10.9985, 76.9640, 600);
        assertEquals(1, http.posts.get());
    }

    @Test
    @DisplayName("a busy Overpass server (200 with an HTML error) is skipped for the next one, and never cached")
    void busyServer() {
        Recorded http = new Recorded();
        OsmService osm = new OsmService(true, http, "https://nominatim.test", "https://busy.test,https://overpass.test");
        assertEquals("Coimbatore Junction", osm.nearby(10.9985, 76.9640, 600).get(0).name());
        assertEquals(2, http.posts.get());

        OsmService allBusy = new OsmService(true, http, "https://nominatim.test", "https://busy.test");
        assertTrue(allBusy.nearby(10.9985, 76.9640, 600).isEmpty());
        assertTrue(allBusy.nearby(10.9985, 76.9640, 600).isEmpty());
        assertEquals(4, http.posts.get()); // asked again: a failure is not remembered
    }

    @Test
    @DisplayName("endpoints: sign-in required, 404 when switched off")
    void endpoints() throws Exception {
        mvc.perform(get("/me/places/area?lat=11.0068&lng=76.9490")).andExpect(status().isUnauthorized());
        String phone = "9720" + String.format("%06d", (int) (Math.random() * 1_000_000));
        String code = json.readTree(mvc.perform(post("/auth/otp").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"RIDER\"}")).andReturn().getResponse().getContentAsString())
                .get("devCode").asText();
        String token = json.readTree(mvc.perform(post("/auth/verify").contentType("application/json")
                .content("{\"phone\":\"" + phone + "\",\"role\":\"RIDER\",\"code\":\"" + code + "\",\"name\":\"T\"}"))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        mvc.perform(get("/me/places/area?lat=11.0068&lng=76.9490").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/me/places/nearby?lat=11.0068&lng=76.9490").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
