package com.cabeye.backend.feedback;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One rider's spoken feedback on one ride. At most one per ride. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeedbackRecord {

    public String rideId;
    public String riderId;
    public String driverId;
    /** 1–5, or null when the rider only reported an issue. */
    public Integer rating;
    /** SAFETY, DRIVER, PICKUP, ROUTE, PAYMENT, VEHICLE, OTHER — or null for a rating alone. */
    public String category;
    /** The rider's own words, as transcribed. */
    public String text;
    /** True for SAFETY: surfaces first in any review queue and is logged at WARN. */
    public boolean urgent;
    public long createdAt;

    // The trip the feedback is about, copied from the ride so a complaint like "overcharged"
    // or "took a long route" can be checked against what was actually measured.
    /** Metres the trip covered, or null for feedback saved before this existed. */
    public Integer distanceMeters;
    /** "GPS" (measured) or "ESTIMATE" (straight line); null if unknown. */
    public String distanceSource;
    public Integer fareRupees;
    public Integer durationMinutes;

    public FeedbackRecord() {}
}
