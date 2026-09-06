package com.cabeye.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Cab Eye backend.
 *
 * <p>Deliberately minimal. All ride state lives in memory inside
 * {@link com.cabeye.backend.websocket.RideSessionManager} — there is no database, no
 * message broker and no security layer in this MVP, per the brief.
 *
 * <p>Module layout: {@code controller / service / websocket / model}.
 */
@SpringBootApplication
public class CabEyeBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CabEyeBackendApplication.class, args);
    }
}
