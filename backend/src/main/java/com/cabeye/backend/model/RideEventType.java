package com.cabeye.backend.model;

import java.util.Arrays;

/**
 * The ride-lifecycle event vocabulary.
 *
 * <p>This enum is the contract between the backend and both Android surfaces. It exists as a
 * closed set rather than free-form strings for two reasons that are not stylistic:
 *
 * <ol>
 *   <li>The debug broadcast endpoint must reject anything that is not a ride-lifecycle event
 *       ({@code /debug/broadcast} scope rule). Without a closed set there is nothing to check
 *       an incoming type <em>against</em>, and "validate the type" degenerates into "accept
 *       any string".</li>
 *   <li>Each event carries a fixed narration tier on the rider's phone. A type nobody
 *       declared is a type with no tier, and an event with no tier either interrupts the
 *       rider when it should not have, or says nothing when it should have spoken.</li>
 * </ol>
 *
 * <p>Transport-level notices ({@code CONNECTED}, {@code PARTICIPANT_JOINED},
 * {@code REPLAY_COMPLETE}, {@code ERROR}) are deliberately <b>not</b> members. They are facts
 * about the socket, not about the ride, and letting a debug caller inject a fake
 * {@code CONNECTED} would let them tell a blind rider the connection is healthy when it is not.
 */
public enum RideEventType {

    /** A rider booked. Broadcast to the ride topic and to the driver dispatch topic. */
    RIDE_CREATED,

    /** A driver accepted. Payload carries driver details and the ETA. Tier 1 — queues. */
    RIDE_ASSIGNED,

    /** Driver started navigating to the pickup point. Tier 1. */
    DRIVER_ENROUTE,

    /**
     * Driver moved. Payload: {@code distanceMeters}, {@code bearingDeg}.
     * Tier 2 — fires an earcon and speaks nothing. This is the highest-frequency event in the
     * system and narrating it would consume the rider's attention continuously.
     */
    DRIVER_LOCATION,

    /**
     * The driver tapped a canned position phrase; the rider's phone speaks it. Tier 1.
     * The driver never speaks and the rider never reads — this event is that whole exchange.
     */
    POSITION_PRESET,

    /** Driver pressed the audio beacon. Payload: {@code bearingDeg}. Earcon only, panned. */
    BEACON,

    /**
     * Driver is at the pickup point. Payload carries {@code boardingCode}.
     * Tier 0 — interrupts, because its value collapses entirely if it arrives late.
     */
    DRIVER_ARRIVED,

    /** The rider confirmed the code they heard the driver say aloud matches. Tier 1. */
    CODE_CONFIRMED,

    /** The driver confirmed the passenger is physically seated. Gates {@link #TRIP_STARTED}. */
    PASSENGER_SEATED,

    /** The journey began. Tier 1. */
    TRIP_STARTED,

    /**
     * The car left the expected route. Tier 0 — one of only two events allowed to interrupt,
     * because a rider who cannot see where they are needs to know immediately.
     */
    ROUTE_DEVIATION,

    /** Journey finished. Payload: {@code fareRupees}, {@code durationMinutes}. Tier 1. */
    TRIP_COMPLETED,

    /** Ride cancelled by either party. Tier 0. */
    RIDE_CANCELLED,

    /**
     * The fare's payment status changed (REPORTED, CONFIRMED or FAILED). Deliberately after
     * RIDE_CANCELLED: it is not a phase change, and a ride that is COMPLETED stays COMPLETED
     * while its payment settles. Payload: {@code status}, {@code paymentRef},
     * {@code fareRupees}, and {@code reason} on failure.
     */
    PAYMENT_UPDATED;

    /** @return true when {@code name} is a member of this enum, case-sensitively. */
    public static boolean isLifecycleType(String name) {
        return name != null && Arrays.stream(values()).anyMatch(t -> t.name().equals(name));
    }
}
