package com.cabeye.backend.admin;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.account.AccountService;
import com.cabeye.backend.feedback.FeedbackRecord;
import com.cabeye.backend.feedback.FeedbackService;
import com.cabeye.backend.feedback.RiderFeedbackRecord;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.persistence.RidePersistence;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.store.DataDirectory;
import com.cabeye.backend.store.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The admin inbox.
 *
 * <p>Every feedback submission becomes (or updates) one case, {@code fb-<rideId>}. Urgent ones
 * alert the admin at once on Telegram and email; the rest are listed in a daily email at 9 pm.
 * Existing feedback is copied in on start, without alerting, so nothing already said is lost.
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);
    static final int MAX_NOTE = 1000;

    private final Table<AdminCase> cases;
    private final AccountService accounts;
    private final RideService rides;
    private final AlertService alerts;

    /**
     * {@code ridesLoaded} is unused except to make Spring restore saved rides first, so cases
     * copied in at start can show the driver and plate of their ride.
     */
    public CaseService(DataDirectory data, AccountService accounts, RideService rides,
                       FeedbackService feedback, AlertService alerts, RidePersistence ridesLoaded) {
        this.cases = data.table("admin_cases", AdminCase.class);
        this.accounts = accounts;
        this.rides = rides;
        this.alerts = alerts;

        int copied = 0;
        for (FeedbackRecord f : feedback.queue()) {
            if (cases.get(caseId(f)).isEmpty()) {
                fromFeedback(f, false);
                copied++;
            }
        }
        if (copied > 0) log.info("ADMIN inbox: copied {} earlier feedback item(s)", copied);
        feedback.onSubmitted(f -> fromFeedback(f, true));
        rides.onEvent((ride, event) -> {
            if ("WAIT_OVERDUE".equals(event.type())) stopOverdue(ride, event.payload());
        });

        for (RiderFeedbackRecord f : feedback.driverQueue()) {
            if (worthACase(f) && cases.get(caseId(f)).isEmpty()) fromDriverReport(f, false);
        }
        feedback.onDriverSubmitted(f -> {
            if (worthACase(f)) fromDriverReport(f, true);
        });
    }

    static String caseId(FeedbackRecord f) {
        return "fb-" + f.rideId;
    }

    static String caseId(RiderFeedbackRecord f) {
        return "dr-" + f.rideId;
    }

    /**
     * A driver's 4- or 5-star rating alone is not something support needs to read; a low
     * rating, a written note or a safety concern is.
     */
    static boolean worthACase(RiderFeedbackRecord f) {
        return f.urgent || (f.rating != null && f.rating <= 2) || (f.text != null && !f.text.isBlank());
    }

    /** Creates or refreshes the case for one ride's driver report about the passenger. */
    AdminCase fromDriverReport(RiderFeedbackRecord f, boolean alert) {
        String id = caseId(f);
        AdminCase existing = cases.get(id).orElse(null);
        boolean becameUrgent = f.urgent && (existing == null || !existing.urgent);
        long now = System.currentTimeMillis();

        AdminCase c = existing != null ? existing : new AdminCase();
        c.id = id;
        c.kind = AdminCase.Kind.DRIVER_REPORT;
        c.rideId = f.rideId;
        c.rating = f.rating;
        c.category = f.category;
        c.text = f.text;
        c.urgent = f.urgent;
        if (c.createdAt == 0) c.createdAt = f.createdAt == 0 ? now : f.createdAt;
        c.updatedAt = now;
        if (existing != null && existing.status == AdminCase.Status.RESOLVED && alert) {
            c.status = AdminCase.Status.NEW;
        }
        fillPeople(c, f.riderId, f.driverId);
        cases.put(id, c);

        if (alert && becameUrgent) sendAlert(c);
        return c;
    }

    /** Creates or refreshes the case for one ride's feedback. */
    AdminCase fromFeedback(FeedbackRecord f, boolean alert) {
        String id = caseId(f);
        AdminCase existing = cases.get(id).orElse(null);
        boolean becameUrgent = f.urgent && (existing == null || !existing.urgent);
        long now = System.currentTimeMillis();

        AdminCase c = existing != null ? existing : new AdminCase();
        c.id = id;
        c.kind = AdminCase.Kind.FEEDBACK;
        c.rideId = f.rideId;
        c.rating = f.rating;
        c.category = f.category;
        c.text = f.text;
        c.urgent = f.urgent;
        if (c.createdAt == 0) c.createdAt = f.createdAt == 0 ? now : f.createdAt;
        c.updatedAt = now;
        // Something new was said on a closed case: it needs looking at again.
        if (existing != null && existing.status == AdminCase.Status.RESOLVED && alert) {
            c.status = AdminCase.Status.NEW;
        }
        fillPeople(c, f.riderId, f.driverId);
        cases.put(id, c);

        if (alert && becameUrgent) sendAlert(c);
        return c;
    }

    /**
     * Multi-stop: the rider went out at a WAIT stop and has not come back within the limit.
     * Urgent — a blind rider may be lost or need help — so the admin gets Telegram and email,
     * with the stop's location and the rider's emergency contact to call.
     */
    AdminCase stopOverdue(Ride ride, java.util.Map<String, Object> stop) {
        String stopId = String.valueOf(stop.getOrDefault("stopId", "stop"));
        String id = "stop-" + ride.rideId() + "-" + stopId;
        if (cases.get(id).isPresent()) return cases.get(id).get();
        long now = System.currentTimeMillis();
        AdminCase c = new AdminCase();
        c.id = id;
        c.kind = AdminCase.Kind.STOP_OVERDUE;
        c.urgent = true;
        c.rideId = ride.rideId();
        c.createdAt = now;
        c.updatedAt = now;
        Object name = stop.get("name");
        long waited = stop.get("waitedSeconds") instanceof Number n ? n.longValue() : 0;
        c.text = "Rider has not come back to the car at stop " + stop.getOrDefault("index", "?")
                + (name == null ? "" : " (" + name + ")") + " after " + Math.max(1, waited / 60) + " minutes.";
        if (stop.get("latitude") instanceof Number lat && stop.get("longitude") instanceof Number lng) {
            c.latitude = lat.doubleValue();
            c.longitude = lng.doubleValue();
            c.locationAt = now;
        }
        fillPeople(c, ride.riderId(), ride.driverId());
        cases.put(id, c);
        sendAlert(c);
        log.warn("ADMIN case {} opened: rider not back ride={} stop={}", id, ride.rideId(), stopId);
        return c;
    }

    private void fillPeople(AdminCase c, String riderId, String driverId) {
        Ride ride = c.rideId == null ? null : rides.find(c.rideId).orElse(null);
        if (ride != null) {
            c.destination = ride.destination();
            c.driverName = ride.driverName();
            c.driverPhone = blankToNull(ride.driverPhone());
            c.vehicle = ride.vehicleModel();
            c.vehiclePlate = ride.vehiclePlate();
            // What the trip measured, so a fare or route complaint can be checked against it.
            if (ride.phase() == com.cabeye.backend.model.RidePhase.COMPLETED) {
                c.distanceMeters = ride.tripDistanceMeters();
                c.distanceSource = ride.distanceSource() == null || ride.distanceSource().isEmpty() ? null : ride.distanceSource();
                c.fareRupees = ride.fareRupees();
                c.durationMinutes = ride.durationMinutes();
            }
        }
        c.riderId = riderId;
        c.driverId = driverId;
        Optional<Account> rider = accounts.find(riderId);
        rider.ifPresent(a -> {
            c.riderName = a.name;
            c.riderPhone = a.phone;
            if (a.rider != null) {
                c.emergencyContactName = a.rider.emergencyContactName;
                c.emergencyContactPhone = a.rider.emergencyContactPhone;
            }
        });
        accounts.find(driverId).ifPresent(a -> {
            if (a.name != null) c.driverName = a.name;
            if (a.phone != null) c.driverPhone = a.phone;
            if (a.driver != null && a.driver.vehiclePlate != null) c.vehiclePlate = a.driver.vehiclePlate;
        });
    }

    private void sendAlert(AdminCase c) {
        alerts.alert(c).thenAccept(result -> cases.update(c.id, row -> {
            row.alertResult = result;
            return row;
        }));
    }

    // -----------------------------------------------------------------------------------
    //  Admin actions
    // -----------------------------------------------------------------------------------

    /** Open first, urgent first, then newest. {@code status} null = everything. */
    public List<AdminCase> list(AdminCase.Status status) {
        List<AdminCase> out = status == null ? cases.all() : cases.where(c -> c.status == status);
        out.sort(Comparator.comparing((AdminCase c) -> c.status == AdminCase.Status.RESOLVED)
                .thenComparing(c -> !c.urgent)
                .thenComparing(Comparator.comparingLong((AdminCase c) -> c.createdAt).reversed()));
        return out;
    }

    public Optional<AdminCase> get(String id) {
        return cases.get(id);
    }

    public Optional<AdminCase> setStatus(String id, AdminCase.Status status, String admin) {
        return cases.update(id, c -> {
            if (c.status != status) {
                c.notes.add(new AdminCase.Note(System.currentTimeMillis(), admin, "Marked " + status.name().toLowerCase()));
                c.status = status;
                c.updatedAt = System.currentTimeMillis();
            }
            return c;
        });
    }

    public Optional<AdminCase> addNote(String id, String text, String admin) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("The note is empty.");
        String note = t.length() > MAX_NOTE ? t.substring(0, MAX_NOTE) : t;
        return cases.update(id, c -> {
            c.notes.add(new AdminCase.Note(System.currentTimeMillis(), admin, note));
            c.updatedAt = System.currentTimeMillis();
            return c;
        });
    }

    /** Low ratings and urgent reports against one driver, for the drivers page. */
    public long complaintsAgainst(String driverId) {
        // A driver's own report about a passenger carries that driver's id too, but it is not
        // a complaint against them.
        return cases.where(c -> driverId.equals(c.driverId)
                && c.kind != AdminCase.Kind.DRIVER_REPORT
                && (c.urgent || (c.rating != null && c.rating <= 2))).size();
    }

    /** Drivers' reports about this rider that became cases (low rating, note or safety). */
    public long reportsAbout(String riderId) {
        return cases.where(c -> riderId.equals(c.riderId) && c.kind == AdminCase.Kind.DRIVER_REPORT).size();
    }

    public long openCount() {
        return cases.where(c -> c.status != AdminCase.Status.RESOLVED).size();
    }

    // -----------------------------------------------------------------------------------
    //  Daily summary, 9 pm India time
    // -----------------------------------------------------------------------------------

    @Scheduled(cron = "0 0 21 * * *", zone = "Asia/Kolkata")
    public void dailySummary() {
        long since = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        List<AdminCase> today = cases.where(c -> c.createdAt >= since);
        long open = openCount();
        if (today.isEmpty() && open == 0) return;
        StringBuilder b = new StringBuilder();
        b.append(today.size()).append(" new item(s) in the last 24 hours, ").append(open).append(" still open.\n\n");
        for (AdminCase c : today) {
            b.append("- ").append(c.urgent ? "[URGENT] " : "").append(c.rideId)
                    .append(c.rating == null ? "" : " · " + c.rating + "/5")
                    .append(c.category == null ? "" : " · " + c.category)
                    .append(c.text == null ? "" : " · \"" + c.text + "\"").append('\n');
        }
        alerts.email("Daily summary: " + today.size() + " new, " + open + " open", b.toString());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
