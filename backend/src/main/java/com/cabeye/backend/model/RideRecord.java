package com.cabeye.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything about one ride, as it is saved in the database: the booking, the driver, where
 * the ride is, the payment status and its event log. See {@link Ride#toRecord()} and
 * {@link Ride#fromRecord(RideRecord)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RideRecord {
    public String rideId;
    public String riderId;
    public String destination;
    public String destinationAddress;
    public Double destinationLatitude;
    public Double destinationLongitude;
    public String destinationPlaceId;
    public Double pickupLatitude;
    public Double pickupLongitude;
    public String contactName;
    public String contactPhone;
    public String dropNote;
    public String rideType;
    public String boardingCode;
    public Instant createdAt;
    public String spokenAs;

    public String phase;
    public String driverId;
    public String driverName;
    public String vehicleModel;
    public String vehiclePlate;
    public String driverPhone;
    public int etaMinutes;
    public int distanceMeters = -1;
    public float bearingDeg;
    public boolean codeConfirmed;
    public int fareRupees;
    public int durationMinutes;

    public String paymentStatus;
    public String paymentRef;

    public long lastSeq;
    public List<RideEvent> events = new ArrayList<>();
    /** Multi-stop: the intermediate stops with their status. Empty for an A-to-B ride. */
    public List<RideStop> stops = new ArrayList<>();

    public RideRecord() {}
}
