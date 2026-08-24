package com.cabeye.api;

import com.cabeye.speech.SpeechService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Cloud speech, for the cases the browser cannot handle.
 *
 * <p>These endpoints are an escape hatch, not the main path. The rider
 * app listens with the browser's own recogniser because that is instant,
 * free, and needs no upload; it reaches for these only when the browser
 * has no recogniser at all, or when the utterance is Tamil.
 *
 * <p>{@code GET /capabilities} exists so the client can find that out at
 * start-up instead of discovering it through a failed upload mid-booking.
 */
@RestController
@RequestMapping("/api/v1/speech")
public class SpeechController {

    private final SpeechService speech;

    public SpeechController(SpeechService speech) {
        this.speech = speech;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "cloudSpeech", speech.available(),
                "transcribe", speech.available(),
                "synthesize", speech.available(),
                "languages", List.of("en-IN", "ta-IN"),
                "note", speech.available()
                        ? "Cloud speech is on. Use it for Tamil and code-mixed utterances."
                        : "Cloud speech is off. Use the browser's Web Speech API."
        );
    }

    /**
     * One utterance of audio in, a transcript out.
     *
     * <p>Multipart rather than base64 JSON: the browser already has a
     * {@code Blob} from {@code MediaRecorder}, and base64 would inflate
     * it by a third on a connection that may be a phone's.
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SpeechService.Transcript transcribe(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(required = false) String lang) throws IOException {

        return speech.transcribe(audio.getBytes(), audio.getContentType(), lang);
    }

    /**
     * Text in, MP3 out.
     *
     * <p>Returned as bytes rather than base64 so the client can hand the
     * response straight to an {@code <audio>} element or an
     * {@code ObjectURL} without a decode step in the middle of a spoken
     * turn.
     */
    @PostMapping(value = "/synthesize", produces = "audio/mpeg")
    public ResponseEntity<byte[]> synthesize(@RequestBody SynthesizeRequest req) {
        SpeechService.Audio out = speech.synthesize(req.text(), req.lang(), req.voice());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, out.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(out.bytes());
    }

    public record SynthesizeRequest(String text, String lang, String voice) { }
}
