package com.cabeye.speech;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Cloud speech could not be used.
 *
 * <p>503 rather than 500, deliberately: this is never fatal to the rider.
 * The client is expected to read it as "carry on with the browser's own
 * recogniser", not as "the booking failed". A blind user must never lose
 * a ride because a speech vendor had a bad minute.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class SpeechUnavailableException extends RuntimeException {

    public SpeechUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
