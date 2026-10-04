package com.cabeye.rider.net

import android.util.Log
import com.cabeye.rider.auth.AccountInfo
import com.cabeye.rider.auth.AuthStore
import com.cabeye.rider.memory.RiderMemory
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
class RideApi(private val settings: AppSettings, private val auth: AuthStore) {

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
            val builder = chain.request().newBuilder()
            // The sign-in token for whichever role this phone is in right now. The server
            // trusts it over X-User-Id, so a rider's token never rides on a driver's request.
            auth.token(s.role)?.let { builder.header("Authorization", "Bearer $it") }
            chain.proceed(
                builder
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
        dropNote: String = "",
        spokenAs: String = ""
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
            // The rider's own words, for their memory's alias learning.
            .put("spokenAs", spokenAs)
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

    /**
     * Relays what the rider's UPI app claimed. The server records it as a claim, not as proof.
     */
    suspend fun reportPayment(
        rideId: String,
        status: String,
        txnRef: String
    ): ApiResult<RideSnapshot> =
        postForSnapshot(
            "${base()}/rides/$rideId/payment",
            JSONObject().put("status", status).put("txnRef", txnRef)
        )

    /** Polled until the payment status settles, so the rider can be told the real outcome. */
    suspend fun paymentStatus(rideId: String): ApiResult<RideSnapshot> {
        val request = Request.Builder().url("${base()}/rides/$rideId/payment").get().build()
        return when (val result = call(request)) {
            is ApiResult.Ok -> RideSnapshot.parse(result.value)
                ?.let { ApiResult.Ok(it) }
                ?: ApiResult.Failed("The server sent something I couldn't read.", result.value.take(200))
            is ApiResult.Failed -> result
        }
    }

    // ===================================================================================
    //  Payment gateway (sandbox)
    // ===================================================================================

    /**
     * Creates — or returns the still-open — payment order for a finished ride's fare.
     * Idempotent on the server, so a double tap cannot create two orders.
     */
    suspend fun createPaymentOrder(rideId: String): ApiResult<PaymentOrder> {
        val request = Request.Builder()
            .url("${base()}/rides/$rideId/payment/order")
            .post("{}".toRequestBody(JSON))
            .build()
        return orderResult(call(request))
    }

    /** Polled while the rider is paying. Only the gateway can move an order to PAID. */
    suspend fun paymentOrder(orderId: String): ApiResult<PaymentOrder> {
        val request = Request.Builder().url("${base()}/payments/$orderId").get().build()
        return orderResult(call(request))
    }

    /**
     * Pays (or declines) an order through the sandbox gateway, from inside the app.
     * The server decides and returns the final order — PAID with a bank reference, or FAILED.
     */
    suspend fun simulatePayment(
        orderId: String,
        success: Boolean,
        method: String,
        reason: String = ""
    ): ApiResult<PaymentOrder> {
        val body = JSONObject()
            .put("outcome", if (success) "SUCCESS" else "FAILURE")
            .put("method", method.ifBlank { "UPI" })
        if (reason.isNotBlank()) body.put("reason", reason)
        val request = Request.Builder()
            .url("${base()}/payments/$orderId/simulate")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return orderResult(call(request))
    }

    /**
     * Maps a gateway reply. On a refusal the server sends `{"error": "..."}` already phrased
     * to be spoken ("This ride is already paid."), which is far more useful to the rider than
     * the generic sentence for the status code, so it is preferred when present.
     */
    private fun orderResult(result: ApiResult<String>): ApiResult<PaymentOrder> = when (result) {
        is ApiResult.Ok -> PaymentOrder.parse(result.value)
            ?.let { ApiResult.Ok(it) }
            ?: ApiResult.Failed("The server sent something I couldn't read.", result.value.take(200))
        is ApiResult.Failed -> {
            val serverSaid = PaymentOrder.errorMessage(result.detail.substringAfter(": ", ""))
            if (serverSaid != null) ApiResult.Failed(serverSaid, result.detail) else result
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

    /**
     * Ends the trip. The server prices it from the distance it measured and the trip's time,
     * so the app sends no fare — a fare a phone names could be anything.
     */
    suspend fun complete(rideId: String): ApiResult<RideSnapshot> =
        postForSnapshot("${base()}/rides/$rideId/complete", JSONObject())

    /**
     * One GPS fix from the driver's phone during the trip. The server adds up the distance
     * (ignoring jitter and glitches) and answers with the live km and fare.
     *
     * @param atMillis when the phone took the fix, so fixes sent together after a signal gap
     *   are still timed correctly on the server
     */
    suspend fun tripLocation(rideId: String, lat: Double, lng: Double, atMillis: Long): ApiResult<RideSnapshot> =
        postForSnapshot(
            "${base()}/rides/$rideId/trip-location",
            JSONObject().put("lat", lat).put("lng", lng).put("at", atMillis)
        )

    // ===================================================================================
    //  Sign-in and profile
    // ===================================================================================

    /** Asks the server to send a one-time code. */
    suspend fun sendOtp(phone: String, role: AppRole): ApiResult<OtpSent> {
        val body = JSONObject().put("phone", phone).put("role", role.wireName)
        return when (val result = call(post("${base()}/auth/otp", body))) {
            is ApiResult.Ok -> runCatching { OtpSent.parse(JSONObject(result.value)) }.fold(
                onSuccess = { ApiResult.Ok(it) },
                onFailure = { ApiResult.Failed("The server sent something I couldn't read.", it.toString()) }
            )
            is ApiResult.Failed -> result.preferServerSentence()
        }
    }

    /** Checks the code; on success the caller stores [SignIn.token]. */
    suspend fun verifyOtp(phone: String, role: AppRole, code: String, name: String = ""): ApiResult<SignIn> {
        val body = JSONObject().put("phone", phone).put("role", role.wireName).put("code", code)
        if (name.isNotBlank()) body.put("name", name)
        return when (val result = call(post("${base()}/auth/verify", body))) {
            is ApiResult.Ok -> runCatching {
                val json = JSONObject(result.value)
                SignIn(
                    token = json.getString("token"),
                    account = AccountInfo.parse(json) ?: error("no account"),
                    isNew = json.optBoolean("isNew", false)
                )
            }.fold(
                onSuccess = { ApiResult.Ok(it) },
                onFailure = { ApiResult.Failed("The server sent something I couldn't read.", it.toString()) }
            )
            is ApiResult.Failed -> result.preferServerSentence()
        }
    }

    /** The signed-in account. A 401 here means the stored token is dead. */
    suspend fun me(): ApiResult<AccountInfo> = accountResult(
        call(Request.Builder().url("${base()}/me").get().build())
    )

    /** Partial profile update: only the keys in [changes] are touched on the server. */
    suspend fun updateProfile(changes: JSONObject): ApiResult<AccountInfo> = accountResult(
        call(
            Request.Builder()
                .url("${base()}/me/profile")
                .patch(changes.toString().toRequestBody(JSON))
                .build()
        )
    )

    /**
     * Revokes [token] on the server. Takes the token explicitly because the phone forgets it
     * first — signing out must work offline, so the local wipe cannot wait for this call.
     */
    suspend fun logout(token: String): ApiResult<String> = call(
        Request.Builder()
            .url("${base()}/auth/logout")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody(JSON))
            .build()
    )

    // ===================================================================================
    //  Feedback
    // ===================================================================================

    /** Optional post-ride feedback. Any of the three may be null; the server refuses all-null. */
    suspend fun submitFeedback(rideId: String, rating: Int?, category: String?, text: String?): ApiResult<String> {
        val body = JSONObject()
        if (rating != null) body.put("rating", rating)
        if (!category.isNullOrBlank()) body.put("category", category)
        if (!text.isNullOrBlank()) body.put("text", text)
        return when (val result = call(post("${base()}/rides/$rideId/feedback", body))) {
            is ApiResult.Ok -> result
            is ApiResult.Failed -> result.preferServerSentence()
        }
    }

    /**
     * The DRIVER's feedback about the passenger. Category: SAFETY, BEHAVIOUR, PICKUP, PAYMENT or
     * OTHER. Any of the three may be null; the server refuses all-null.
     */
    suspend fun submitRiderFeedback(rideId: String, rating: Int?, category: String?, text: String?): ApiResult<String> {
        val body = JSONObject()
        if (rating != null) body.put("rating", rating)
        if (!category.isNullOrBlank()) body.put("category", category)
        if (!text.isNullOrBlank()) body.put("text", text)
        return when (val result = call(post("${base()}/rides/$rideId/rider-feedback", body))) {
            is ApiResult.Ok -> result
            is ApiResult.Failed -> result.preferServerSentence()
        }
    }

    // ===================================================================================
    //  Rider memory
    // ===================================================================================

    suspend fun memory(): ApiResult<RiderMemory> =
        when (val result = call(Request.Builder().url("${base()}/me/memory").get().build())) {
            is ApiResult.Ok -> runCatching { RiderMemory.parse(JSONObject(result.value)) }.fold(
                onSuccess = { ApiResult.Ok(it) },
                onFailure = { ApiResult.Failed("The server sent something I couldn't read.", it.toString()) }
            )
            is ApiResult.Failed -> result
        }

    suspend fun memoryOutcome(placeKey: String, kind: String, accepted: Boolean, heard: String): ApiResult<String> =
        call(
            post(
                "${base()}/me/memory/outcome",
                JSONObject().put("placeKey", placeKey).put("kind", kind)
                    .put("accepted", accepted).put("heard", heard)
            )
        )

    // ---- Live camera ("help me find my passenger") -----------------------------------
    // Control only. The pictures travel over the ride socket (RideSocket.sendFrame); these
    // calls decide whether they may. Each returns the camera's new state — OFF, REQUESTED or
    // LIVE — or a refusal whose sentence can be shown to the driver or spoken to the rider.

    suspend fun cameraRequest(rideId: String): ApiResult<String> =
        cameraState(call(post("${base()}/rides/$rideId/camera/request", JSONObject())))

    suspend fun cameraAnswer(rideId: String, accept: Boolean, reason: String = ""): ApiResult<String> =
        cameraState(
            call(post("${base()}/rides/$rideId/camera/answer",
                JSONObject().put("accept", accept).put("reason", reason)))
        )

    suspend fun cameraStop(rideId: String, reason: String): ApiResult<String> =
        cameraState(call(post("${base()}/rides/$rideId/camera/stop", JSONObject().put("reason", reason))))

    private fun cameraState(result: ApiResult<String>): ApiResult<String> = when (result) {
        is ApiResult.Ok -> ApiResult.Ok(
            runCatching { JSONObject(result.value).optString("state", "OFF") }.getOrDefault("OFF")
        )
        is ApiResult.Failed -> result.preferServerSentence()
    }

    suspend fun forgetMemory(): ApiResult<String> =
        call(Request.Builder().url("${base()}/me/memory").delete().build())

    private fun accountResult(result: ApiResult<String>): ApiResult<AccountInfo> = when (result) {
        is ApiResult.Ok -> AccountInfo.parse(result.value)
            ?.let { ApiResult.Ok(it) }
            ?: ApiResult.Failed("The server sent something I couldn't read.", result.value.take(200))
        is ApiResult.Failed -> result.preferServerSentence()
    }

    private fun post(url: String, body: JSONObject): Request =
        Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()

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

/** `/auth/otp` reply. [devCode] is present only while the backend runs in mock-OTP mode. */
data class OtpSent(
    val sent: Boolean,
    val expiresInSeconds: Int,
    val registered: Boolean,
    val devCode: String?
) {
    companion object {
        fun parse(o: JSONObject) = OtpSent(
            sent = o.optBoolean("sent", false),
            expiresInSeconds = o.optInt("expiresInSeconds", 300),
            registered = o.optBoolean("registered", false),
            devCode = o.optString("devCode", "").ifBlank { null }
        )
    }
}

data class SignIn(val token: String, val account: AccountInfo, val isNew: Boolean)

/** True when the server said the token is missing, wrong or expired. */
val ApiResult.Failed.isUnauthorized: Boolean get() = detail.startsWith("HTTP 401")

/**
 * The account and auth endpoints answer refusals with `{"error": "<a sentence to speak>"}`.
 * That sentence is always better than the generic one for the status code.
 */
fun ApiResult.Failed.preferServerSentence(): ApiResult.Failed {
    val json = detail.substringAfter(": ", "")
    val said = runCatching { JSONObject(json).optString("error", "") }.getOrDefault("")
    return if (said.isNotBlank()) ApiResult.Failed(said, detail) else this
}
