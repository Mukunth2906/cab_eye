package com.cabeye.backend.fare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link FareCalculator} from configuration, so the team can change the tariff
 * without touching code.
 *
 * <p>Each value can be set in {@code application.properties}, on the command line
 * ({@code -Dcabeye.fare.auto.per-km=18}) or as an environment variable
 * ({@code CABEYE_FARE_AUTO_PER_KM=18}). The defaults are the PLACEHOLDER rates in
 * {@link FareCalculator} — not real tariffs.
 */
@Configuration
public class FareConfig {

    private static final Logger log = LoggerFactory.getLogger(FareConfig.class);

    @Bean
    public FareCalculator fareCalculator(
            @Value("${cabeye.fare.auto.base:40}") int autoBase,
            @Value("${cabeye.fare.auto.per-km:15}") double autoPerKm,
            @Value("${cabeye.fare.auto.per-minute:1}") double autoPerMinute,
            @Value("${cabeye.fare.auto.minimum:50}") int autoMinimum,
            @Value("${cabeye.fare.cab.base:60}") int cabBase,
            @Value("${cabeye.fare.cab.per-km:18}") double cabPerKm,
            @Value("${cabeye.fare.cab.per-minute:1.5}") double cabPerMinute,
            @Value("${cabeye.fare.cab.minimum:80}") int cabMinimum) {
        FareCalculator.Rates auto = new FareCalculator.Rates(autoBase, autoPerKm, autoPerMinute, autoMinimum);
        FareCalculator.Rates cab = new FareCalculator.Rates(cabBase, cabPerKm, cabPerMinute, cabMinimum);
        log.info("FARES auto={} cab={}", auto, cab);
        return new FareCalculator(auto, cab);
    }
}
