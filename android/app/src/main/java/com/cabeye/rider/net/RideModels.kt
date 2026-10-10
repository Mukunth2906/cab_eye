package com.cabeye.rider.net

import com.cabeye.rider.audio.Earcon
import com.cabeye.rider.audio.NarrationTier
import org.json.JSONObject

/**
 * The reserved topic an online driver subscribes to before there is a ride to subscribe to.
 *
 * Must match `RideService.DISPATCH_TOPIC` on the backend. It is not a real ride and never
 * appears in the ride map, which is what stops a debug caller being able to target it — the
 * scope check on `/debug/broadcast` requires a ride that genuinely exists.
 */
const val DISPATCH_TOPIC = "dispatch"

/**
 * The wire model, parsed by hand with `org.json`.
 *
 * ## Why no JSON library
 * The brief allows OkHttp and DataStore and nothing else without a reason, and there is no
 * reason here. `org.json` ships inside Android — zero bytes added, zero reflection, no
 * `kapt`/KSP step, and nothing for R8 to strip out of a release build. The envelope has eight
 * fields and exactly one shape in both directions; a code-generating serialiser would be
 * solving a problem this protocol does not have.
 *
 * Parsing is deliberately total: an unknown `type` produces [RideEventType.UNKNOWN] rather than
 * an exception. A malformed event must never be able to crash the app, because the crash is
 * indistinguishable, to a blind rider, from the app simply going quiet.
 */
data class RideEvent(
    /** Stable across replay. This is the de-duplication key — see [com.cabeye.rider.net.RideSocket]. */
    val eventId: String,
    /** Per-ride monotonic counter. Used to ask the server for "everything after N". */
    val seq: Long,
    val type: RideEventType,
    /** The raw type string, kept so an unrecognised event can still be logged usefully. */
    val rawType: String,
    val rideId: String,
    val senderId: String,
    val senderRole: String,
    val payload: JSONObject
) {
    fun string(key: String, fallback: String = ""): String =
        payload.optString(key, fallback).ifEmpty { fallback }

    fun int(key: String, fallback: Int = 0): Int = payload.optInt(key, fallback)
    fun float(key: String, fallback: Float = 0f): Float = payload.optDouble(key, fallback.toDouble()).toFloat()
    fun bool(key: String, fallback: Boolean = false): Boolean = payload.optBoolean(key, fallback)

    companion object {
        fun parse(json: String): RideEvent? = runCatching {
            val o = JSONObject(json)
            val raw = o.optString("type")
            RideEvent(
                eventId = o.optString("eventId").ifEmpty {
                    // A server that sent no id still has to be de-duplicable, so synthesise one
                    // from the fields that identify the event. Falling back to a random id would
                    // make every replayed copy look new — the exact failure this guards against.
                    "${o.optString("rideId")}:${o.optString("type")}:${o.optLong("seq")}"
                },
                seq = o.optLong("seq", 0L),
                type = RideEventType.parse(raw),
                rawType = raw,
                rideId = o.optString("rideId"),
                senderId = o.optString("senderId"),
                senderRole = o.optString("senderRole"),
                payload = o.optJSONObject("payload") ?: JSONObject()
            )
        }.getOrNull()
    }
}

/**
 * The event vocabulary, mirroring the backend's `RideEventType` plus the transport notices.
 *
 * **Every member carries its narration tier**, which is the point of the enum existing on this
 * side at all. Tier is not a per-call-site decision: if each handler chose its own, the one
 * that got it wrong would interrupt a rider mid-sentence, and it would do so only under the
 * conditions that produced that event. Attaching the tier to the type makes the policy
 * inspectable in one place and impossible to forget at a call site.
 *
 * @param tier how urgently this reaches the rider — see [NarrationTier]
 * @param earcon the sound to play; for [NarrationTier.EARCON_ONLY] events it is the entire
 *   output, and for others it precedes the sentence
 */
