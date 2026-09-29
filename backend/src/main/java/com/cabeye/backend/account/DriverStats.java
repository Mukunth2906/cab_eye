package com.cabeye.backend.account;

import com.cabeye.backend.service.RideService;
import org.springframework.stereotype.Component;

/** Counts a completed trip against the signed-in driver who drove it. */
@Component
public class DriverStats {

    public DriverStats(RideService rides, AccountService accounts) {
        rides.onCompleted(ride -> {
            if (ride.driverId() != null) accounts.recordDriverTrip(ride.driverId());
        });
    }
}
