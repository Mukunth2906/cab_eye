package com.cabeye.nlu;

/**
 * One entry in the gazetteer.
 *
 * <p>{@code name} is what gets read out loud, so it is stored exactly as
 * it should be spoken — "Anna Nagar East", not "anna_nagar_east". The
 * narrator has no dictionary to consult and no way to guess where the
 * word breaks go.
 *
 * <p>The coordinates are not decoration. They are what the ambiguity
 * rule measures: two candidates that score identically but sit four
 * hundred metres apart are not worth a spoken clarification, and two
 * that sit twelve kilometres apart are.
 */
public record Place(String name, double lat, double lng) {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** Great-circle distance to another place, in kilometres. */
    public double kmTo(Place other) {
        double dLat = Math.toRadians(other.lat - lat);
        double dLng = Math.toRadians(other.lng - lng);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                 + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat))
                 * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
