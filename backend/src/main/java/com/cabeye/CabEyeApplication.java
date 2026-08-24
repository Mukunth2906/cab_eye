package com.cabeye;

import com.cabeye.nlu.InMemoryPlaceRepository;
import com.cabeye.nlu.NluService;
import com.cabeye.nlu.PlaceRepository;
import com.cabeye.speech.DisabledSpeechService;
import com.cabeye.speech.GoogleSpeechService;
import com.cabeye.speech.SpeechProperties;
import com.cabeye.speech.SpeechService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableConfigurationProperties(SpeechProperties.class)
public class CabEyeApplication {

    private static final Logger log = LoggerFactory.getLogger(CabEyeApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CabEyeApplication.class, args);
    }

    @Bean
    public PlaceRepository placeRepository() {
        return new InMemoryPlaceRepository();
    }

    @Bean
    public NluService nluService(PlaceRepository places) {
        return new NluService(places);
    }

    /**
     * Cloud speech if it is configured, a service that refuses cleanly if
     * it is not. Choosing here rather than with {@code @ConditionalOnProperty}
     * keeps the reason visible in the startup log — "why is Tamil not
     * working" should be answerable from the console, not from a bean graph.
     */
    @Bean
    public SpeechService speechService(SpeechProperties props, RestClient.Builder builder) {
        if (props.configured()) {
            log.info("Cloud speech ENABLED — Google STT/TTS, primary {} with alternatives {}",
                    props.getLanguageCode(), props.getAlternativeLanguageCodes());
            return new GoogleSpeechService(props, builder);
        }
        log.info("Cloud speech DISABLED — no cabeye.google.api-key. "
               + "The rider app will use the browser's Web Speech API.");
        return new DisabledSpeechService();
    }
}
