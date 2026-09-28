package com.cabeye.backend.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A place a rider has actually been taken to, and everything that makes it recognisable again.
 *
 * <p>This is the "previously visited places" half of the rider's memory. One row per
 * (rider, place); the trip log keeps the individual journeys.
 *
 * <ul>
 *   <li>{@link #hourCounts} and the weekday/weekend split are what let the agent say
 *       "It's 8:40 on a Monday — PSG College, like usual?" without storing a calendar.</li>
 *   <li>{@link #aliases} are the rider's own words for the place, learned only from answers
 *       they confirmed: "piece g" becomes PSG College after they said yes to it once.</li>
 *   <li>{@link #accepted}/{@link #rejected} count the agent's suggestions of this place, so a
 *       place the rider keeps turning down stops being offered.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VisitedPlace {

    public String riderId;
    /** Google place id when known, otherwise {@code "name:<normalised name>"}. */
    public String placeKey;
    public String name;
    public String address;
    public Double latitude;
    public Double longitude;
    public String placeId;

    public int visitCount;
    public long firstVisitedAt;
    public long lastVisitedAt;

    /** Visits started in each hour of the day, rider's local time. Always 24 long. */
    public int[] hourCounts = new int[24];
    public int weekdayVisits;
    public int weekendVisits;

    /** Lower-case spoken forms the rider has confirmed mean this place. */
    public List<String> aliases = new ArrayList<>();

    public int accepted;
    public int rejected;

    public VisitedPlace() {}
}
