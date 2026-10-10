package com.cabeye.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Locale;

/**
 * One intermediate stop on a multi-stop ride (the final destination is not a stop — it stays
 * in {@link Ride#destination()} so every existing client keeps working).
 *
 * <h2>Kinds — the same three a rider means on Uber or Rapido</h2>
 * <ul>
 *   <li>{@link Kind#DROP} — someone with the rider gets off; the rider stays in the car.</li>
 *   <li>{@link Kind#PICKUP} — someone joins the ride here; the rider stays in the car.</li>
 *   <li>{@link Kind#WAIT} — the rider gets out for an errand and comes back. The car may not
 *       leave until the rider's phone has heard the boarding code again: a blind rider
 *       returning to a kerb cannot see which car is theirs, exactly as at pickup.</li>
 * </ul>
 *
 * <h2>Status</h2>
 * {@code PENDING → ARRIVED → DONE} for DROP/PICKUP, {@code PENDING → WAITING → DONE} for WAIT,
 * or {@code SKIPPED} (the rider changed their mind). Stops are visited strictly in order.
 *
 * Plain public fields: this is both the stored row inside {@link RideRecord} and the JSON the
 * apps read.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RideStop {

    public enum Kind {
        DROP, PICKUP, WAIT;

        /** Lenient: anything unrecognised is a DROP, the stop that asks nothing of anyone. */
        public static Kind parse(Object raw) {
            if (raw == null) return DROP;
            try {
                return valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DROP;
            }
        }
    }

    public enum Status {
        PENDING, ARRIVED, WAITING, DONE, SKIPPED;

        public boolean isOpen() {
            return this == PENDING || this == ARRIVED || this == WAITING;
        }
    }

    /** 1-based position in the route, renumbered whenever the stop list changes. */
    public int index;
    /** Stable id, so an edit or a late tap can never act on a different stop by position. */
    public String stopId;
    public Kind kind = Kind.DROP;
    public Status status = Status.PENDING;

    public String name;
    public String address;
    public Double latitude;
    public Double longitude;
    public String placeId;
    /** What the rider said for it — for the memory agent's aliases, exactly like the destination. */
    public String spokenAs;
    /** "my friend Priya", "mother" — who is dropped or picked up. Spoken to the driver's screen only. */
    public String note;

    public long arrivedAt;
    public long waitStartedAt;
    public long doneAt;
    /** How long the driver will wait at a WAIT stop before the admin is told. */
    public int waitLimitSeconds;
    /** WAIT only: the rider's phone heard the boarding code again on the way back in. */
    public boolean riderBack;
    /** Reminder bookkeeping, so each warning is sent once. */
    public boolean warnedHalf;
    public boolean warnedLastMinute;
    public boolean overdue;

    public RideStop() {}

    public RideStop copy() {
        RideStop s = new RideStop();
        s.index = index;
        s.stopId = stopId;
        s.kind = kind;
        s.status = status;
        s.name = name;
        s.address = address;
        s.latitude = latitude;
        s.longitude = longitude;
        s.placeId = placeId;
        s.spokenAs = spokenAs;
        s.note = note;
        s.arrivedAt = arrivedAt;
        s.waitStartedAt = waitStartedAt;
        s.doneAt = doneAt;
        s.waitLimitSeconds = waitLimitSeconds;
        s.riderBack = riderBack;
        s.warnedHalf = warnedHalf;
        s.warnedLastMinute = warnedLastMinute;
        s.overdue = overdue;
        return s;
    }

    /** Seconds the driver has been waiting at a WAIT stop; 0 otherwise. */
    public long waitedSeconds(long now) {
        if (waitStartedAt <= 0) return 0;
        long end = doneAt > 0 ? doneAt : now;
        return Math.max(0, (end - waitStartedAt) / 1000);
    }
}
