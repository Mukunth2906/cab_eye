package com.cabeye.rider.places

import android.content.Context
import android.location.Location
import android.util.Log
import com.cabeye.rider.state.PlaceOption
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Resolves a spoken destination against Google's live Places database.
 *
 * The existing Gazetteer remains the offline fallback. This class only replaces the source of
 * candidates: the confidence/ambiguity policy stays in MatchGate, so the old safety behaviour
 * is not bypassed by the new provider.
 */
class GooglePlaceResolver(context: Context) {

    private val appContext = context.applicationContext

    private val placesClient: PlacesClient by lazy {
        Places.createClient(appContext)
    }

    /**
     * Search by arbitrary text. The user's GPS is a bias, not a hard restriction, so an explicit
     * destination such as "Chennai airport" can still be found even when the rider is elsewhere.
     *
     * Two attempts are made before giving up:
     *   1. biased by the rider's location (better local results for "Gandhipuram")
     *   2. unbiased, region-only (so a bad/stale GPS fix can never hide a real place)
     *
     * A failure is surfaced to the caller with its real Google status code so the fallback to
     * the Gazetteer is a diagnosable event rather than a silent one.
     */
    suspend fun search(query: String, location: Location?): Result<List<PlaceOption>> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return Result.success(emptyList())

        if (!Places.isInitialized()) {
            Log.e(TAG, "PLACE_SEARCH failure reason=SDK_NOT_INITIALIZED query=\"$trimmed\"")
            return Result.failure(IllegalStateException("Places SDK not initialized"))
        }

        val biased = runSearch(trimmed, location)

        // A successful-but-empty biased search is retried without the bias. A stale or wrong GPS
        // fix must never be the reason a real place cannot be found.
        val biasedResults = biased.getOrNull()
        if (location != null && biasedResults != null && biasedResults.isEmpty()) {
            Log.i(TAG, "PLACE_SEARCH retry without locationBias query=\"$trimmed\"")
            return runSearch(trimmed, location = null)
        }

        // A biased search that failed outright is also retried unbiased — unless the failure is
        // an auth/config problem, where a retry would fail identically.
        if (biased.isFailure && location != null && !isConfigFailure(biased.exceptionOrNull())) {
            Log.i(TAG, "PLACE_SEARCH retry without locationBias after failure query=\"$trimmed\"")
            return runSearch(trimmed, location = null)
        }

