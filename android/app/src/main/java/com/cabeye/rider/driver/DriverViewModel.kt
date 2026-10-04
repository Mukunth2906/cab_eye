package com.cabeye.rider.driver

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cabeye.rider.CabEyeApp
import com.cabeye.rider.net.ApiResult
import com.cabeye.rider.net.preferServerSentence
import com.cabeye.rider.net.ConnectionState
import com.cabeye.rider.net.RideEvent
import com.cabeye.rider.net.RideEventType
import com.cabeye.rider.net.RidePhase
import com.cabeye.rider.net.RideSnapshot
import com.cabeye.rider.net.PaymentOrder
import com.cabeye.rider.net.PaymentOrderStatus
import com.cabeye.rider.telemetry.Telemetry
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The driver's state holder — **entirely separate from the rider's**.
 *
 * ## Why separate rather than one view model with a role flag
 * A role flag would mean one object holding both the rider's microphone purpose and the
 * driver's ride queue, with half its fields meaningless at any given moment. Worse, it would
 * put the rider's narration call sites in the same class as the driver's, one missed `if` away
 * from a phone speaking at someone who is driving. Two classes make the separation something
 * the compiler enforces: **there is no path from this file to the narrator**, because this file
 * does not import it.
 *
 * ## Nothing here speaks
 * Not one line below produces audio. In driver mode the app additionally holds a
 * [com.cabeye.rider.audio.SilentAudioEngine] and has released the real one, so even a mistake
 * cannot make a sound. Two defences, deliberately.
 *
 * ## Every action goes over REST, not the socket
 * A driver tapping "confirm seated" needs to know whether it took. REST gives that answer;
 * a fire-and-forget socket frame does not. The socket is used only to *learn* things — that a
 * request has arrived, that the rider confirmed the code.
 */
class DriverViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CabEyeApp
    private val api get() = app.api
    private val socket get() = app.socket

    var uiState by mutableStateOf(DriverUiState())
        private set

    /**
     * The newest picture from the passenger's camera, or null. Kept out of [uiState] because it
     * changes several times a second and nothing else on the screen should recompose for it.
     */
    var cameraFrame by mutableStateOf<Bitmap?>(null)
        private set

    private var pollJob: Job? = null
    private var paymentJob: Job? = null
    private var bannerJob: Job? = null
    /** Sends GPS fixes to the server for the whole trip. Null when no trip is running. */
    private var tripMeterJob: Job? = null

    /** The ride this driver is currently on. */
    private var rideId: String? = null

    /**
     * Driver identity, from the signed-in driver's profile.
     *
     * The server re-stamps these from the sign-in token anyway, so what is sent here matters only
     * when no one is signed in (the browser test page path). The fallbacks are written the way
     * they should sound — "Bajaj auto, yellow" rather than a model code a TTS engine would spell
     * out letter by letter.
     */
    private val account get() = app.driverAccount.value
    private val driverName get() = account?.name?.ifBlank { null } ?: "Karthik"
    private val vehicleModel get() = account?.driver?.vehicleDescription?.ifBlank { null } ?: "Bajaj auto, yellow"
    private val vehiclePlate get() = account?.driver?.vehiclePlate?.ifBlank { null } ?: "TN 37 BX 4412"
    private val driverPhone get() = account?.phone?.ifBlank { null }?.let { "+91$it" } ?: "+910000000000"

    private companion object {
        const val TAG = "CabEye.Driver"
        const val PAYMENT_POLL_MS = 3_000L

        /**
         * How often the trip meter asks for a GPS fix. The server only publishes progress every
         * 100 m, so a fix every few seconds is plenty for an auto or a cab in city traffic.
         */
        const val TRIP_FIX_INTERVAL_MS = 4_000L

        /**
         * How often to poll for open requests while online.
         *
         * Polling *as well as* the socket, not instead of it. The dispatch socket delivers a
         * request the instant it is created, but only to a driver who was already connected —
         * a driver who goes online a second after a rider books would otherwise never see that
         * ride at all. The poll is what closes that window.
         */
        const val POLL_INTERVAL_MS = 4_000L

        /**
         * Cap on a typed message.
         *
         * This is spoken aloud, not read, so length is time — and it arrives while the rider
         * may be crossing a road. Long enough for "running about ten minutes late, heavy
         * traffic at Avinashi Road", short enough that it cannot become a monologue the rider
         * has no way to skip.
         */
        const val MAX_MESSAGE_CHARS = 120
    }

    init {
        viewModelScope.launch {
            socket.events.collect { onRideEvent(it) }
        }
        viewModelScope.launch {
            socket.state.collect { state ->
                uiState = uiState.copy(connected = state is ConnectionState.Connected)
            }
        }
        viewModelScope.launch {
            socket.frames.collect { jpeg ->
                if (uiState.camera != DriverCamera.LIVE) return@collect
                // Decoded off the main thread; only the newest picture is ever shown.
                val bitmap = withContext(Dispatchers.Default) {
                    runCatching {
                        val bytes = Base64.decode(jpeg, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
                if (bitmap != null && uiState.camera == DriverCamera.LIVE) cameraFrame = bitmap
            }
        }
    }

    // =================================================================================
    //  The passenger's camera — for finding them at pickup
    // =================================================================================

    /**
     * Asks to see the passenger's back camera. Their phone asks them by voice; nothing is
     * shown until they say yes. Refused by the server after the code is confirmed.
     */
    fun requestCamera() {
        val id = rideId ?: return
        if (uiState.camera == DriverCamera.ASKING || uiState.camera == DriverCamera.LIVE) return
        Telemetry.logDriverAction("CAMERA_REQUEST", id)
        viewModelScope.launch {
            when (val result = api.cameraRequest(id)) {
                is ApiResult.Ok -> {
                    val live = result.value == "LIVE"
                    uiState = uiState.copy(camera = if (live) DriverCamera.LIVE else DriverCamera.ASKING)
                    if (!live) showBanner("Asking your passenger to share their camera…")
                }
                is ApiResult.Failed -> showBanner(result.spoken)
            }
        }
    }

    /** Stops the passenger's camera — found them, or no longer needed. */
    fun stopCamera() {
        val id = rideId ?: return
        if (uiState.camera == DriverCamera.OFF) return
        Telemetry.logDriverAction("CAMERA_STOP", id)
        endCamera()
        viewModelScope.launch { api.cameraStop(id, "DRIVER_STOPPED") }
    }

    private fun endCamera(state: DriverCamera = DriverCamera.OFF) {
        cameraFrame = null
        if (uiState.camera != state) uiState = uiState.copy(camera = state)
    }

    // =================================================================================
    //  1 — Go online
    // =================================================================================

    fun goOnline() {
        Telemetry.logDriverAction("GO_ONLINE", "-")
        uiState = uiState.copy(state = DriverState.Online())

        // The reserved dispatch topic. Not a real ride — it is where a driver with no ride
        // listens for one, so that going online does not require inventing a second endpoint.
        socket.subscribe(com.cabeye.rider.net.DISPATCH_TOPIC)

        startPolling()
    }

    fun goOffline() {
        Telemetry.logDriverAction("GO_OFFLINE", rideId ?: "-")
        pollJob?.cancel()
        socket.unsubscribe("driver offline")
        rideId = null
        uiState = uiState.copy(state = DriverState.Offline, banner = "")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var waited = 0
            while (uiState.state is DriverState.Online) {
                when (val result = api.openRequests()) {
                    is ApiResult.Ok -> {
                        val first = result.value.firstOrNull()
                        if (first != null && uiState.state is DriverState.Online) {
                            offerRequest(first)
                            return@launch
                        }
                    }
                    is ApiResult.Failed -> {
                        showBanner("Can't reach the server. Retrying.")
                    }
                }
                delay(POLL_INTERVAL_MS)
                waited += (POLL_INTERVAL_MS / 1000).toInt()
                val current = uiState.state
                if (current is DriverState.Online) {
                    uiState = uiState.copy(state = current.copy(waitingSeconds = waited))
                }
            }
        }
    }

    private fun offerRequest(snapshot: RideSnapshot) {
        pollJob?.cancel()
        Telemetry.logDriverAction("REQUEST_OFFERED", snapshot.rideId, "dest=${snapshot.destination}")
        uiState = uiState.copy(
            state = DriverState.Request(
                rideId = snapshot.rideId,
                destination = snapshot.destination,
                rideType = snapshot.rideType,
                // Every rider on this platform is a Cab Eye rider. The badge is unconditional
                // rather than a per-rider flag, because a flag would mean a rider having to
                // disclose their disability to every driver individually to get the behaviour
                // the whole app exists to provide.
                visuallyImpaired = true,
                // The server already sends these on every snapshot; they were simply being
                // dropped here. Carrying them is what lets the driver hand off to Google Maps.
                destinationLatitude = snapshot.destinationLatitude,
                destinationLongitude = snapshot.destinationLongitude,
                destinationAddress = snapshot.destinationAddress,
                pickupLatitude = snapshot.pickupLatitude,
                pickupLongitude = snapshot.pickupLongitude,
                contactName = snapshot.contactName,
                contactPhone = snapshot.contactPhone,
                dropNote = snapshot.dropNote
            )
        )
    }

    // =================================================================================
    //  2 — Accept the request
    // =================================================================================

    fun acceptRequest() {
        val request = uiState.state as? DriverState.Request ?: return
        Telemetry.logDriverAction("ACCEPT", request.rideId)

        viewModelScope.launch {
            when (val result = api.accept(
                rideId = request.rideId,
                driverName = driverName,
                vehicleModel = vehicleModel,
                vehiclePlate = vehiclePlate,
                driverPhone = driverPhone,
                etaMinutes = 4
            )) {
                is ApiResult.Ok -> {
                    rideId = request.rideId
                    // Leave the dispatch topic and join the ride's own. One connection per
                    // active ride: the driver is on a ride now, so that is the topic that
                    // matters, and staying on both would double every event.
                    socket.subscribe(request.rideId)
                    api.enroute(request.rideId)

                    uiState = uiState.copy(
                        state = DriverState.Navigating(
                            rideId = request.rideId,
                            destination = request.destination,
                            pickupLatitude = request.pickupLatitude,
                            pickupLongitude = request.pickupLongitude,
                            destinationLatitude = request.destinationLatitude,
                            destinationLongitude = request.destinationLongitude,
                            destinationAddress = request.destinationAddress,
                            contactName = request.contactName,
                            contactPhone = request.contactPhone,
                            dropNote = request.dropNote
                        ),
                        banner = "Accepted. Navigate to the pickup point."
                    )
                }

                is ApiResult.Failed -> {
                    // Usually 409: another driver took it first. That is a normal outcome, not
                    // a failure, so it goes back to waiting rather than to an error screen.
                    // A 403 carries the server's own sentence (account paused by an admin).
                    val said = result.preferServerSentence()
                    showBanner(if (said !== result) said.spoken else "That request is no longer available.")
                    uiState = uiState.copy(state = DriverState.Online())
                    startPolling()
                }
            }
        }
    }

    fun declineRequest() {
        Telemetry.logDriverAction("DECLINE", (uiState.state as? DriverState.Request)?.rideId ?: "-")
        uiState = uiState.copy(state = DriverState.Online())
        startPolling()
    }

    // =================================================================================
    //  3 — Navigate to the pickup
    // =================================================================================

    /**
     * Sends a canned position phrase for the rider's phone to speak.
     *
     * The driver taps; the rider hears. Neither crosses into the other's modality — a driver
     * reading a message aloud is a driver not watching the road, and a blind rider cannot read
     * anything at all.
     */
    fun sendPreset(preset: PositionPreset) {
        val id = rideId ?: return
        Telemetry.logDriverAction("PRESET", id, "preset=${preset.name}")

        viewModelScope.launch {
            when (api.preset(id, preset.spoken)) {
                is ApiResult.Ok -> {
                    uiState = uiState.copy(lastPresetSent = preset.label)
                    showBanner("Sent: ${preset.label}")
                }
                is ApiResult.Failed -> showBanner("Couldn't send that. Check the connection.")
            }
        }
    }

    /**
     * Sends a free-typed sentence for the rider's phone to speak.
     *
     * The presets cover where the car is standing; they cannot cover "I'm stuck behind a bus,
     * about ten minutes" or "the gate on your side is shut, come to the next one". Those are
     * the things a driver would normally phone about, and a phone call is the one channel a
     * blind rider is least able to act on while standing at a kerb.
     *
     * Deliberately the same `/preset` endpoint the buttons use. It already carries arbitrary
     * text and the rider already speaks whatever arrives on it, so this needs no new event
     * type, no backend change, and no second path through the narrator's tiering — a custom
     * message is announced exactly as carefully as a preset is.
     *
     * Blank input is dropped rather than sent: an empty utterance would reach the rider as a
     * silent, unexplained interruption of whatever they were listening to.
     */
    fun sendMessage(text: String) {
        val id = rideId ?: return
        val message = text.trim().take(MAX_MESSAGE_CHARS)
        if (message.isBlank()) return

        Telemetry.logDriverAction("MESSAGE", id, "chars=${message.length}")

        viewModelScope.launch {
            when (api.preset(id, message)) {
                is ApiResult.Ok -> showBanner("Sent: $message")
                is ApiResult.Failed -> showBanner("Couldn't send that. Check the connection.")
            }
        }
    }

    /**
     * Fires the audio beacon, panned on the rider's phone to the bearing given here.
     *
     * The bearing is the driver's real one relative to the rider — the whole value of the
     * beacon is that it comes from the direction the car is actually in, so the rider has
     * something to physically turn toward. A centred tone would tell them a car exists and
     * nothing about where.
     */
    fun fireBeacon(bearingDegrees: Float) {
        val id = rideId ?: return
        Telemetry.logDriverAction("BEACON", id, "bearing=$bearingDegrees")

        viewModelScope.launch {
            when (api.beacon(id, bearingDegrees)) {
                is ApiResult.Ok -> showBanner("Beacon sent.")
                is ApiResult.Failed -> showBanner("Couldn't send the beacon.")
            }
        }
    }

    /** Reports distance and bearing. Becomes a rising, panned tone on the rider's phone. */
    fun sendLocation(distanceMeters: Int, bearingDegrees: Float) {
        val id = rideId ?: return
        val current = uiState.state as? DriverState.Navigating ?: return

        uiState = uiState.copy(
            state = current.copy(distanceMeters = distanceMeters, bearingDegrees = bearingDegrees)
        )
        viewModelScope.launch { api.location(id, distanceMeters, bearingDegrees) }
    }

    // =================================================================================
    //  4 — Arrived, and the boarding code
    // =================================================================================

    /**
     * Declares arrival, which is what puts the boarding code on this screen.
     *
     * The code comes from the server's response — it is never generated here. A code invented
     * on the driver's phone would be a code the rider's phone had no independent knowledge of,
     * and "both ends agree on a number one end made up" verifies nothing at all.
     */
    fun markArrived() {
        val id = rideId ?: return
        Telemetry.logDriverAction("ARRIVED", id)

        viewModelScope.launch {
            when (val result = api.arrived(id)) {
                is ApiResult.Ok -> {
                    uiState = uiState.copy(
                        state = DriverState.Arrived(id, result.value.boardingCode),
                        banner = "Say the code out loud to your passenger."
                    )
                }
                is ApiResult.Failed -> showBanner(result.spoken)
            }
        }
    }

    // =================================================================================
    //  5 — Confirm the passenger is seated
    // =================================================================================

    /**
     * Confirms the passenger is physically in the vehicle.
     *
     * The gate before the trip can start, and the server enforces it too — a start request that
     * has not been preceded by this returns 409. A UI-only gate would be a gate that a stale
     * screen, a double tap or a future refactor could walk straight through, and the person it
     * protects is the one who cannot see a car begin to move.
     */
    fun confirmSeated() {
        val id = rideId ?: return
        Telemetry.logDriverAction("SEATED", id)

        viewModelScope.launch {
            when (val result = api.seated(id)) {
                is ApiResult.Ok -> {
                    endCamera()
                    uiState = uiState.copy(
                        state = DriverState.Seated(id),
                        banner = "Passenger seated. You can start the trip."
                    )
                }
                // Usually 409: the passenger's phone has not confirmed the boarding code yet.
                // The server's sentence says what to do about it.
                is ApiResult.Failed -> showBanner(result.preferServerSentence().spoken)
            }
        }
    }

    // =================================================================================
    //  6 — In trip
    // =================================================================================

    fun startTrip() {
        val id = rideId ?: return
        Telemetry.logDriverAction("START_TRIP", id)

        viewModelScope.launch {
            when (val result = api.startTrip(id, etaMinutes = 12)) {
                is ApiResult.Ok -> {
                    uiState = uiState.copy(
                        state = DriverState.InTrip(
                            rideId = id,
                            destination = result.value.destination,
                            etaMinutes = result.value.etaMinutes,
                            destinationLatitude = result.value.destinationLatitude,
                            destinationLongitude = result.value.destinationLongitude,
                            destinationAddress = result.value.destinationAddress,
                            contactName = result.value.contactName,
                            contactPhone = result.value.contactPhone,
                            dropNote = result.value.dropNote
                        ),
                        banner = ""
                    )
                    startTripMeter(id)
                }
                is ApiResult.Failed ->
                    // The 409 path. Says what has to happen rather than just refusing, because
                    // the driver cannot see the server's state machine.
                    showBanner("Confirm the passenger is seated first.")
            }
        }
    }

    // =================================================================================
    //  7 — Complete
    // =================================================================================

    fun completeTrip() {
        val id = rideId ?: return
        Telemetry.logDriverAction("COMPLETE", id)

        viewModelScope.launch {
            // The server prices the trip from what it measured; this phone sends no fare.
            when (val result = api.complete(id)) {
                is ApiResult.Ok -> {
                    stopTripMeter()
                    uiState = uiState.copy(
                        state = DriverState.Complete(
                            id, result.value.fareRupees, result.value.durationMinutes,
                            distanceMeters = result.value.tripDistanceMeters,
                            distanceSource = result.value.distanceSource
                        ),
                        banner = ""
                    )
                    watchPayment(id)
                }
                is ApiResult.Failed -> showBanner(result.spoken)
            }
        }
    }

    // =================================================================================
    //  Trip meter — real km and a live fare
    // =================================================================================

    /**
     * Streams this phone's GPS to the server for the whole trip. The server adds up the
     * distance and prices the trip; this screen only shows what the server answers.
     *
     * No location permission means no fixes: the trip still completes and the server uses a
     * straight-line estimate, which the Complete screen labels as such.
     */
    private fun startTripMeter(id: String) {
        tripMeterJob?.cancel()
        tripMeterJob = viewModelScope.launch {
            app.locationProvider.updates(TRIP_FIX_INTERVAL_MS).collect { fix ->
                val result = api.tripLocation(id, fix.latitude, fix.longitude, fix.time)
                if (result is ApiResult.Ok) {
                    applyTripProgress(id, result.value.tripDistanceMeters, result.value.liveFareRupees, gps = true)
                }
            }
        }
    }

    private fun stopTripMeter() {
        tripMeterJob?.cancel()
        tripMeterJob = null
    }

    /** Updates the In-trip screen's km and fare. Ignores news about any other ride. */
    private fun applyTripProgress(id: String, metres: Int, fareRupees: Int, gps: Boolean) {
        val current = uiState.state as? DriverState.InTrip ?: return
        if (current.rideId != id) return
        uiState = uiState.copy(
            state = current.copy(
                distanceMeters = maxOf(current.distanceMeters, metres),
                fareSoFarRupees = if (fareRupees > 0) fareRupees else current.fareSoFarRupees,
                gpsLive = current.gpsLive || gps
            )
        )
    }

    // =================================================================================
    //  Rate the passenger
    // =================================================================================

    /**
     * The driver's feedback about the passenger, from the Complete screen. All optional, but
     * at least one must be given.
     *
     * @param category SAFETY, BEHAVIOUR, PICKUP, PAYMENT or OTHER, or null
     */
    fun sendRiderFeedback(rating: Int?, category: String?, note: String) {
        val current = uiState.state as? DriverState.Complete ?: return
        if (current.riderFeedbackSent) return
        if (rating == null && category == null && note.isBlank()) {
            showBanner("Choose a rating or a reason first.")
            return
        }
        Telemetry.logDriverAction("RATE_RIDER", current.rideId, "rating=$rating category=$category")
        viewModelScope.launch {
            when (val result = api.submitRiderFeedback(current.rideId, rating, category, note.trim().ifBlank { null })) {
                is ApiResult.Ok -> {
                    val now = uiState.state as? DriverState.Complete
                    if (now != null && now.rideId == current.rideId) {
                        uiState = uiState.copy(state = now.copy(riderFeedbackSent = true))
                    }
                    showBanner("Thanks — your feedback was sent.")
                }
                is ApiResult.Failed -> showBanner(result.spoken)
            }
        }
    }

    /**
     * Keeps the Complete screen's payment line current until the fare is settled.
     *
     * The PAYMENT_UPDATED event normally arrives first over the socket; this poll is the
     * fallback for a dropped socket, so a driver is never left staring at "waiting" for a fare
     * that was paid a minute ago.
     */
    private fun watchPayment(id: String) {
        paymentJob?.cancel()
        paymentJob = viewModelScope.launch {
            while (true) {
                val current = uiState.state as? DriverState.Complete ?: return@launch
                if (current.rideId != id || current.paymentStatus == "CONFIRMED") return@launch
                (api.paymentStatus(id) as? ApiResult.Ok<RideSnapshot>)?.value?.let { snap ->
                    applyPayment(id, snap.paymentStatus, snap.paymentRef)
                }
                refreshQr(id)
                delay(PAYMENT_POLL_MS)
            }
        }
    }

    /**
     * Keeps the QR code pointing at a payable order. The server's create call is idempotent:
     * it returns the same open order the customer's phone is using, and only makes a new one
     * after the last one failed or expired — so the customer and the QR always pay the same
     * order, and a stale QR is replaced by itself.
     */
    private suspend fun refreshQr(id: String) {
        val order = (api.createPaymentOrder(id) as? ApiResult.Ok<PaymentOrder>)?.value ?: return
        val current = uiState.state as? DriverState.Complete ?: return
        if (current.rideId != id) return
        if (order.status == PaymentOrderStatus.PAID) {
            applyPayment(id, "CONFIRMED", order.bankRef)
            return
        }
        if (current.checkoutUrl != order.checkoutUrl) {
            uiState = uiState.copy(state = current.copy(checkoutUrl = order.checkoutUrl))
        }
    }

    private fun applyPayment(id: String, status: String, ref: String) {
        val current = uiState.state as? DriverState.Complete ?: return
        if (current.rideId != id || current.paymentStatus == status) return
        uiState = uiState.copy(state = current.copy(paymentStatus = status, paymentRef = ref))
        when (status) {
            "CONFIRMED" -> showBanner("Payment received: ₹${current.fareRupees}.")
            "FAILED" -> showBanner("The rider's payment failed. They can try again.")
        }
    }

    /** Back to waiting, ready for the next request. */
    fun finishAndGoOnline() {
        endCamera()
        stopTripMeter()
        paymentJob?.cancel()
        socket.unsubscribe("ride finished")
        rideId = null
        uiState = uiState.copy(state = DriverState.Online(), banner = "")
        socket.subscribe(com.cabeye.rider.net.DISPATCH_TOPIC)
        startPolling()
    }

    fun cancelRide() {
        val id = rideId ?: return
        Telemetry.logDriverAction("CANCEL", id)
        viewModelScope.launch {
            api.cancel(id, reason = "driver cancelled")
            finishAndGoOnline()
        }
    }

    // =================================================================================
    //  Incoming events
    // =================================================================================

    private fun onRideEvent(event: RideEvent) {
        when (event.type) {

            // A new request landed on the dispatch topic while this driver was waiting.
            RideEventType.RIDE_CREATED -> {
                if (uiState.state !is DriverState.Online) return
                Log.i(TAG, "Request offered over socket: ${event.rideId}")
                viewModelScope.launch {
                    when (val result = api.openRequests()) {
                        is ApiResult.Ok -> result.value
                            .firstOrNull { it.rideId == event.rideId }
                            ?.let { offerRequest(it) }
                        is ApiResult.Failed -> Unit
                    }
                }
            }

            // Somebody else took it. Withdraw the offer rather than letting this driver tap
            // accept and receive a 409 — a refusal after a tap reads as a bug, not a race.
            RideEventType.REQUEST_TAKEN -> {
                val current = uiState.state
                if (current is DriverState.Request && current.rideId == event.rideId) {
                    showBanner("Another driver took that request.")
                    uiState = uiState.copy(state = DriverState.Online())
                    startPolling()
                }
            }

            // The rider's app checked the code it heard against the one the server issued.
            RideEventType.CODE_CONFIRMED -> {
                val matched = event.bool("matched", false)
                val current = uiState.state
                if (current is DriverState.Arrived) {
                    uiState = uiState.copy(
                        state = current.copy(codeConfirmed = matched),
                        banner = if (matched) "Passenger confirmed the code."
                        else "Passenger says the code does NOT match. Do not start the trip."
                    )
                }
            }

            RideEventType.RIDE_CANCELLED -> {
                showBanner("The ride was cancelled.")
                finishAndGoOnline()
            }

            RideEventType.CAMERA_STARTED -> {
                if (event.rideId == rideId) {
                    uiState = uiState.copy(camera = DriverCamera.LIVE)
                    showBanner("Your passenger is sharing their camera.")
                }
            }

            RideEventType.CAMERA_DECLINED -> {
                if (event.rideId == rideId && uiState.camera == DriverCamera.ASKING) {
                    endCamera(DriverCamera.DECLINED)
                    showBanner(
                        if (event.string("reason") == "NO_ANSWER") "No answer from your passenger. Try a message or the beacon."
                        else "Your passenger said no. Try a message or the beacon."
                    )
                }
            }

            RideEventType.CAMERA_STOPPED -> {
                if (event.rideId == rideId && uiState.camera != DriverCamera.OFF) {
                    endCamera()
                    showBanner(
                        when (event.string("reason")) {
                            "CODE_CONFIRMED" -> "Code confirmed — camera off."
                            "SEATED", "TRIP_STARTED" -> "Camera off."
                            "TIME_LIMIT" -> "The camera turns off after three minutes."
                            "RIDER_SAID_STOP", "RIDER_STOPPED" -> "Your passenger turned the camera off."
                            "APP_IN_BACKGROUND", "NO_PICTURES", "CAMERA_UNAVAILABLE" ->
                                "Your passenger's camera stopped."
                            else -> "Camera off."
                        }
                    )
                }
            }

            // The server's trip meter moved. Normally the trip-location answers already carry
            // this; the event keeps the screen right if one of those answers was lost.
            RideEventType.TRIP_PROGRESS ->
                applyTripProgress(event.rideId, event.int("distanceMeters", 0), event.int("fareRupees", 0), gps = true)

            // The fare's payment moved on the server — paid, failed, or reported by the rider.
            RideEventType.PAYMENT_UPDATED ->
                applyPayment(event.rideId, event.string("status"), event.string("paymentRef"))

            else -> Unit
        }
    }

    // =================================================================================
    //  Plumbing
    // =================================================================================

    /** A banner that clears itself, so stale feedback cannot linger into the next action. */
    private fun showBanner(text: String) {
        bannerJob?.cancel()
        uiState = uiState.copy(banner = text)
        bannerJob = viewModelScope.launch {
            delay(4_000)
            uiState = uiState.copy(banner = "")
        }
    }

    /**
     * @return the phase this driver believes the ride is in, for the screen's own bookkeeping.
     *   Never used to decide whether an action is legal — that is the server's job, which is
     *   why [startTrip] asks and handles a refusal rather than checking locally first.
     */
    fun currentPhase(): RidePhase = when (uiState.state) {
        is DriverState.Offline, is DriverState.Online -> RidePhase.UNKNOWN
        is DriverState.Request -> RidePhase.REQUESTED
        is DriverState.Navigating -> RidePhase.ENROUTE
        is DriverState.Arrived -> RidePhase.ARRIVED
        is DriverState.Seated -> RidePhase.SEATED
        is DriverState.InTrip -> RidePhase.IN_TRIP
        is DriverState.Complete -> RidePhase.COMPLETED
    }

    override fun onCleared() {
        pollJob?.cancel()
        paymentJob?.cancel()
        tripMeterJob?.cancel()
        bannerJob?.cancel()
        super.onCleared()
    }
}
