package com.cabeye.rider.auth

import com.cabeye.rider.net.AppRole
import org.json.JSONObject

/**
 * The signed-in person, as the backend's `/me` describes them.
 *
 * Parsed with `org.json` like every other model in the app, and cached as its raw JSON so a
 * rider with no signal can still open the app, hear their name and use their memory.
 */
data class AccountInfo(
    val id: String,
    val role: AppRole,
    val phone: String,
    val name: String,
    val rider: RiderProfile? = null,
    val driver: DriverProfile? = null,
    val profileComplete: Boolean = true,
    /** True for "continue without signing in" — no account on the server at all. */
    val isGuest: Boolean = false
) {
    /** "Harshini", or empty — for sentences like "Welcome back, Harshini." */
    val spokenName: String get() = name.trim()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("role", role.name)
        .put("phone", phone)
        .put("name", name)
        .put("profileComplete", profileComplete)
        .put("isGuest", isGuest)
        .apply {
            rider?.let { put("rider", it.toJson()) }
            driver?.let { put("driver", it.toJson()) }
        }

    companion object {
        const val GUEST_ID = "guest"

        fun guest() = AccountInfo(
            id = GUEST_ID, role = AppRole.RIDER, phone = "", name = "",
            rider = RiderProfile(), isGuest = true
        )

        /**
         * Accepts either the server's wrapper `{"account":{…},"profileComplete":…}` or a bare
         * account object (the cached form).
         */
        fun parse(raw: JSONObject): AccountInfo? = runCatching {
            val a = raw.optJSONObject("account") ?: raw
            val role = AppRole.parse(a.optString("role"))
            val rider = a.optJSONObject("rider")?.let(RiderProfile::parse)
            val driver = a.optJSONObject("driver")?.let(DriverProfile::parse)
            val name = a.optString("name", "").takeUnless { it == "null" }.orEmpty()
            val complete = if (raw.has("profileComplete")) raw.optBoolean("profileComplete")
            else a.optBoolean("profileComplete", name.isNotBlank())
            AccountInfo(
                id = a.getString("id"),
                role = role,
                phone = a.optString("phone", ""),
                name = name,
                rider = rider ?: if (role == AppRole.RIDER) RiderProfile() else null,
                driver = driver ?: if (role == AppRole.DRIVER) DriverProfile() else null,
                profileComplete = complete,
                isGuest = a.optBoolean("isGuest", false)
            )
        }.getOrNull()

        fun parse(raw: String?): AccountInfo? =
            raw?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }
}

data class RiderProfile(
    val language: String = "en-IN",
    val speechRate: Double = 1.0,
    val preferredRideType: String = "AUTO",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val memoryEnabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("language", language)
        .put("speechRate", speechRate)
        .put("preferredRideType", preferredRideType)
        .put("emergencyContactName", emergencyContactName)
        .put("emergencyContactPhone", emergencyContactPhone)
        .put("memoryEnabled", memoryEnabled)

    companion object {
        fun parse(o: JSONObject) = RiderProfile(
            language = o.optString("language", "en-IN"),
            speechRate = o.optDouble("speechRate", 1.0),
            preferredRideType = o.optString("preferredRideType", "AUTO"),
            emergencyContactName = o.optString("emergencyContactName", ""),
            emergencyContactPhone = o.optString("emergencyContactPhone", ""),
            memoryEnabled = o.optBoolean("memoryEnabled", true)
        )
    }
}

data class DriverProfile(
    val vehicleType: String = "AUTO",
    val vehicleModel: String = "",
    val vehiclePlate: String = "",
    val vehicleColour: String = "",
    val licenceNumber: String = "",
    val languages: String = "",
    val completedTrips: Int = 0,
    val ratingAverage: Double = 0.0,
    val ratingCount: Int = 0
) {
    /** "Yellow Bajaj RE" — what the rider is told to look for. */
    val vehicleDescription: String
        get() = listOf(vehicleColour, vehicleModel).filter { it.isNotBlank() }.joinToString(" ")

    fun toJson(): JSONObject = JSONObject()
        .put("vehicleType", vehicleType)
        .put("vehicleModel", vehicleModel)
        .put("vehiclePlate", vehiclePlate)
        .put("vehicleColour", vehicleColour)
        .put("licenceNumber", licenceNumber)
        .put("languages", languages)
        .put("completedTrips", completedTrips)
        .put("ratingAverage", ratingAverage)
        .put("ratingCount", ratingCount)

    companion object {
        fun parse(o: JSONObject) = DriverProfile(
            vehicleType = o.optString("vehicleType", "AUTO"),
            vehicleModel = o.optString("vehicleModel", ""),
            vehiclePlate = o.optString("vehiclePlate", ""),
            vehicleColour = o.optString("vehicleColour", ""),
            licenceNumber = o.optString("licenceNumber", ""),
            languages = o.optString("languages", ""),
            completedTrips = o.optInt("completedTrips", 0),
            ratingAverage = o.optDouble("ratingAverage", 0.0),
            ratingCount = o.optInt("ratingCount", 0)
        )
    }
}
