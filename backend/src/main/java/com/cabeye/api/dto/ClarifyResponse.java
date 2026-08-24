package com.cabeye.api.dto;

import com.cabeye.nlu.Place;

/**
 * Either the rider settled the question, or they did not and it has to be
 * asked again. {@code prompt} carries the exact re-ask wording so the
 * client never has to compose it.
 */
public record ClarifyResponse(
        boolean decided,
        int index,
        InterpretResponse.PlaceDto place,
        String prompt
) {
    public static ClarifyResponse picked(int index, Place place) {
        return new ClarifyResponse(true, index,
                new InterpretResponse.PlaceDto(place.name(), place.lat(), place.lng()), null);
    }

    public static ClarifyResponse unresolved(String prompt) {
        return new ClarifyResponse(false, -1, null, prompt);
    }
}
