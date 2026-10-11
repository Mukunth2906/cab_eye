package com.cabeye.rider.places

import android.content.Context
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The name of the area a point is in — "Gandhipuram", "RS Puram" — from the phone's own
 * geocoder. Free, no API key; on most phones it uses Google's service, so it needs a network
 * connection. Null when it can't tell, and the ride guide then says nothing rather than guess.
 */
class AreaNamer(context: Context) {

    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context.applicationContext, Locale.ENGLISH) else null

    /**
     * @param orCity false on a ride: only a neighbourhood counts, or "Now in Coimbatore" would be
     *   said every time the geocoder has no neighbourhood for a stretch of road
     */
    @Suppress("DEPRECATION") // the listener form needs API 33; this runs off the main thread
    suspend fun areaAt(latitude: Double, longitude: Double, orCity: Boolean = false): String? {
        val g = geocoder ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val a = g.getFromLocation(latitude, longitude, 1)?.firstOrNull() ?: return@runCatching null
                (a.subLocality ?: a.locality?.takeIf { orCity })?.trim()?.takeIf { it.isNotBlank() }
            }.onFailure { Log.w("CabEye.Area", "AREA failure ${it.javaClass.simpleName}") }.getOrNull()
        }
    }
}
