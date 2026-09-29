package com.cabeye.rider.net

import android.util.Log
import com.cabeye.rider.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Socket health, as the rest of the app needs to see it.
 *
 * Three states rather than a boolean, because [Reconnecting] and [Disconnected] call for
 * different things to be said. The first is "hold on"; the second is "this is broken".
 */
sealed interface ConnectionState {

    /** No ride, no socket. Not a fault — there is simply nothing to be connected to. */
    data object Idle : ConnectionState

    /** First connection attempt for this ride. Nothing has been announced yet. */
    data object Connecting : ConnectionState

    /** Live. The heartbeat runs and events flow. */
    data class Connected(val rideId: String, val connectMillis: Long) : ConnectionState

    /**
     * The socket dropped and is being retried.
     *
     * @param attempt 1-based retry number, for the log and for the backoff curve
     * @param announced whether the rider has already been told. The announcement happens
     *   **once per outage**, not once per retry — a rider hearing "Connection lost.
     *   Reconnecting." nine times in ninety seconds learns nothing after the first and loses
     *   the ability to hear anything else in the meantime.
     */
    data class Reconnecting(val rideId: String, val attempt: Int, val announced: Boolean) : ConnectionState

    /** Deliberately closed — ride completed or cancelled. Not a fault, and never announced. */
    data object Closed : ConnectionState
}

/**
 * One WebSocket per active ride, with de-duplication and reconnection.
 *
 * ## Why a dropped socket is an accessibility failure, not a networking one
 * A sighted user watching a spinner knows the app is trying. A blind rider gets nothing —
 * and this app's whole contract is that **silence means everything is fine**. So a socket that
 * drops silently is worse than an error: it actively lies. Three things follow from that, and
 * all three are implemented here rather than left to the caller:
 *
 *  1. The connection state is a [StateFlow] the view model watches, so an outage cannot be
 *     missed by whoever happened to be listening at the time.
 *  2. [ConnectionState.Reconnecting] carries `announced`, so the "Connection lost" sentence is
 *     said once per outage — not once per retry.
 *  3. On recovery the caller is expected to fetch the REST snapshot and reconcile *before*
 *     trusting anything replayed. [connectMillis] and the reconnect counter are recorded so
 *     that recovery time is measurable rather than anecdotal.
 *
 * ## De-duplication
 * Reconnecting replays. That is deliberate on the server, and it means this client will
 * legitimately receive events it has already acted on. Every event carries a stable `eventId`,
 * and [seenEventIds] holds the ones already emitted. Without it, a rider who lost signal for a
 * minute would hear "Your car has arrived" twice — and having no way to look, they have no way
 * to tell that from two cars.
 *
 * The set is bounded: [MAX_SEEN_IDS] entries, oldest evicted first. A ride cannot outlive its
 * own memory of what it has seen, and an unbounded set on a long trip is a slow leak.
 */
