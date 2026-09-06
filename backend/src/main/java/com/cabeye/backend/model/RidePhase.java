package com.cabeye.backend.model;

/**
 * Where a ride is, as far as the server is concerned.
 *
 * <p>This is the <b>authoritative</b> phase. The rider's app has its own richer
 * {@code RiderState} hierarchy covering things the server has no opinion about (listening,
 * resolving, clarifying), but when the two disagree — which is exactly what happens after a
 * dropped socket — this one wins.
 *
 * <p>That is the whole point of exposing it over REST: after a reconnect the rider's app
 * fetches this value and announces only the difference between what it last told the rider and
 * where the ride actually is. Replaying the intermediate events instead would make a rider who
 * lost signal for ninety seconds listen to a backlog of narration describing a car that has
 * already pulled up.
 *
 * <p>Transitions are enforced in {@code RideService}; there is no path that skips
 * {@link #SEATED}, which is what makes "confirm the passenger is seated before the trip can
 * start" a property of the system rather than a convention the driver app is trusted to honour.
 */
public enum RidePhase {

    /** Booked, no driver yet. The rider's app runs the system-alive heartbeat through this. */
    REQUESTED,

    /** A driver accepted. */
    ASSIGNED,

    /** Driver is navigating to the pickup point. Earcons only on the rider's phone. */
    ENROUTE,

    /** Driver is at the pickup point and the boarding code is live. */
    ARRIVED,

    /** Driver has confirmed the passenger is physically in the vehicle. */
    SEATED,

    /** Journey underway. */
    IN_TRIP,

    /** Journey finished. Terminal. */
    COMPLETED,

    /** Cancelled by either party. Terminal. */
    CANCELLED;

    /** @return true when no further transition is possible from here. */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