        return biased
    }

    private suspend fun runSearch(query: String, location: Location?): Result<List<PlaceOption>> =
        suspendCoroutine { continuation ->
            val fields = listOf(
                Place.Field.ID,
                Place.Field.DISPLAY_NAME,
                Place.Field.FORMATTED_ADDRESS,
                Place.Field.LOCATION,
                // Used only to tell a road from a building. "Thadagam Road" needs a follow-up
                // question about who is meeting the rider; "GKNM Hospital" does not.
                Place.Field.TYPES
            )

            val builder = SearchByTextRequest.builder(query, fields)
                .setMaxResultCount(MAX_RESULTS)
                .setRegionCode(REGION_CODE)

            if (location != null) {
                builder.setLocationBias(
                    CircularBounds.newInstance(
                        LatLng(location.latitude, location.longitude),
                        SEARCH_BIAS_RADIUS_METRES
                    )
                )
            }

            Log.i(
                TAG,
                "PLACE_SEARCH query=\"$query\" bias=${if (location != null) "yes" else "none"} " +
                    "region=$REGION_CODE max=$MAX_RESULTS"
            )

            try {
                placesClient.searchByText(builder.build())
                    .addOnSuccessListener { response ->
                        val raw = response.places
                        val results = raw.mapIndexedNotNull { index, place ->
                            val point = place.location
                            if (point == null) {
                                Log.w(TAG, "PLACE_SEARCH dropped result index=$index reason=no-location")
                                return@mapIndexedNotNull null
                            }
                            val name = place.displayName?.trim().orEmpty()
                            if (name.isBlank()) {
                                Log.w(TAG, "PLACE_SEARCH dropped result index=$index reason=no-display-name")
                                return@mapIndexedNotNull null
                            }

                            PlaceOption(
                                name = name,
                                latitude = point.latitude,
                                longitude = point.longitude,
                                // Google already ranks Text Search results by relevance. We retain
                                // the existing MatchGate by translating rank + lexical agreement
                                // into its 0..1 score rather than inventing a second gate.
                                score = score(query, name, index),
                                // Google has already performed semantic place matching; the
                                // legacy fragment gate is therefore not needed for its candidates.
                                // Short/noisy queries are filtered before reaching this provider.
                                coversWholeToken = true,
                                cityName = extractCity(place.formattedAddress),
                                formattedAddress = place.formattedAddress.orEmpty(),
                                placeId = place.id.orEmpty(),
                                isVague = isRoad(place)
                            )
                        }

                        Log.i(
                            TAG,
                            "PLACE_SEARCH success raw=${raw.size} usable=${results.size} " +
                                "top=\"${results.firstOrNull()?.name.orEmpty()}\""
                        )
                        continuation.resume(Result.success(results))
                    }
                    .addOnFailureListener { error ->
                        logFailure(query, error)
                        continuation.resume(Result.failure(error))
                    }
            } catch (t: Throwable) {
                // A misconfigured SDK can throw synchronously rather than failing the Task.
                logFailure(query, t)
                continuation.resume(Result.failure(t))
            }
        }

    /** Never logs the API key. Only Google's own status code and message. */
    private fun logFailure(query: String, error: Throwable) {
        val code = (error as? ApiException)?.statusCode
        Log.e(
            TAG,
            "PLACE_SEARCH failure query=\"$query\" type=${error.javaClass.simpleName} " +
                "code=${code ?: "n/a"} message=${error.message}"
        )
        if (code != null) {
            Log.e(TAG, "PLACE_SEARCH hint=${hintFor(code)}")
        }
    }

    /**
     * Turns a Places status code into the concrete thing to go and fix, so a REQUEST_DENIED is
     * never mistaken for "that place does not exist".
     */
    private fun hintFor(code: Int): String = when (code) {
        9011, 9012 ->
            "REQUEST_DENIED — key not authorised. Check the key's Android restriction lists " +
                "package com.cabeye.rider with the debug SHA-1, and that 'Places API (New)' is " +
                "enabled on this Cloud project and allowed on the key."
        9010 -> "INVALID_REQUEST — bad query/field mask."
        9013 -> "OVER_QUERY_LIMIT — quota or billing exhausted for this project."
        7 -> "NETWORK_ERROR — device could not reach Google."
        else -> "See Places SDK status codes for $code."
    }

    private fun isConfigFailure(error: Throwable?): Boolean =
        (error as? ApiException)?.statusCode in setOf(9011, 9012, 9013)

    /**
     * True when Google calls this a road rather than a place you can stand at a door of.
     *
     * Read defensively: `placeTypes` is nullable, its contents vary by SDK version, and a
     * missing types list must mean "treat it as a normal destination" rather than crash a
     * booking. Being wrong here costs one extra question; throwing here costs the ride.
     */
    private fun isRoad(place: Place): Boolean = try {
        place.placeTypes.orEmpty().any { it.lowercase() in ROAD_TYPES }
    } catch (_: Throwable) {
        false
    }

    private fun score(query: String, name: String, rank: Int): Float {
        val q = normalise(query)
        val n = normalise(name)
        if (q.isBlank() || n.isBlank()) return 0f
        if (q == n) return 1f

        val qWords = q.split(' ').filter { it.isNotBlank() }
        val nWords = n.split(' ').filter { it.isNotBlank() }
        val exactWords = qWords.count { it in nWords }
        val wordRatio = if (qWords.isEmpty()) 0f else exactWords.toFloat() / qWords.size
        val contains = n.contains(q) || q.contains(n)
        val rankScore = (1f - rank.coerceAtMost(MAX_RESULTS - 1).toFloat() / MAX_RESULTS) * 0.15f
        return (0.55f + 0.25f * wordRatio + (if (contains) 0.15f else 0f) + rankScore)
            .coerceIn(0f, 0.99f)
    }


    private fun normalise(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun extractCity(address: String?): String {
        val parts = address.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
        return parts.dropLast(1).lastOrNull().orEmpty()
    }

    private companion object {
        const val TAG = "CabEye.Places"
        const val MAX_RESULTS = 5
        const val REGION_CODE = "IN"
        const val SEARCH_BIAS_RADIUS_METRES = 25_000.0

        /** Google's own type names for "this is a stretch of road, not an address". */
        val ROAD_TYPES = setOf("route", "street_address", "intersection")
    }
}
