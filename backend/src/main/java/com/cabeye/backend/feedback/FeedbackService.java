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

    private final Table<FeedbackRecord> feedback;
    private final RideService rides;
    private final AccountService accounts;
    private final List<Consumer<FeedbackRecord>> listeners = new CopyOnWriteArrayList<>();

    public FeedbackService(DataDirectory data, RideService rides, AccountService accounts) {
        this.feedback = data.table("feedback", FeedbackRecord.class);
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
