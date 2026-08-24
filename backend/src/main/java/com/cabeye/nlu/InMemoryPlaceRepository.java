package com.cabeye.nlu;

import java.util.Comparator;
import java.util.List;

/**
 * The fifteen-place Chennai gazetteer, identical to the one in
 * {@code js/nlu.js}.
 *
 * <p>The two copies must agree, because the rider app falls back to its
 * own copy whenever this service is unreachable — and a fallback that
 * resolves a different destination than the server would is worse than
 * no fallback at all. {@code NluServiceTest} pins the parity.
 */
public class InMemoryPlaceRepository implements PlaceRepository {

    private static final List<Place> PLACES = List.of(
            new Place("Anna Nagar East", 13.0878, 80.2183),
            new Place("Anna Nagar West", 13.0850, 80.1998),
            new Place("T Nagar",         13.0418, 80.2341),
            new Place("Besant Nagar",    13.0002, 80.2668),
            new Place("Adyar",           13.0067, 80.2570),
            new Place("Velachery",       12.9750, 80.2210),
            new Place("Guindy",          13.0067, 80.2206),
            new Place("Nungambakkam",    13.0569, 80.2425),
            new Place("Mylapore",        13.0339, 80.2698),
            new Place("Egmore",          13.0732, 80.2609),
            new Place("Chennai Central", 13.0827, 80.2755),
            new Place("Chennai Airport", 12.9941, 80.1709),
            new Place("Tambaram",        12.9229, 80.1275),
            new Place("Porur",           13.0374, 80.1575),
            new Place("Sholinganallur",  12.9010, 80.2279)
    );

    @Override
    public List<Place> findAll() {
        return PLACES;
    }

    @Override
    public List<Place> search(String query, int limit) {
        return PLACES.stream()
                .map(p -> new Scored(p, Scorer.score(query, p.name())))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(limit)
                .map(Scored::place)
                .toList();
    }

    private record Scored(Place place, double score) { }
}
