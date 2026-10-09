package com.cabeye.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticks the WAIT-stop clock: reminders to the rider at half time and one minute left, and
 * {@code WAIT_OVERDUE} (which opens an urgent admin case) when the limit passes.
 *
 * <p>Server-side on purpose: the rider is away from the car, possibly with the phone in a
 * pocket, and the driver's phone may be locked. Only the server is sure to be awake.
 */
@Component
public class StopWaitMonitor {

    private final RideService rides;

    public StopWaitMonitor(RideService rides,
                           @Value("${cabeye.stops.wait-minutes:10}") int waitMinutes) {
        this.rides = rides;
        rides.setDefaultWaitSeconds(waitMinutes * 60);
    }

    @Scheduled(fixedDelay = 5_000)
    public void tick() {
        rides.checkWaits(System.currentTimeMillis());
    }
}
