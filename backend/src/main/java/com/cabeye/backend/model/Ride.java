package com.cabeye.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One ride, held entirely in memory.
 *
 * <p>Mutable and synchronised rather than an immutable record, for a reason specific to this
 * product: the event log and the phase must move together. If a client could observe
 * {@code phase == ARRIVED} while the {@code DRIVER_ARRIVED} event had not yet landed in the
 * log, a reconnecting rider would reconcile against a phase whose evidence had not arrived,
 * and could be told the car is here before the server can say which car.
 *
 * <h2>The event log</h2>
 * Every event is appended, with a per-ride sequence number, and kept for the life of the
 * process. This is what a reconnecting client replays. It is bounded by
 * {@link #MAX_LOG_EVENTS} so a long {@code DRIVER_LOCATION} stream cannot grow without limit
 * during a demo; the oldest events are dropped first, which is safe because the client's
 * primary reconciliation path is the phase snapshot, not the log.
 */
public class Ride {

    /**
     * How many events to keep per ride.
     *
     * <p>{@code DRIVER_LOCATION} arrives roughly every 1.5 s during the approach, so a
     * fifteen-minute ride produces several hundred events. 500 covers a realistic ride with
     * room to spare while keeping a demo's memory flat.
     */
    public static final int MAX_LOG_EVENTS = 500;

    /** See {@link #paymentStatus}. */
    public enum PaymentStatus { NONE, REPORTED, CONFIRMED, FAILED }

    private final String rideId;
    private final String riderId;
    private final String destination;
    private final String destinationAddress;
    private final Double destinationLatitude;
    private final Double destinationLongitude;
    private final String destinationPlaceId;
    private final Double pickupLatitude;
    private final Double pickupLongitude;

    /**
     * Someone waiting at the drop-off, used as a landmark the driver can ring.
     *
     * <p>A road is not an address. "Thadagam Road" is five kilometres long, and a rider who
     * cannot see has no way to wave the car down or describe which gate they mean. The person
     * meeting them can do both, so the driver is given a way to reach them.
     *
     * <p>Optional by construction: most rides go to a building and carry neither field.
     */
    private final String contactName;
    private final String contactPhone;

    /**
     * The rider's own words for where on the road to stop — "near Perur bus stop", "opposite
     * the temple".
     *
     * <p>Populated when the rider named a landmark that Places could not resolve to a point.
     * Kept verbatim rather than discarded: a phrase a local driver understands is worth more
     * than a geocoder's failure to match it, and the rider has already said it out loud once.
     */
    private final String dropNote;

    /**
     * Payment lifecycle: NONE -> REPORTED -> CONFIRMED, or FAILED.
     *
     * <p>REPORTED means the rider's phone relayed what its UPI app claimed. That claim arrives
     * over an Intent from an app on the same device and is a hint, never evidence — only a
     * payment provider's webhook can justify CONFIRMED. The two are kept distinct because a
     * rider who cannot see the screen has no way to notice that "paid" was wrong, and would
     * walk away from an unpaid fare believing it settled.
     */
    private volatile PaymentStatus paymentStatus = PaymentStatus.NONE;

    /** Transaction reference the UPI app returned, for reconciliation. Never a credential. */
    private volatile String paymentRef = "";

    private final String rideType;
    private final String boardingCode;
    private final Instant createdAt;

    private final AtomicLong sequence = new AtomicLong(0);
    private final List<RideEvent> log = new ArrayList<>();

    private volatile RidePhase phase = RidePhase.REQUESTED;
    private volatile String driverId;
    private volatile String driverName;
    private volatile String vehicleModel;
    private volatile String vehiclePlate;
    private volatile String driverPhone;
    private volatile int etaMinutes;
    private volatile int distanceMeters = -1;
    private volatile float bearingDeg;
    private volatile boolean codeConfirmed;
    private volatile int fareRupees;
    private volatile int durationMinutes;

    public Ride(String rideId,
                String riderId,
                String destination,
                String destinationAddress,
                Double destinationLatitude,
                Double destinationLongitude,
                String destinationPlaceId,
                Double pickupLatitude,
                Double pickupLongitude,
                String contactName,
                String contactPhone,
                String dropNote,
                String rideType,
                String boardingCode) {
        this.rideId = rideId;
        this.riderId = riderId;
        this.destination = destination;
        this.destinationAddress = destinationAddress;
        this.destinationLatitude = destinationLatitude;
        this.destinationLongitude = destinationLongitude;
        this.destinationPlaceId = destinationPlaceId;
        this.pickupLatitude = pickupLatitude;
        this.pickupLongitude = pickupLongitude;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.dropNote = dropNote;
        this.rideType = rideType;
        this.boardingCode = boardingCode;
        this.createdAt = Instant.now();
    }

    /** Kept for callers that predate the meeting contact. */
    public Ride(String rideId,
                String riderId,
                String destination,
                String destinationAddress,
                Double destinationLatitude,
                Double destinationLongitude,
                String destinationPlaceId,
                Double pickupLatitude,
                Double pickupLongitude,
                String rideType,
                String boardingCode) {
        this(rideId, riderId, destination, destinationAddress, destinationLatitude,
                destinationLongitude, destinationPlaceId, pickupLatitude, pickupLongitude,
                "", "", "", rideType, boardingCode);
    }

    /** Backward-compatible constructor used by older callers/tests. */
    public Ride(String rideId, String riderId, String destination, String rideType, String boardingCode) {
        this(rideId, riderId, destination, "", null, null, "", null, null, "", "", "", rideType, boardingCode);
    }

    // -----------------------------------------------------------------------------------
    //  Event log
    // -----------------------------------------------------------------------------------

    /**
     * Appends an event, assigning it the next sequence number for this ride.
     *
     * @param event event to record; its {@code eventId} is preserved so replay is de-duplicable
     * @return the same event, now carrying its sequence number
     */
    public synchronized RideEvent append(RideEvent event) {
        RideEvent numbered = event.withSeq(sequence.incrementAndGet());
        log.add(numbered);
        while (log.size() > MAX_LOG_EVENTS) {
            log.remove(0);
        }
        return numbered;
    }

    /**
     * @param afterSeq return only events with a sequence number strictly greater than this;
     *                 pass {@code 0} for the whole retained log
     * @return an immutable snapshot, oldest first
     */
    public synchronized List<RideEvent> eventsAfter(long afterSeq) {
        List<RideEvent> out = new ArrayList<>();
        for (RideEvent e : log) {
            if (e.seq() > afterSeq) {
                out.add(e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public long lastSeq() {
        return sequence.get();
    }

    // -----------------------------------------------------------------------------------
    //  Snapshot for REST reconciliation
    // -----------------------------------------------------------------------------------

    /**
     * The complete current state of the ride, as the rider's app fetches it after a reconnect.
     *
     * <p>Everything needed to decide what to announce is here, so reconciliation is a single
     * round trip. A design that required the client to fetch the phase and then fetch the
     * driver would leave a window in which a blind rider is told a car has arrived without
     * being able to be told anything about it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshot(
            String rideId,
            String riderId,
            String phase,
            String destination,
            String destinationAddress,
            Double destinationLatitude,
            Double destinationLongitude,
            String destinationPlaceId,
            Double pickupLatitude,
            Double pickupLongitude,
            String contactName,
            String contactPhone,
            String dropNote,
            String rideType,
            String boardingCode,
            String driverId,
            String driverName,
            String vehicleModel,
            String vehiclePlate,
            String driverPhone,
            int etaMinutes,
            int distanceMeters,
            float bearingDeg,
            boolean codeConfirmed,
            int fareRupees,
            int durationMinutes,
            String paymentStatus,
            String paymentRef,
            long lastSeq,
            Instant createdAt
    ) {}

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                rideId, riderId, phase.name(), destination, destinationAddress,
                destinationLatitude, destinationLongitude, destinationPlaceId,
                pickupLatitude, pickupLongitude, contactName, contactPhone, dropNote, rideType, boardingCode,
                driverId, driverName, vehicleModel, vehiclePlate, driverPhone,
                etaMinutes, distanceMeters, bearingDeg, codeConfirmed,
                fareRupees, durationMinutes,
                paymentStatus.name(), paymentRef,
                sequence.get(), createdAt);
    }

    // -----------------------------------------------------------------------------------
    //  Accessors
    // -----------------------------------------------------------------------------------

    public String rideId()       { return rideId; }
    public String riderId()      { return riderId; }
    public String destination()  { return destination; }
    public String destinationAddress() { return destinationAddress; }
    public Double destinationLatitude() { return destinationLatitude; }
    public Double destinationLongitude() { return destinationLongitude; }
    public String destinationPlaceId() { return destinationPlaceId; }
    public Double pickupLatitude() { return pickupLatitude; }
    public Double pickupLongitude() { return pickupLongitude; }
    public String contactName()  { return contactName; }
    public String contactPhone() { return contactPhone; }
    public PaymentStatus paymentStatus() { return paymentStatus; }
    public void paymentStatus(PaymentStatus status) { this.paymentStatus = status; }
    public String paymentRef() { return paymentRef; }
    public void paymentRef(String ref) { this.paymentRef = ref == null ? "" : ref; }
    public String dropNote()     { return dropNote; }
    public String rideType()     { return rideType; }
    public String boardingCode() { return boardingCode; }
    public RidePhase phase()     { return phase; }
    public String driverId()     { return driverId; }
    public String driverName()   { return driverName; }
    public String vehicleModel() { return vehicleModel; }
    public String vehiclePlate() { return vehiclePlate; }
    public String driverPhone()  { return driverPhone; }
    public int etaMinutes()      { return etaMinutes; }
    public boolean codeConfirmed() { return codeConfirmed; }

    public void phase(RidePhase next)          { this.phase = next; }
    public void etaMinutes(int minutes)        { this.etaMinutes = minutes; }
    public void distance(int metres)           { this.distanceMeters = metres; }
    public void bearing(float degrees)         { this.bearingDeg = degrees; }
    public void codeConfirmed(boolean value)   { this.codeConfirmed = value; }
    public void fare(int rupees)               { this.fareRupees = rupees; }
    public int fareRupees()                    { return fareRupees; }
    public void durationMinutes(int minutes)   { this.durationMinutes = minutes; }

    public void assignDriver(String id, String name, String model, String plate, String phone, int eta) {
        this.driverId = id;
        this.driverName = name;
        this.vehicleModel = model;
        this.vehiclePlate = plate;
        this.driverPhone = phone;
        this.etaMinutes = eta;
    }
}
