package com.cabeye.speech;

import java.util.List;

/**
 * Speech in, speech out — behind an interface so the rider app never
 * learns which vendor is doing it, and so swapping Google for an
 * on-device model later touches one class.
 */
public interface SpeechService {

    /** False when no credentials are configured; callers fall back to the browser. */
    boolean available();

    Transcript transcribe(byte[] audio, String contentType, String language);

    Audio synthesize(String text, String language, String voice);

    /**
     * @param alternatives runner-up readings. Worth keeping: when the top
     *                     reading resolves to nothing in the gazetteer, a
     *                     second-choice transcript often resolves cleanly,
     *                     which saves a spoken "say that again".
     */
    record Transcript(String text, double confidence, String language, List<String> alternatives) { }

    record Audio(byte[] bytes, String contentType) { }
}
