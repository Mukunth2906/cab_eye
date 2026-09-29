package com.cabeye.backend.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One completed journey. The raw material the visited-places summary is built from, and what
 * the time-of-travel pattern is read from ("on weekdays around 8:30 you go to college").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TripRecord {

    public String rideId;
    public String riderId;
    public String placeKey;
    public String destination;
    /** What the rider said, e.g. "piece g". */
    public String spokenAs;
    public Double pickupLatitude;
    public Double pickupLongitude;
    public String rideType;
    /** When the ride was booked, epoch ms. */
    public long bookedAt;
    /** Local hour (0–23) and ISO day of week (1 = Monday … 7 = Sunday) at booking. */
    public int hour;
    public int dayOfWeek;
    public int fareRupees;
    public int durationMinutes;

    public TripRecord() {}
}
