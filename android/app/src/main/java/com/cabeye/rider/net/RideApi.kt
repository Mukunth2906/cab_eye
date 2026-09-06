package com.cabeye.rider.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** The outcome of a call, with a spoken form for the failure cases. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /**
     * @param spoken a sentence ready to be read aloud. Failures on this app are announced, not
     *   displayed — so every failure has to arrive already phrased for the ear. A `Throwable`
     *   with a stack trace helps a developer and tells the rider nothing.
     */
    data class Failed(val spoken: String, val detail: String) : ApiResult<Nothing>
}

/**
 * The REST client.
 *
 * ## The one method that matters most
 * [snapshot] is not a convenience. It is the reconciliation path: when the socket drops and
 * comes back, this is how the app learns where the ride *actually* is, so it can announce the
 * difference rather than replaying everything that happened while it was deaf. Everything else
 * here is the driver app driving the ride forward.
 *
 * ## Timeouts
 * Short, and short on purpose. The default OkHttp read timeout is ten seconds; ten seconds of
 * silence is an eternity to someone who cannot see a spinner, and the app's contract is that
 * silence means "working normally". Failing fast lets the app *say* something.
 *
 * ## Headers
 * `X-User-Id` and `X-Role` go on every request, from an interceptor rather than from each call
 * site. The role toggle has to reach every request without exception — a single endpoint that
 * forgot the header would report the wrong role to the backend under precisely the conditions
 * the toggle exists to create.
 */
class RideApi(private val settings: AppSettings) {

    private companion object {
        const val TAG = "CabEye.Api"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        // ngrok's free tier idles connections aggressively; letting OkHttp retry a request that
        // died on a stale pooled connection turns a spurious failure into a slightly slower
        // success, which is strictly better than an announcement that the backend is down.
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val s = settings.current()
            chain.proceed(
                chain.request().newBuilder()
                    .header("X-User-Id", s.userId)
                    .header("X-Role", s.role.wireName)
                    // ngrok's free tier serves an HTML interstitial to browser-looking clients.
                    // This header suppresses it. Without it the very first call returns HTML,
                    // JSON parsing fails, and the app announces that the backend is unreachable
                    // when it is in fact perfectly healthy.
                    .header("ngrok-skip-browser-warning", "true")
                    .header("User-Agent", "CabEye-Android")
                    .build()
            )
        }
        .build()

    /** Base URL, re-read per call so a settings change takes effect without a restart. */
    private fun base(): String = settings.current().backendBaseUrl

    // ===================================================================================
    //  Health
    // ===================================================================================

    /**
     * Pings `/health`. Backs the debug screen's "Test connection" button.
     *
     * @return a sentence to speak, in both the success and failure cases. This method returns
     *   speech rather than a boolean because the result is *only* ever delivered aloud, and
     *   phrasing it here keeps the "what does the user hear" decision next to the facts that
     *   determine it.
     */
    suspend fun health(): ApiResult<String> {
        val url = BackendUrl.healthUrl(base())
        return when (val result = call(Request.Builder().url(url).get().build())) {
            is ApiResult.Ok -> {
                val activeRides = runCatching { JSONObject(result.value).optInt("activeRides", 0) }
                    .getOrDefault(0)
                ApiResult.Ok(
                    "Connected to ${BackendUrl.spokenHost(base())}. " +
                            if (activeRides == 0) "No rides in progress."
                            else "$activeRides ride${if (activeRides == 1) "" else "s"} in progress."
                )
            }
            is ApiResult.Failed -> result
        }
    }

    // ===================================================================================
    //  Rider
    // ===================================================================================

    suspend fun createRide(
        destination: String,
        rideType: String,
        destinationAddress: String = "",
        destinationLatitude: Double? = null,
        destinationLongitude: Double? = null,
        destinationPlaceId: String = "",
        pickupLatitude: Double? = null,
        pickupLongitude: Double? = null,
        contactName: String = "",
        contactPhone: String = "",
        dropNote: String = ""
    ): ApiResult<RideSnapshot> {
        val body = JSONObject()
            .put("destination", destination)
            .put("rideType", rideType)
            .put("destinationAddress", destinationAddress)
            .put("destinationPlaceId", destinationPlaceId)
            // Sent only when the rider supplied them. The server defaults each to "".
            .put("contactName", contactName)
            .put("contactPhone", contactPhone)
            .put("dropNote", dropNote)
        if (destinationLatitude != null) body.put("destinationLatitude", destinationLatitude)
        if (destinationLongitude != null) body.put("destinationLongitude", destinationLongitude)
        if (pickupLatitude != null) body.put("pickupLatitude", pickupLatitude)
        if (pickupLongitude != null) body.put("pickupLongitude", pickupLongitude)
        return postForSnapshot("${base()}/rides", body)
    }

    /**
     * The reconciliation fetch. Called on every reconnect, before any replayed event is trusted.
     */
    suspend fun snapshot(rideId: String): ApiResult<RideSnapshot> {
        val request = Request.Builder().url("${base()}/rides/$rideId").get().build()
        return when (val result = call(request)) {
            is ApiResult.Ok -> RideSnapshot.parse(result.value)
                ?.let { ApiResult.Ok(it) }
                ?: ApiResult.Failed("The server sent something I couldn't read.", result.value.take(200))
            is ApiResult.Failed -> result
        }
    }

    /** Reports whether the code the rider heard the driver say matched the expected one. */
    suspend fun confirmCode(rideId: String, matched: Boolean): ApiResult<RideSnapshot> =
        postForSnapshot("${base()}/rides/$rideId/code", JSONObject().put("matched", matched))

