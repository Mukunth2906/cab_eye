package com.cabeye.nlu;

import java.util.List;

/**
 * Where places come from.
 *
 * <p>This interface exists so the swap to PostgreSQL + PostGIS is one
 * new class and no change anywhere else. Today the only implementation
 * holds fifteen Chennai neighbourhoods in a list; tomorrow's will run
 * a {@code ST_DWithin} query against a real table. Nothing above this
 * line needs to know which.
 */
public interface PlaceRepository {

    /** Every place the system can resolve to. */
    List<Place> findAll();

    /**
     * Places whose name plausibly matches a fragment of speech.
     *
     * <p>The in-memory implementation scores every row, which is fine
     * for fifteen and absurd for fifteen thousand. The PostGIS
     * implementation will push this into a trigram index.
     */
    List<Place> search(String query, int limit);
}
