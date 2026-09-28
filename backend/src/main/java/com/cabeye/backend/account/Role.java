package com.cabeye.backend.account;

/**
 * Which app an account signs in to.
 *
 * <p>The same phone number may hold one RIDER and one DRIVER account — a driver can also book a
 * ride — so an account is keyed by (role, phone), never by phone alone.
 */
public enum Role {
    RIDER,
    DRIVER;

    /** Lenient parse for request bodies: anything unrecognised is a rider. */
    public static Role parse(Object raw) {
        if (raw == null) return RIDER;
        return "DRIVER".equalsIgnoreCase(String.valueOf(raw).trim()) ? DRIVER : RIDER;
    }
}
