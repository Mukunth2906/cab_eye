package com.cabeye.backend.persistence;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideRecord;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.store.DataDirectory;
import com.cabeye.backend.store.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Keeps every ride in the database, so a backend restart no longer loses them.
 *
 * <p>A ride is saved when it is created and after every event (assigned, arrived, code
 * confirmed, seated, trip, payment…). At start-up every saved ride is put back — a ride that
 * was in progress continues, the phones reconnect to it and get its event log as usual, and
 * feedback or payment for a finished ride still works after a restart.
 *
 * <p>Driver position pings and beacons are not saved on their own: they arrive every second or
 * two and are only useful live. They are still in the event log the next time the ride is saved.
 */
@Component
public class RidePersistence {

    private static final Logger log = LoggerFactory.getLogger(RidePersistence.class);

    private static final Set<String> NOT_WORTH_A_SAVE = Set.of("DRIVER_LOCATION", "BEACON");

    private final Table<RideRecord> table;

    public RidePersistence(RideService rides, DataDirectory data) {
        this.table = data.table("rides", RideRecord.class);

        int restored = 0;
        int active = 0;
        for (RideRecord record : table.all()) {
            try {
                Ride ride = Ride.fromRecord(record);
                rides.restore(ride);
                restored++;
                if (!ride.phase().isTerminal()) active++;
            } catch (RuntimeException e) {
                log.warn("RIDE_RESTORE_FAILED ride={} : {}", record.rideId, e.toString());
            }
        }
        log.info("RIDES restored {} from the database ({} still in progress)", restored, active);

        rides.onCreated(this::save);
        rides.onEvent((ride, event) -> {
            if (!NOT_WORTH_A_SAVE.contains(event.type())) save(ride);
        });
    }

    /** Saves the ride; a failed save is logged and never breaks the ride itself. */
    public void save(Ride ride) {
        try {
            table.put(ride.rideId(), ride.toRecord());
        } catch (RuntimeException e) {
            log.warn("RIDE_SAVE_FAILED ride={} : {}", ride.rideId(), e.toString());
        }
    }
}