    suspend fun cancel(rideId: String, reason: String = ""): ApiResult<RideSnapshot> =
        postForSnapshot("${base()}/rides/$rideId/cancel", JSONObject().put("reason", reason))

    // ===================================================================================
    //  Driver
    // ===================================================================================

    /** Open requests a driver may accept. */
    suspend fun openRequests(): ApiResult<List<RideSnapshot>> {
        val request = Request.Builder().url("${base()}/rides/open").get().build()
        return when (val result = call(request)) {
            is ApiResult.Ok -> runCatching {
                val array = JSONArray(result.value)
                (0 until array.length()).mapNotNull { RideSnapshot.parse(array.getJSONObject(it).toString()) }
            }.fold(
                onSuccess = { ApiResult.Ok(it) },
                onFailure = { ApiResult.Failed("Couldn't read the request list.", it.toString()) }
            )
            is ApiResult.Failed -> result
        }
    }

    suspend fun accept(
        rideId: String,
        driverName: String,
        vehicleModel: String,
        vehiclePlate: String,
        driverPhone: String,
        etaMinutes: Int
    ): ApiResult<RideSnapshot> = postForSnapshot(
        "${base()}/rides/$rideId/accept",
        JSONObject()
            .put("driverName", driverName)
            .put("vehicleModel", vehicleModel)
            .put("vehiclePlate", vehiclePlate)
            .put("driverPhone", driverPhone)
            .put("etaMinutes", etaMinutes)
    )

    suspend fun enroute(rideId: String): ApiResult<RideSnapshot> =
        postForSnapshot("${base()}/rides/$rideId/enroute", JSONObject())

    suspend fun location(rideId: String, distanceMeters: Int, bearingDeg: Float): ApiResult<RideSnapshot> =
        postForSnapshot(
            "${base()}/rides/$rideId/location",
            JSONObject().put("distanceMeters", distanceMeters).put("bearingDeg", bearingDeg.toDouble())
        )

    /** A canned position phrase. The driver taps; the rider's phone speaks. */
    suspend fun preset(rideId: String, text: String): ApiResult<RideSnapshot> =
        postForSnapshot("${base()}/rides/$rideId/preset", JSONObject().put("text", text))

    suspend fun beacon(rideId: String, bearingDeg: Float): ApiResult<RideSnapshot> =
        postForSnapshot("${base()}/rides/$rideId/beacon", JSONObject().put("bearingDeg", bearingDeg.toDouble()))

    suspend fun arrived(rideId: String): ApiResult<RideSnapshot> =
        postForSnapshot("${base()}/rides/$rideId/arrived", JSONObject())

    /** The gate. [startTrip] returns 409 until this has succeeded. */
    suspend fun seated(rideId: String): ApiResult<RideSnapshot> =
        postForSnapshot("${base()}/rides/$rideId/seated", JSONObject())

    suspend fun startTrip(rideId: String, etaMinutes: Int): ApiResult<RideSnapshot> =
        postForSnapshot("${base()}/rides/$rideId/start", JSONObject().put("etaMinutes", etaMinutes))

    suspend fun complete(rideId: String, fareRupees: Int, durationMinutes: Int): ApiResult<RideSnapshot> =
        postForSnapshot(
            "${base()}/rides/$rideId/complete",
            JSONObject().put("fareRupees", fareRupees).put("durationMinutes", durationMinutes)
        )

    // ===================================================================================
    //  Plumbing
    // ===================================================================================

    private suspend fun postForSnapshot(url: String, body: JSONObject): ApiResult<RideSnapshot> {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .build()

        return when (val result = call(request)) {
            is ApiResult.Ok -> RideSnapshot.parse(result.value)
                ?.let { ApiResult.Ok(it) }
                ?: ApiResult.Failed("The server sent something I couldn't read.", result.value.take(200))
            is ApiResult.Failed -> result
        }
    }

    /**
     * Executes a request off the main thread and maps every failure to a spoken sentence.
     *
     * The mapping is not decoration. Each branch below is a genuinely different situation with
     * a genuinely different fix, and a rider who cannot read an error code needs to be told
     * which one it is: "the address is wrong" and "the server is not running" lead to opposite
     * next actions.
     */
    private suspend fun call(request: Request): ApiResult<String> = withContext(Dispatchers.IO) {
        suspendCoroutine { continuation ->
            client.newCall(request).enqueue(object : Callback {

                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "${request.method} ${request.url} failed: $e")
                    val spoken = when {
                        e is java.net.UnknownHostException ->
                            "I can't find that server address. Check the backend address in settings."
                        e is java.net.SocketTimeoutException ->
                            "The server isn't answering. It may be starting up."
                        e is javax.net.ssl.SSLException ->
                            "I couldn't make a secure connection to the server."
                        else ->
                            "I can't reach the server."
                    }
                    continuation.resume(ApiResult.Failed(spoken, e.toString()))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (it.isSuccessful) {
                            Log.d(TAG, "${request.method} ${request.url} -> ${it.code}")
                            continuation.resume(ApiResult.Ok(body))
                            return
                        }
                        Log.w(TAG, "${request.method} ${request.url} -> ${it.code} $body")
                        val spoken = when (it.code) {
                            404 -> "That ride no longer exists."
                            409 -> "The server refused that. Something has to happen first."
                            in 500..599 -> "The server had a problem."
                            else -> "The server refused that request."
                        }
                        continuation.resume(ApiResult.Failed(spoken, "HTTP ${it.code}: ${body.take(200)}"))
                    }
                }
            })
        }
    }
}
