package com.cabeye.api.dto;

import com.cabeye.nlu.Place;
import com.cabeye.nlu.Resolution;

import java.util.List;

/**
 * The shape the rider app consumes.
 *
 * <p>Kept structurally identical to what {@code js/nlu.js} returns
 * locally, so {@code CE.nlu.resolve} can hand the app either one and the
 * state machine cannot tell the difference. That is what makes the
 * offline fallback honest rather than a second, subtly different app.
 */
public record InterpretResponse(
        boolean ok,
        boolean ask,
        PlaceDto place,
        List<PlaceDto> candidates,
        String query,
        String rideType,
        boolean fast,
        double gap,
        double div,
        boolean tied,
        String prompt,
        String source
) {

    /** Mirrors the {n, lat, lng} objects the browser gazetteer uses. */
    public record PlaceDto(String n, double lat, double lng) {
        static PlaceDto of(Place p) {
            return p == null ? null : new PlaceDto(p.name(), p.lat(), p.lng());
        }
    }

    public static InterpretResponse from(Resolution r) {
        return new InterpretResponse(
                r.resolved(),
                r.needsClarify(),
                PlaceDto.of(r.place()),
                r.candidates().stream().map(PlaceDto::of).toList(),
                r.query(),
                r.rideType(),
                r.fastPath(),
                round(r.gap()),
                round(r.divergenceKm()),
                r.tied(),
                r.prompt(),
                "server"
        );
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
