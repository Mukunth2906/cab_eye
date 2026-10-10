package com.cabeye.backend.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A route the rider named — "Monday errands" — so it can be booked again in two words.
 *
 * <p>Saved only when the rider asks ("save this as Monday errands"); never inferred. The
 * memory agent separately notices routes the rider repeats (see the phone's RoutineAgent), but
 * a name is the rider's own, so it is the rider's to give.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SavedRoute {

    public String riderId;
    /** As the rider said it: "Monday errands". */
    public String name;
    /** Lower-case, punctuation-free: the lookup key. */
    public String key;
    public List<Leg> stops = new ArrayList<>();
    public Leg destination;
    public String rideType;
    public long createdAt;
    public long lastUsedAt;
    public int useCount;

    public SavedRoute() {}

    /** One place on the route. {@code kind} is DROP / PICKUP / WAIT for stops, null for the destination. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Leg {
        public String name;
        public String address;
        public Double latitude;
        public Double longitude;
        public String placeId;
        public String kind;
        public String note;

        public Leg() {}
    }
}
