package com.cabeye.backend.fare;

/**
 * Turns a finished trip's real distance and duration into a fare in whole rupees.
 *
 * <p>The fare is {@code base + perKm * km + perMinute * minutes}, never below a minimum, rounded
 * to the nearest rupee. It is computed on the server from what the trip actually measured, so
 * a driver's phone cannot name its own price.
 *
 * <p>Plain class with no framework types on purpose: the maths can be unit-tested without
 * starting Spring, and the wiring (reading rates from configuration) lives elsewhere.
 *
 * <p><b>The default rates below are PLACEHOLDERS</b>, not real tariffs. The team should agree
 * the real numbers and set them in configuration before any demo that shows a fare.
 */
public final class FareCalculator {

    /** One vehicle type's tariff. */
    public record Rates(int baseRupees, double perKmRupees, double perMinuteRupees, int minimumRupees) {}

    /** PLACEHOLDER auto-rickshaw tariff. */
    public static final Rates DEFAULT_AUTO = new Rates(40, 15.0, 1.0, 50);

    /** PLACEHOLDER cab tariff. */
    public static final Rates DEFAULT_CAB = new Rates(60, 18.0, 1.5, 80);

    private final Rates auto;
    private final Rates cab;

    public FareCalculator() {
        this(DEFAULT_AUTO, DEFAULT_CAB);
    }

    public FareCalculator(Rates auto, Rates cab) {
        this.auto = auto;
        this.cab = cab;
    }

    /**
     * @param rideType        {@code "CAB"} uses the cab tariff; anything else, including null,
     *                        uses the auto tariff (the app's own default ride type)
     * @param distanceMeters  metres actually travelled; negative is treated as zero
     * @param durationMinutes minutes the trip took; negative is treated as zero
     */
    public int fareRupees(String rideType, int distanceMeters, int durationMinutes) {
        Rates r = rideType != null && "CAB".equalsIgnoreCase(rideType.trim()) ? cab : auto;
        double km = Math.max(0, distanceMeters) / 1000.0;
        int minutes = Math.max(0, durationMinutes);
        double raw = r.baseRupees() + r.perKmRupees() * km + r.perMinuteRupees() * minutes;
        return Math.max(r.minimumRupees(), (int) Math.round(raw));
    }
}
