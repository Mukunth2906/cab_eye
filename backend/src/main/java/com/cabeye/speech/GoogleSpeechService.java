package com.cabeye.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Speech-to-Text and Text-to-Speech, over their REST endpoints.
 *
 * <p>WHY REST AND NOT THE CLOUD SDK
 *
 * <p>{@code google-cloud-speech} drags in gRPC, Netty, protobuf and a
 * service-account JSON file, and its streaming API is genuinely better
 * for long audio. None of that helps here: the utterances this app
 * transcribes are one sentence long, so a single synchronous request is
 * the right shape, and two REST calls with an API key add nothing to the
 * build. If continuous streaming recognition is ever needed on the
 * server — it currently is not, because recognition happens on the
 * device — this class is the one to replace.
 *
 * <p>WHY THIS EXISTS AT ALL, GIVEN THE BROWSER CAN ALREADY LISTEN
 *
 * <p>The Web Speech API is free and on-device-ish, but it is Chrome-only,
 * it will not switch languages mid-utterance, and it has no useful Tamil.
 * A Chennai rider saying "Velachery-ku poganum" is speaking two languages
 * in five words. {@code alternativeLanguageCodes} is the setting that
 * handles it, and only the cloud recogniser has it.
 */
public class GoogleSpeechService implements SpeechService {

    private static final Logger log = LoggerFactory.getLogger(GoogleSpeechService.class);

    private static final String STT_URL = "https://speech.googleapis.com/v1/speech:recognize";
    private static final String TTS_URL = "https://texttospeech.googleapis.com/v1/text:synthesize";

    private final SpeechProperties props;
    private final RestClient http;

    public GoogleSpeechService(SpeechProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    @Override
    public boolean available() {
        return props.configured();
    }

    @Override
    public Transcript transcribe(byte[] audio, String contentType, String language) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("encoding", encodingFor(contentType));
        config.put("languageCode", language != null && !language.isBlank()
                ? language : props.getLanguageCode());
        config.put("alternativeLanguageCodes", props.getAlternativeLanguageCodes());
        config.put("enableAutomaticPunctuation", false);   // a destination is not a sentence
        config.put("maxAlternatives", 3);
        config.put("model", "latest_short");               // one utterance, not a dictation
        config.put("useEnhanced", true);

        // WEBM_OPUS carries its own sample rate; declaring one conflicts.
        if ("LINEAR16".equals(config.get("encoding"))) {
            config.put("sampleRateHertz", 16000);
        }

        Map<String, Object> body = Map.of(
                "config", config,
                "audio", Map.of("content", java.util.Base64.getEncoder().encodeToString(audio))
        );

        try {
            Map<?, ?> res = post(STT_URL, body);
            List<?> results = (List<?>) res.get("results");
            if (results == null || results.isEmpty()) {
                return new Transcript("", 0, language, List.of());
            }

            // Google returns one result per detected segment. One
            // utterance can still split, so stitch the transcripts.
            StringBuilder text = new StringBuilder();
            double confidence = 0;
            List<String> alternatives = new java.util.ArrayList<>();

            for (Object r : results) {
                List<?> alts = (List<?>) ((Map<?, ?>) r).get("alternatives");
                if (alts == null || alts.isEmpty()) {
                    continue;
                }
                Map<?, ?> top = (Map<?, ?>) alts.get(0);
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(String.valueOf(top.get("transcript")).trim());
                Object c = top.get("confidence");
                if (c instanceof Number n) {
                    confidence = Math.max(confidence, n.doubleValue());
                }
                for (int i = 1; i < alts.size(); i++) {
                    Object t = ((Map<?, ?>) alts.get(i)).get("transcript");
                    if (t != null) {
                        alternatives.add(String.valueOf(t).trim());
                    }
                }
            }
            return new Transcript(text.toString().trim(), confidence, language, alternatives);

        } catch (RestClientException e) {
            log.warn("Google speech-to-text failed: {}", e.getMessage());
            throw new SpeechUnavailableException("Transcription failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Audio synthesize(String text, String language, String voice) {
        Map<String, Object> voiceConf = new LinkedHashMap<>();
        voiceConf.put("languageCode", language != null && !language.isBlank()
                ? language : props.getLanguageCode());
        String name = voice != null && !voice.isBlank() ? voice : props.getVoiceName();
        if (name != null && !name.isBlank()) {
            voiceConf.put("name", name);
        }

        Map<String, Object> body = Map.of(
                "input", Map.of("text", text),
                "voice", voiceConf,
                "audioConfig", Map.of(
                        "audioEncoding", "MP3",
                        "speakingRate", props.getSpeakingRate()
                )
        );

        try {
            Map<?, ?> res = post(TTS_URL, body);
            Object content = res.get("audioContent");
            if (content == null) {
                throw new SpeechUnavailableException("Google returned no audio", null);
            }
            return new Audio(java.util.Base64.getDecoder().decode(String.valueOf(content)), "audio/mpeg");
        } catch (RestClientException e) {
            log.warn("Google text-to-speech failed: {}", e.getMessage());
            throw new SpeechUnavailableException("Synthesis failed: " + e.getMessage(), e);
        }
    }

    /** One authenticated JSON POST, whichever credential is configured. */
    private Map<?, ?> post(String url, Object body) {
        String target = url;
        var spec = http.post();

        if (props.getAccessToken() != null && !props.getAccessToken().isBlank()) {
            spec = http.post();
            return spec.uri(target)
                    .header("Authorization", "Bearer " + props.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        }

        target = url + "?key=" + props.getApiKey();
        return spec.uri(target)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * What the browser actually uploads.
     *
     * <p>{@code MediaRecorder} in Chrome produces WebM/Opus and nothing
     * else worth having, so that is the path that matters. The others are
     * here for a native client that records raw PCM.
     */
    private static String encodingFor(String contentType) {
        if (contentType == null) {
            return "WEBM_OPUS";
        }
        String c = contentType.toLowerCase();
        if (c.contains("webm"))  return "WEBM_OPUS";
        if (c.contains("ogg"))   return "OGG_OPUS";
        if (c.contains("flac"))  return "FLAC";
        if (c.contains("l16") || c.contains("wav") || c.contains("pcm")) return "LINEAR16";
        if (c.contains("mp3"))   return "MP3";
        return "WEBM_OPUS";
    }
}
