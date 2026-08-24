package com.cabeye.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One utterance, as the rider app heard it.
 *
 * @param text      the transcript. Never the audio — transcription is a
 *                  separate endpoint, so a client that already has
 *                  on-device recognition never uploads a recording.
 * @param lang      BCP-47 tag; "en-IN" today, "ta-IN" once the Tamil
 *                  gazetteer lands.
 * @param sessionId opaque, client-generated. Used only to correlate log
 *                  lines across a booking; nothing is persisted against it.
 */
public record InterpretRequest(
        @NotBlank @Size(max = 500) String text,
        String lang,
        String sessionId
) {
    public String langOrDefault() {
        return lang == null || lang.isBlank() ? "en-IN" : lang;
    }
}