class RideSocket(
    private val scope: CoroutineScope,
    private val settings: AppSettings
) {

    private companion object {
        const val TAG = "CabEye.Socket"

        /** First retry delay. Short — most drops are a momentary blip. */
        const val BASE_BACKOFF_MS = 1_000L

        /** Ceiling, per the brief. Beyond this, waiting longer helps nobody. */
        const val MAX_BACKOFF_MS = 30_000L

        /**
         * Jitter as a fraction of the computed delay.
         *
         * With two phones in a demo (and, later, many), an un-jittered curve makes every client
         * that lost the same server retry at the same instant, so the server comes back to a
         * thundering herd and drops them all again. ±25% breaks the lockstep.
         */
        const val JITTER_FRACTION = 0.25

        /** How many event ids to remember. Well above a realistic ride's event count. */
        const val MAX_SEEN_IDS = 600

        /** Keepalive interval. OkHttp sends a real WebSocket ping frame. */
        const val PING_INTERVAL_SECONDS = 20L

        /** Unsent bytes above which a camera frame is skipped rather than queued. */
        const val MAX_FRAME_BACKLOG_BYTES = 120_000L
    }

    /**
     * A dedicated client with a ping interval.
     *
     * Separate from [RideApi]'s client on purpose: this one must never time out a read. A ride
     * spends long stretches with nothing to say — `finding` in particular — and a read timeout
     * would tear down a perfectly healthy socket during exactly the phase where the rider is
     * relying on the heartbeat to tell them the system is alive.
     *
     * The ping interval is what distinguishes a live socket from a half-open one. Without it a
     * TCP connection whose peer vanished looks open indefinitely, and the app would keep
     * promising a rider that everything is fine while nothing at all is arriving.
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _events = MutableSharedFlow<RideEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        // Dropping the oldest is correct here and nowhere else: the buffer only fills when the
        // collector has stalled, and the freshest ride state is what the rider needs. An
        // arrival held behind sixty stale location pings is worse than a lost location ping.
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Every de-duplicated ride event, in arrival order. Collected by the view models. */
    val events: Flow<RideEvent> = _events.asSharedFlow()

    /**
     * Live-camera pictures arriving for the driver, as base64 JPEG.
     *
     * Kept off [events] entirely. Frames arrive several times a second; on the event stream
     * they would fill its drop-oldest buffer and could push out an arrival, and their ids would
     * evict real ones from [seenEventIds] and let a replay repeat something. Only the newest
     * picture matters, so this keeps one and drops the rest.
     */
    private val _frames = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: Flow<String> = _frames.asSharedFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Reconnect count for this ride, part of the required instrumentation. */
    @Volatile
    var reconnectCount: Int = 0
        private set

    /** Milliseconds from `subscribe` to the first open socket. Instrumentation. */
    @Volatile
    var lastConnectMillis: Long = -1
        private set

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null

    /** The ride this socket belongs to. One connection per active ride, never more. */
    @Volatile
    private var rideId: String? = null

    /** Highest sequence number actually processed. Sent on reconnect so the server replays. */
    @Volatile
    private var lastSeq: Long = 0

    /** Set while a close was requested by us, so the listener does not schedule a retry. */
    @Volatile
    private var closingDeliberately = false

    private var attempt = 0
    private var outageAnnounced = false
    private var subscribedAt = 0L

    /** Insertion-ordered so the oldest id is the one evicted. Guarded by its own monitor. */
    private val seenEventIds = object : LinkedHashSet<String>() {
        fun addBounded(id: String): Boolean = synchronized(this) {
            if (!add(id)) return false
            while (size > MAX_SEEN_IDS) {
                val oldest = iterator()
                oldest.next()
                oldest.remove()
            }
            true
        }
    }

    // ===================================================================================
    //  Lifecycle
    // ===================================================================================

    /**
     * Opens the socket for a ride. Called when a ride is created or accepted.
     *
     * Idempotent for the same ride: a second call while already connected is ignored rather
     * than opening a second socket, which would double every event the rider hears.
     */
    fun subscribe(rideId: String) {
        if (this.rideId == rideId && socket != null) {
            Log.d(TAG, "subscribe($rideId) ignored — already connected")
            return
        }
        unsubscribe(reason = "switching ride")

        this.rideId = rideId
        lastSeq = 0
        attempt = 0
        reconnectCount = 0
        outageAnnounced = false
        closingDeliberately = false
        synchronized(seenEventIds) { seenEventIds.clear() }
        subscribedAt = System.currentTimeMillis()

        _state.value = ConnectionState.Connecting
        Telemetry.logSocket("SUBSCRIBE", rideId, "")
        open()
    }

    /**
     * Closes the socket. Called on ride completion or cancellation, and from the view model's
     * `onCleared`.
     *
     * Sets [closingDeliberately] first, which is what stops the close callback treating an
     * intentional teardown as an outage and announcing "Connection lost" to a rider whose ride
     * simply ended.
     */
    fun unsubscribe(reason: String = "ride ended") {
        val id = rideId
        closingDeliberately = true
        reconnectJob?.cancel()
        reconnectJob = null

        runCatching { socket?.close(1000, reason) }
        socket = null
        rideId = null
        attempt = 0
        outageAnnounced = false

        if (id != null) {
            Telemetry.logSocket("UNSUBSCRIBE", id, "reason=$reason reconnects=$reconnectCount")
        }
        _state.value = if (id == null) ConnectionState.Idle else ConnectionState.Closed
    }

    /**
     * Reopens the socket against the current settings.
     *
     * Called when the backend URL changes: the live socket is pointing at the old host and
     * would keep working right up until it dropped, at which point it would retry an address
     * the user has already replaced.
     */
    fun refreshEndpoint() {
        val id = rideId ?: return
        Log.i(TAG, "Endpoint changed — reopening socket for ride=$id")
        closingDeliberately = true
        runCatching { socket?.close(1000, "endpoint changed") }
        socket = null
        closingDeliberately = false
        attempt = 0
        open()
    }

    /** True when the caller should be treating the connection as healthy. */
    val isConnected: Boolean get() = _state.value is ConnectionState.Connected

    /**
     * Marks an outage as announced.
     *
     * Called by the view model immediately after it speaks "Connection lost. Reconnecting."
     * Kept here rather than in the view model so the flag lives with the retry loop that
     * resets it — the two must not be able to disagree about whether the rider has been told.
     */
    fun markOutageAnnounced() {
        outageAnnounced = true
        val current = _state.value
        if (current is ConnectionState.Reconnecting) {
            _state.value = current.copy(announced = true)
        }
    }

    // ===================================================================================
    //  Connection
    // ===================================================================================

    private fun open() {
        val id = rideId ?: return
        val s = settings.current()
        val url = BackendUrl.rideSocketUrl(
            normalisedBase = s.backendBaseUrl,
            rideId = id,
            userId = s.userId,
            role = s.role.wireName,
            lastSeq = lastSeq
        )

        Log.i(TAG, "Opening socket -> $url")

        val request = Request.Builder()
            .url(url)
            // Redundant with the query parameters, and sent anyway: a native client can set
            // headers where a browser cannot, and the backend prefers whichever it finds.
            .header("X-User-Id", s.userId)
            .header("X-Role", s.role.wireName)
            .header("X-Ride-Id", id)
            .header("ngrok-skip-browser-warning", "true")
            .build()

        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val id = rideId ?: return
            val elapsed = System.currentTimeMillis() - subscribedAt
            lastConnectMillis = elapsed
            attempt = 0

            Telemetry.logSocket("OPEN", id, "connectMs=$elapsed reconnects=$reconnectCount")
            _state.value = ConnectionState.Connected(id, elapsed)
            outageAnnounced = false
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = RideEvent.parse(text)
            if (event == null) {
                Log.w(TAG, "Unparseable frame, ignoring: ${text.take(200)}")
                return
            }

            // A camera picture: straight to [frames], never through de-duplication or seq.
            if (event.type == RideEventType.CAMERA_FRAME) {
                val jpeg = event.string("jpeg")
                if (jpeg.isNotEmpty()) _frames.tryEmit(jpeg)
                return
            }

            // Sequence tracking happens for every event, including duplicates — a replayed
            // event still proves the server has at least that many, and losing the position
            // would make the next reconnect re-request material already handled.
            if (event.seq > lastSeq) lastSeq = event.seq

            // ---- De-duplication --------------------------------------------------------
            // The single most important line in this class. Reconnects replay; without this,
            // replay and repetition are the same thing, and the rider is told twice.
            if (!seenEventIds.addBounded(event.eventId)) {
                Telemetry.logSocket("DUPLICATE", event.rideId, "type=${event.rawType} id=${event.eventId}")
                return
            }

            Log.d(TAG, "RECV ${event.rawType} seq=${event.seq} id=${event.eventId}")
            scope.launch { _events.emit(event) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Socket closing code=$code reason=$reason")
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDrop("closed code=$code reason=$reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDrop("failure=${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * A socket went away. Decide whether that was expected, and retry if it was not.
     */
    private fun handleDrop(detail: String) {
        val id = rideId

        if (closingDeliberately || id == null) {
            // We asked for this. Not an outage, and specifically not something to announce —
            // telling a rider the connection was lost when their ride simply finished would be
            // a false alarm, and false alarms are how a real alarm stops being believed.
            Log.d(TAG, "Socket closed deliberately ($detail)")
            return
        }

        socket = null
        attempt++
        reconnectCount++

        Telemetry.logSocket("DROP", id, "attempt=$attempt total=$reconnectCount $detail")

        _state.value = ConnectionState.Reconnecting(id, attempt, outageAnnounced)

        scheduleReconnect()
    }

    /**
     * Exponential backoff with jitter, capped at [MAX_BACKOFF_MS].
     *
     * The curve is 1s, 2s, 4s, 8s, 16s, 30s, 30s… each multiplied by a random factor in
     * [0.75, 1.25]. Capping matters for this product specifically: a rider mid-ride whose train
     * went through a tunnel must not be left waiting minutes for the next attempt once signal
     * returns, because for the whole of that wait the app has nothing true to tell them.
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val exponential = BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 20)
            val capped = min(exponential, MAX_BACKOFF_MS)
            val jitter = 1.0 + Random.nextDouble(-JITTER_FRACTION, JITTER_FRACTION)
            val wait = (capped * jitter).toLong().coerceIn(500L, MAX_BACKOFF_MS)

            Log.i(TAG, "Reconnect attempt $attempt in ${wait}ms")
            delay(wait)

            if (rideId != null && !closingDeliberately) {
                open()
            }
        }
    }

    // ===================================================================================
    //  Sending
    // ===================================================================================

    /**
     * Sends an event on the socket. Best-effort by design.
     *
     * Returns false rather than queueing when the socket is down. A queue would mean a message
     * arriving minutes late, out of order, describing a situation that has since changed —
     * and every command in this app that matters ([RideApi]) goes over REST precisely so it can
     * fail loudly instead.
     */
    /**
     * Sends one camera picture, or skips it when the connection is backed up.
     *
     * A picture that cannot go now is worthless a second later, so this never queues: if more
     * than [MAX_FRAME_BACKLOG_BYTES] is still waiting to leave the phone, the frame is dropped
     * and the next, fresher one gets its chance. On a slow network the driver sees fewer
     * pictures rather than an ever-growing delay.
     *
     * @return true when the frame was handed to the socket
     */
    fun sendFrame(jpegBase64: String, n: Int): Boolean {
        val ws = socket ?: return false
        if (ws.queueSize() > MAX_FRAME_BACKLOG_BYTES) return false
        val json = org.json.JSONObject()
            .put("type", RideEventType.CAMERA_FRAME.name)
            .put("payload", org.json.JSONObject().put("jpeg", jpegBase64).put("n", n))
        return runCatching { ws.send(json.toString()) }.getOrDefault(false)
    }

    fun send(type: String, payload: Map<String, Any> = emptyMap()): Boolean {
        val ws = socket ?: return false
        val json = org.json.JSONObject()
            .put("type", type)
            .put("payload", org.json.JSONObject(payload))
        return runCatching { ws.send(json.toString()) }.getOrDefault(false)
    }
}