enum class RideEventType(
    val tier: NarrationTier,
    val earcon: Earcon? = null
) {

    // ---- Ride lifecycle -------------------------------------------------------------

    RIDE_CREATED(NarrationTier.QUEUED, Earcon.BOOKING_CONFIRMED),

    /** A driver accepted. Tier 1 — worth saying, not worth interrupting for. */
    RIDE_ASSIGNED(NarrationTier.QUEUED, Earcon.BOOKING_CONFIRMED),

    DRIVER_ENROUTE(NarrationTier.QUEUED),

    /**
     * Tier 2. Fires an earcon and speaks nothing at all.
     *
     * This is the highest-frequency event in the system — roughly every 1.5 s for the whole
     * approach. Pitch carries the distance, pan carries the bearing, and a rider learns to read
     * both in a fraction of the time a sentence would cost them.
     */
    DRIVER_LOCATION(NarrationTier.EARCON_ONLY, Earcon.DRIVER_APPROACH),

    /** The driver's canned phrase, spoken by the rider's phone. Tier 1. */
    POSITION_PRESET(NarrationTier.QUEUED),

    /** Tier 2 — the beacon *is* the message. Speaking over it would mask what it is for. */
    BEACON(NarrationTier.EARCON_ONLY, Earcon.BEACON),

    /** Tier 0. Its value collapses entirely if it arrives a sentence late. */
    DRIVER_ARRIVED(NarrationTier.INTERRUPT, Earcon.ARRIVED),

    CODE_CONFIRMED(NarrationTier.QUEUED, Earcon.UNDERSTOOD),
    PASSENGER_SEATED(NarrationTier.QUEUED, Earcon.UNDERSTOOD),
    TRIP_STARTED(NarrationTier.QUEUED, Earcon.BOOKING_CONFIRMED),

    /** Tier 0 — the other event a rider who cannot see out of the window must hear at once. */
    ROUTE_DEVIATION(NarrationTier.INTERRUPT, Earcon.ERROR),

    TRIP_COMPLETED(NarrationTier.QUEUED, Earcon.BOOKING_CONFIRMED),
    RIDE_CANCELLED(NarrationTier.INTERRUPT, Earcon.CANCELLED),

    /**
     * The fare's payment status changed on the server (payload: status, paymentRef,
     * fareRupees, reason). Not a phase change — a COMPLETED ride stays COMPLETED while its
     * payment settles — so it sits after RIDE_CANCELLED and outside [isRideLifecycle]. The
     * driver's Complete screen listens for it; the rider learns the outcome by polling the
     * order, because the rider's socket has already left the ride topic by then.
     */
    PAYMENT_UPDATED(NarrationTier.EARCON_ONLY),

    /**
     * The trip meter moved (payload: distanceMeters, fareRupees, minutes), about every 100 m.
     * Silent — "silence means all is fine". The rider hears the km and fare when they ask
     * ("status", "how far") and once at the end; the driver's screen shows them live. Not a
     * phase change, so it sits after RIDE_CANCELLED and outside [isRideLifecycle].
     */
    TRIP_PROGRESS(NarrationTier.EARCON_ONLY),

    // ---- Multi-stop rides -----------------------------------------------------------
    // Outside [isRideLifecycle] on purpose: the phase stays IN_TRIP throughout; these say
    // where in the route the car is. The rider's view model speaks its own sentences.

    STOP_ARRIVED(NarrationTier.INTERRUPT, Earcon.ARRIVED),
    RIDER_RETURNED(NarrationTier.QUEUED, Earcon.UNDERSTOOD),
    STOP_DONE(NarrationTier.QUEUED, Earcon.BOOKING_CONFIRMED),
    STOP_SKIPPED(NarrationTier.QUEUED, Earcon.UNDERSTOOD),
    STOPS_CHANGED(NarrationTier.QUEUED, Earcon.UNDERSTOOD),
    WAIT_WARNING(NarrationTier.QUEUED),
    WAIT_OVERDUE(NarrationTier.INTERRUPT, Earcon.ERROR),

    // ---- The live camera ------------------------------------------------------------
    // "Help me find my passenger". Not phase changes, so they sit outside [isRideLifecycle],
    // and they are never replayed: the server broadcasts them without logging them, so a
    // reconnect cannot ask the rider an old question again. The rider's view model speaks its
    // own sentences for these; the tier only matters to [narrate]'s callers.

    /** The driver asked to see the rider's camera. The rider's phone asks for consent. */
    CAMERA_REQUESTED(NarrationTier.QUEUED),

    /** The rider said yes; the rider's phone starts streaming. */
    CAMERA_STARTED(NarrationTier.EARCON_ONLY),

    /** The rider said no, or did not answer in time. Payload: reason. */
    CAMERA_DECLINED(NarrationTier.EARCON_ONLY),

    /** The camera went off. Payload: reason (CODE_CONFIRMED, SEATED, TIME_LIMIT, …), by. */
    CAMERA_STOPPED(NarrationTier.EARCON_ONLY),

    /**
     * One picture from the rider's camera, on its way to the driver. Handled by the socket
     * itself and delivered on [com.cabeye.rider.net.RideSocket.frames] — it never reaches the
     * ride-event stream, so a few pictures a second cannot crowd out an arrival.
     */
    CAMERA_FRAME(NarrationTier.EARCON_ONLY),

    // ---- Transport notices ----------------------------------------------------------
    // Facts about the socket, not about the ride. Handled by the socket layer and never
    // narrated from a ride handler, which is why they carry EARCON_ONLY and no sound: the
    // connection-lost announcement is made once, by the client, not per event.

    CONNECTED(NarrationTier.EARCON_ONLY),
    PARTICIPANT_JOINED(NarrationTier.EARCON_ONLY),
    PARTICIPANT_LEFT(NarrationTier.EARCON_ONLY),
    REPLAY_COMPLETE(NarrationTier.EARCON_ONLY),
    REQUEST_TAKEN(NarrationTier.EARCON_ONLY),
    PONG(NarrationTier.EARCON_ONLY),
    ERROR(NarrationTier.EARCON_ONLY),

    /** Anything the server sent that this build does not know about. Never narrated. */
    UNKNOWN(NarrationTier.EARCON_ONLY);

    /** True for events that describe the ride rather than the connection carrying it. */
    val isRideLifecycle: Boolean
        get() = ordinal <= RIDE_CANCELLED.ordinal

    companion object {
        fun parse(raw: String?): RideEventType =
            entries.firstOrNull { it.name == raw?.trim() } ?: UNKNOWN
    }
}


