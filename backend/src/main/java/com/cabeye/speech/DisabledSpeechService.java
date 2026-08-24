package com.cabeye.speech;

/**
 * What runs when no Google credentials are configured — which is the
 * normal state of a fresh checkout.
 *
 * <p>It refuses loudly rather than returning empty results, because a
 * silently-empty transcript is indistinguishable from "the user said
 * nothing" and would send the rider app into a retry loop. A 503 tells
 * the client exactly one thing: use the browser's recogniser instead.
 */
public class DisabledSpeechService implements SpeechService {

    private static final String WHY =
            "Cloud speech is not configured. Set cabeye.google.api-key to enable it; "
          + "until then the app uses the browser's own speech recognition.";

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Transcript transcribe(byte[] audio, String contentType, String language) {
        throw new SpeechUnavailableException(WHY, null);
    }

    @Override
    public Audio synthesize(String text, String language, String voice) {
        throw new SpeechUnavailableException(WHY, null);
    }
}
