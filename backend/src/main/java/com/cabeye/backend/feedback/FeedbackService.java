package com.cabeye.backend.feedback;

import com.cabeye.backend.account.AccountService;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RidePhase;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.store.DataDirectory;
import com.cabeye.backend.store.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.Locale;
import java.util.Set;

/**
 * Optional post-ride feedback: a rating, a spoken report, or both.
 *
 * <p>The category is decided on the phone (where the words were heard) and re-checked here only
 * for being one of the known values. A SAFETY report is marked urgent and logged at WARN so it
 * cannot sit unseen among star ratings.
 *
 * <p>The driver's rating average moves once per ride, on the first rating, so a rider saying
 * "five" twice does not count twice.
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    static final Set<String> CATEGORIES = Set.of("SAFETY", "DRIVER", "PICKUP", "ROUTE", "PAYMENT", "VEHICLE", "OTHER");
    static final int MAX_TEXT = 500;

    /** What a driver can file a passenger report under. */
    static final Set<String> DRIVER_CATEGORIES = Set.of("SAFETY", "BEHAVIOUR", "PICKUP", "PAYMENT", "OTHER");

    private final Table<FeedbackRecord> feedback;
    /** Drivers' feedback about their passengers, one row per ride. */
    private final Table<RiderFeedbackRecord> riderFeedback;
    private final RideService rides;
    private final AccountService accounts;
    private final List<Consumer<FeedbackRecord>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<RiderFeedbackRecord>> driverListeners = new CopyOnWriteArrayList<>();

    public FeedbackService(DataDirectory data, RideService rides, AccountService accounts) {
        this.feedback = data.table("feedback", FeedbackRecord.class);
        this.riderFeedback = data.table("rider_feedback", RiderFeedbackRecord.class);
        this.rides = rides;
        this.accounts = accounts;
    }

    /**
     * @param riderId the signed-in rider, or null for a guest (then the ride's own rider is used)
     * @throws IllegalArgumentException with a speakable sentence when refused
     */
    public FeedbackRecord submit(String rideId, String riderId, Integer rating, String category, String text) {
        Ride ride = rides.find(rideId).orElseThrow(() -> new IllegalArgumentException("I can't find that ride any more."));
        if (ride.phase() != RidePhase.COMPLETED) {
            throw new IllegalArgumentException("Feedback can be given once the ride has finished.");
        }
        if (riderId != null && !riderId.equals(ride.riderId())) {
            throw new IllegalArgumentException("That ride isn't yours.");
        }
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("A rating is from one to five.");
        }
        String cat = category == null || category.isBlank() ? null : category.trim().toUpperCase(Locale.ROOT);
        if (cat != null && !CATEGORIES.contains(cat)) cat = "OTHER";
        String words = text == null ? null : text.trim();
        if (words != null && words.length() > MAX_TEXT) words = words.substring(0, MAX_TEXT);
        if (rating == null && (words == null || words.isEmpty())) {
            throw new IllegalArgumentException("There was nothing to send.");
        }

        FeedbackRecord existing = feedback.get(rideId).orElse(null);
        FeedbackRecord f = existing != null ? existing : new FeedbackRecord();
        boolean firstRating = rating != null && (existing == null || existing.rating == null);

        f.rideId = rideId;
        f.riderId = ride.riderId();
        f.driverId = ride.driverId();
        tripFacts(f, ride);
        if (rating != null && f.rating == null) f.rating = rating;
        if (cat != null) f.category = cat;
        if (words != null && !words.isEmpty()) f.text = words;
        // The rider's words are kept exactly as they confirmed them; only the queue can change.
        if (SafetyWords.concerning(f.text) && !"SAFETY".equals(f.category)) {
            log.info("FEEDBACK ride={} re-filed {} -> SAFETY from its words", rideId, f.category);
            f.category = "SAFETY";
        }
        f.urgent = f.urgent || "SAFETY".equals(f.category);
        if (f.createdAt == 0) f.createdAt = System.currentTimeMillis();
        feedback.put(rideId, f);

        if (firstRating && ride.driverId() != null) accounts.recordDriverRating(ride.driverId(), rating);

        if (f.urgent) {
            log.warn("FEEDBACK_URGENT ride={} rider={} driver={} text=\"{}\"", rideId, f.riderId, f.driverId, f.text);
        } else {
            log.info("FEEDBACK ride={} rating={} category={}", rideId, f.rating, f.category);
        }
        for (Consumer<FeedbackRecord> l : listeners) {
            try {
                l.accept(f);
            } catch (RuntimeException e) {
                log.warn("FEEDBACK listener failed ride={} : {}", rideId, e.toString());
            }
        }
        return f;
    }

    /** Copies what the trip measured onto the feedback, so the report can be checked against it. */
    static void tripFacts(FeedbackRecord f, Ride ride) {
        f.distanceMeters = ride.tripDistanceMeters();
        f.distanceSource = ride.distanceSource() == null || ride.distanceSource().isEmpty() ? null : ride.distanceSource();
        f.fareRupees = ride.fareRupees();
        f.durationMinutes = ride.durationMinutes();
    }

    // -----------------------------------------------------------------------------------
    //  Driver -> rider
    // -----------------------------------------------------------------------------------

    /**
     * A driver's feedback about the passenger on a finished ride: a rating, a report, or both.
     *
     * <p>The same rules as the rider's side: only after the ride, only by that ride's driver,
     * 1–5, something must be said, and the rider's rating average moves once per ride.
     *
     * @param driverId the signed-in driver, or null when not signed in (then the ride's own
     *                 driver is used — the browser test page path)
     * @throws IllegalArgumentException with a sentence the driver's screen can show
     */
    public RiderFeedbackRecord submitFromDriver(String rideId, String driverId, Integer rating,
                                                String category, String text) {
        Ride ride = rides.find(rideId).orElseThrow(() -> new IllegalArgumentException("That ride can't be found any more."));
        if (ride.phase() != RidePhase.COMPLETED) {
            throw new IllegalArgumentException("You can rate your passenger once the trip has finished.");
        }
        if (driverId != null && ride.driverId() != null && !driverId.equals(ride.driverId())) {
            throw new IllegalArgumentException("That ride isn't yours.");
        }
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("A rating is from one to five.");
        }
        String cat = category == null || category.isBlank() ? null : category.trim().toUpperCase(Locale.ROOT);
        if (cat != null && !DRIVER_CATEGORIES.contains(cat)) cat = "OTHER";
        String words = text == null ? null : text.trim();
        if (words != null && words.length() > MAX_TEXT) words = words.substring(0, MAX_TEXT);
        if (rating == null && cat == null && (words == null || words.isEmpty())) {
            throw new IllegalArgumentException("There was nothing to send.");
        }

        RiderFeedbackRecord existing = riderFeedback.get(rideId).orElse(null);
        RiderFeedbackRecord f = existing != null ? existing : new RiderFeedbackRecord();
        boolean firstRating = rating != null && (existing == null || existing.rating == null);

        f.rideId = rideId;
        f.driverId = ride.driverId();
        f.riderId = ride.riderId();
        if (rating != null && f.rating == null) f.rating = rating;
        if (cat != null) f.category = cat;
        if (words != null && !words.isEmpty()) f.text = words;
        if (SafetyWords.concerning(f.text) && !"SAFETY".equals(f.category)) {
            log.info("RIDER_FEEDBACK ride={} re-filed {} -> SAFETY from its words", rideId, f.category);
            f.category = "SAFETY";
        }
        f.urgent = f.urgent || "SAFETY".equals(f.category);
        f.distanceMeters = ride.tripDistanceMeters();
        f.distanceSource = ride.distanceSource() == null || ride.distanceSource().isEmpty() ? null : ride.distanceSource();
        f.fareRupees = ride.fareRupees();
        f.durationMinutes = ride.durationMinutes();
        if (f.createdAt == 0) f.createdAt = System.currentTimeMillis();
        riderFeedback.put(rideId, f);

        if (firstRating && ride.riderId() != null) accounts.recordRiderRating(ride.riderId(), rating);

        if (f.urgent) {
            log.warn("RIDER_FEEDBACK_URGENT ride={} driver={} rider={} text=\"{}\"", rideId, f.driverId, f.riderId, f.text);
        } else {
            log.info("RIDER_FEEDBACK ride={} rating={} category={}", rideId, f.rating, f.category);
        }
        for (Consumer<RiderFeedbackRecord> l : driverListeners) {
            try {
                l.accept(f);
            } catch (RuntimeException e) {
                log.warn("RIDER_FEEDBACK listener failed ride={} : {}", rideId, e.toString());
            }
        }
        return f;
    }

    /** Called after every saved driver submission (the admin inbox listens here). */
    public void onDriverSubmitted(Consumer<RiderFeedbackRecord> listener) {
        driverListeners.add(listener);
    }

    /** Every driver report, urgent first then newest. */
    public List<RiderFeedbackRecord> driverQueue() {
        List<RiderFeedbackRecord> out = riderFeedback.all();
        out.sort(Comparator.comparing((RiderFeedbackRecord f) -> !f.urgent)
                .thenComparing(Comparator.comparingLong((RiderFeedbackRecord f) -> f.createdAt).reversed()));
        return out;
    }

    /** The driver's feedback on one ride, if they gave any. */
    public java.util.Optional<RiderFeedbackRecord> driverFeedbackFor(String rideId) {
        return riderFeedback.get(rideId);
    }

    /** Called after every saved submission (the admin inbox listens here). */
    public void onSubmitted(Consumer<FeedbackRecord> listener) {
        listeners.add(listener);
    }

    public List<FeedbackRecord> forRider(String riderId) {
        List<FeedbackRecord> out = feedback.where(f -> riderId.equals(f.riderId));
        out.sort(Comparator.comparingLong((FeedbackRecord f) -> f.createdAt).reversed());
        return out;
    }

    /** Urgent first, then newest. For a support/review screen. */
    public List<FeedbackRecord> queue() {
        List<FeedbackRecord> out = feedback.all();
        out.sort(Comparator.comparing((FeedbackRecord f) -> !f.urgent)
                .thenComparing(Comparator.comparingLong((FeedbackRecord f) -> f.createdAt).reversed()));
        return out;
    }
}