private fun JSONObject.optDoubleNullable(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

/** Where the server says the ride is. The authority after a reconnect. */
enum class RidePhase {
    REQUESTED, ASSIGNED, ENROUTE, ARRIVED, SEATED, IN_TRIP, COMPLETED, CANCELLED, UNKNOWN;

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED

    companion object {
        fun parse(raw: String?): RidePhase =
            entries.firstOrNull { it.name == raw?.trim() } ?: UNKNOWN
    }
}

/**
 * The server's complete view of a ride, fetched over REST.
 *
 * This is what reconciliation compares against. It exists so that after a dropped socket the
 * app can answer one question — "what does the rider not yet know?" — with a single round trip,
 * instead of inferring it from a stream of events it may have missed the beginning of.
 */
data class RideSnapshot(
    val rideId: String,
    val phase: RidePhase,
    val destination: String,
    val destinationAddress: String = "",
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    val destinationPlaceId: String = "",
    val pickupLatitude: Double? = null,
    val pickupLongitude: Double? = null,
    // Optional meeting contact and drop note. Blank on an ordinary ride to a building; set
    // only when the destination was a road vague enough that the rider named someone waiting
    // there, or described the spot in their own words.
    val contactName: String = "",
    val contactPhone: String = "",
    val dropNote: String = "",
    val rideType: String,
    val boardingCode: String,
    val driverName: String,
    val vehicleModel: String,
    val vehiclePlate: String,
    val driverPhone: String,
    val etaMinutes: Int,
    val distanceMeters: Int,
    val bearingDeg: Float,
    val codeConfirmed: Boolean,
    val fareRupees: Int,
    val durationMinutes: Int,
    // NONE | REPORTED | CONFIRMED | FAILED. REPORTED is only what the rider's UPI app claimed;
    // CONFIRMED is the server saying money actually arrived. The app must never speak the
    // first as if it were the second.
    val paymentStatus: String = "NONE",
    val paymentRef: String = "",
    val lastSeq: Long,
    /** Multi-stop: every stop with its status, in order. Empty for an A-to-B ride. */
    val stops: List<StopInfo> = emptyList(),
    /** 1-based index of the stop being headed to or visited; null once all are behind. */
    val currentStop: Int? = null,
    // ---- Trip meter (the server measures these; the app only shows and speaks them) ----
    /** Metres travelled: live during the trip, final once completed. 0 = not measured. */
    val tripDistanceMeters: Int = 0,
    /** "GPS" (measured) or "ESTIMATE" (straight line, no GPS reached the server); "" = none. */
    val distanceSource: String = "",
    /** What the trip would cost if it ended now. 0 before the first 100 m. */
    val liveFareRupees: Int = 0
) {
    companion object {
        fun parse(json: String): RideSnapshot? = runCatching {
            val o = JSONObject(json)
            RideSnapshot(
                rideId = o.optString("rideId"),
                phase = RidePhase.parse(o.optString("phase")),
                destination = o.optString("destination"),
                destinationAddress = o.optString("destinationAddress"),
                destinationLatitude = o.optDoubleNullable("destinationLatitude"),
                destinationLongitude = o.optDoubleNullable("destinationLongitude"),
                destinationPlaceId = o.optString("destinationPlaceId"),
                pickupLatitude = o.optDoubleNullable("pickupLatitude"),
                pickupLongitude = o.optDoubleNullable("pickupLongitude"),
                contactName = o.optString("contactName"),
                contactPhone = o.optString("contactPhone"),
                dropNote = o.optString("dropNote"),
                rideType = o.optString("rideType", "AUTO"),
                boardingCode = o.optString("boardingCode"),
                driverName = o.optString("driverName"),
                vehicleModel = o.optString("vehicleModel"),
                vehiclePlate = o.optString("vehiclePlate"),
                driverPhone = o.optString("driverPhone"),
                etaMinutes = o.optInt("etaMinutes", 0),
                distanceMeters = o.optInt("distanceMeters", -1),
                bearingDeg = o.optDouble("bearingDeg", 0.0).toFloat(),
                codeConfirmed = o.optBoolean("codeConfirmed", false),
                fareRupees = o.optInt("fareRupees", 0),
                durationMinutes = o.optInt("durationMinutes", 0),
                paymentStatus = o.optString("paymentStatus", "NONE"),
                paymentRef = o.optString("paymentRef"),
                lastSeq = o.optLong("lastSeq", 0L),
                stops = StopInfo.parseList(o.optJSONArray("stops")),
                currentStop = if (o.has("currentStop") && !o.isNull("currentStop")) o.optInt("currentStop") else null,
                tripDistanceMeters = o.optInt("tripDistanceMeters", 0),
                distanceSource = o.optString("distanceSource"),
                liveFareRupees = o.optInt("liveFareRupees", 0)
            )
        }.getOrNull()
    }
}

/**
 * One stop of a multi-stop ride, as the server reports it (snapshot, or an event payload).
 *
 * @param kind DROP, PICKUP or WAIT — see `com.cabeye.rider.trip.StopKind`
 * @param status PENDING, ARRIVED, WAITING, DONE or SKIPPED
 */
data class StopInfo(
    val stopId: String,
    val index: Int,
    val kind: String,
    val status: String,
    val name: String,
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val note: String = "",
    val waitLimitSeconds: Int = 0,
    val waitStartedAt: Long = 0,
    val riderBack: Boolean = false
) {
    val isOpen: Boolean get() = status == "PENDING" || status == "ARRIVED" || status == "WAITING"
    val isWait: Boolean get() = kind == "WAIT"

    companion object {
        fun parse(o: JSONObject): StopInfo = StopInfo(
            stopId = o.optString("stopId"),
            index = o.optInt("index", 0),
            kind = o.optString("kind", "DROP"),
            status = o.optString("status", "PENDING"),
            name = o.optString("name"),
            address = o.optString("address"),
            latitude = o.optDoubleNullable("latitude"),
            longitude = o.optDoubleNullable("longitude"),
            note = o.optString("note"),
            waitLimitSeconds = o.optInt("waitLimitSeconds", 0),
            waitStartedAt = o.optLong("waitStartedAt", 0),
            riderBack = o.optBoolean("riderBack", false)
        )

        fun parseList(a: org.json.JSONArray?): List<StopInfo> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parse) }
    }
}
