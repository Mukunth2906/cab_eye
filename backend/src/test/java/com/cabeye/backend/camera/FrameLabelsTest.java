package com.cabeye.backend.camera;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the rider's phone detected travels to the driver with the picture — checked on the way. */
class FrameLabelsTest {

    @Test
    @DisplayName("well-formed labels pass, clamped; anything else is dropped; at most 8")
    void labelsAreChecked() {
        List<Object> raw = new java.util.ArrayList<>();
        raw.add(Map.of("label", "door", "clock", "1 o'clock", "box", List.of(0.6, 0.1, 1.4, 0.9)));
        raw.add(Map.of("label", "", "box", List.of(0, 0, 1, 1)));
        raw.add(Map.of("label", "no box"));
        raw.add(Map.of("label", "bad box", "box", List.of("a", 0, 1, 1)));
        raw.add("not a map");
        for (int i = 0; i < 12; i++) raw.add(Map.of("label", "chair " + i, "box", List.of(0, 0, 0.5, 0.5)));

        List<Map<String, Object>> out = RideCameraService.labels(raw);
        assertEquals(8, out.size());
        assertEquals("door", out.get(0).get("label"));
        assertEquals(List.of(0.6, 0.1, 1.0, 0.9), out.get(0).get("box"));
        assertEquals("1 o'clock", out.get(0).get("clock"));
        assertTrue(RideCameraService.labels("nope").isEmpty());
    }
}
