package com.cabeye.rider.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * The rider's memory as the phone holds it: previously visited places, recent trips, and how
 * the rider has answered suggestions. Plain data — the agent that reasons over it is
 * [PreferenceMemoryAgent] — so every rule about it is testable on the JVM.
 */
data class RiderMemory(
    val places: List<VisitedPlace> = emptyList(),
    val trips: List<TripRecord> = emptyList(),
    val stats: MemoryStats = MemoryStats()
) {
    val isEmpty: Boolean get() = places.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("places", JSONArray().apply { places.forEach { put(it.toJson()) } })
        .put("trips", JSONArray().apply { trips.forEach { put(it.toJson()) } })
        .put("stats", stats.toJson())

    companion object {
        val EMPTY = RiderMemory()

        fun parse(o: JSONObject): RiderMemory = RiderMemory(
            places = o.optJSONArray("places").objects().mapNotNull { runCatching { VisitedPlace.parse(it) }.getOrNull() },
            trips = o.optJSONArray("trips").objects().mapNotNull { runCatching { TripRecord.parse(it) }.getOrNull() },
            stats = o.optJSONObject("stats")?.let(MemoryStats::parse) ?: MemoryStats()
        )

        fun parse(raw: String?): RiderMemory? =
            raw?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }
}

/** A place the rider has been taken to before. Mirrors the backend's `VisitedPlace`. */
data class VisitedPlace(
    val placeKey: String,
    val name: String,
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val placeId: String = "",
    val visitCount: Int = 0,
    val lastVisitedAt: Long = 0,
    /** Visits by local hour of day, 24 entries. */
    val hourCounts: List<Int> = List(24) { 0 },
    val weekdayVisits: Int = 0,
    val weekendVisits: Int = 0,
    /** The rider's own confirmed words for this place, lower case. */
    val aliases: List<String> = emptyList(),
    val accepted: Int = 0,
    val rejected: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("placeKey", placeKey).put("name", name).put("address", address)
        .put("latitude", latitude ?: JSONObject.NULL).put("longitude", longitude ?: JSONObject.NULL)
        .put("placeId", placeId).put("visitCount", visitCount).put("lastVisitedAt", lastVisitedAt)
        .put("hourCounts", JSONArray(hourCounts)).put("weekdayVisits", weekdayVisits)
        .put("weekendVisits", weekendVisits).put("aliases", JSONArray(aliases))
        .put("accepted", accepted).put("rejected", rejected)

    companion object {
        fun parse(o: JSONObject): VisitedPlace {
            val hours = o.optJSONArray("hourCounts")
            return VisitedPlace(
                placeKey = o.getString("placeKey"),
                name = o.getString("name"),
                address = o.optString("address", "").takeUnless { it == "null" }.orEmpty(),
                latitude = o.optDoubleOrNull("latitude"),
                longitude = o.optDoubleOrNull("longitude"),
                placeId = o.optString("placeId", "").takeUnless { it == "null" }.orEmpty(),
                visitCount = o.optInt("visitCount", 0),
                lastVisitedAt = o.optLong("lastVisitedAt", 0),
                hourCounts = List(24) { i -> hours?.optInt(i, 0) ?: 0 },
                weekdayVisits = o.optInt("weekdayVisits", 0),
                weekendVisits = o.optInt("weekendVisits", 0),
                aliases = o.optJSONArray("aliases").strings(),
                accepted = o.optInt("accepted", 0),
                rejected = o.optInt("rejected", 0)
            )
        }
    }
}

/** One completed journey. */
data class TripRecord(
    val rideId: String,
    val placeKey: String,
    val destination: String,
    val spokenAs: String = "",
    val pickupLatitude: Double? = null,
    val pickupLongitude: Double? = null,
    val rideType: String = "AUTO",
    val bookedAt: Long = 0,
    /** Local hour 0–23 at booking. */
    val hour: Int = 0,
    /** ISO day of week, 1 = Monday … 7 = Sunday. */
    val dayOfWeek: Int = 1
) {
    fun toJson(): JSONObject = JSONObject()
        .put("rideId", rideId).put("placeKey", placeKey).put("destination", destination)
        .put("spokenAs", spokenAs)
        .put("pickupLatitude", pickupLatitude ?: JSONObject.NULL)
        .put("pickupLongitude", pickupLongitude ?: JSONObject.NULL)
        .put("rideType", rideType).put("bookedAt", bookedAt).put("hour", hour).put("dayOfWeek", dayOfWeek)

    companion object {
        fun parse(o: JSONObject) = TripRecord(
            rideId = o.getString("rideId"),
            placeKey = o.getString("placeKey"),
            destination = o.optString("destination", ""),
            spokenAs = o.optString("spokenAs", "").takeUnless { it == "null" }.orEmpty(),
            pickupLatitude = o.optDoubleOrNull("pickupLatitude"),
            pickupLongitude = o.optDoubleOrNull("pickupLongitude"),
            rideType = o.optString("rideType", "AUTO"),
            bookedAt = o.optLong("bookedAt", 0),
            hour = o.optInt("hour", 0),
            dayOfWeek = o.optInt("dayOfWeek", 1)
        )
    }
}

/** How the rider has answered the agent. Input to the confidence recalibration loop. */
data class MemoryStats(
    val proactiveAccepted: Int = 0,
    val proactiveRejected: Int = 0,
    val repairAccepted: Int = 0,
    val repairRejected: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("proactiveAccepted", proactiveAccepted).put("proactiveRejected", proactiveRejected)
        .put("repairAccepted", repairAccepted).put("repairRejected", repairRejected)

    companion object {
        fun parse(o: JSONObject) = MemoryStats(
            proactiveAccepted = o.optInt("proactiveAccepted", 0),
            proactiveRejected = o.optInt("proactiveRejected", 0),
            repairAccepted = o.optInt("repairAccepted", 0),
            repairRejected = o.optInt("repairRejected", 0)
        )
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, null) }

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
