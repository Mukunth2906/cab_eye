package com.cabeye.rider

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cabeye.rider.audio.Earcon
import com.cabeye.rider.audio.Headphones
import com.cabeye.rider.audio.NarrationTier
import com.cabeye.rider.dialogue.RecoveryLadder
import com.cabeye.rider.intent.Classification
import com.cabeye.rider.intent.Classifier
import com.cabeye.rider.intent.RiderIntent
import com.cabeye.rider.intent.Slot
import com.cabeye.rider.intent.Stopwords
import com.cabeye.rider.intent.UtteranceClass
import com.cabeye.rider.net.ApiResult
import com.cabeye.rider.net.ConnectionState
import com.cabeye.rider.net.Reconciler
import com.cabeye.rider.net.RideEvent
import com.cabeye.rider.net.RideEventType
import com.cabeye.rider.net.RidePhase
import com.cabeye.rider.net.RideSnapshot
import com.cabeye.rider.places.City
import com.cabeye.rider.places.ContactLookup
import com.cabeye.rider.places.ContactMatch
import com.cabeye.rider.places.Gazetteer
import com.cabeye.rider.places.MatchGate
import com.cabeye.rider.service.NarrationService
import com.cabeye.rider.speech.SpeechError
import com.cabeye.rider.speech.SpeechInputListener
import com.cabeye.rider.state.DriverInfo
import com.cabeye.rider.state.PlaceOption
import com.cabeye.rider.state.RideType
import com.cabeye.rider.state.RiderState
import com.cabeye.rider.state.RiderUiState
import com.cabeye.rider.state.ThemeChoice
import com.cabeye.rider.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Why the microphone is currently open.
 *
 * The same audio pipeline serves several quite different questions, and the transcript means
 * something different in each. Without this the app would try to book a ride called "cancel".
 */
private enum class MicPurpose {
    /** Rider is naming a destination from scratch. */
    BOOKING,

    /** Rider is answering a clarification question the app just asked out loud. */
    CLARIFY_ANSWER,

    /** Rider is answering a yes/no near-miss question: "Did you mean Adyar?" */
    NEAR_MISS_ANSWER,

    /**
     * Rider is filling in one missing slot after an INCOMPLETE utterance.
     *
     * Distinct from [BOOKING] because the app already holds part of the request — the ride
     * type, say — and re-running a full booking parse would throw that away and ask for it
     * again, which is exactly the kind of amnesia that makes a voice interface exhausting.
     */
    SLOT_ANSWER,

    /** The five-second window after an optimistic booking, listening for "cancel". */
    CANCEL_WINDOW,

    /**
     * The microphone is open to hear the **driver** say the boarding code aloud.
     *
     * The only purpose here where the expected speaker is not the rider. That is the whole
     * design of the boarding code: the rider never says the secret, the driver does, and this
     * app checks it. Hearing the right code is precisely the verification a blind rider cannot
     * perform by looking at a number plate.
     */
    CODE_VERIFY,

    // ---------------------------------------------------------------------------------
    //  The meeting-contact ladder, for a destination Google classified as a road.
    //
    //  Four purposes rather than one, because the app must know which question it just
    //  asked. A bare "yes" means something different after "is someone meeting you there?"
    //  than after "Ravi, ending four two one zero — correct?", and routing both through one
    //  purpose is how an app ends up sending a stranger's number to a driver.
    // ---------------------------------------------------------------------------------

    /** "Is someone meeting you there?" — rung 1, and its one re-ask. */
    MEETING_ANSWER,

    /** "What's their name?" — the rider names someone in their contacts. */
    CONTACT_NAME_ANSWER,

    /** "Ravi, ending four two one zero. Correct?" */
    CONTACT_CONFIRM_ANSWER,

    /** "Which landmark or bus stop should the driver look for?" — the last rung. */
    LANDMARK_ANSWER
}

/**
 * Drives the voice loop and the rider's half of the ride.
 *
 * ## The two rules that shape this whole class
 *
 * **1. The microphone is never open while TTS is speaking.** If it were, the recogniser would
 * hear the phone's own voice and transcribe the app talking to itself. Every prompt therefore
 * reopens the mic from its `onDone` callback rather than on a timer.
 *
 * **2. If the app asks a question out loud, it listens for the answer out loud — and it never
 * leaves the rider in silence with nothing to do next.** Rule 1 is what makes rule 2
 * implementable rather than merely aspirational: because the mic opens the instant the question
 * ends, "asked a question" and "listening for the answer" are the same event in the code.
 *
 * ## What the socket changed
 * The ride no longer advances on timers. It advances because the server said so, over a
 * WebSocket, and that introduces a failure the previous steps could not have: the socket can
 * drop. To a rider who cannot see a spinner, a dropped socket presents as an app that has gone
 * quiet — and this app's contract is that quiet means everything is fine. So the connection is
 * treated as a first-class part of the interface:
 *
 *  - a drop stops the heartbeat and says "Connection lost. Reconnecting." **once**,
 *  - a recovery says "Connected." and restarts the heartbeat,
 *  - and on recovery the app fetches the ride over REST and announces only the *delta* — one
 *    sentence about where things now stand, never a backlog of what it missed.
 */
class RiderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CabEyeApp
    private val engine get() = app.audioEngine
    private val stt get() = app.speechInput
    private val prefs get() = app.preferences
    private val api get() = app.api
    private val socket get() = app.socket

    var uiState by mutableStateOf(
        RiderUiState(connected = false, activeCityName = Gazetteer.activeCity.displayName)
    )
        private set

    /** The active theme, hoisted here because it is changed by voice like anything else. */
    var themeChoice by mutableStateOf(ThemeChoice.DEEP_DARK)
        private set

    /** Set by the Activity once RECORD_AUDIO is granted. */
    var micPermissionGranted: Boolean = false

    private var micPurpose = MicPurpose.BOOKING

    /** Wall-clock time the current recognition session actually started listening. */
    private var micOpenedAtMillis: Long = 0L
    private var cancelWindowJob: Job? = null
    private var silenceJob: Job? = null

    /** The pending "reopen the mic after a short gap" job. See [reopenMicAfterGap]. */
    private var micReopenJob: Job? = null

    /** The bounded "I didn't hear the code" listen. Cancelled the moment the code is settled. */
    private var codeListenJob: Job? = null

    // ---------------------------------------------------------------------------------
    //  Meeting-contact ladder
    // ---------------------------------------------------------------------------------

    private val contactLookup by lazy { ContactLookup(getApplication()) }

    /**
     * Asks the Activity to show the READ_CONTACTS system dialog.
     *
     * A view model cannot request a runtime permission itself, and this one deliberately does
     * not try to hold an Activity to do it. Null until the Activity wires it up, and a null
     * here simply means the ladder falls through to the landmark question — a permission that
     * cannot be asked for must not strand a rider mid-booking.
     */
    var contactPermissionRequest: (() -> Unit)? = null

    /**
     * The rider answered the system contacts dialog.
     *
     * Granted, the ladder carries on and asks for the name. Refused, it drops to the landmark
     * question rather than pressing the point — a permission asked twice is a permission being
     * demanded, and the rider still needs their cab either way.
     */
    fun onContactPermissionResult(granted: Boolean) {
        Log.i(TAG, "CONTACT_PERMISSION granted=$granted")
        if (pendingVaguePlace == null) return
        if (granted) {
            speak("Thank you. What is their name?", NarrationTier.QUEUED) {
                reopenMicAfterGap(MicPurpose.CONTACT_NAME_ANSWER)
            }
        } else {
            askLandmark()
        }
    }

    /** The road we are about to book to, held while the ladder runs. */
    private var pendingVaguePlace: PlaceOption? = null
    private var pendingVagueRideType: RideType? = null

    /** How many times "is someone meeting you there?" has been asked. Capped at 2. */
    private var meetingAsks = 0

    /** Contact matches read back to the rider, awaiting a yes/no. */
    private var pendingContacts: List<ContactMatch> = emptyList()

    /** Settled results of the ladder, attached to the booking. */
    private var chosenContact: ContactMatch? = null
    private var chosenDropNote: String = ""

    /**
     * True once this ride's boarding code has been accepted — by voice, by tap, or implicitly
     * because the driver reported the passenger is seated.
     *
     * Every path that would reopen the code microphone checks this first. Without it the app
     * keeps asking for a code the rider has already given, which on device meant being asked
     * twice for a correct code and then asked again after the driver had already confirmed the
     * rider was in the car.
     */
    private var codeVerified = false
    private var listPlacesJob: Job? = null
    private var demoRideJob: Job? = null
    private var trace: Telemetry.RideTrace? = null

    /** Backing data for a clarification currently on screen. */
    private var pendingOptions: Pair<PlaceOption, PlaceOption>? = null

    /** The candidate behind an outstanding "Did you mean ...?" question. */
    private var pendingNearMiss: PlaceOption? = null

    /** A slot the rider already filled, held across the follow-up question so it is not re-asked. */
    private var heldRideType: RideType? = null

    /** Last thing said, so "repeat" has something to repeat. */
    private var lastSpoken: String = ""

    /** Last booking, so "book again" has something to rebook. */
    private var lastBooking: Pair<String, RideType>? = null
    /** Last resolved Google/local place, retained so Book Again reuses its coordinates. */
    private var lastBookingPlace: PlaceOption? = null
    private var pickupLatitude: Double? = null
    private var pickupLongitude: Double? = null

    private var failureCount = 0
    private var silenceRepromptUsed = false
    private var consecutiveSpeechErrors = 0

    // ---------------------------------------------------------------------------------
    //  Ride / connection state
    // ---------------------------------------------------------------------------------

    /** The ride currently on this device, or null. One socket, one ride. */
    private var activeRideId: String? = null

    /** The boarding code the server issued, which the driver will read aloud. */
    private var expectedCode: String = ""

    /**
     * The last phase the app **announced out loud**.
     *
     * Not the last phase it displayed, and not the last event it received. Reconciliation
     * compares against this, because a rider who cannot see the screen knows only what was
     * spoken — reconciling against UI state would re-announce things they already heard and
     * skip things they never did.
     */
    private var lastSpokenPhase: RidePhase? = null

    /** When the current outage began, so the recovery can be measured rather than guessed at. */
    private var outageStartedAt: Long = 0

    private companion object {
        const val MAX_CONSECUTIVE_ERRORS = 2
        const val TAG = "CabEye.Dialogue"

        /** Longest one group of the place list may take before the reader gives up on it. */
        const val GROUP_TIMEOUT_MS = 12_000L

        /** How long to listen for the driver to say the boarding code before offering help. */
        const val CODE_LISTEN_MS = 15_000L

        /**
         * The recogniser is configured with `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS = 800`
         * (see [OnDeviceSpeechInput]). Calling `stop()` before that much audio has been
         * captured reliably returns NO_MATCH regardless of what was said — observed on device
         * as releases at 9ms, 70ms and 304ms all failing outright. A quick "yes" or a short
         * place name released promptly is a normal, expected gesture, not rider error, so the
         * stop is held back rather than firing on the raw touch-up.
         */
        const val MIN_CAPTURE_BEFORE_STOP_MS = 900L

        /**
         * Breathing room between the app finishing a recovery sentence and the microphone
         * opening again.
         *
         * Without it the mic reopens on the exact syllable the sentence ends, so a rider who
         * misses twice hears: prompt, mic, silence, prompt, mic — with no gap anywhere they
         * could gather a thought. On device that reads as a microphone that is simply always
         * on. The pause is short enough not to feel like a hang and long enough to be a turn
         * boundary rather than a collision.
         */
        const val MIC_REOPEN_GAP_MS = 1_200L

        /**
         * How many times "is someone meeting you there?" may be asked.
         *
         * Two: the question, and one re-ask that explains why it is worth answering. A third
         * would be nagging someone who has already said no twice.
         */
        const val MAX_MEETING_ASKS = 2

        /** A landmark further than this from the road is a different place with a similar name. */
        const val LANDMARK_MAX_KM = 3.0
    }

    init {
        themeChoice = prefs.theme
        uiState = uiState.copy(activeCityName = Gazetteer.activeCity.displayName)
        collectSocket()
        speakWelcomeIfFirstRun()
    }

    // =================================================================================
    //  The socket
    // =================================================================================

    /**
     * Wires the two flows the socket exposes.
     *
     * Two separate collectors rather than one combined stream, because they answer different
     * questions and must not be able to starve each other: connection health has to reach the
     * rider even while a backlog of ride events is being processed.
     */
    private fun collectSocket() {
        viewModelScope.launch {
            socket.events.collect { event -> onRideEvent(event) }
        }

        viewModelScope.launch {
            socket.state.collect { state -> onConnectionState(state) }
        }
    }

    /**
     * Connection health, turned into something the rider can perceive.
     *
     * This is the accessibility requirement, not a networking one. Each branch is written
     * against a single question: **if the rider cannot see the screen, what does this state
     * sound like?** The answer must never be "the same as everything working".
     */
    private fun onConnectionState(state: ConnectionState) {
        when (state) {

            is ConnectionState.Connected -> {
                val wasDown = !uiState.connected && activeRideId != null
                uiState = uiState.copy(connected = true)

                if (wasDown) {
                    val outage = System.currentTimeMillis() - outageStartedAt
                    Log.i(TAG, "SOCKET recovered after ${outage}ms, ${socket.reconnectCount} attempt(s)")

                    // Said before reconciliation, not after. The rider has been waiting through
                    // an outage they were told about; the first thing they need is that it is
                    // over. What changed can follow a beat later.
                    speak("Connected.", NarrationTier.INTERRUPT)

                    // The heartbeat resumes only where it belongs — during a wait. Restarting it
                    // unconditionally would have it pulsing through `arrived`, where the rider
                    // is being asked to do something and a background tone is just noise.
                    if (heartbeatBelongsHere()) engine.heartbeat(true)

                    reconcileAfterReconnect(outage)
                } else {
                    if (heartbeatBelongsHere()) engine.heartbeat(true)
                }
            }

            is ConnectionState.Reconnecting -> {
                uiState = uiState.copy(connected = false)

                // Nothing to lose contact with if there is no ride. Announcing an outage to a
                // rider who is not on a ride would be a false alarm about a socket they have no
                // stake in — and false alarms are how a real alarm stops being believed.
                if (activeRideId == null) return

                if (!state.announced) {
                    outageStartedAt = System.currentTimeMillis()

                    // The heartbeat means "the system is alive and working". It is not, so it
                    // must stop — leaving it running would be the app lying with a sound.
                    engine.heartbeat(false)

                    engine.earcon(Earcon.ERROR)
                    speak("Connection lost. Reconnecting.", NarrationTier.INTERRUPT)

                    // Marked immediately, so the next nine retries stay silent. Once, not once
                    // per attempt — a rider hearing this every two seconds learns nothing after
                    // the first time and cannot hear anything else in the meantime.
                    socket.markOutageAnnounced()
                }
            }

            is ConnectionState.Connecting -> uiState = uiState.copy(connected = false)

            // Deliberate teardown. Never announced: the ride ended, nothing broke.
            ConnectionState.Closed, ConnectionState.Idle ->
                uiState = uiState.copy(connected = false)
        }
    }

    /** The heartbeat belongs in the quiet waiting phases, and nowhere else. */
    private fun heartbeatBelongsHere(): Boolean = when (uiState.ride) {
        is RiderState.Finding, is RiderState.InTrip, is RiderState.Assigned -> true
        else -> false
    }

    /**
     * The reconnect path: fetch the truth, say only what is new.
     *
     * The rule, in one line: **do not resume the stream, announce the delta.** If the driver
     * arrived while the socket was down, the rider hears "Your car has arrived" — not the three
     * events that led there. The replayed events still arrive and are still de-duplicated by
     * the socket, but this snapshot is what the rider is actually told.
     */
    private fun reconcileAfterReconnect(outageMillis: Long) {
        val rideId = activeRideId ?: return

        viewModelScope.launch {
            when (val result = api.snapshot(rideId)) {
                is ApiResult.Ok -> {
                    val snapshot = result.value
                    val reconciliation = Reconciler.reconcile(lastSpokenPhase, snapshot)

                    Telemetry.logReconcile(
                        rideId = rideId,
                        outageMillis = outageMillis,
                        reconnects = socket.reconnectCount,
                        announced = reconciliation.spoken ?: "-"
                    )

                    // The screen always catches up, whether or not anything is said. A sighted
                    // helper looking over the rider's shoulder should never be shown a state the
                    // server abandoned a minute ago.
                    applySnapshot(snapshot, announce = false)

                    val sentence = reconciliation.spoken
                    if (sentence == null) {
                        // Genuinely nothing changed. Silence is correct here — and it is safe,
                        // because "Connected." has just been spoken and the heartbeat is back.
                        Log.i(TAG, "RECONCILE ride=$rideId nothing to announce")
                        return@launch
                    }

                    reconciliation.earcon?.let { engine.earcon(it) }
                    speak(sentence, reconciliation.tier)
                    lastSpokenPhase = snapshot.phase
                }

                is ApiResult.Failed -> {
                    // The socket is back but the REST call failed, so the app cannot know where
                    // the ride is. It says so rather than guessing: a wrong announcement is
                    // worse than an admitted gap, because the rider will act on it.
                    Log.w(TAG, "RECONCILE failed: ${result.detail}")
                    engine.earcon(Earcon.ERROR)
                    speak(
                        "I'm back online but I couldn't check your ride. Say status to try again.",
                        NarrationTier.QUEUED
                    )
                }
            }
        }
    }

    // =================================================================================
    //  Ride events
    // =================================================================================

    /**
     * One event from the server, routed through the tier system.
     *
     * Every branch below takes its tier from [RideEventType], never from a local decision. The
     * tier is a property of the event type, decided once in one place; a per-call-site choice
     * is a per-call-site chance to interrupt a rider mid-sentence for a driver who moved forty
     * metres.
     */
    private fun onRideEvent(event: RideEvent) {
        // Events for a ride this device is not on. The socket should not deliver these, but a
        // stale frame arriving during a ride switch would otherwise narrate someone else's car.
        if (event.rideId.isNotEmpty() && activeRideId != null && event.rideId != activeRideId) {
            Log.w(TAG, "Ignoring event for ride=${event.rideId}, active=$activeRideId")
            return
        }

        Log.i(TAG, "EVENT ${event.rawType} seq=${event.seq} tier=${event.type.tier}")

        when (event.type) {

            RideEventType.RIDE_ASSIGNED -> {
                val driver = driverFrom(event)
                val eta = event.int("etaMinutes", 4)
                engine.heartbeat(false)
                transition(RiderState.Assigned(driver, eta), announce = false)
                // No ETA spoken. The number the server sends here is a fixed placeholder, not a
                // measurement, and a confident "four minutes" that turns out to be twelve is
                // worse than no number at all for a rider who cannot look out for the car.
                // Timing now reaches the rider only when the driver actually sends it.
                narrate(event, "${driver.name} is coming.")
                lastSpokenPhase = RidePhase.ASSIGNED
                // The wait is over, so the heartbeat's job is done until the next quiet phase.
                engine.heartbeat(true)
            }

            RideEventType.DRIVER_ENROUTE -> {
                val current = uiState.ride
                val driver = (current as? RiderState.Assigned)?.driver
                    ?: (current as? RiderState.Approaching)?.driver
                    ?: driverFrom(event)
                engine.heartbeat(false)
                transition(RiderState.Approaching(driver, distanceMeters = -1, bearingDegrees = 0f), announce = false)
                narrate(event, "${driver.name} is on the way.")
                lastSpokenPhase = RidePhase.ENROUTE
            }

            // Tier 2. The single highest-frequency event in the system, and it says nothing at
            // all: pitch carries the distance, pan carries the bearing. A rider learns to read
            // both in a fraction of the time a sentence would cost.
            RideEventType.DRIVER_LOCATION -> {
                val metres = event.int("distanceMeters", -1)
                val bearing = event.float("bearingDeg", 0f)
                val driver = (uiState.ride as? RiderState.Approaching)?.driver
                    ?: (uiState.ride as? RiderState.Assigned)?.driver
                    ?: driverFrom(event)

                transition(RiderState.Approaching(driver, metres, bearing), announce = false)
                engine.heartbeat(false)
                engine.earcon(
                    Earcon.DRIVER_APPROACH,
                    pan = panFor(bearing),
                    pitchShift = pitchFor(metres)
                )
            }

            // The driver tapped a phrase; this phone speaks it. Neither party crosses into the
            // other's modality — the driver never speaks, the rider never reads.
            RideEventType.POSITION_PRESET -> {
                val text = event.string("text")
                if (text.isNotBlank()) narrate(event, text)
            }

            // Tier 2 — the beacon IS the message. Speaking over it would mask the one thing the
            // rider is meant to turn toward.
            RideEventType.BEACON -> {
                val bearing = event.float("bearingDeg", 0f)
                engine.earcon(Earcon.BEACON, pan = panFor(bearing))
            }

            RideEventType.DRIVER_ARRIVED -> {
                expectedCode = event.string("boardingCode", expectedCode)
                onDriverArrived()
                lastSpokenPhase = RidePhase.ARRIVED
            }

            RideEventType.PASSENGER_SEATED -> {
                // The driver has confirmed the rider is physically in the car. Whatever the
                // code loop was still doing, it is now moot — continuing to ask for a boarding
                // code after the journey has effectively begun is the app arguing with reality.
                settleBoardingCode()
                narrate(event, "You're in. Sit back.")
                lastSpokenPhase = RidePhase.SEATED
            }

            RideEventType.TRIP_STARTED -> {
                val destination = event.string("destination", lastBooking?.first ?: "your destination")
                val eta = event.int("etaMinutes", 12)
                val driver = currentDriver() ?: driverFrom(event)
                transition(RiderState.InTrip(destination, eta, driver), announce = false)
                // Destination only — see the note on RIDE_ASSIGNED. The driver's own message
                // is the channel for "about ten minutes, traffic at Avinashi Road".
                narrate(event, "On the way to $destination.")
                lastSpokenPhase = RidePhase.IN_TRIP
                engine.heartbeat(true)
            }

            // Tier 0, and one of only three things in the whole app allowed to interrupt.
            RideEventType.ROUTE_DEVIATION -> {
                val note = event.string("note")
                narrate(event, if (note.isBlank()) "The car has left the expected route." else note)
            }

            RideEventType.TRIP_COMPLETED -> {
                val destination = event.string("destination", lastBooking?.first ?: "your destination")
                val fare = event.int("fareRupees", 0)
                val minutes = event.int("durationMinutes", 0)
                engine.heartbeat(false)
                transition(RiderState.Done(destination, fare, minutes), announce = false)
                narrate(event, "You've arrived. $fare rupees.")
                lastSpokenPhase = RidePhase.COMPLETED
                endRide("completed")
            }

            RideEventType.RIDE_CANCELLED -> {
                engine.heartbeat(false)
                narrate(event, "Your ride was cancelled.")
                lastSpokenPhase = RidePhase.CANCELLED
                transition(RiderState.Idle, announce = false)
                endRide("cancelled")
            }

            RideEventType.CODE_CONFIRMED,
            RideEventType.RIDE_CREATED -> Unit

            // Transport notices. Handled by the socket layer; narrating them here would mean
            // the rider hears about the connection twice, from two places that could disagree.
            RideEventType.CONNECTED,
            RideEventType.PARTICIPANT_JOINED,
            RideEventType.PARTICIPANT_LEFT,
            RideEventType.REPLAY_COMPLETE,
            RideEventType.REQUEST_TAKEN,
            RideEventType.PONG,
            RideEventType.ERROR,
            RideEventType.UNKNOWN -> Unit
        }
    }

    /** Speaks an event's message at the tier its type declares, with its earcon. */
    private fun narrate(event: RideEvent, text: String) {
        val type = event.type
        type.earcon?.let { engine.earcon(it) }
        if (type.tier == NarrationTier.EARCON_ONLY) return
        speak(text, type.tier)
    }

    // =================================================================================
    //  Arrival and the boarding code
    // =================================================================================

    /**
     * The driver is here. **The boarding code runs backwards from the usual design.**
     *
     * The conventional flow has the rider's phone announce a code for the rider to repeat to
     * the driver. That is wrong for this user in a specific way: a blind rider standing on a
     * street cannot tell who is within earshot, so announcing a secret over a loudspeaker
     * hands it to anyone standing nearby — and the person most likely to be standing nearby is
     * whoever is pretending to be the driver.
     *
     * So it is reversed. **The driver's screen displays the code, the driver says it aloud, and
     * this app verifies it.** The rider never speaks a secret. And hearing the correct code
     * come from the car is exactly the check a blind rider cannot perform by looking at a
     * number plate.
     *
     * The expected code is spoken here only through the earpiece, and only when headphones are
     * connected. Otherwise the app stays silent about it and waits to hear it — and says so, so
     * that the silence is never mistaken for a fault.
     */
    private fun onDriverArrived() {
        val driver = currentDriver() ?: DriverInfo("Your driver", "", "", "", 0f)
        val headphones = Headphones.connected(getApplication())

        engine.heartbeat(false)
        transition(
            RiderState.Arrived(driver, expectedCode, headphonesConnected = headphones),
            announce = false
        )

        engine.earcon(Earcon.ARRIVED)

        if (headphones) {
            // Private channel. Spoken with spaces between the digits so TTS reads "four seven
            // two" rather than "four hundred and seventy-two".
            val spokenCode = expectedCode.replace("-", " ")
            speak(
                "Your car has arrived. In your ear only: the code is $spokenCode. " +
                        "Wait for the driver to say it.",
                NarrationTier.INTERRUPT
            ) {
                openMic(MicPurpose.CODE_VERIFY)
            }
        } else {
            // No headphones, so the code is not spoken at all. This is the correct behaviour
            // and the app says why, because unexplained silence is the one thing it must never
            // produce.
            speak(
                "Your car has arrived. Ask the driver to say the code, and I'll check it.",
                NarrationTier.INTERRUPT
            ) {
                openMic(MicPurpose.CODE_VERIFY)
            }
        }

        // A bounded listen. An open microphone that hears nothing is, to this rider, identical
        // to a crash — so it ends in something being said either way.
        //
        // Held in codeListenJob so that a code settled before the timeout cancels it. Left
        // uncancelled it fired 15 seconds after a *correct* code and asked for it all over
        // again, because the ride is still in Arrived at that point and the purpose is still
        // CODE_VERIFY — both of its old guards were satisfied by a ride that had gone right.
        codeListenJob?.cancel()
        codeListenJob = viewModelScope.launch {
            delay(CODE_LISTEN_MS)
            if (!codeVerified && micPurpose == MicPurpose.CODE_VERIFY && uiState.ride is RiderState.Arrived) {
                stt?.cancel()
                closeMic()
                speak(
                    "I didn't hear the code. Ask the driver to say it again, or press the screen.",
                    NarrationTier.QUEUED
                ) { openMic(MicPurpose.CODE_VERIFY) }
            }
        }
    }

    /**
     * Checks what was heard against the code the server issued.
     *
     * Digits are extracted rather than the string compared, because the transcript is a
     * recogniser's best guess at a human saying three numbers in a noisy street. "four seven
     * two", "472", "4 7 2" and "four, seven, two." are all the same answer and all of them
     * arrive.
     */
    private fun verifyBoardingCode(heard: String) {
        val expectedDigits = expectedCode.filter { it.isDigit() }
        val heardDigits = digitsFrom(heard)

        Log.i(TAG, "CODE_VERIFY expected=$expectedDigits heard=\"$heard\" digits=$heardDigits")

        if (heardDigits.isEmpty()) {
            speak("I didn't catch a code. Ask the driver to say it again.", NarrationTier.QUEUED) {
                openMic(MicPurpose.CODE_VERIFY)
            }
            return
        }

        if (heardDigits.contains(expectedDigits)) {
            // Settle the code BEFORE speaking. Everything that could ask again — the bounded
            // listen, the speech-error retry, a stray press — keys off these three lines.
            settleBoardingCode()

            engine.earcon(Earcon.UNDERSTOOD)
            speak("That's the right code. This is your car.", NarrationTier.INTERRUPT)
            activeRideId?.let { rideId ->
                viewModelScope.launch { api.confirmCode(rideId, matched = true) }
            }
            return
        }

        // A wrong code is not a recognition failure to be retried quietly. It is the one
        // outcome this entire mechanism exists to catch, and it is tier 0.
        engine.earcon(Earcon.ERROR)
        speak(
            "That is not the right code. Do not get in. Say S O S if you need help.",
            NarrationTier.INTERRUPT
        )
        activeRideId?.let { rideId ->
            viewModelScope.launch { api.confirmCode(rideId, matched = false) }
        }
    }

    /** Digits from a transcript, spelled-out number words included. */
    private fun digitsFrom(text: String): String {
        val words = mapOf(
            "zero" to '0', "oh" to '0', "one" to '1', "two" to '2', "to" to '2', "too" to '2',
            "three" to '3', "four" to '4', "for" to '4', "five" to '5', "six" to '6',
            "seven" to '7', "eight" to '8', "ate" to '8', "nine" to '9'
        )
        val out = StringBuilder()
        for (token in text.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (token.isEmpty()) continue
            if (token.all { it.isDigit() }) {
                out.append(token)
            } else {
                words[token]?.let { out.append(it) }
            }
        }
        return out.toString()
    }

    /**
     * Closes the boarding-code question for good.
     *
     * One place, called from every route that resolves it — heard correctly, tapped by a
     * helper, or overtaken by the driver reporting the rider is seated. Cancelling the bounded
     * listen and closing the microphone here is what stops the app asking a question it has
     * already had answered.
     */
    private fun settleBoardingCode() {
        codeVerified = true
        codeListenJob?.cancel()
        codeListenJob = null
        if (micPurpose == MicPurpose.CODE_VERIFY) {
            stt?.cancel()
            closeMic()
        }
    }

    /** The rider (or a helper) tapped "Code is right" instead of letting the app hear it. */
    fun onCodeConfirmed() {
        trace?.tapCount = (trace?.tapCount ?: 0) + 1
        settleBoardingCode()
        stt?.cancel()
        closeMic()
        engine.earcon(Earcon.UNDERSTOOD)
        speak("Confirmed. Have a good trip.", NarrationTier.QUEUED)
        activeRideId?.let { rideId ->
            viewModelScope.launch { api.confirmCode(rideId, matched = true) }
        }
    }

    // =================================================================================
    //  Input from the surface
    // =================================================================================

    /** Rider pressed the surface, or a volume key. Opens the microphone. */
    fun onHoldStart() {
        if (uiState.ride is RiderState.Listening) return

        // ------------------------------------------------------------------------------
        //  A ride already under way is not a booking opportunity.
        //
        //  The whole screen is the press target, which is right when the rider's job is to
        //  say where they are going — and wrong once a driver is assigned. Observed on
        //  device: the rider reaches for the payment button, brushes the surface, and the
        //  app opens the microphone asking for a new destination. Worse, the driver sends a
        //  spoken message, the rider touches the phone to react to it, and lands in a fresh
        //  booking mid-ride. In both cases the app has interpreted "I am paying attention to
        //  you" as "take me somewhere else".
        //
        //  So in these states a press answers instead of asking: the rider hears where the
        //  driver is and how long, which is the actual question behind the touch. The mic
        //  never opens, so nothing can be misheard as a destination.
        //
        //  Deliberately NOT applied to Confirming: that is the five-second cancel window, and
        //  a rider who presses during it is entitled to abandon the booking and start over.
        //  Arrived keeps its own existing behaviour (CODE_VERIFY) below.
        // ------------------------------------------------------------------------------
        if (isRideUnderway(uiState.ride)) {
            trace?.tapCount = (trace?.tapCount ?: 0) + 1
            engine.stopSpeaking()
            engine.earcon(Earcon.UNDERSTOOD)
            speakStatus()
            return
        }

        trace = Telemetry.RideTrace(rideId = activeRideId ?: "local").also { it.tapCount++ }

        consecutiveSpeechErrors = 0
        failureCount = 0
        pendingNearMiss = null
        pendingOptions = null
        heldRideType = null

        engine.stopSpeaking()
        listPlacesJob?.cancel()
        cancelWindowJob?.cancel()

        if (stt?.isListening == true) stt?.cancel()

        // While the driver is at the kerb, a press means "I want to check the code", not "book
        // me a new ride". Interpreting it as a fresh booking here would be the app ignoring the
        // most important thing happening to the rider.
        val purpose =
            if (uiState.ride is RiderState.Arrived) MicPurpose.CODE_VERIFY else MicPurpose.BOOKING
        openMic(purpose)
    }

    /**
     * States in which a driver is already committed to this rider, so a press means "what is
     * happening" rather than "book me a ride".
     *
     * [RiderState.Arrived] is excluded on purpose — a press there already means "check the
     * boarding code", which is the more urgent question at the kerb and is handled below.
     */
    private fun isRideUnderway(ride: RiderState): Boolean =
        ride is RiderState.Finding ||
            ride is RiderState.Assigned ||
            ride is RiderState.Approaching ||
            ride is RiderState.InTrip ||
            ride is RiderState.Done

    /** Rider released. Closes the microphone; a final transcript is still expected. */
    fun onHoldEnd() {
        if (uiState.ride !is RiderState.Listening) return
        // T0 — the clock the rider actually experiences starts here.
        trace?.mark(Telemetry.Stage.MIC_RELEASED)

        val openedAt = micOpenedAtMillis
        val elapsed = System.currentTimeMillis() - openedAt
        if (elapsed >= MIN_CAPTURE_BEFORE_STOP_MS) {
            stt?.stop()
        } else {
            // A short word released promptly ("yes", a one-word place name) would otherwise be
            // cut off before the recogniser's own minimum capture window and rejected as
            // NO_MATCH no matter what was actually said. Let the window finish before stopping.
            viewModelScope.launch {
                delay(MIN_CAPTURE_BEFORE_STOP_MS - elapsed)
                // Only if no new session has started in the meantime.
                if (micOpenedAtMillis == openedAt) stt?.stop()
            }
        }
    }

    /** Sighted-helper fallback for the clarification question. The rider answers by voice. */
    fun onClarifyChoice(option: PlaceOption) {
        trace?.tapCount = (trace?.tapCount ?: 0) + 1
        stt?.cancel()
        closeMic()
        beginOptimisticBooking(option, heldRideType ?: RideType.AUTO)
    }

    /** Sighted-helper fallback for the near-miss question. */
    fun onNearMissAnswer(accepted: Boolean) {
        trace?.tapCount = (trace?.tapCount ?: 0) + 1
        stt?.cancel()
        closeMic()
        if (accepted) acceptNearMiss() else rejectNearMiss()
    }

    fun onCancel() {
        cancelWindowJob?.cancel()
        silenceJob?.cancel()
        demoRideJob?.cancel()
        stt?.cancel()
        closeMic()
        engine.heartbeat(false)
        engine.earcon(Earcon.CANCELLED)
        speak("Cancelled.", NarrationTier.INTERRUPT)
        NarrationService.stop(getApplication())

        val rideId = activeRideId
        if (rideId != null) {
            viewModelScope.launch { api.cancel(rideId, reason = "rider cancelled") }
            endRide("rider cancelled")
        } else {
            uiState = uiState.copy(selectedDestination = null)
        }
        transition(RiderState.Idle)
    }

    fun onSos() {
        uiState = uiState.copy(sosActive = true)
        engine.earcon(Earcon.SOS)
        speak("S O S activated. Sharing your location.", NarrationTier.INTERRUPT)
        Telemetry.logStateChange(uiState.ride::class.simpleName ?: "?", "SOS")
    }

    fun onDismissSos() {
        uiState = uiState.copy(sosActive = false)
    }

    // =================================================================================
    //  Microphone
    // =================================================================================

    private fun openMic(purpose: MicPurpose) {
        // Any real open supersedes a pending delayed one, including the rider's own press.
        micReopenJob?.cancel()

        if (!micPermissionGranted) {
            engine.earcon(Earcon.ERROR)
            speak("I need microphone permission to hear you.", NarrationTier.INTERRUPT)
            return
        }

        // Null in driver mode, where the recogniser is genuinely released rather than merely
        // ignored. Unreachable from this view model in practice — the rider surface is not
        // composed in driver mode — and handled anyway, because "unreachable" is a claim about
        // today's call graph.
        val recogniser = stt ?: run {
            Log.w(TAG, "openMic($purpose) with no recogniser — audio session is not active")
            return
        }

        if (!recogniser.isAvailable) {
            engine.earcon(Earcon.ERROR)
            speak(SpeechError.UNAVAILABLE.spokenExplanation, NarrationTier.INTERRUPT)
            return
        }

        // Never stack two sessions. Observed on device as `Listening -> Listening` followed by
        // MIC_RELEASED at +15 ms: a second open arrived while one was already running, so the
        // rider's release terminated a session that had only just started.
        if (recogniser.isListening) {
            Telemetry.logStateChange("Listening", "Listening(ignored duplicate open)")
            return
        }

        micPurpose = purpose

        // Two states must stay on screen through their own open microphone: `confirming`, where
        // the countdown is the information, and `arrived`, where the rider is being asked to
        // wait for the driver to speak. Moving either to `Listening` would replace the thing
        // the rider needs with a generic listening screen.
        if (purpose != MicPurpose.CANCEL_WINDOW && purpose != MicPurpose.CODE_VERIFY) {
            transition(
                RiderState.Listening(isFollowUp = purpose != MicPurpose.BOOKING),
                announce = false
            )
        }

        uiState = uiState.copy(micOpen = true)
        micOpenedAtMillis = System.currentTimeMillis()
        recogniser.start(speechListener)
        startSilenceWatch(purpose)
    }

    /** Reflects a closed microphone in the UI so the pulsing indicator stops. */
    private fun closeMic() {
        silenceJob?.cancel()
        if (uiState.micOpen) uiState = uiState.copy(micOpen = false)
    }

    /**
     * The 8-second silence timeout.
     *
     * An open microphone that hears nothing is the most ambiguous state this app can be in: to
     * a rider who cannot see the indicator, it is identical to a crash. So the app checks in
     * once, and if that also goes unanswered it says out loud that it is going quiet and what
     * word brings it back. Explained silence is fine; unexplained silence is a bug.
     *
     * Not armed during the cancel window, where five seconds of quiet is the expected outcome,
     * nor during code verification, which runs on its own longer clock — the rider is waiting
     * on the driver there, not on themselves.
     */
    private fun startSilenceWatch(purpose: MicPurpose) {
        silenceJob?.cancel()
        if (purpose == MicPurpose.CANCEL_WINDOW || purpose == MicPurpose.CODE_VERIFY) return

        silenceJob = viewModelScope.launch {
            delay(RecoveryLadder.SILENCE_TIMEOUT_MS)
            if (!uiState.micOpen) return@launch

            stt?.cancel()
            closeMic()

            if (!silenceRepromptUsed) {
                silenceRepromptUsed = true
                Log.i(TAG, "SILENCE timeoutMs=${RecoveryLadder.SILENCE_TIMEOUT_MS} action=REPROMPT")
                speak(RecoveryLadder.SILENCE_REPROMPT, NarrationTier.QUEUED) {
                    reopenMicAfterGap(purpose)
                }
            } else {
                Log.i(TAG, "SILENCE timeoutMs=${RecoveryLadder.SILENCE_TIMEOUT_MS} action=REST")
                silenceRepromptUsed = false
                transition(RiderState.Idle, announce = false)
                speak(RecoveryLadder.SILENCE_REST, NarrationTier.QUEUED)
            }
        }
    }

    private val speechListener = object : SpeechInputListener {

        /** Fired when the mic is *genuinely* open — the earcon belongs here, not at start(). */
        override fun onReadyForSpeech() = onMain {
            engine.earcon(Earcon.LISTENING_START)
        }

        override fun onPartial(text: String) = onMain {
            if (text.isNotBlank()) startSilenceWatch(micPurpose)
            val current = uiState.ride
            if (current is RiderState.Listening) {
                uiState = uiState.copy(ride = current.copy(partialTranscript = text))
            }
        }

        override fun onLevel(level: Float) = onMain {
            val current = uiState.ride
            if (current is RiderState.Listening) {
                uiState = uiState.copy(ride = current.copy(inputLevel = level))
            }
        }

        override fun onFinal(text: String) = onMain {
            // T1 — recogniser returned.
            trace?.mark(Telemetry.Stage.SPEECH_FINAL)
            silenceJob?.cancel()
            closeMic()
            engine.earcon(Earcon.LISTENING_END)
            handleTranscript(text)
        }

        override fun onError(error: SpeechError) = onMain {
            silenceJob?.cancel()
            closeMic()
            handleSpeechError(error)
        }
    }

    // =================================================================================
    //  Understanding
    // =================================================================================

    /**
     * The single entry point for everything heard through the microphone.
     *
     * **Classification happens before resolution, always.** Step 1 handed every utterance
     * straight to the gazetteer, so "hi" became a search for a place called "hi". Nothing below
     * may reorder these two steps.
     */
    private fun handleTranscript(text: String) {
        consecutiveSpeechErrors = 0
        silenceRepromptUsed = false

        // The one purpose where the expected speaker is not the rider. Checked before anything
        // else, because a driver saying "four seven two" must not be parsed as a destination.
        if (micPurpose == MicPurpose.CODE_VERIFY) {
            verifyBoardingCode(text)
            return
        }

        val rideActive = uiState.ride !is RiderState.Idle
        val classification = Classifier.classify(text, rideActive)

        // T2 — intent extracted.
        trace?.mark(Telemetry.Stage.INTENT_PARSED)
        trace?.regexFastPathHit = classification.kind != UtteranceClass.UNKNOWN

        Log.i(TAG, "${classification.diagnostic} heard=\"$text\" micPurpose=${micPurpose.name}")

        // Cancel wins everywhere, and especially inside the cancel window. This is the one
        // promise the optimistic-booking design cannot break.
        if (classification.intent is RiderIntent.Cancel) {
            onCancel()
            return
        }

        when (micPurpose) {
            MicPurpose.CANCEL_WINDOW -> {
                if (uiState.ride is RiderState.Confirming) openMic(MicPurpose.CANCEL_WINDOW)
                return
            }

            MicPurpose.CLARIFY_ANSWER -> {
                resolveClarifyAnswer(text)
                return
            }

            MicPurpose.NEAR_MISS_ANSWER -> {
                resolveNearMissAnswer(classification, text)
                return
            }

            MicPurpose.SLOT_ANSWER -> {
                if (classification.kind != UtteranceClass.COMMAND) {
                    resolveSlotAnswer(classification, text)
                    return
                }
            }

            MicPurpose.MEETING_ANSWER -> {
                resolveMeetingAnswer(classification)
                return
            }

            MicPurpose.CONTACT_NAME_ANSWER -> {
                resolveContactName(text)
                return
            }

            MicPurpose.CONTACT_CONFIRM_ANSWER -> {
                resolveContactConfirmation(classification)
                return
            }

            MicPurpose.LANDMARK_ANSWER -> {
                resolveLandmark(text)
                return
            }

            MicPurpose.CODE_VERIFY -> return // handled above
            MicPurpose.BOOKING -> Unit
        }

        when (classification.kind) {
            UtteranceClass.GREETING -> handleGreeting()
            UtteranceClass.COMMAND -> runCommand(classification.intent)
            UtteranceClass.INCOMPLETE -> askForMissingSlot(classification)
            UtteranceClass.BOOKING -> {
                val book = classification.intent as RiderIntent.Book
                heldRideType = book.rideType
                resolveDestination(book.destinationQuery, book.rawDestination, book.rideType)
            }
            UtteranceClass.UNKNOWN -> {
                engine.earcon(Earcon.NOT_UNDERSTOOD)
                runLadder(unrecognised = classification.phrase)
            }
        }
    }

    private fun handleGreeting() {
        engine.earcon(Earcon.UNDERSTOOD)
        failureCount = 0
        speak("Hello. Just say where you want to go.", NarrationTier.QUEUED) {
            openMic(MicPurpose.BOOKING)
        }
    }

    private fun askForMissingSlot(classification: Classification) {
        failureCount = 0
        heldRideType = classification.knownRideType
        engine.earcon(Earcon.UNDERSTOOD)

        val question = when (classification.missingSlot) {
            Slot.DESTINATION -> "Where would you like to go?"
            null -> "Where would you like to go?"
        }

        Log.i(TAG, "INCOMPLETE ask=\"$question\" heldRideType=${heldRideType?.name ?: "-"}")
        speak(question, NarrationTier.QUEUED) { openMic(MicPurpose.SLOT_ANSWER) }
    }

    private fun resolveSlotAnswer(classification: Classification, raw: String) {
        val rideType = heldRideType ?: RideType.AUTO

        if (classification.kind == UtteranceClass.BOOKING) {
            val book = classification.intent as RiderIntent.Book
            resolveDestination(book.destinationQuery, book.rawDestination, rideType)
            return
        }

        if (classification.kind == UtteranceClass.INCOMPLETE) {
            engine.earcon(Earcon.NOT_UNDERSTOOD)
            runLadder(unrecognised = "")
            return
        }

        val normalised = Stopwords.normalise(raw)
        resolveDestination(
            query = Stopwords.strip(normalised),
            rawPhrase = normalised,
            rideType = rideType
        )
    }

    private fun resolveDestination(query: String, rawPhrase: String, rideType: RideType) {
        transition(RiderState.Resolving(query), announce = false)
        heldRideType = rideType

        // Very short/empty queries should never spend a Places request. This also preserves the
        // original recovery behaviour for speech fragments such as "to".
        val substantive = Stopwords.strip(Gazetteer.normalise(rawPhrase))
        if (substantive.length < MatchGate.MIN_PHRASE_LENGTH) {
            handleResolvedCandidates(Gazetteer.score(query), rawPhrase, rideType, source = "local-short-query")
            return
        }

        viewModelScope.launch {
            val apiKeyConfigured = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()
            val googleCandidates = if (apiKeyConfigured) {
                val location = app.locationProvider.current()
                pickupLatitude = location?.latitude
                pickupLongitude = location?.longitude
                app.placeResolver.search(query, location).getOrNull().orEmpty()
            } else {
                emptyList()
            }

            // Google is the primary resolver. If it returns no usable result (offline, quota,
            // missing coordinates, or a genuinely unknown place), the original gazetteer is
            // retained as a safe fallback so existing demos and tests keep working.
            val candidates = if (googleCandidates.isNotEmpty()) {
                googleCandidates
            } else {
                if (apiKeyConfigured) Log.w(TAG, "Places returned no usable result; falling back to Gazetteer")
                Gazetteer.score(query)
            }

            handleResolvedCandidates(candidates, rawPhrase, rideType, source = if (googleCandidates.isNotEmpty()) "google" else "gazetteer")
        }
    }

    private fun handleResolvedCandidates(
        candidates: List<PlaceOption>,
        rawPhrase: String,
        rideType: RideType,
        source: String
    ) {
        // T3 — place resolved.
        trace?.mark(Telemetry.Stage.PLACE_RESOLVED)

        val decision = MatchGate.evaluate(rawPhrase, candidates)
        Log.i(TAG, "PLACE_RESOLVER source=$source ${decision.diagnostic}")

        when (decision) {
            is MatchGate.Decision.Proceed -> proceedWith(decision, rideType)
            is MatchGate.Decision.NearMiss -> askNearMiss(decision.candidate)
            is MatchGate.Decision.Reject -> {
                engine.earcon(Earcon.NOT_UNDERSTOOD)
                runLadder(unrecognised = Stopwords.strip(rawPhrase).ifBlank { rawPhrase })
            }
        }
    }

    private fun proceedWith(decision: MatchGate.Decision.Proceed, rideType: RideType) {
        val top = decision.top
        val runnerUp = decision.runnerUp

        if (runnerUp != null) {
            val gap = top.score - runnerUp.score
            val divergenceKm = Gazetteer.distanceKm(top, runnerUp)
            val shouldAsk = gap < RiderState.CONFIDENCE_DELTA && divergenceKm > RiderState.DIVERGENCE_KM

            Telemetry.logClarificationDecision(top.name, runnerUp.name, gap, divergenceKm, shouldAsk)

            if (shouldAsk) {
                trace?.clarificationTurns = (trace?.clarificationTurns ?: 0) + 1
                pendingOptions = top to runnerUp
                transition(RiderState.Clarify(top, runnerUp, gap, divergenceKm), announce = false)

                speak("Did you mean ${top.name}, or ${runnerUp.name}?", NarrationTier.QUEUED) {
                    openMic(MicPurpose.CLARIFY_ANSWER)
                }
                return
            }
        }

        failureCount = 0
        engine.earcon(Earcon.UNDERSTOOD)

        // A road is not an address. Before booking a five-kilometre stretch of Thadagam Road,
        // find out who is meeting the rider there — the one person who can wave the car down
        // on their behalf. Buildings skip this entirely, so an ordinary booking is unchanged.
        if (top.isVague && chosenContact == null && chosenDropNote.isBlank()) {
            startMeetingLadder(top, rideType)
            return
        }

        beginOptimisticBooking(top, rideType)
    }

    // =================================================================================
    //  The meeting-contact ladder
    //
    //  Three rungs, each with an exit, and it can never ask more than three times:
    //
    //    1. "Is someone meeting you there?"      no -> 2
    //    2. "Are you sure? ..."                  no -> 3
    //    3. "Which landmark or bus stop?"        no answer -> book as-is
    //
    //  The cap is the point. This app spent its design budget on NOT nagging a rider who
    //  cannot walk away from the conversation, and a helpful question asked a fourth time
    //  is indistinguishable from an app that will not let you leave.
    // =================================================================================

    private fun startMeetingLadder(place: PlaceOption, rideType: RideType) {
        pendingVaguePlace = place
        pendingVagueRideType = rideType
        meetingAsks = 1
        transition(RiderState.Resolving(place.name), announce = false)
        speak(
            "${place.name} is a long road. Is someone meeting you there?",
            NarrationTier.QUEUED
        ) { reopenMicAfterGap(MicPurpose.MEETING_ANSWER) }
    }

    /** Rung 1 and its single re-ask. */
    private fun resolveMeetingAnswer(classification: Classification) {
        when {
            classification.intent is RiderIntent.Yes -> askContactName()

            classification.intent is RiderIntent.No && meetingAsks < MAX_MEETING_ASKS -> {
                // The one re-ask. Worth making because riders say no reflexively to a question
                // they have not understood the value of, and this says what the value is.
                meetingAsks++
                speak(
                    "Are you sure? Someone the driver can call makes you much easier to find.",
                    NarrationTier.QUEUED
                ) { reopenMicAfterGap(MicPurpose.MEETING_ANSWER) }
            }

            classification.intent is RiderIntent.No -> askLandmark()

            // Anything that is neither yes nor no. Asking again would be the fourth question;
            // book instead. The rider has a ride to catch.
            else -> finishLadderAndBook()
        }
    }

    private fun askContactName() {
        if (!contactLookup.hasPermission()) {
            // Said out loud BEFORE the system dialog appears, because a bare permission prompt
            // is meaningless to someone who cannot read it and has no idea what triggered it.
            speak(
                "To find their number I need to look at your contacts. " +
                    "Please allow it, then say their name.",
                NarrationTier.QUEUED
            ) {
                val request = contactPermissionRequest
                if (request != null) request() else askLandmark()
            }
            return
        }
        speak("What is their name?", NarrationTier.QUEUED) {
            reopenMicAfterGap(MicPurpose.CONTACT_NAME_ANSWER)
        }
    }

    /** Rung 2: the rider named someone. Look them up and read the match back. */
    private fun resolveContactName(raw: String) {
        val spoken = Stopwords.strip(Stopwords.normalise(raw))
        val matches = contactLookup.find(spoken)

        if (matches.isEmpty()) {
            Log.i(TAG, "CONTACT no match for \"$spoken\"")
            // Not a failure worth a ladder of its own — fall through to the landmark rung,
            // which is the same information by another route.
            speak("I couldn't find that name in your contacts.", NarrationTier.QUEUED) {
                askLandmark()
            }
            return
        }

        pendingContacts = matches
        val first = matches.first()
        speak("${first.spokenConfirmation}. Is that right?", NarrationTier.QUEUED) {
            reopenMicAfterGap(MicPurpose.CONTACT_CONFIRM_ANSWER)
        }
    }

    /** Rung 2b: confirm the match before a single digit leaves the phone. */
    private fun resolveContactConfirmation(classification: Classification) {
        val candidates = pendingContacts
        when {
            classification.intent is RiderIntent.Yes && candidates.isNotEmpty() -> {
                chosenContact = candidates.first()
                pendingContacts = emptyList()
                Log.i(TAG, "CONTACT confirmed name=\"${chosenContact?.name}\"")
                engine.earcon(Earcon.UNDERSTOOD)
                finishLadderAndBook()
            }

            // More than one Ravi in the phonebook: offer the next before giving up.
            classification.intent is RiderIntent.No && candidates.size > 1 -> {
                pendingContacts = candidates.drop(1)
                val next = pendingContacts.first()
                speak("${next.spokenConfirmation}. Is that right?", NarrationTier.QUEUED) {
                    reopenMicAfterGap(MicPurpose.CONTACT_CONFIRM_ANSWER)
                }
            }

            else -> {
                pendingContacts = emptyList()
                askLandmark()
            }
        }
    }

    /** The last rung. Nothing after this asks anything. */
    private fun askLandmark() {
        speak(
            "Which landmark or bus stop should the driver look for?",
            NarrationTier.QUEUED
        ) { reopenMicAfterGap(MicPurpose.LANDMARK_ANSWER) }
    }

    /**
     * Rung 3: the rider described the spot.
     *
     * Their words are searched against Places, biased to the road itself, so "Perur bus stop"
     * becomes a point on Thadagam Road rather than a bus stop in another district. When that
     * search finds nothing the phrase is still carried to the driver verbatim — a local driver
     * understands "opposite the temple" perfectly well, and discarding what the rider already
     * said out loud would be the app deciding its geocoder outranks them.
     */
    private fun resolveLandmark(raw: String) {
        val phrase = raw.trim()
        val road = pendingVaguePlace

        if (phrase.isBlank() || road == null) {
            finishLadderAndBook()
            return
        }

        // "I don't know", "nothing", "just go" — all legitimate answers, all end the ladder.
        if (Stopwords.strip(Stopwords.normalise(phrase)).length < MatchGate.MIN_PHRASE_LENGTH) {
            finishLadderAndBook()
            return
        }

        chosenDropNote = phrase
        viewModelScope.launch {
            val refined = runCatching {
                app.placeResolver
                    .search("$phrase ${road.name}", app.locationProvider.current())
                    .getOrNull()
                    .orEmpty()
                    .firstOrNull { Gazetteer.distanceKm(it, road) <= LANDMARK_MAX_KM }
            }.getOrNull()

            if (refined != null) {
                Log.i(TAG, "LANDMARK refined \"$phrase\" -> \"${refined.name}\"")
                // The refined point keeps the road's vague flag cleared so booking proceeds.
                pendingVaguePlace = refined.copy(isVague = false)
            } else {
                Log.i(TAG, "LANDMARK kept verbatim: \"$phrase\"")
            }
            finishLadderAndBook()
        }
    }

    /** Every exit from the ladder lands here. */
    private fun finishLadderAndBook() {
        val place = pendingVaguePlace ?: return
        val rideType = pendingVagueRideType ?: RideType.AUTO
        pendingVaguePlace = null
        pendingVagueRideType = null
        meetingAsks = 0
        pendingContacts = emptyList()
        beginOptimisticBooking(place, rideType)
    }

    private fun askNearMiss(candidate: PlaceOption) {
        pendingNearMiss = candidate
        transition(
            RiderState.Clarify(candidate, candidate, 0f, 0f, isNearMiss = true),
            announce = false
        )
        engine.earcon(Earcon.NOT_UNDERSTOOD)
        speak("Did you mean ${candidate.name}?", NarrationTier.QUEUED) {
            openMic(MicPurpose.NEAR_MISS_ANSWER)
        }
    }

    private fun resolveNearMissAnswer(classification: Classification, raw: String) {
        when {
            classification.intent is RiderIntent.Yes -> acceptNearMiss()
            classification.intent is RiderIntent.No -> rejectNearMiss()
            else -> {
                pendingNearMiss = null
                val normalised = Stopwords.normalise(raw)
                resolveDestination(
                    query = Stopwords.strip(normalised),
                    rawPhrase = normalised,
                    rideType = heldRideType ?: RideType.AUTO
                )
            }
        }
    }

    private fun acceptNearMiss() {
        val candidate = pendingNearMiss ?: return
        pendingNearMiss = null
        failureCount = 0
        engine.earcon(Earcon.UNDERSTOOD)
        beginOptimisticBooking(candidate, heldRideType ?: RideType.AUTO)
    }

    private fun rejectNearMiss() {
        pendingNearMiss = null
        engine.earcon(Earcon.NOT_UNDERSTOOD)
        runLadder(unrecognised = "")
    }

    private fun resolveClarifyAnswer(text: String) {
        val (a, b) = pendingOptions ?: run {
            val normalised = Stopwords.normalise(text)
            resolveDestination(
                query = Stopwords.strip(normalised),
                rawPhrase = normalised,
                rideType = heldRideType ?: RideType.AUTO
            )
            return
        }

        val normalised = Gazetteer.normalise(text)
        val chosen = when {
            normalised.contains("first") || normalised.contains("one") ||
                normalised.contains(Gazetteer.normalise(a.name)) -> a
            normalised.contains("second") || normalised.contains("two") ||
                normalised.contains(Gazetteer.normalise(b.name)) -> b
            else -> Gazetteer.score(text).firstOrNull { it.name == a.name || it.name == b.name }
        }

        if (chosen == null) {
            speak("Sorry — ${a.name}, or ${b.name}?", NarrationTier.QUEUED) {
                openMic(MicPurpose.CLARIFY_ANSWER)
            }
            return
        }

        pendingOptions = null
        failureCount = 0
        engine.earcon(Earcon.UNDERSTOOD)
        beginOptimisticBooking(chosen, heldRideType ?: RideType.AUTO)
    }

    // =================================================================================
    //  Commands
    // =================================================================================

    private fun runCommand(intent: RiderIntent) {
        failureCount = 0

        when (intent) {
            is RiderIntent.Help -> speakHelp()
            is RiderIntent.Repeat -> speak(lastSpoken.ifBlank { "Nothing to repeat." }, NarrationTier.INTERRUPT)
            is RiderIntent.Status -> speakStatus()
            is RiderIntent.ListPlaces -> speakPlaceList()
            is RiderIntent.CallSupport -> speak(
                "Connecting you to support. Stay on the line.",
                NarrationTier.INTERRUPT
            )
            is RiderIntent.SwitchCity -> switchCity(intent.spokenCity)
            is RiderIntent.SetTheme -> setTheme(intent.theme)
            is RiderIntent.DemoRide -> startDemoRide()
            is RiderIntent.Greeting -> handleGreeting()
            is RiderIntent.BookAgain -> {
                val previous = lastBooking
                if (previous == null) {
                    speak("You have no previous ride. Just say where you want to go.", NarrationTier.QUEUED) {
                        openMic(MicPurpose.BOOKING)
                    }
                } else {
                    val place = lastBookingPlace
                    if (place != null) {
                        beginOptimisticBooking(place, previous.second)
                    } else {
                        resolveDestination(previous.first, previous.first, previous.second)
                    }
                }
            }
            is RiderIntent.CallDriver -> {
                val driver = currentDriver()
                if (driver == null) speak("No driver assigned yet.", NarrationTier.QUEUED)
                else speak("${driver.name}, ${driver.vehicleModel}.", NarrationTier.QUEUED)
            }
            is RiderIntent.Cancel -> onCancel()

            else -> speak("Say where you want to go, or say help.", NarrationTier.QUEUED) {
                openMic(MicPurpose.BOOKING)
            }
        }
    }

    private fun speakPlaceList() {
        listPlacesJob?.cancel()
        val groups = RecoveryLadder.listPlaces(Gazetteer.activeCity)

        listPlacesJob = viewModelScope.launch {
            for ((index, group) in groups.withIndex()) {
                var finished = false
                speak(group, if (index == 0) NarrationTier.INTERRUPT else NarrationTier.QUEUED) {
                    finished = true
                }
                withTimeoutOrNull(GROUP_TIMEOUT_MS) {
                    while (!finished) delay(80)
                }
            }
            speak("Which one?", NarrationTier.QUEUED) { openMic(MicPurpose.BOOKING) }
        }
    }

    private fun switchCity(spoken: String) {
        val city = City.fromSpoken(spoken)

        if (city == null) {
            val known = City.entries.joinToString(" and ") { it.displayName }
            speak("I don't know that city. I know $known.", NarrationTier.QUEUED) {
                openMic(MicPurpose.BOOKING)
            }
            return
        }

        Gazetteer.activeCity = city
        prefs.city = city
        uiState = uiState.copy(activeCityName = city.displayName)
        Log.i(TAG, "CITY switched to=${city.name}")

        engine.earcon(Earcon.UNDERSTOOD)
        speak("Switched to ${city.displayName}. Where would you like to go?", NarrationTier.QUEUED) {
            openMic(MicPurpose.BOOKING)
        }
    }

    private fun setTheme(choice: ThemeChoice) {
        themeChoice = choice
        prefs.theme = choice
        Log.i(TAG, "THEME switched to=${choice.name}")

        engine.earcon(Earcon.UNDERSTOOD)
        speak("${choice.spokenName} theme.", NarrationTier.QUEUED)
    }

    private fun speakWelcomeIfFirstRun() {
        if (prefs.hasHeardWelcome) return
        prefs.hasHeardWelcome = true

        val city = Gazetteer.activeCity
        speak(
            "Cab Eye is ready, covering ${city.displayName}. " +
                    "Hold anywhere and say where you want to go.",
            NarrationTier.QUEUED
        )
    }

    // =================================================================================
    //  The recovery ladder
    // =================================================================================

    private fun runLadder(unrecognised: String) {
        failureCount++
        pendingOptions = null
        pendingNearMiss = null

        val crossCity = if (failureCount <= 1 && unrecognised.isNotBlank()) {
            Gazetteer.findInOtherCities(unrecognised)?.let { (city, place) -> city to place.name }
        } else {
            null
        }

        val recovery = RecoveryLadder.respond(
            attempt = failureCount,
            unrecognised = unrecognised,
            city = Gazetteer.activeCity,
            crossCity = crossCity
        )

        Log.i(
            TAG,
            "LADDER rung=${recovery.rung} attempt=$failureCount " +
                    "crossCity=${crossCity?.first?.name ?: "-"} reopenMic=${recovery.openMicAfter}"
        )

        transition(RiderState.Idle, announce = false)

        if (failureCount >= RecoveryLadder.MAX_RUNG) failureCount = 0

        if (recovery.openMicAfter) {
            speak(recovery.message, NarrationTier.QUEUED) { reopenMicAfterGap(MicPurpose.BOOKING) }
        } else {
            speak(recovery.message, NarrationTier.QUEUED)
        }
    }

    /**
     * Opens the microphone after [MIC_REOPEN_GAP_MS], so a recovery prompt and the mic that
     * follows it are two turns rather than one run-on.
     *
     * Cancellable through [micReopenJob]: if the rider presses to talk during the gap, or the
     * ride state moves on, the pending open must not fire underneath them and reopen a mic
     * they have already taken control of.
     */
    private fun reopenMicAfterGap(purpose: MicPurpose) {
        micReopenJob?.cancel()
        micReopenJob = viewModelScope.launch {
            delay(MIC_REOPEN_GAP_MS)
            openMic(purpose)
        }
    }

    // =================================================================================
    //  Optimistic execution
    // =================================================================================

    /**
     * Books immediately and opens a real cancellation window.
     *
     * There is no confirmation question. A question would add a full conversational turn to
     * every booking to guard against a rare mistake; instead the app states what it is doing
     * and genuinely honours "cancel" for five seconds.
     *
     * The countdown starts when the **microphone opens**, not when the sentence starts —
     * otherwise part of the "five second window" would be spent listening to the app talk.
     */
    private fun beginOptimisticBooking(place: PlaceOption, rideType: RideType) {
        cancelWindowJob?.cancel()
        silenceJob?.cancel()
        lastBooking = place.name to rideType
        lastBookingPlace = place
        pendingOptions = null
        pendingNearMiss = null
        failureCount = 0
        heldRideType = null

        // The ladder's answers belong to the ride being booked right now. Clearing them after
        // they have been read means the NEXT booking starts from nothing rather than quietly
        // sending a driver to a friend who has no idea they are involved.
        pendingContacts = emptyList()

        // A fresh booking means a fresh boarding code, so the code question must be open again
        // even if the previous ride ended with it settled.
        codeVerified = false
        codeListenJob?.cancel()
        codeListenJob = null

        // Keep the selected real place in UI state so the map can pin exactly what Google
        // resolved, while the existing five-second cancellation window remains unchanged.
        uiState = uiState.copy(selectedDestination = place)
        transition(
            RiderState.Confirming(place.name, rideType, RiderState.CANCEL_WINDOW_MS),
            announce = false
        )
        engine.earcon(Earcon.BOOKING_CONFIRMED)

        speak(
            buildBookingAnnouncement(place, rideType),
            NarrationTier.QUEUED,
            onStart = {
                // T4 — the moment the rider first HEARS something.
                trace?.mark(Telemetry.Stage.FIRST_SOUND)
                trace?.timeToBookingMs = System.currentTimeMillis() - bookingStartedAt
                trace?.speechSeconds = Telemetry.speechSecondsTotal
                trace?.finish()
                trace = null
            },
            onDone = {
                openMic(MicPurpose.CANCEL_WINDOW)
                startCancelCountdown(place, rideType)
            }
        )
        bookingStartedAt = System.currentTimeMillis()
    }

    private fun buildBookingAnnouncement(place: PlaceOption, rideType: RideType): String {
        val address = place.formattedAddress.takeIf { it.isNotBlank() }
        return if (address != null) {
            "Booking ${rideType.spokenName} to ${place.name}, $address. Say cancel to stop."
        } else {
            "Booking ${rideType.spokenName} to ${place.name}. Say cancel to stop."
        }
    }

    private var bookingStartedAt: Long = System.currentTimeMillis()

    private fun startCancelCountdown(place: PlaceOption, rideType: RideType) {
        cancelWindowJob?.cancel()
        cancelWindowJob = viewModelScope.launch {
            var remaining = RiderState.CANCEL_WINDOW_MS
            while (remaining > 0) {
                delay(100)
                remaining -= 100
                val current = uiState.ride
                if (current !is RiderState.Confirming) return@launch
                uiState = uiState.copy(
                    ride = current.copy(cancelWindowMillisRemaining = remaining)
                )
            }

            // Window elapsed without a cancel — the ride stands. Only now does anything reach
            // the server: cancelling during the window must leave no trace of a ride, because a
            // ride that was created and then withdrawn would have been offered to drivers.
            stt?.cancel()
            closeMic()
            transition(RiderState.Finding())
            NarrationService.start(getApplication())
            engine.heartbeat(true)

            submitRide(place, rideType)
        }
    }

    /**
     * Creates the ride on the server and opens the socket for it.
     *
     * Subscribing here, on ride creation, is what makes "one connection per active ride" true:
     * there is exactly one place a socket is opened, and it is the same place a ride comes into
     * existence.
     */
    private fun submitRide(place: PlaceOption, rideType: RideType) {
        viewModelScope.launch {
            when (val result = api.createRide(
                destination = place.name,
                rideType = rideType.name,
                destinationAddress = place.formattedAddress,
                destinationLatitude = place.latitude,
                destinationLongitude = place.longitude,
                destinationPlaceId = place.placeId,
                pickupLatitude = pickupLatitude,
                pickupLongitude = pickupLongitude,
                // Exactly one contact, chosen and confirmed aloud by the rider, and only for
                // this booking. The phonebook itself never leaves the phone.
                contactName = chosenContact?.name.orEmpty(),
                contactPhone = chosenContact?.phone.orEmpty(),
                dropNote = chosenDropNote
            )) {
                is ApiResult.Ok -> {
                    val snapshot = result.value
                    activeRideId = snapshot.rideId
                    expectedCode = snapshot.boardingCode
                    lastSpokenPhase = RidePhase.REQUESTED

                    Log.i(TAG, "RIDE created id=${snapshot.rideId} code=${snapshot.boardingCode}")
                    Telemetry.logSocket("RIDE_CREATED", snapshot.rideId, "dest=${place.name}")

                    socket.subscribe(snapshot.rideId)
                }

                is ApiResult.Failed -> {
                    // The booking failed, and this is the moment a silent app would be at its
                    // most dangerous: the rider has been told a cab is coming. It is not.
                    Log.w(TAG, "RIDE creation failed: ${result.detail}")
                    engine.heartbeat(false)
                    engine.earcon(Earcon.ERROR)
                    transition(RiderState.Idle, announce = false)
                    NarrationService.stop(getApplication())
                    speak(
                        "${result.spoken} Your ride was not booked. Hold and try again.",
                        NarrationTier.INTERRUPT
                    )
                }
            }
        }
    }

    /** Closes the socket for a finished ride and forgets its state. */
    private fun endRide(reason: String) {
        socket.unsubscribe(reason)
        activeRideId = null
        expectedCode = ""
        lastSpokenPhase = null
        // The next ride gets its own code, its own contact and its own drop note.
        chosenContact = null
        chosenDropNote = ""
        pendingVaguePlace = null
        pendingVagueRideType = null
        meetingAsks = 0
        codeVerified = false
        codeListenJob?.cancel()
        codeListenJob = null
        uiState = uiState.copy(selectedDestination = null)
        NarrationService.stop(getApplication())
    }

    // =================================================================================
    //  Demo ride — DEBUG ONLY
    // =================================================================================

    /**
     * Walks the ride arc on timers, with no backend at all.
     *
     * Kept from step 3 and still useful: it is the only way to exercise the six late ride
     * screens with no server, no driver phone and no network — which is exactly the situation
     * on a train to a demo. Guarded by [BuildConfig.DEBUG] so it cannot fire in a release build.
     */
    private fun startDemoRide() {
        if (!BuildConfig.DEBUG) {
            speak("That command isn't available.", NarrationTier.QUEUED)
            return
        }

        demoRideJob?.cancel()
        val driver = DriverInfo(
            name = "Karthik",
            vehicleModel = "Bajaj auto, yellow",
            vehiclePlate = "TN 37 BX 4412",
            phoneNumber = "+910000000000",
            rating = 4.8f
        )
        val destination = Gazetteer.activeCity.places.first().name

        demoRideJob = viewModelScope.launch {
            transition(RiderState.Finding())
            engine.heartbeat(true)
            delay(2_500)

            transition(RiderState.Assigned(driver, etaMinutes = 4))
            engine.heartbeat(false)
            engine.earcon(Earcon.BOOKING_CONFIRMED)
            speak("${driver.name} is coming. Four minutes.", NarrationTier.QUEUED)
            delay(3_500)

            for (metres in listOf(400, 250, 120, 40)) {
                transition(RiderState.Approaching(driver, metres, bearingDegrees = 45f), announce = false)
                engine.earcon(Earcon.DRIVER_APPROACH, pan = 0.4f, pitchShift = pitchFor(metres))
                delay(1_500)
            }

            expectedCode = "4-7-2"
            onDriverArrived()
            delay(8_000)

            transition(RiderState.InTrip(destination, etaMinutes = 12, driver = driver))
            engine.heartbeat(true)
            delay(5_000)

            engine.heartbeat(false)
            transition(RiderState.Done(destination, fareRupees = 148, durationMinutes = 12))
        }
    }

    // =================================================================================
    //  Speech helpers
    // =================================================================================

    private fun speak(
        text: String,
        tier: NarrationTier,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        lastSpoken = text
        engine.speak(
            text,
            tier,
            onStart = onStart?.let { cb -> { onMain { cb() } } },
            onDone = onDone?.let { cb -> { onMain { cb() } } }
        )
    }

    private fun speakHelp() {
        speak(
            "I cover ${Gazetteer.activeCity.displayName}. Say: take me to a place. " +
                    "Or say list places, status, repeat, cancel, call driver, book again, " +
                    "switch city, or yellow theme.",
            NarrationTier.QUEUED
        ) { openMic(MicPurpose.BOOKING) }
    }

    private fun speakStatus() {
        // "Status" while offline must say so. It is the one question whose honest answer during
        // an outage is "I don't currently know", and inventing a stale answer would be worse
        // than admitting the gap.
        if (activeRideId != null && !uiState.connected) {
            speak("I've lost the connection and I'm reconnecting.", NarrationTier.QUEUED)
            return
        }

        val status = when (val ride = uiState.ride) {
            is RiderState.Idle -> "No ride in progress."
            is RiderState.Confirming -> "Booking ${ride.rideType.spokenName} to ${ride.destination}."
            is RiderState.Finding -> "Still finding a driver."
            // Status reports the phase, not a manufactured duration. The rider gets a time
            // only when the driver has actually sent one.
            is RiderState.Assigned -> "${ride.driver.name} is on the way to you."
            is RiderState.Approaching -> "Your driver is close now."
            is RiderState.Arrived ->
                if (codeVerified) "Your driver is here and the code is confirmed."
                else "Your driver is here, waiting for you to check the code."
            is RiderState.InTrip -> "On the way to ${ride.destination}."
            is RiderState.Done -> "That ride is finished."
            else -> "Working on it."
        }
        speak(status, NarrationTier.QUEUED)
    }

    private fun handleSpeechError(error: SpeechError) {
        if (micPurpose == MicPurpose.CANCEL_WINDOW) {
            if (uiState.ride is RiderState.Confirming) openMic(MicPurpose.CANCEL_WINDOW)
            return
        }

        // A quiet street while waiting for the driver to speak is expected, not a fault. The
        // bounded listen in onDriverArrived is what closes this loop, so simply reopen — but
        // never once the code has been settled, or a recogniser timeout after a correct code
        // reopens the microphone and the rider is asked all over again.
        if (micPurpose == MicPurpose.CODE_VERIFY) {
            if (!codeVerified && uiState.ride is RiderState.Arrived) openMic(MicPurpose.CODE_VERIFY)
            return
        }

        consecutiveSpeechErrors++

        if (consecutiveSpeechErrors > MAX_CONSECUTIVE_ERRORS) {
            consecutiveSpeechErrors = 0
            pendingOptions = null
            pendingNearMiss = null
            engine.earcon(Earcon.ERROR)
            transition(RiderState.Idle, announce = false)
            speak(
                "${error.spokenExplanation} Press and hold to try again when you're ready.",
                NarrationTier.INTERRUPT
            )
            return
        }

        if (error == SpeechError.PERMISSION ||
            error == SpeechError.UNAVAILABLE ||
            error == SpeechError.LANGUAGE
        ) {
            consecutiveSpeechErrors = 0
            engine.earcon(Earcon.ERROR)
            transition(RiderState.Idle, announce = false)
            speak(error.spokenExplanation, NarrationTier.INTERRUPT)
            return
        }

        engine.earcon(Earcon.NOT_UNDERSTOOD)

        when (micPurpose) {
            MicPurpose.CLARIFY_ANSWER -> {
                val options = pendingOptions
                if (options != null) {
                    speak("Sorry — ${options.first.name}, or ${options.second.name}?", NarrationTier.QUEUED) {
                        openMic(MicPurpose.CLARIFY_ANSWER)
                    }
                    return
                }
            }

            MicPurpose.NEAR_MISS_ANSWER -> {
                val candidate = pendingNearMiss
                if (candidate != null) {
                    speak("Sorry — did you mean ${candidate.name}? Yes or no.", NarrationTier.QUEUED) {
                        openMic(MicPurpose.NEAR_MISS_ANSWER)
                    }
                    return
                }
            }

            MicPurpose.SLOT_ANSWER -> {
                speak("I still need a place. Where would you like to go?", NarrationTier.QUEUED) {
                    openMic(MicPurpose.SLOT_ANSWER)
                }
                return
            }

            // Not hearing an answer to a ladder question is a legitimate answer to it. The
            // rider is standing on a street with somewhere to be; the app takes the silence,
            // books the ride, and stops asking.
            MicPurpose.MEETING_ANSWER,
            MicPurpose.CONTACT_NAME_ANSWER,
            MicPurpose.CONTACT_CONFIRM_ANSWER,
            MicPurpose.LANDMARK_ANSWER -> {
                finishLadderAndBook()
                return
            }

            MicPurpose.CANCEL_WINDOW, MicPurpose.CODE_VERIFY -> return
            MicPurpose.BOOKING -> Unit
        }

        runLadder(unrecognised = "")
    }

    // =================================================================================
    //  Plumbing
    // =================================================================================

    /** Applies a server snapshot to the screen without narrating anything. */
    private fun applySnapshot(snapshot: RideSnapshot, announce: Boolean) {
        expectedCode = snapshot.boardingCode.ifBlank { expectedCode }

        val driver = DriverInfo(
            name = snapshot.driverName.ifBlank { "Your driver" },
            vehicleModel = snapshot.vehicleModel,
            vehiclePlate = snapshot.vehiclePlate,
            phoneNumber = snapshot.driverPhone,
            rating = 0f
        )

        val next = when (snapshot.phase) {
            RidePhase.REQUESTED -> RiderState.Finding()
            RidePhase.ASSIGNED -> RiderState.Assigned(driver, snapshot.etaMinutes)
            RidePhase.ENROUTE -> RiderState.Approaching(
                driver, snapshot.distanceMeters, snapshot.bearingDeg
            )
            RidePhase.ARRIVED -> RiderState.Arrived(
                driver, expectedCode, Headphones.connected(getApplication())
            )
            RidePhase.SEATED, RidePhase.IN_TRIP -> RiderState.InTrip(
                snapshot.destination, snapshot.etaMinutes, driver
            )
            RidePhase.COMPLETED -> RiderState.Done(
                snapshot.destination, snapshot.fareRupees, snapshot.durationMinutes
            )
            RidePhase.CANCELLED -> RiderState.Idle
            RidePhase.UNKNOWN -> return
        }

        transition(next, announce = announce)
    }

    private fun currentDriver(): DriverInfo? = when (val ride = uiState.ride) {
        is RiderState.Assigned -> ride.driver
        is RiderState.Approaching -> ride.driver
        is RiderState.Arrived -> ride.driver
        is RiderState.InTrip -> ride.driver
        else -> null
    }

    private fun driverFrom(event: RideEvent) = DriverInfo(
        name = event.string("driverName", "Your driver"),
        vehicleModel = event.string("vehicleModel"),
        vehiclePlate = event.string("vehiclePlate"),
        phoneNumber = event.string("driverPhone"),
        rating = 0f
    )

    /**
     * Bearing to stereo pan.
     *
     * 0° (dead ahead) is centre, 90° is hard right, 270° hard left. A sine mapping rather than
     * a linear one, because it matches how the ear actually localises: the change per degree is
     * largest near the front, where the rider is most able to act on it by turning.
     */
    private fun panFor(bearingDegrees: Float): Float {
        val radians = Math.toRadians(bearingDegrees.toDouble())
        return kotlin.math.sin(radians).toFloat().coerceIn(-1f, 1f)
    }

    /**
     * Distance to pitch multiplier.
     *
     * 1.0 at 400 m or further, rising to about 1.6 as the car closes. Rising as it nears, so the
     * contour reads as approach — the direction of movement carries the meaning, since absolute
     * pitch is hard to judge without a reference.
     */
    private fun pitchFor(distanceMeters: Int): Float {
        if (distanceMeters < 0) return 1f
        val clamped = distanceMeters.coerceIn(0, 400)
        return 1f + (400 - clamped) / 660f
    }

    private fun spokenMinutes(minutes: Int): String =
        if (minutes == 1) "one minute" else "$minutes minutes"

    private fun transition(next: RiderState, announce: Boolean = true) {
        Telemetry.logStateChange(
            uiState.ride::class.simpleName ?: "?",
            next::class.simpleName ?: "?"
        )
        if (next is RiderState.Idle) engine.heartbeat(false)
        uiState = uiState.copy(ride = next)
    }

    /** TTS callbacks arrive on a binder thread; state updates must not. */
    private inline fun onMain(crossinline block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun onCleared() {
        cancelWindowJob?.cancel()
        silenceJob?.cancel()
        micReopenJob?.cancel()
        codeListenJob?.cancel()
        listPlacesJob?.cancel()
        demoRideJob?.cancel()
        stt?.cancel()
        engine.heartbeat(false)
        // The socket is application-scoped and deliberately NOT closed here. A rotation or a
        // brief backgrounding destroys this view model; tearing the ride's connection down with
        // it would cost the rider a "Connection lost / Connected" pair for an event that was
        // not an outage at all.
        super.onCleared()
    }
}
