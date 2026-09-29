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

    public FeedbackRecord() {}
}
