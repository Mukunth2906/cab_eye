package com.cabeye.backend.feedback;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A driver's feedback about their passenger on one ride. At most one per ride.
 *
 * <p>The mirror of {@link FeedbackRecord}: the rider rates the trip, the driver rates the rider.
 * It builds the rider's own rating and lets support see a pattern (a rider who is repeatedly
 * reported as abusive, or as never at the pickup point). It never changes anything the rider
 * hears, and a rider is never refused a ride because of it — that is a decision for a person.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RiderFeedbackRecord {

    public String rideId;
    public String driverId;
    public String riderId;
    /** 1–5, or null when the driver only wrote a note. */
    public Integer rating;
    /** SAFETY, BEHAVIOUR, PICKUP, PAYMENT, OTHER — or null for a rating alone. */
    public String category;
    /** The driver's own words. */
    public String text;
    /** True for SAFETY (or safety words in the text): surfaces first, alerts support. */
    public boolean urgent;
    public long createdAt;

    // The trip it is about, copied from the ride.
    public Integer distanceMeters;
    public String distanceSource;
    public Integer fareRupees;
    public Integer durationMinutes;

    public RiderFeedbackRecord() {}
}
