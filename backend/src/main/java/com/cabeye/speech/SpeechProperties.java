package com.cabeye.speech;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Google Cloud Speech configuration.
 *
 * <p>Everything here is optional. With no credentials the speech
 * endpoints report themselves unavailable and the rider app keeps using
 * the browser's own Web Speech API — which is the right default for a
 * prototype, because it costs nothing and needs no billing account.
 *
 * <p>Set credentials when you want the thing the browser cannot do:
 * Tamil, and code-mixed Tamil-English in one utterance.
 */
@ConfigurationProperties(prefix = "cabeye.google")
public class SpeechProperties {

    /**
     * A Google Cloud API key. The simplest option, and enough for both
     * Speech-to-Text and Text-to-Speech. Restrict it to those two APIs
     * in the Cloud console — it travels in a query string.
     */
    private String apiKey;

    /**
     * An OAuth access token, if you would rather use a service account.
     * Takes precedence over the API key. Short-lived: mint it with
     * {@code gcloud auth print-access-token}, or inject one refreshed
     * outside this service.
     */
    private String accessToken;

    /** Primary recognition language. */
    private String languageCode = "en-IN";

    /**
     * Additional languages Google may switch to mid-utterance. This is
     * the setting that makes "Velachery-ku poganum" work: the recogniser
     * is allowed to hear Tamil words inside an English-tagged request.
     */
    private List<String> alternativeLanguageCodes = List.of("ta-IN");

    /** Voice for synthesis. Leave null to let Google pick for the locale. */
    private String voiceName;

    /** Slightly quicker than natural — experienced screen-reader users prefer it. */
    private double speakingRate = 1.06;

    private int timeoutSeconds = 12;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }

    public List<String> getAlternativeLanguageCodes() { return alternativeLanguageCodes; }
    public void setAlternativeLanguageCodes(List<String> v) { this.alternativeLanguageCodes = v; }

    public String getVoiceName() { return voiceName; }
    public void setVoiceName(String voiceName) { this.voiceName = voiceName; }

    public double getSpeakingRate() { return speakingRate; }
    public void setSpeakingRate(double speakingRate) { this.speakingRate = speakingRate; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    /** True when there is any way to authenticate to Google. */
    public boolean configured() {
        return (apiKey != null && !apiKey.isBlank())
            || (accessToken != null && !accessToken.isBlank());
    }
}
