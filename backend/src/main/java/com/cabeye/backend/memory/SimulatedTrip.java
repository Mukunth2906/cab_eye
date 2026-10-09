package com.cabeye.backend.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One past trip, as a "be the user" demo would describe it: when it happened, where the rider
 * went, and the stops on the way. Accepted only by the dev-only history simulator
 * ({@code cabeye.dev.memory-simulator=true}); real trips are recorded from completed rides.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SimulatedTrip {

    /** When the ride was booked, epoch millis. Must be in the past. */
    public long at;
    public Place destination;
    /** Stops in visiting order; each is remembered as visited. */
    public List<Place> stops = new ArrayList<>();
    public String rideType = "AUTO";
    public Double pickupLatitude;
    public Double pickupLongitude;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Place {
        public String name;
        public String address;
        public Double latitude;
        public Double longitude;
        public String placeId;
        /** Stops only: WAIT, DROP or PICKUP. */
        public String kind;
        /** The rider's own words for it — becomes a learned alias. */
        public String spokenAs;

        public Place() {}
    }
}
