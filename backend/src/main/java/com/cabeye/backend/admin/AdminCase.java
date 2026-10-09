package com.cabeye.backend.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * One item in the admin inbox: a rider's feedback today, an SOS next.
 *
 * <p>It copies the who/what at the time it was opened (rider name and phone, driver, plate) so
 * the admin can act on it — call the rider, find the car — even if a profile changes later.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminCase {

    public enum Kind {
        FEEDBACK, SOS,
        /** Multi-stop: the rider did not come back to the car within the WAIT stop's limit. */
        STOP_OVERDUE
    }

    public enum Status { NEW, ACKNOWLEDGED, RESOLVED }

    public String id;
    public Kind kind;
    public Status status = Status.NEW;
    public boolean urgent;

    public String rideId;
    public String riderId;
    public String riderName;
    public String riderPhone;
    public String emergencyContactName;
    public String emergencyContactPhone;
    public String driverId;
    public String driverName;
    public String driverPhone;
    public String vehicle;
    public String vehiclePlate;
    public String destination;

    /** Feedback only. */
    public Integer rating;
    public String category;
    /** The rider's own words, exactly as they confirmed them. */
    public String text;

    /** SOS only (next step). */
    public Double latitude;
    public Double longitude;
    public Long locationAt;

    public List<Note> notes = new ArrayList<>();
    public long createdAt;
    public long updatedAt;
    /** What the alert did, e.g. "Telegram sent · email not set up". */
    public String alertResult;

    public AdminCase() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Note {
        public long at;
        public String by;
        public String text;

        public Note() {}

        Note(long at, String by, String text) {
            this.at = at;
            this.by = by;
            this.text = text;
        }
    }
}
