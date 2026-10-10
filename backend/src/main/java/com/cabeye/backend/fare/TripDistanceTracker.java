package com.cabeye.backend.fare;

/**
 * Adds up the distance a trip really covered from the driver phone's GPS fixes.
 *
 * <p>Raw GPS is noisy, so a fix is not simply "added on". Three rules keep the total honest:
 *
 * <ol>
 *   <li><b>Jitter is ignored.</b> A fix less than {@value #MIN_STEP_METERS} m from the last
 *       counted one adds nothing, and the anchor does not move, so a standing car does not
 *       "drive" a few metres every second, while slow creeping still counts once it has
 *       moved far enough.</li>
 *   <li><b>Teleports are ignored.</b> A jump that would need more than
 *       {@value #MAX_SPEED_METERS_PER_SECOND} m/s (about 200 km/h) is a GPS glitch and adds
 *       nothing.</li>
 *   <li><b>But a glitch cannot freeze the meter.</b> After {@value #MAX_CONSECUTIVE_REJECTS}
 *       rejected fixes in a row the car really is somewhere else (a tunnel, a lost signal),
 *       so the anchor jumps there without adding the gap.</li>
 * </ol>
 *
 * <p>No framework types, so it can be tested without Spring. {@link #state()} and
 * {@link #from(State)} let the ride store it and carry on after a restart.
 */
public final class TripDistanceTracker {

    static final double MIN_STEP_METERS = 10;
    static final double MAX_SPEED_METERS_PER_SECOND = 55;
    static final int MAX_CONSECUTIVE_REJECTS = 3;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    /** The tracker's whole memory, for saving with the ride. */
    public record State(boolean hasAnchor, double anchorLat, double anchorLng, long anchorAtMillis, double meters) {}

    private boolean hasAnchor;
    private double anchorLat;
    private double anchorLng;
    private long anchorAtMillis;
    private double meters;
    private int rejects;

    public TripDistanceTracker() {}

    public static TripDistanceTracker from(State s) {
        TripDistanceTracker t = new TripDistanceTracker();
        if (s != null) {
            t.hasAnchor = s.hasAnchor();
            t.anchorLat = s.anchorLat();
            t.anchorLng = s.anchorLng();
            t.anchorAtMillis = s.anchorAtMillis();
            t.meters = s.meters();
        }
        return t;
    }

    public synchronized State state() {
        return new State(hasAnchor, anchorLat, anchorLng, anchorAtMillis, meters);
    }

    /** @return true when this fix added distance to the total */
    public synchronized boolean add(double lat, double lng, long atMillis) {
        if (!valid(lat, lng)) {
            return false;
        }
        if (!hasAnchor) {
            anchor(lat, lng, atMillis);
            return false;
        }
        double step = haversineMeters(anchorLat, anchorLng, lat, lng);
        if (step < MIN_STEP_METERS) {
            return false;
        }
        double seconds = Math.max(1.0, (atMillis - anchorAtMillis) / 1000.0);
        if (step / seconds > MAX_SPEED_METERS_PER_SECOND) {
            if (++rejects >= MAX_CONSECUTIVE_REJECTS) {
                anchor(lat, lng, atMillis);
            }
            return false;
        }
        meters += step;
        anchor(lat, lng, atMillis);
        return true;
    }

    /** Whole metres covered so far. */
    public synchronized int meters() {
        return (int) Math.round(meters);
    }

    private void anchor(double lat, double lng, long atMillis) {
        hasAnchor = true;
        anchorLat = lat;
        anchorLng = lng;
        anchorAtMillis = atMillis;
        rejects = 0;
    }

    /** A real position: finite, on the globe, and not the (0,0) a phone reports before it has a fix. */
    private static boolean valid(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng) || Double.isInfinite(lat) || Double.isInfinite(lng)) {
            return false;
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return false;
        }
        return !(lat == 0 && lng == 0);
    }

    /** Great-circle distance in metres. */
    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
