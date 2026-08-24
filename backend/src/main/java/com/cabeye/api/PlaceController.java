package com.cabeye.api;

import com.cabeye.api.dto.InterpretResponse.PlaceDto;
import com.cabeye.nlu.PlaceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The gazetteer, readable.
 *
 * <p>Exists so the rider app can pull the place list once at start-up and
 * keep its offline fallback in step with the server, instead of shipping a
 * second hand-maintained copy that silently drifts.
 */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final PlaceRepository places;

    public PlaceController(PlaceRepository places) {
        this.places = places;
    }

    @GetMapping
    public List<PlaceDto> list(@RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "10") int limit) {
        var found = (q == null || q.isBlank())
                ? places.findAll()
                : places.search(q, Math.clamp(limit, 1, 50));
        return found.stream().map(p -> new PlaceDto(p.name(), p.lat(), p.lng())).toList();
    }
}
