package com.cabeye.backend.account;

import com.cabeye.backend.store.DataDirectory;
import com.cabeye.backend.store.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Riders and drivers: lookup, first-time creation and profile edits.
 *
 * <p>Validation of profile values lives here rather than in the controller so the same rules
 * apply whether a change came from the rider's voice onboarding or the driver's form.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    static final double MIN_SPEECH_RATE = 0.5;
    static final double MAX_SPEECH_RATE = 3.0;
    static final int MAX_TEXT = 80;

    private final Table<Account> accounts;

    public AccountService(DataDirectory data) {
        this.accounts = data.table("accounts", Account.class);
    }

    public Optional<Account> find(String accountId) {
        return accountId == null ? Optional.empty() : accounts.get(accountId);
    }

    public Optional<Account> findByPhone(Role role, String phone) {
        return accounts.first(a -> a.role == role && phone.equals(a.phone));
    }

    /**
     * Returns the existing account for this number, or creates it. Called only after the OTP
     * was verified, so creating here cannot be used to squat on someone else's number.
     *
     * @return the account and whether it was just created
     */
    public synchronized Login loginOrCreate(Role role, String phone, String nameIfNew) {
        long now = System.currentTimeMillis();
        Optional<Account> existing = findByPhone(role, phone);
        if (existing.isPresent()) {
            Account updated = accounts.update(existing.get().id, a -> {
                a.lastLoginAt = now;
                return a;
            }).orElseThrow();
            return new Login(updated, false);
        }

        Account a = new Account();
        a.id = (role == Role.DRIVER ? "driver-" : "rider-") + UUID.randomUUID().toString().substring(0, 8);
        a.role = role;
        a.phone = phone;
        a.name = clean(nameIfNew);
        a.createdAt = now;
        a.lastLoginAt = now;
        if (role == Role.DRIVER) a.driver = new Account.DriverProfile();
        else a.rider = new Account.RiderProfile();
        accounts.put(a.id, a);
        log.info("ACCOUNT_CREATED id={} role={} phone={}", a.id, role, Phone.masked(phone));
        return new Login(a, true);
    }

    /**
     * Applies a partial profile update. Only keys present in {@code changes} are touched, so
     * the rider app can change speech rate without resending the emergency contact.
     *
     * @throws IllegalArgumentException with a sentence fit to be spoken, when a value is invalid
     */
    public Account updateProfile(String accountId, Map<String, Object> changes) {
        return accounts.update(accountId, a -> {
            if (changes.containsKey("name")) a.name = clean(changes.get("name"));

            if (a.role == Role.RIDER) {
                if (a.rider == null) a.rider = new Account.RiderProfile();
                Account.RiderProfile p = a.rider;
                if (changes.containsKey("language")) p.language = orDefault(clean(changes.get("language")), "en-IN");
                if (changes.containsKey("speechRate")) p.speechRate = rate(changes.get("speechRate"));
                if (changes.containsKey("preferredRideType")) p.preferredRideType = rideType(changes.get("preferredRideType"));
                if (changes.containsKey("emergencyContactName")) p.emergencyContactName = clean(changes.get("emergencyContactName"));
                if (changes.containsKey("emergencyContactPhone")) p.emergencyContactPhone = optionalPhone(changes.get("emergencyContactPhone"));
                if (changes.containsKey("memoryEnabled")) p.memoryEnabled = Boolean.parseBoolean(String.valueOf(changes.get("memoryEnabled")));
            } else {
                if (a.driver == null) a.driver = new Account.DriverProfile();
                Account.DriverProfile d = a.driver;
                if (changes.containsKey("vehicleType")) d.vehicleType = rideType(changes.get("vehicleType"));
                if (changes.containsKey("vehicleModel")) d.vehicleModel = clean(changes.get("vehicleModel"));
                if (changes.containsKey("vehiclePlate")) d.vehiclePlate = plate(changes.get("vehiclePlate"));
                if (changes.containsKey("vehicleColour")) d.vehicleColour = clean(changes.get("vehicleColour"));
                if (changes.containsKey("licenceNumber")) d.licenceNumber = clean(changes.get("licenceNumber"));
                if (changes.containsKey("languages")) d.languages = clean(changes.get("languages"));
            }
            return a;
        }).orElseThrow(() -> new IllegalArgumentException("Account not found."));
    }

    /** Server-maintained driver stats; never settable by the driver app. */
    public void recordDriverTrip(String driverId) {
        accounts.update(driverId, a -> {
            if (a.driver != null) a.driver.completedTrips++;
            return a;
        });
    }

    /** Adds a rating without counting a trip (feedback arrives after the trip was counted). */
    public void recordDriverRating(String driverId, int rating) {
        if (rating < 1 || rating > 5) return;
        accounts.update(driverId, a -> {
            if (a.driver == null) return a;
            double total = a.driver.ratingAverage * a.driver.ratingCount + rating;
            a.driver.ratingCount++;
            a.driver.ratingAverage = Math.round(total / a.driver.ratingCount * 10.0) / 10.0;
            return a;
        });
    }

    /** Adds a driver's rating of this rider (once per ride — the caller makes sure of that). */
    public void recordRiderRating(String riderId, int rating) {
        if (rating < 1 || rating > 5) return;
        accounts.update(riderId, a -> {
            if (a.rider == null) return a;
            double total = a.rider.ratingAverage * a.rider.ratingCount + rating;
            a.rider.ratingCount++;
            a.rider.ratingAverage = Math.round(total / a.rider.ratingCount * 10.0) / 10.0;
            return a;
        });
    }

    /** Every account with this role, newest first. For the admin console. */
    public List<Account> all(Role role) {
        List<Account> out = accounts.where(a -> a.role == role);
        out.sort(Comparator.comparingLong((Account a) -> a.createdAt).reversed());
        return out;
    }

    /**
     * Admin only. Suspends or restores a driver. Suspension stops new accepts; a ride already
     * in progress carries on, because stranding a blind passenger mid-trip is the worse harm.
     */
    public Optional<Account> setSuspended(String driverId, boolean suspended, String reason) {
        return accounts.update(driverId, a -> {
            if (a.driver == null) return a;
            a.driver.suspended = suspended;
            a.driver.suspendedReason = suspended ? clean(reason) : null;
            a.driver.suspendedAt = suspended ? System.currentTimeMillis() : 0;
            return a;
        }).filter(a -> a.role == Role.DRIVER);
    }

    public record Login(Account account, boolean created) {}

    // -----------------------------------------------------------------------------------
    //  Value rules
    // -----------------------------------------------------------------------------------

    static String clean(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    private static String orDefault(String s, String fallback) {
        return s == null ? fallback : s;
    }

    private static double rate(Object raw) {
        double v;
        try {
            v = Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Speech rate must be a number.");
        }
        if (v < MIN_SPEECH_RATE || v > MAX_SPEECH_RATE) {
            throw new IllegalArgumentException("Speech rate must be between half and three times normal.");
        }
        return v;
    }

    private static String rideType(Object raw) {
        return "CAB".equalsIgnoreCase(String.valueOf(raw).trim()) ? "CAB" : "AUTO";
    }

    private static String optionalPhone(Object raw) {
        String s = clean(raw);
        if (s == null) return null;
        return Phone.normalize(s).orElseThrow(
                () -> new IllegalArgumentException("That emergency contact is not a ten digit mobile number."));
    }

    /** "tn38ab1234" → "TN 38 AB 1234" where the shape allows; otherwise just upper-cased. */
    static String plate(Object raw) {
        String s = clean(raw);
        if (s == null) return null;
        String compact = s.toUpperCase().replaceAll("[^A-Z0-9]", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([A-Z]{2})(\\d{1,2})([A-Z]{0,3})(\\d{1,4})$").matcher(compact);
        if (!m.matches()) return s.toUpperCase();
        StringBuilder out = new StringBuilder(m.group(1)).append(' ').append(m.group(2));
        if (!m.group(3).isEmpty()) out.append(' ').append(m.group(3));
        return out.append(' ').append(m.group(4)).toString();
    }
}
