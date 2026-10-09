package com.cabeye.backend.memory;

import com.cabeye.backend.account.AccountService;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideStop;
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
    static final int MAX_SAVED_ROUTES = 10;

    private final Table<VisitedPlace> places;
    private final Table<TripRecord> trips;
    private final Table<MemoryStats> stats;
    private final Table<SavedRoute> routes;
    private final AccountService accounts;
    private final ZoneId zone;

    public MemoryService(DataDirectory data,
                         AccountService accounts,
                         RideService rides,
                         @Value("${cabeye.timezone:Asia/Kolkata}") String zone) {
        this.places = data.table("visited_places", VisitedPlace.class);
        this.trips = data.table("trips", TripRecord.class);
        this.stats = data.table("memory_stats", MemoryStats.class);
        this.routes = data.table("saved_routes", SavedRoute.class);
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

        // Multi-stop: every stop the rider actually reached is a place they went to, at the
        // time they reached it. Skipped stops were never visited and are not remembered.
        List<TripRecord.TripStop> visited = new ArrayList<>();
        for (RideStop stop : ride.copyStops()) {
            if (stop.status != RideStop.Status.DONE || stop.name == null || stop.name.isBlank()) continue;
            String stopKey = placeKey(stop.placeId, stop.name);
            visited.add(new TripRecord.TripStop(stopKey, stop.name, stop.kind.name()));
            ZonedDateTime at = stop.arrivedAt > 0
                    ? ZonedDateTime.ofInstant(Instant.ofEpochMilli(stop.arrivedAt), zone) : booked;
            recordVisit(ride.riderId(), stopKey, stop.name, stop.address, stop.latitude, stop.longitude,
                    stop.placeId, stop.spokenAs, at);
        }
        if (!visited.isEmpty()) t.stops = visited;
        trips.put(t.rideId, t);
        trimTrips(ride.riderId());

        VisitedPlace p = recordVisit(ride.riderId(), key, ride.destination(), ride.destinationAddress(),
                ride.destinationLatitude(), ride.destinationLongitude(), ride.destinationPlaceId(),
                ride.spokenAs(), booked);
        log.info("MEMORY visit rider={} place=\"{}\" visits={} hour={} spokenAs=\"{}\" stops={}",
                ride.riderId(), p.name, p.visitCount, t.hour, t.spokenAs, visited.size());
    }

    /** Oldest history the simulator accepts — older trips would only age out of the scores anyway. */
    static final long SIMULATE_MAX_AGE_MS = 120L * 24 * 3600 * 1000;
    static final int SIMULATE_MAX_TRIPS = MAX_TRIPS_PER_RIDER;

    /**
     * Dev only — "be the user": folds a described history into the rider's memory exactly as
     * completed rides would be (same trip record, same visits, same aliases), with the
     * timestamps the demo needs. Real rides cannot be backdated, and a habit is only visible
     * across weeks, so this is the honest way to show the memory agent working today.
     *
     * @return trips recorded
     * @throws IllegalArgumentException with a sentence, when the history is unusable
     */
    public int simulate(String riderId, List<SimulatedTrip> history) {
        if (history == null || history.isEmpty()) throw new IllegalArgumentException("No trips to simulate.");
        if (history.size() > SIMULATE_MAX_TRIPS) {
            throw new IllegalArgumentException("At most " + SIMULATE_MAX_TRIPS + " trips at a time.");
        }
        long now = System.currentTimeMillis();
        for (SimulatedTrip t : history) {
            if (t == null || t.destination == null || t.destination.name == null || t.destination.name.isBlank()) {
                throw new IllegalArgumentException("Every trip needs a destination name.");
            }
            if (t.at <= 0 || t.at > now || now - t.at > SIMULATE_MAX_AGE_MS) {
                throw new IllegalArgumentException("Trip times must be in the last 120 days.");
            }
            if (t.stops != null && t.stops.size() > 3) {
                throw new IllegalArgumentException("A ride can have up to 3 stops before the destination.");
            }
        }
        int n = 0;
        List<SimulatedTrip> ordered = new ArrayList<>(history);
        ordered.sort(Comparator.comparingLong(t -> t.at));
        for (SimulatedTrip s : ordered) {
            ZonedDateTime booked = ZonedDateTime.ofInstant(Instant.ofEpochMilli(s.at), zone);
            SimulatedTrip.Place d = s.destination;
            String key = placeKey(d.placeId, d.name);

            TripRecord t = new TripRecord();
            t.rideId = "sim-" + Long.toString(s.at, 36) + "-" + n;
            t.riderId = riderId;
            t.placeKey = key;
            t.destination = d.name;
            t.spokenAs = d.spokenAs;
            t.pickupLatitude = s.pickupLatitude;
            t.pickupLongitude = s.pickupLongitude;
            t.rideType = s.rideType == null ? "AUTO" : s.rideType;
            t.bookedAt = s.at;
            t.hour = booked.getHour();
            t.dayOfWeek = booked.getDayOfWeek().getValue();

            List<TripRecord.TripStop> visited = new ArrayList<>();
            if (s.stops != null) {
                for (SimulatedTrip.Place stop : s.stops) {
                    if (stop == null || stop.name == null || stop.name.isBlank()) continue;
                    String stopKey = placeKey(stop.placeId, stop.name);
                    visited.add(new TripRecord.TripStop(stopKey, stop.name, RideStop.Kind.parse(stop.kind).name()));
                    recordVisit(riderId, stopKey, stop.name, stop.address, stop.latitude, stop.longitude,
                            stop.placeId, stop.spokenAs, booked);
                }
            }
            if (!visited.isEmpty()) t.stops = visited;
            trips.put(t.rideId, t);
            recordVisit(riderId, key, d.name, d.address, d.latitude, d.longitude, d.placeId, d.spokenAs, booked);
            n++;
        }
        trimTrips(riderId);
        log.info("MEMORY simulated rider={} trips={}", riderId, n);
        return n;
    }

    /** One visit to one place: count, hour, weekday/weekend, and the rider's words as an alias. */
    private VisitedPlace recordVisit(String riderId, String key, String name, String address,
                                     Double latitude, Double longitude, String placeId,
                                     String spokenAs, ZonedDateTime when) {
        String rowKey = rowKey(riderId, key);
        long at = when.toInstant().toEpochMilli();
        VisitedPlace p = places.get(rowKey).orElseGet(() -> {
            VisitedPlace fresh = new VisitedPlace();
            fresh.riderId = riderId;
            fresh.placeKey = key;
            fresh.firstVisitedAt = at;
            return fresh;
        });
        p.name = name;
        if (address != null && !address.isBlank()) p.address = address;
        if (latitude != null) p.latitude = latitude;
        if (longitude != null) p.longitude = longitude;
        if (placeId != null && !placeId.isBlank()) p.placeId = placeId;
        p.visitCount++;
        p.lastVisitedAt = at;
        if (p.hourCounts == null || p.hourCounts.length != 24) p.hourCounts = new int[24];
        p.hourCounts[when.getHour()]++;
        boolean weekend = when.getDayOfWeek() == DayOfWeek.SATURDAY || when.getDayOfWeek() == DayOfWeek.SUNDAY;
        if (weekend) p.weekendVisits++; else p.weekdayVisits++;
        addAlias(p, spokenAs);
        places.put(rowKey, p);
        return p;
    }

    // ===================================================================================
    //  Saved routes — "save this as Monday errands" / "book Monday errands"
    // ===================================================================================

    /**
     * Saves (or replaces) a named route.
     *
     * @throws IllegalArgumentException with a speakable sentence when refused
     */
    public SavedRoute saveRoute(String riderId, SavedRoute in) {
        String name = in.name == null ? "" : in.name.trim();
        String key = normalise(name);
        if (key.isEmpty() || key.length() > 40) throw new IllegalArgumentException("Give the route a short name.");
        if (in.destination == null || in.destination.name == null || in.destination.name.isBlank()) {
            throw new IllegalArgumentException("A saved route needs a destination.");
        }
        if (in.stops != null && in.stops.size() > 3) throw new IllegalArgumentException("A route can have up to 3 stops.");
        String rowKey = rowKey(riderId, key);
        boolean exists = routes.get(rowKey).isPresent();
        if (!exists && savedRoutes(riderId).size() >= MAX_SAVED_ROUTES) {
            throw new IllegalArgumentException("You already have " + MAX_SAVED_ROUTES + " saved routes. Forget one first.");
        }
        long now = System.currentTimeMillis();
        SavedRoute r = routes.get(rowKey).orElseGet(SavedRoute::new);
        r.riderId = riderId;
        r.name = name;
        r.key = key;
        r.stops = in.stops == null ? new ArrayList<>() : new ArrayList<>(in.stops);
        r.destination = in.destination;
        r.rideType = in.rideType == null || in.rideType.isBlank() ? "AUTO" : in.rideType;
        if (r.createdAt == 0) r.createdAt = now;
        routes.put(rowKey, r);
        log.info("MEMORY route saved rider={} name=\"{}\" stops={}", riderId, name, r.stops.size());
        return r;
    }

    /** The rider booked a saved route: counted, so "my routes" can list the used ones first. */
    public Optional<SavedRoute> routeUsed(String riderId, String name) {
        return routes.update(rowKey(riderId, normalise(name)), r -> {
            r.useCount++;
            r.lastUsedAt = System.currentTimeMillis();
            return r;
        });
    }

    public boolean forgetRoute(String riderId, String name) {
        return routes.remove(rowKey(riderId, normalise(name)));
    }

    public List<SavedRoute> savedRoutes(String riderId) {
        List<SavedRoute> out = routes.where(r -> riderId.equals(r.riderId));
        out.sort(Comparator.comparingInt((SavedRoute r) -> r.useCount).reversed()
                .thenComparing(Comparator.comparingLong((SavedRoute r) -> r.createdAt).reversed()));
        return out;
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
        removed += routes.removeWhere(r -> riderId.equals(r.riderId));
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
