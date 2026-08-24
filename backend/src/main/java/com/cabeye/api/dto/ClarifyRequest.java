package com.cabeye.api.dto;

import com.cabeye.nlu.Place;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The rider's spoken answer to "Did you mean X, or Y?", together with the
 * X and Y that were offered.
 *
 * <p>Sending the candidates back rather than looking them up by a
 * server-side session id is what keeps this endpoint stateless — and
 * therefore what lets the browser fall back to answering the question
 * locally when the network drops mid-booking.
 */
public record ClarifyRequest(
        @NotBlank String answer,
        @NotNull List<InterpretResponse.PlaceDto> candidates,
        String sessionId
) {
    public List<Place> toPlaces() {
        return candidates == null ? List.of()
                : candidates.stream().map(c -> new Place(c.n(), c.lat(), c.lng())).toList();
    }
}
