package com.cabeye.backend.memory;

import com.cabeye.backend.account.AccountService;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.store.DataDirectory;
import com.cabeye.backend.store.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The rider's long-term memory: previously visited places, the trip log, and how they have
 * answered the agent's suggestions.
 *
 * <p>Written from one place only — a ride reaching COMPLETED — so a booking that was cancelled,
 * or never found a driver, never becomes a "usual" destination. The phone reads it through
 * {@code GET /me/memory} and does the scoring itself, on-device, so a suggestion never waits on
 * the network.
 *
 * <p>Only signed-in riders have memory. A guest was told "I won't remember your places", and
 * that promise is kept here rather than trusted to the client.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** Trips kept per rider. Enough for weeks of daily commuting; old ones age out. */
    static final int MAX_TRIPS_PER_RIDER = 300;
    /** Spoken aliases kept per place. */
    static final int MAX_ALIASES = 8;

    private final Table<VisitedPlace> places;
    private final Table<TripRecord> trips;
    private final Table<MemoryStats> stats;
    private final AccountService accounts;
    private final ZoneId zone;

    public MemoryService(DataDirectory data,
                         AccountService accounts,
                         RideService rides,
                         @Value("${cabeye.timezone:Asia/Kolkata}") String zone) {
        this.places = data.table("visited_places", VisitedPlace.class);
        this.trips = data.table("trips", TripRecord.class);
        this.stats = data.table("memory_stats", MemoryStats.class);
        this.accounts = accounts;
        this.zone = ZoneId.of(zone);
        rides.onCompleted(this::recordCompleted);
    }

    // ===================================================================================
    //  Writes
    // ===================================================================================

    /** A ride finished: log the trip and fold it into the rider's visited places. */
    void recordCompleted(Ride ride) {
        if (accounts.find(ride.riderId()).isEmpty()) return; // guests and test ids keep nothing
        if (ride.destination() == null || ride.destination().isBlank()) return;

        ZonedDateTime booked = ZonedDateTime.ofInstant(
                ride.createdAt() == null ? Instant.now() : ride.createdAt(), zone);
        String key = placeKey(ride.destinationPlaceId(), ride.destination());

        TripRecord t = new TripRecord();
        t.rideId = ride.rideId();
        t.riderId = ride.riderId();
        t.placeKey = key;
        t.destination = ride.destination();
        t.spokenAs = ride.spokenAs();
        t.pickupLatitude = ride.pickupLatitude();
        t.pickupLongitude = ride.pickupLongitude();
        t.rideType = ride.rideType();
        t.bookedAt = booked.toInstant().toEpochMilli();
        t.hour = booked.getHour();
        t.dayOfWeek = booked.getDayOfWeek().getValue();
        t.fareRupees = ride.fareRupees();
        t.durationMinutes = ride.durationMinutes();
        trips.put(t.rideId, t);
        trimTrips(ride.riderId());

        String rowKey = rowKey(ride.riderId(), key);
        VisitedPlace p = places.get(rowKey).orElseGet(() -> {
            VisitedPlace fresh = new VisitedPlace();
            fresh.riderId = ride.riderId();
            fresh.placeKey = key;
            fresh.firstVisitedAt = t.bookedAt;
            return fresh;
        });
        p.name = ride.destination();
        if (ride.destinationAddress() != null && !ride.destinationAddress().isBlank()) p.address = ride.destinationAddress();
        if (ride.destinationLatitude() != null) p.latitude = ride.destinationLatitude();
        if (ride.destinationLongitude() != null) p.longitude = ride.destinationLongitude();
        if (ride.destinationPlaceId() != null && !ride.destinationPlaceId().isBlank()) p.placeId = ride.destinationPlaceId();
        p.visitCount++;
        p.lastVisitedAt = t.bookedAt;
        if (p.hourCounts == null || p.hourCounts.length != 24) p.hourCounts = new int[24];
        p.hourCounts[t.hour]++;
        boolean weekend = booked.getDayOfWeek() == DayOfWeek.SATURDAY || booked.getDayOfWeek() == DayOfWeek.SUNDAY;
        if (weekend) p.weekendVisits++; else p.weekdayVisits++;
        addAlias(p, ride.spokenAs());
        places.put(rowKey, p);

        log.info("MEMORY visit rider={} place=\"{}\" visits={} hour={} spokenAs=\"{}\"",
                ride.riderId(), p.name, p.visitCount, t.hour, t.spokenAs);
    }

    /**
     * The rider answered a memory suggestion.
     *
     * @param kind     PROACTIVE ("like usual?") or REPAIR ("did you mean…?")
     * @param heard    what the rider had said, learned as an alias only when accepted
     * @return the updated place, or empty when the key is not one of this rider's places
     */
    public Optional<VisitedPlace> recordOutcome(String riderId, String placeKey, String kind,
                                                boolean accepted, String heard) {
        MemoryStats s = stats.get(riderId).orElseGet(() -> {
            MemoryStats fresh = new MemoryStats();
            fresh.riderId = riderId;
            return fresh;
        });
        boolean repair = "REPAIR".equalsIgnoreCase(kind);
        if (repair) {
            if (accepted) s.repairAccepted++; else s.repairRejected++;
        } else {
            if (accepted) s.proactiveAccepted++; else s.proactiveRejected++;
        }
        s.updatedAt = System.currentTimeMillis();
        stats.put(riderId, s);

        return places.update(rowKey(riderId, placeKey), p -> {
            if (accepted) {
                p.accepted++;
                // A confirmed mishearing is exactly the alias worth keeping.
                if (repair) addAlias(p, heard);
            } else {
                p.rejected++;
            }
            return p;
        });
    }

    /** "Forget my history": every place, trip and statistic for this rider. */
    public int forgetAll(String riderId) {
        int removed = places.removeWhere(p -> riderId.equals(p.riderId));
        removed += trips.removeWhere(t -> riderId.equals(t.riderId));
        stats.remove(riderId);
        log.info("MEMORY forget-all rider={} rows={}", riderId, removed);
        return removed;
    }

    /** Forgets one place and the trips to it. */
    public boolean forgetPlace(String riderId, String placeKey) {
        boolean removed = places.remove(rowKey(riderId, placeKey));
        trips.removeWhere(t -> riderId.equals(t.riderId) && placeKey.equals(t.placeKey));
        return removed;
    }

    // ===================================================================================
    //  Reads
    // ===================================================================================

    /** Most-visited first, then most recent. */
    public List<VisitedPlace> places(String riderId) {
        List<VisitedPlace> out = places.where(p -> riderId.equals(p.riderId));
        out.sort(Comparator.comparingInt((VisitedPlace p) -> p.visitCount).reversed()
                .thenComparing(Comparator.comparingLong((VisitedPlace p) -> p.lastVisitedAt).reversed()));
        return out;
    }

    /** Newest first. */
    public List<TripRecord> trips(String riderId, int limit) {
        List<TripRecord> out = trips.where(t -> riderId.equals(t.riderId));
        out.sort(Comparator.comparingLong((TripRecord t) -> t.bookedAt).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public MemoryStats stats(String riderId) {
        return stats.get(riderId).orElseGet(() -> {
            MemoryStats empty = new MemoryStats();
            empty.riderId = riderId;
            return empty;
        });
    }

    // ===================================================================================
    //  Helpers
    // ===================================================================================

    static String placeKey(String placeId, String name) {
        if (placeId != null && !placeId.isBlank()) return placeId.trim();
        return "name:" + normalise(name);
    }

    private static String rowKey(String riderId, String placeKey) {
        return riderId + "|" + placeKey;
    }

    static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Adds a spoken form unless it is empty, already known, or just the place's own name. */
    private static void addAlias(VisitedPlace p, String heard) {
        String alias = normalise(heard);
        if (alias.isEmpty() || alias.length() > 60) return;
        if (alias.equals(normalise(p.name))) return;
        if (p.aliases == null) p.aliases = new ArrayList<>();
        if (p.aliases.contains(alias)) return;
        p.aliases.add(alias);
        while (p.aliases.size() > MAX_ALIASES) p.aliases.remove(0);
    }

    private void trimTrips(String riderId) {
        List<TripRecord> mine = trips(riderId, Integer.MAX_VALUE);
        if (mine.size() <= MAX_TRIPS_PER_RIDER) return;
        for (TripRecord old : mine.subList(MAX_TRIPS_PER_RIDER, mine.size())) {
            trips.remove(old.rideId);
        }
    }
}
