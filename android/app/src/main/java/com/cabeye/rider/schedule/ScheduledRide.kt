package com.cabeye.rider.schedule

import org.json.JSONObject

/**
 * A next journey the rider booked for later by voice: "tomorrow at 8 30, to college".
 *
 * [destinationPhrase] is always kept — the rider's own words — so if the remembered place has
 * no coordinates, or the rider named somewhere new, it is resolved exactly as if they had said
 * it at the scheduled time.
 */
data class ScheduledRide(
    val id: String,
    val at: Long,
    val destinationPhrase: String,
    val placeName: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String = "",
    val placeId: String = "",
    val rideType: String = "AUTO",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** The name to say: the resolved place when known, otherwise what the rider said. */
    val spokenDestination: String get() = placeName.ifBlank { destinationPhrase }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("at", at).put("destinationPhrase", destinationPhrase)
        .put("placeName", placeName)
        .put("latitude", latitude ?: JSONObject.NULL).put("longitude", longitude ?: JSONObject.NULL)
        .put("address", address).put("placeId", placeId).put("rideType", rideType).put("createdAt", createdAt)

    companion object {
        fun parse(o: JSONObject) = ScheduledRide(
            id = o.getString("id"),
            at = o.getLong("at"),
            destinationPhrase = o.optString("destinationPhrase", ""),
            placeName = o.optString("placeName", ""),
            latitude = if (o.isNull("latitude")) null else o.optDouble("latitude"),
            longitude = if (o.isNull("longitude")) null else o.optDouble("longitude"),
            address = o.optString("address", ""),
            placeId = o.optString("placeId", ""),
            rideType = o.optString("rideType", "AUTO"),
            createdAt = o.optLong("createdAt", 0)
        )
    }
}
