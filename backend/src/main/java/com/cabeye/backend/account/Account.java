package com.cabeye.backend.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One signed-in person: a rider or a driver.
 *
 * <p>Plain public fields on purpose — this is both the stored row and the JSON the apps read, and
 * Jackson maps public fields with no ceremony. Exactly one of {@link #rider} / {@link #driver}
 * is set, matching {@link #role}.
 *
 * <p>Times are epoch milliseconds rather than {@code Instant} so the Android side can read them
 * with a plain {@code optLong} and no date parsing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Account {

    public String id;
    public Role role;
    /** Ten digits, canonical — see {@link Phone#normalize}. */
    public String phone;
    public String name;
    public long createdAt;
    public long lastLoginAt;

    public RiderProfile rider;
    public DriverProfile driver;

    public Account() {}

    /** What a rider has told the app about how they want it to behave. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RiderProfile {
        /** BCP-47 tag for TTS/STT, e.g. "en-IN", "ta-IN". */
        public String language = "en-IN";
        /** TTS rate multiplier; 1.0 is normal. Many screen-reader users run far above 1.0. */
        public double speechRate = 1.0;
        /** AUTO or CAB. */
        public String preferredRideType = "AUTO";
        public String emergencyContactName;
        public String emergencyContactPhone;
        /** Whether the memory agent may suggest destinations from past trips. */
        public boolean memoryEnabled = true;

        public RiderProfile() {}
    }

    /** What a rider hears about the driver, and what the driver app shows. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DriverProfile {
        /** AUTO or CAB. */
        public String vehicleType = "AUTO";
        public String vehicleModel;
        /** e.g. "TN 38 AB 1234" — spoken to the rider, so kept in its readable form. */
        public String vehiclePlate;
        public String vehicleColour;
        public String licenceNumber;
        /** Languages the driver speaks, comma separated, e.g. "Tamil, English". */
        public String languages;
        /** Completed trips, maintained by the server. */
        public int completedTrips;
        /** Running average of rider ratings, 0 until the first rating. */
        public double ratingAverage;
        public int ratingCount;
        /**
         * Set only by an admin (never by the driver app). A suspended driver cannot accept rides.
         */
        public boolean suspended;
        public String suspendedReason;
        public long suspendedAt;

        public DriverProfile() {}

        /** True once the fields a rider needs to find the car are present. */
        @JsonIgnore
        public boolean isComplete() {
            return notBlank(vehicleModel) && notBlank(vehiclePlate);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
