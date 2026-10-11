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
import com.cabeye.rider.camera.CameraStreamer
import com.cabeye.rider.dialogue.CameraConsent
import com.cabeye.rider.dialogue.ClarifyAnswer
import com.cabeye.rider.dialogue.FeedbackParser
import com.cabeye.rider.dialogue.NextJourneyChoice
import com.cabeye.rider.dialogue.SpokenTime
import com.cabeye.rider.schedule.ScheduledRide
import com.cabeye.rider.state.FeedbackStep
import com.cabeye.rider.state.NextStep
import java.time.ZonedDateTime
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
import com.cabeye.rider.net.PaymentOrder
import com.cabeye.rider.net.PaymentOrderStatus
import com.cabeye.rider.net.UpiResponse
import com.cabeye.rider.net.lastFourSpoken
import com.cabeye.rider.places.City
import com.cabeye.rider.security.BiometricGate
import com.cabeye.rider.places.ContactLookup
import com.cabeye.rider.places.ContactMatch
import com.cabeye.rider.places.Gazetteer
import com.cabeye.rider.places.MatchGate
import com.cabeye.rider.service.NarrationService
import com.cabeye.rider.speech.ListenOptions
import com.cabeye.rider.speech.SpeechError
import com.cabeye.rider.speech.SpeechInputListener
import com.cabeye.rider.state.DriverInfo
import com.cabeye.rider.state.PlaceOption
import com.cabeye.rider.state.RideType
import com.cabeye.rider.state.RiderState
import com.cabeye.rider.state.RiderUiState
import com.cabeye.rider.state.PaymentPhase
import com.cabeye.rider.state.PaymentUi
import com.cabeye.rider.state.ThemeChoice
import com.cabeye.rider.state.CameraShare
import com.cabeye.rider.ui.HapticPattern
import com.cabeye.rider.ui.Haptics
import com.cabeye.rider.telemetry.Telemetry
import com.cabeye.rider.memory.PreferenceMemoryAgent
import com.cabeye.rider.memory.SuggestionKind
import com.cabeye.rider.memory.VisitedPlace
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import com.cabeye.rider.memory.RouteLeg
import com.cabeye.rider.memory.RoutineAgent
import com.cabeye.rider.memory.SavedRoute
import com.cabeye.rider.net.StopInfo
import com.cabeye.rider.net.stopsJson
import com.cabeye.rider.trip.PlannedLeg
import com.cabeye.rider.trip.StopKind
import com.cabeye.rider.trip.StopPlanParser
import com.cabeye.rider.trip.TripPlan
import com.cabeye.rider.memory.TextSimilarity
import com.cabeye.rider.mindmap.AnchorParser
import com.cabeye.rider.mindmap.Edge
import com.cabeye.rider.mindmap.EdgeKind
import com.cabeye.rider.mindmap.Geo
import com.cabeye.rider.mindmap.MindMap
import com.cabeye.rider.mindmap.MindMapAgent
import com.cabeye.rider.mindmap.MindMapBuilder
import com.cabeye.rider.mindmap.MindMapCommands
import com.cabeye.rider.mindmap.MindMapInterpreter
import com.cabeye.rider.mindmap.MindMapStore
import com.cabeye.rider.mindmap.NearbyPlace
import com.cabeye.rider.mindmap.NearbySuggestionAgent
import com.cabeye.rider.mindmap.Node
import com.cabeye.rider.mindmap.NodeKind
import com.cabeye.rider.mindmap.RideCommands
import com.cabeye.rider.mindmap.RideGuideAgent
import com.cabeye.rider.mindmap.SyntheticRide
import com.cabeye.rider.places.AreaNamer
import com.cabeye.rider.places.AtlasStore
import com.cabeye.rider.walk.DetailLevel
import com.cabeye.rider.walk.Landmark
import com.cabeye.rider.walk.RouteBuilder
import com.cabeye.rider.walk.RouteMemory
import com.cabeye.rider.walk.Side
import com.cabeye.rider.walk.TurnDirection
import com.cabeye.rider.walk.WalkCommands
import com.cabeye.rider.walk.WalkEvent
import com.cabeye.rider.walk.WalkLogEntry
import com.cabeye.rider.walk.WalkNarrator
import com.cabeye.rider.walk.WalkRecorder
import com.cabeye.rider.walk.WalkRecordingService
import com.cabeye.rider.walk.WalkRoute
import com.cabeye.rider.walk.WalkStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

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
    LANDMARK_ANSWER,

    /**
     * Answering the memory agent: "Going to PSG College, like most weekday mornings?" or
     * "Did you mean PSG College?". Yes books it (through the normal cancel window); no goes
     * back one step to "where would you like to go?"; naming another place books that.
     */
    MEMORY_ANSWER,

    /** Answering one of the feedback questions; which one is in [RiderState.Feedback.step]. */
    FEEDBACK,

    /** Answering a next-journey question; which one is in [RiderState.NextJourney.step]. */
    NEXT_JOURNEY,

    /**
     * The live camera: answering "can your driver see your camera?", or — while it is on —
     * "stop camera". Only camera words mean anything here. Nothing heard under this purpose
     * can book, cancel or change the ride.
     */
    CAMERA,

    /** Multi-stop planning: naming the next stop ("next stop? or say that's all"). */
    PLAN_LEG,

    /** Multi-stop: what happens at a stop — the driver waits, a drop-off, or a pick-up. */
    STOP_KIND,

    /** Multi-stop: the numbered read-back — yes, or an edit ("remove stop two"). */
    PLAN_REVIEW,

    /**
     * During a multi-stop ride: only stop words mean anything ("I'm back", "skip the next
     * stop", "what are my stops", "add a stop at …"). Nothing heard here can cancel the ride.
     */
    TRIP_COMMAND,

    /** During a ride: yes or no to a stop change just read back. */
    STOP_CONFIRM,

    /**
     * The walking-route agent: consent, a landmark while recording, a route's name, "next" while
     * being guided, or "was it still there?". What it means depends on the walk's mode.
     */
    WALK,

    /** Answering the offer of well-known places nearby: more, take me to one, remember, no. */
    NEARBY,

    /** Yes or no: may this sentence go to the server to be understood? Or: did I read it right? */
    INTERPRET,

    /** The explore module is open: describe, keep telling me, find, read, this is …, finish. */
    EXPLORE
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

    /** Set by the Activity: shows the system camera-permission dialog. */
    var cameraPermissionRequest: (() -> Unit)? = null

    private var micPurpose = MicPurpose.BOOKING

    /** Wall-clock time the current recognition session actually started listening. */
    private var micOpenedAtMillis: Long = 0L
    private var cancelWindowJob: Job? = null
    private var silenceJob: Job? = null

    /** The pending "reopen the mic after a short gap" job. See [reopenMicAfterGap]. */
    private var micReopenJob: Job? = null

    /** The bounded "I didn't hear the code" listen. Cancelled the moment the code is settled. */
    private var codeListenJob: Job? = null

    /**
     * The ride just finished, kept after [endRide] clears [activeRideId].
     *
     * Payment happens on the Done screen, which is *after* the ride has ended — so without
     * holding the id here there would be nothing to report the payment against.
     */
    private var lastCompletedRideId: String? = null

    /** The in-flight "has it settled yet?" poll. */
    private var paymentPollJob: Job? = null

    /** The Done screen a press started from, so "pay" can return to it. See [handleTranscript]. */
    private var doneBeforeListening: RiderState.Done? = null

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
     * Set by the Activity: shows Android's fingerprint / face / screen-lock prompt for a
     * payment and reports the outcome. Lives on the Activity because the system prompt needs
     * one; null (for example in tests) means pay without the prompt.
     */
    var paymentAuthRequest: ((Int, (BiometricGate.Result) -> Unit) -> Unit)? = null

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

    /** Unclear answers to the current A-or-B question. One re-ask, then it is a new place. */
    private var clarifyUnclear = 0

    // ---------------------------------------------------------------------------------
    //  Preference-memory agent
    // ---------------------------------------------------------------------------------

    /** The memory suggestion currently being asked about, if any. */
    private var pendingMemory: PreferenceMemoryAgent.Suggestion? = null

    /** What the rider had said when a REPAIR suggestion was made — learned as an alias on "yes". */
    private var pendingMemoryHeard: String = ""

    /** Places the rider said "no" to during this booking. Never offered again in it. */
    private val rejectedMemoryKeys = mutableSetOf<String>()

    /** The memory may step in once per booking when nothing was heard clearly; not in a loop. */
    private var memoryRescueUsed = false

    /** Last partial transcript of this listen: what the recogniser half-heard before failing. */
    private var lastPartial: String = ""

    // ---------------------------------------------------------------------------------
    //  After the ride: feedback and the next journey
    // ---------------------------------------------------------------------------------

    /** Who drove the ride that just finished, for "How was your ride with Karthik?". */
    private var lastDriverName: String = ""

    /** Unanswered or unclear answers to the current post-ride question. */
    private var postRideMisses = 0

    /** The scheduled ride being put together: when, and where (words + remembered place). */
    private var draftAt: ZonedDateTime? = null
    private var draftPhrase: String = ""
    private var draftPlace: VisitedPlace? = null

    /** When the last unprompted suggestion was offered, and how many were turned down today. */
    private var lastProactiveAt: Long = 0L
    private var proactiveDeclines = 0

    /** A slot the rider already filled, held across the follow-up question so it is not re-asked. */
    private var heldRideType: RideType? = null

    /** Last thing said, so "repeat" has something to repeat. */
    private var lastSpoken: String = ""

    /** The last few sentences spoken, with when, so the app can recognise its own voice. */
    private val recentSpoken = ArrayDeque<Pair<Long, String>>()

    /**
     * What the rider actually said for the destination being booked ("piece g"), sent with the
     * booking so their memory learns their words for the place. Cleared once sent.
     */
    private var pendingSpokenAs: String = ""

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

    /**
     * The trip meter, from the server's TRIP_PROGRESS events: metres so far and the fare if the
     * trip ended now. Never spoken on its own — only when the rider asks ("status", "how far").
     */
    private var tripMeters = 0
    private var tripFareSoFar = 0

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

        /** The ride guide's GPS: a fix every 5 s, and fixes worse than 50 m are ignored. */
        const val RIDE_FIX_MS = 5_000L
        const val RIDE_FIX_ACCURACY_M = 50f

        /** After a drop-off, "here" means that place for this long — while the rider is still this close. */
        const val HERE_FOR_MS = 2 * 3_600_000L
        const val HERE_NEAR_M = 300.0

        /** In the cab, a press released this quickly is a tap ("where are we"), not talking. */
        const val TAP_MS = 500L

        /** The simulated ride: one synthetic fix this often (each stands for 15 s of driving). */
        const val DEMO_FIX_MS = 700L
        /** Where a simulated ride starts when the phone is not in Coimbatore: Saibaba Colony. */
        val DEMO_START = 11.0244 to 76.9450

        /** The offer of places near a new drop-off is only made this soon after arriving. */
        const val NEARBY_OFFER_FOR_MS = 15 * 60_000L
        /** "Take me there", "remember it": the place just talked about. */
        val NEARBY_THAT = Regex("\\b(there|it|that one|this one|that place|that)\\b")

        /** Answers to the nearby-places offer. */
        val NEARBY_MORE = Regex("\\b(more|tell me|details?|about them|what are they)\\b")
        val NEARBY_REMEMBER = Regex("^(?:please\\s+)?(?:remember|save|keep)\\b")
        val NEARBY_ALL = Regex("\\b(them|all|those|both)\\b")
        val NEARBY_GO = Regex("\\b(take me|go to|book|ride to|let'?s go)\\b")
        val NEARBY_NO = Regex("^(?:no|nope|not now|done|that'?s all|nothing|no thanks)\\b")

        /** A sentence meant for the mind map even when the rules can't read the rest. */
        val REMEMBER_LEAD = Regex(
            "^(?:please\\s+)?(?:remember|note that|make a note)\\b(?!\\s+(?:this|it|that|the|my)\\s+(?:route|trip|ride|walk)\\b)"
        )
        /** Questions go to status, never to the interpreter. */
        val QUESTION_START = Regex("^(?:how|what|when|where|why|who|which|is|are|can|could|will|would|do|does|did|should|tell me)\\b")
        const val REMEMBER_HELP = "I didn't catch what to remember. Try: remember Ravi's tea shop, where I stop on Fridays."

        /** Words that can follow "book" without naming anywhere. */
        val NOT_A_PLACE = setOf("other", "another", "ride", "one", "cab", "auto", "again", "now", "new", "more", "trip")

        /** At most one unprompted memory suggestion per this interval. */
        const val PROACTIVE_COOLDOWN_MS = 20 * 60_000L

        /** Two "no"s to unprompted suggestions and the agent stays quiet for the session. */
        const val MAX_PROACTIVE_DECLINES = 2
        const val TAG = "CabEye.Dialogue"

        /** Longest one group of the place list may take before the reader gives up on it. */
        const val GROUP_TIMEOUT_MS = 12_000L

        /** How long to listen for the driver to say the boarding code before offering help. */
        const val CODE_LISTEN_MS = 15_000L

        /** With no press, an optional question (feedback, "anything else?") takes its default. */
        const val NO_PRESS_DEFAULT_MS = 60_000L

        /** How long after saying something the app treats hearing those words as its own echo. */
        const val ECHO_WINDOW_MS = 30_000L

        /** First spoken "still looking" while no driver has accepted, then every so often. */
        const val FINDING_FIRST_UPDATE_MS = 30_000L
        const val FINDING_UPDATE_EVERY_MS = 90_000L

        /** How long the rider has to answer the camera question. The server allows 45 s. */
        const val CAMERA_ANSWER_MS = 60_000L

        /** The camera never stays on longer than this. The server enforces the same limit. */
        const val CAMERA_MAX_MS = 3 * 60_000L

        /** A gentle tick while the camera is on, so the rider can feel that it still is. */
        const val CAMERA_TICK_MS = 5_000L

        /** No picture for this long means the camera has quietly stopped; say so. */
        const val CAMERA_STALL_MS = 8_000L

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

        /** How often to ask the server whether the payment has actually settled. */
        const val PAYMENT_POLL_INTERVAL_MS = 2_000L

        /**
         * How long to keep watching an open order: the gateway's own ten-minute window plus a
         * margin. A screen-reader user working through a checkout page is not fast, and giving
         * up before the order itself expires would announce "unknown" for a payment that is
         * still perfectly able to succeed.
         */
        const val PAYMENT_WATCH_TIMEOUT_MS = 11 * 60_000L

        /** "pay", "payment", "paid", "receipt" as whole words — never "Paytm" or "Payyanur". */
        val PAYMENT_WORDS = Regex("\\b(pay|payment|paid|receipt)\\b")
        val DECLINE_WORDS = Regex("\\b(decline|don'?t pay|do not pay|cancel payment|not now)\\b")
        val CARD_WORDS = Regex("\\b(card|debit|credit)\\b")
        val NETBANKING_WORDS = Regex("\\b(net ?banking|bank)\\b")
        val UPI_WORDS = Regex("\\b(upi|u p i)\\b")
    }

    init {
        themeChoice = prefs.theme
        uiState = uiState.copy(activeCityName = Gazetteer.activeCity.displayName)
        collectSocket()
        speakWelcomeIfFirstRun()
        watchAccount()
        watchSchedules()
    }

    /**
     * When a rider signs in (or the app opens already signed in), refresh their memory and —
     * if there is a habit for this moment — offer it once the greeting has finished.
     */
    private fun watchAccount() {
        viewModelScope.launch {
            var lastId: String? = null
            app.riderAccount.collect { account ->
                val id = account?.takeUnless { it.isGuest }?.id
                if (id != null && id != lastId) {
                    proactiveDeclines = 0
                    lastProactiveAt = 0L
                    app.memory.refresh { onMain { offerProactive("sign-in") } }
                }
                lastId = id
            }
        }
    }

    /** The app came back to the foreground. A good moment for "like usual?" — if idle. */
    fun onForeground() {
        appInForeground = true
        // The driver asked while the phone was locked or in a pocket. Ask again now that the
        // rider can actually answer and the camera can actually open.
        if (uiState.camera == CameraShare.ASKING && cameraAskedInBackground) {
            cameraAskedInBackground = false
            askCameraQuestion()
            return
        }
        offerProactive("foreground")
    }

    /**
     * The app left the screen. Android does not let a backgrounded app keep the camera, so
     * it is switched off here, deliberately and out loud, rather than left to fail silently.
     */
    fun onBackground() {
        appInForeground = false
        if (uiState.camera == CameraShare.LIVE) {
            stopCamera("APP_IN_BACKGROUND", say = "Cab Eye left the screen, so the camera is off.")
        }
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
                if (text.isNotBlank()) {
                    if (uiState.camera == CameraShare.LIVE) {
                        // The driver is watching the camera and guiding: their words come first,
                        // over anything the object narration was saying.
                        engine.stopSpeaking()
                        haptics.perform(HapticPattern.CLARIFY)
                        speak("$cameraDriverName says: $text", NarrationTier.INTERRUPT)
                    } else {
                        narrate(event, text)
                    }
                }
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
                stopCamera("TRIP_STARTED")
                tripMeters = 0
                tripFareSoFar = 0
                val destination = event.string("destination", lastBooking?.first ?: "your destination")
                val eta = event.int("etaMinutes", 12)
                val driver = currentDriver() ?: driverFrom(event)
                transition(RiderState.InTrip(destination, eta, driver, activeStops), announce = false)
                // Destination only — see the note on RIDE_ASSIGNED. The driver's own message
                // is the channel for "about ten minutes, traffic at Avinashi Road".
                val first = currentStop()
                narrate(event, if (first == null) "On the way to $destination."
                else "On the way. First stop, ${first.name}. Then $destination.")
                activeRideId?.let(::refreshStops)
                lastSpokenPhase = RidePhase.IN_TRIP
                engine.heartbeat(true)
                startRideGuide()
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
                // The server measured (GPS) or estimated (straight line) the distance and priced
                // the trip from it. Say which, so a rider is never told a guess as a measurement.
                val metres = event.int("distanceMeters", 0)
                val distance = spokenDistance(metres, estimate = event.string("distanceSource") == "ESTIMATE")
                engine.heartbeat(false)
                lastDriverName = currentDriver()?.name.orEmpty()
                transition(RiderState.Done(destination, fare, minutes, distanceMeters = metres), announce = false)
                narrate(event, if (distance.isEmpty()) "You've arrived. $fare rupees." else "You've arrived. $distance, $fare rupees.")
                lastSpokenPhase = RidePhase.COMPLETED
                lastRidePlace = lastBookingPlace
                lastRideEndedAt = System.currentTimeMillis()
                // A saved walk from this drop-off: say so once, without getting in the way of paying.
                lastRidePlace?.let { place ->
                    RouteMemory.forPlace(walkStore.routes(), RouteMemory.placeKey(place.placeId, place.name))?.let { walk ->
                        speak("I have your walk from here to ${walk.name}. Say guide me when you're ready.", NarrationTier.QUEUED)
                    }
                }
                // Somewhere new: the best-known places close by, offered after paying and feedback.
                prepareNearbyAfterRide(lastRidePlace)
                endRide("completed")
                // The server has just recorded this trip as a visit; pull the updated places so
                // the very next booking can already use them.
                app.memory.refresh()
                // Open the payment panel straight away: the customer should not have to find
                // a payment screen. The fare was just spoken, so it is not repeated.
                if (fare > 0) startPayment(announceFare = false) else startFeedback()
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

            // Multi-stop — see "Multi-stop rides" below.
            RideEventType.STOP_ARRIVED,
            RideEventType.RIDER_RETURNED,
            RideEventType.STOP_DONE,
            RideEventType.STOP_SKIPPED,
            RideEventType.STOPS_CHANGED,
            RideEventType.WAIT_WARNING,
            RideEventType.WAIT_OVERDUE -> onStopEvent(event)

            // The live camera — see "Live camera" below.
            RideEventType.CAMERA_REQUESTED -> onCameraRequested(event)
            RideEventType.CAMERA_DECLINED -> onCameraDeclinedByServer(event)
            RideEventType.CAMERA_STOPPED -> onCameraStoppedByServer(event)
            // STARTED is the echo of this phone's own yes; frames never arrive here.
            RideEventType.CAMERA_STARTED,
            RideEventType.CAMERA_FRAME -> Unit

            // Transport notices. Handled by the socket layer; narrating them here would mean
            // the rider hears about the connection twice, from two places that could disagree.
            RideEventType.CONNECTED,
            RideEventType.PARTICIPANT_JOINED,
            RideEventType.PARTICIPANT_LEFT,
            RideEventType.REPLAY_COMPLETE,
            RideEventType.REQUEST_TAKEN,
            RideEventType.PONG,
            RideEventType.ERROR -> Unit

            // The trip meter moved. Silent by design — kept for when the rider asks, and for
            // the ride guide on a phone without GPS.
            RideEventType.TRIP_PROGRESS -> {
                tripMeters = maxOf(tripMeters, event.int("distanceMeters", 0))
                val fare = event.int("fareRupees", 0)
                if (fare > 0) tripFareSoFar = fare
                rideGuide?.onTripMeters(tripMeters.toDouble(), System.currentTimeMillis())?.forEach(::sayRideGuide)
            }

            // The rider learns the payment outcome from the order it is watching.
            RideEventType.PAYMENT_UPDATED,
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
                        "Hold the screen while the driver says it, and I'll check it.",
                NarrationTier.INTERRUPT
            ) {
                openMic(MicPurpose.CODE_VERIFY)
            }
        } else {
            // No headphones, so the code is not spoken at all. This is the correct behaviour
            // and the app says why, because unexplained silence is the one thing it must never
            // produce.
            speak(
                "Your car has arrived. Ask the driver to say the code, and hold the screen while they say it. I'll check it.",
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
        armCodeListenTimeout()
    }

    /**
     * (Re)starts the bounded listen for the boarding code. Called at arrival, and again after
     * anything else was said at the kerb (the camera question, "Camera on") — time the phone
     * spent talking is time the driver could not be heard, so it must not count against them.
     */
    private fun armCodeListenTimeout() {
        codeListenJob?.cancel()
        codeListenJob = viewModelScope.launch {
            delay(CODE_LISTEN_MS)
            val codeAsked = micPurpose == MicPurpose.CODE_VERIFY || awaitingAnswer?.first == MicPurpose.CODE_VERIFY
            if (!codeVerified && codeAsked && uiState.ride is RiderState.Arrived) {
                stt?.cancel()
                closeMic()
                speak(
                    "I haven't checked the code yet. Hold the screen while the driver says it, close to your phone. " +
                        "If someone with you can see the driver's screen, they can press Code is right.",
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
        // Back at the car at a stop: the same check, and a wrong code means the same thing.
        if (codeForStop && !heardDigits.contains(expectedDigits)) codeForStop = false

        if (heardDigits.contains(expectedDigits)) {
            // Settle the code BEFORE speaking. Everything that could ask again — the bounded
            // listen, the speech-error retry, a stray press — keys off these three lines.
            settleBoardingCode()

            engine.earcon(Earcon.UNDERSTOOD)
            val backAtStop = codeForStop
            codeForStop = false
            speak(if (backAtStop) "That's the right code. Welcome back." else "That's the right code. This is your car.",
                NarrationTier.INTERRUPT)
            activeRideId?.let { rideId ->
                viewModelScope.launch { api.confirmCode(rideId, matched = true) }
            }
            return
        }

        // A wrong code is not a recognition failure to be retried quietly. It is the one
        // outcome this entire mechanism exists to catch, and it is tier 0.
        uiState = uiState.copy(
            ride = (uiState.ride as? RiderState.Arrived)?.copy(verificationFailed = true) ?: uiState.ride
        )
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
        uiState = uiState.copy(
            ride = (uiState.ride as? RiderState.Arrived)?.copy(codeVerified = true, verificationFailed = false) ?: uiState.ride
        )
        // The right car is found; the camera's job is done. "Camera off." is queued behind
        // whatever the caller says next, never over it.
        stopCamera("CODE_CONFIRMED")
        if (micPurpose == MicPurpose.CODE_VERIFY) {
            stt?.cancel()
            closeMic()
        }
    }

    // =================================================================================
    //  Live camera — "help my driver find me"
    // =================================================================================
    //
    //  Driver taps SEE RIDER'S VIEW  →  CAMERA_REQUESTED arrives here
    //  →  "Karthik can't see you. Can they see your back camera to find you? Say yes or no."
    //  →  yes: the back camera streams small pictures to the driver (no audio, no preview,
    //     nothing recorded); no or silence: it stays off.
    //
    //  It never gets in the way of the boarding code. At the kerb the code listen itself
    //  understands "yes", "no" and "stop camera", so the mic is never taken away from the
    //  driver saying the code. Everything the camera says is queued, never interrupting, and
    //  cuts any open microphone before it speaks so the phone never hears itself.
    //
    //  It turns itself off when the code is confirmed, the passenger is seated, the trip
    //  starts, the ride ends, the rider says "stop camera", the driver stops it, three minutes
    //  pass, the pictures stop coming, or the app leaves the screen — and always says so.

    private val cameraStreamer by lazy { CameraStreamer(getApplication()) }

    /** The newest camera picture, for whichever module is looking (ride camera, explore). */
    private val frameTap = com.cabeye.rider.vision.FrameTap()

    /** "What's in front of me": Gemini through the backend, or the phone's own detector. */
    private val sceneDetector by lazy { com.cabeye.rider.vision.SceneDetector(api) }

    /** The explore module: no ride, the camera, the rider's street, into the mind map. */
    val explore by lazy {
        com.cabeye.rider.explore.ExploreController(
            app = getApplication(),
            scope = viewModelScope,
            camera = cameraStreamer,
            tap = frameTap,
            detector = sceneDetector,
            mindMap = mindMapStore,
            walks = walkStore,
            position = { riderPosition() },
            speak = { text, tier -> speak(text, tier) },
            isSpeaking = { engine.isSpeaking },
            cameraSharedWithDriver = { uiState.camera == CameraShare.LIVE }
        )
    }

    /** Ride module: what the shared camera sees, said to the rider and drawn for the driver. */
    private var rideVisionJob: Job? = null

    @Volatile
    private var rideLabels: List<com.cabeye.rider.vision.SceneObject> = emptyList()

    private var awaitingExplorePermission = false
    private val haptics by lazy { Haptics(getApplication()) }

    private val cameraJobs = mutableListOf<Job>()
    private var cameraAskJob: Job? = null
    private var cameraReasked = false
    private var awaitingCameraPermission = false
    private var appInForeground = true
    private var cameraAskedInBackground = false
    private var cameraDriverName = "Your driver"

    @Volatile
    private var lastCameraFrameAt = 0L

    private fun hasCameraPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Before pickup, with the code not yet confirmed: the only time the camera makes sense. */
    private fun cameraMakesSense(): Boolean =
        activeRideId != null && !codeVerified &&
            (uiState.ride is RiderState.Assigned || uiState.ride is RiderState.Approaching ||
                uiState.ride is RiderState.Arrived)

    private fun onCameraRequested(event: RideEvent) {
        if (uiState.camera != CameraShare.OFF) return
        val rideId = activeRideId ?: return
        if (!cameraMakesSense()) {
            viewModelScope.launch { api.cameraAnswer(rideId, accept = false, reason = "NOT_NOW") }
            return
        }
        cameraDriverName = event.string("driverName").ifBlank { currentDriver()?.name.orEmpty() }
            .ifBlank { "Your driver" }
        cameraReasked = false
        uiState = uiState.copy(camera = CameraShare.ASKING, cameraFrom = cameraDriverName)
        Log.i(TAG, "CAMERA requested by $cameraDriverName always=${prefs.alwaysShareCamera}")

        cameraAskJob?.cancel()
        cameraAskJob = viewModelScope.launch {
            delay(CAMERA_ANSWER_MS)
            if (uiState.camera == CameraShare.ASKING) declineCamera("NO_ANSWER")
        }

        if (prefs.alwaysShareCamera && hasCameraPermission() && appInForeground) {
            acceptCamera(always = false, intro = "$cameraDriverName is looking for you, so I'm sharing your camera.")
            return
        }
        if (!appInForeground) {
            // The camera cannot open from the background, and the mic may not hear an answer.
            // Say what is happening and ask properly once the app is back on screen.
            cameraAskedInBackground = true
            haptics.perform(HapticPattern.CLARIFY)
            speakAroundMic(
                "$cameraDriverName can't see you and would like to see your camera. " +
                    "Open Cab Eye to answer."
            ) { }
            return
        }
        askCameraQuestion()
    }

    private fun askCameraQuestion() {
        haptics.perform(HapticPattern.CLARIFY)
        speakAroundMic(
            "$cameraDriverName can't see you and asks to see your back camera to find you. " +
                "Tap Share camera on the screen, or hold the screen and say yes. Say no to keep it off."
        )
    }

    private fun reaskCamera() {
        if (uiState.camera != CameraShare.ASKING) return
        if (cameraReasked) {
            declineCamera("NO_ANSWER")
            return
        }
        cameraReasked = true
        speakAroundMic("To share your camera with your driver, tap Share camera, or hold the screen and say yes. Or say no.")
    }

    /**
     * Camera words, wherever they are heard. Returns true when the transcript was about the
     * camera and has been dealt with.
     */
    private fun handleCameraSpeech(text: String): Boolean {
        val camera = uiState.camera

        if (CameraConsent.isStopCamera(text)) {
            when (camera) {
                CameraShare.LIVE -> stopCamera("RIDER_SAID_STOP")
                CameraShare.ASKING -> declineCamera("RIDER_DECLINED")
                CameraShare.OFF -> speakAroundMic("Your camera is already off.")
            }
            return true
        }

        if (camera == CameraShare.ASKING &&
            (micPurpose == MicPurpose.CAMERA || micPurpose == MicPurpose.CODE_VERIFY)
        ) {
            when (CameraConsent.answer(text)) {
                CameraConsent.Answer.YES -> { acceptCamera(always = false); return true }
                CameraConsent.Answer.ALWAYS -> { acceptCamera(always = true); return true }
                CameraConsent.Answer.NO -> { declineCamera("RIDER_DECLINED"); return true }
                // At the kerb, anything else is most likely the driver saying the code: let
                // the code check have it.
                null -> if (micPurpose == MicPurpose.CAMERA) { reaskCamera(); return true }
            }
        }

        if (micPurpose == MicPurpose.CAMERA) {
            if (Regex("\\bstatus\\b|where is|how far").containsMatchIn(text.lowercase())) {
                speakStatus()
            } else if (camera == CameraShare.LIVE) {
                speakAroundMic("Your camera is still on for your driver. Say stop camera to turn it off.") { }
            }
            return true
        }
        return false
    }

    /** The rider (or a helper) answered the camera question with the on-screen buttons. */
    fun onCameraAnswer(accept: Boolean) {
        if (uiState.camera != CameraShare.ASKING) return
        stt?.cancel()
        closeMic()
        if (accept) acceptCamera(always = false) else declineCamera("RIDER_DECLINED")
    }

    /** STOP CAMERA on screen or in the TalkBack actions. */
    fun onStopCamera() {
        stopCamera("RIDER_STOPPED")
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (awaitingExplorePermission) {
            awaitingExplorePermission = false
            if (granted) openExplore()
            else speak("Without camera permission I can't look around for you.", NarrationTier.QUEUED)
            return
        }
        if (!awaitingCameraPermission) return
        awaitingCameraPermission = false
        Log.i(TAG, "CAMERA_PERMISSION granted=$granted")
        if (uiState.camera != CameraShare.ASKING) return // the request lapsed meanwhile
        if (granted) {
            acceptCamera(always = false)
        } else {
            declineCamera(
                "NO_PERMISSION",
                say = "Without camera permission I can't share it. " +
                    "Your driver can still use sounds and messages to find you."
            )
        }
    }

    private fun acceptCamera(always: Boolean, intro: String? = null) {
        val rideId = activeRideId ?: return
        if (always) prefs.alwaysShareCamera = true
        // Answered: the local no-answer timer is done. (While the permission dialog is up,
        // the server's own 45-second limit still applies.)
        cameraAskJob?.cancel()

        if (!hasCameraPermission()) {
            val request = cameraPermissionRequest
            if (request == null) {
                declineCamera("NO_PERMISSION", say = "I can't use the camera right now, so it stays off.")
                return
            }
            awaitingCameraPermission = true
            // Said before the dialog, because a bare system prompt means nothing to someone
            // who cannot read it or see what triggered it.
            speakAroundMic("Your phone will now ask to use the camera. Choose Allow.") { request() }
            return
        }

        viewModelScope.launch {
            when (val result = api.cameraAnswer(rideId, accept = true)) {
                is ApiResult.Ok ->
                    if (result.value == "LIVE") startCameraStream(intro, always)
                    else uiState = uiState.copy(camera = CameraShare.OFF)
                is ApiResult.Failed -> {
                    Log.w(TAG, "CAMERA answer failed: ${result.detail}")
                    uiState = uiState.copy(camera = CameraShare.OFF)
                    speakAroundMic(result.spoken)
                }
            }
        }
    }

    private fun startCameraStream(intro: String?, always: Boolean) {
        if (!cameraMakesSense()) {
            stopCamera("NOT_NOW", say = null)
            return
        }
        uiState = uiState.copy(camera = CameraShare.LIVE)
        lastCameraFrameAt = System.currentTimeMillis()

        cameraStreamer.pictureSink = frameTap.sink
        cameraStreamer.start(
            onFrame = { jpeg, n ->
                lastCameraFrameAt = System.currentTimeMillis()
                socket.sendFrame(jpeg, n, rideLabels)
            },
            onError = {
                stopCamera(
                    "CAMERA_UNAVAILABLE",
                    say = "The camera stopped working, so it's off now. " +
                        "Your driver can still use sounds and messages."
                )
            }
        )

        engine.earcon(Earcon.UNDERSTOOD)
        val lead = intro?.let { "$it " }.orEmpty()
        val remembered = if (always) " I'll share it automatically from now on." else ""
        speakAroundMic(
            "${lead}Camera on.$remembered Hold your phone up at chest height, pointing at the road. " +
                "Your driver can see it and guide you, and I'll tell you what's around you. " +
                "Tap Stop camera, or say stop camera, to turn it off."
        )
        startRideVision()

        cameraJobs += viewModelScope.launch {
            while (uiState.camera == CameraShare.LIVE) {
                delay(CAMERA_TICK_MS)
                if (uiState.camera == CameraShare.LIVE) haptics.perform(HapticPattern.CAMERA_TICK)
            }
        }
        cameraJobs += viewModelScope.launch {
            while (uiState.camera == CameraShare.LIVE) {
                delay(2_000)
                val quiet = System.currentTimeMillis() - lastCameraFrameAt
                if (uiState.camera == CameraShare.LIVE && quiet > CAMERA_STALL_MS) {
                    Log.w(TAG, "CAMERA no pictures for ${quiet}ms")
                    stopCamera("NO_PICTURES", say = "The camera stopped, so it's off now.")
                }
            }
        }
        cameraJobs += viewModelScope.launch {
            delay(CAMERA_MAX_MS)
            if (uiState.camera == CameraShare.LIVE) {
                stopCamera(
                    "TIME_LIMIT",
                    say = "Three minutes are up, so I've turned the camera off. " +
                        "Your driver can still send you messages."
                )
            }
        }
    }

    /** The rider said no, or nothing. Idempotent. */
    private fun declineCamera(reason: String, say: String? = null) {
        if (uiState.camera != CameraShare.ASKING) return
        cameraAskJob?.cancel()
        awaitingCameraPermission = false
        cameraAskedInBackground = false
        uiState = uiState.copy(camera = CameraShare.OFF)
        activeRideId?.let { rideId ->
            viewModelScope.launch { api.cameraAnswer(rideId, accept = false, reason = reason) }
        }
        if (micPurpose == MicPurpose.CAMERA && stt?.isListening == true) {
            stt?.cancel()
            closeMic()
        }
        speakAroundMic(
            say ?: if (reason == "NO_ANSWER") "No answer, so your camera stays off."
            else "Okay, your camera stays off."
        )
    }

    /**
     * Turns the camera off, whatever it was doing. Idempotent, and safe from any path.
     *
     * @param say spoken afterwards — queued behind anything said in the same breath, so
     *   "That's the right code" is never cut off by "Camera off". Null says nothing.
     * @param tellServer false when the server is the one that stopped it
     */
    private fun stopCamera(reason: String, say: String? = "Camera off.", tellServer: Boolean = true) {
        val was = uiState.camera
        if (was == CameraShare.OFF) return
        cameraAskJob?.cancel()
        cameraJobs.forEach { it.cancel() }
        cameraJobs.clear()
        awaitingCameraPermission = false
        cameraAskedInBackground = false
        rideVisionJob?.cancel()
        rideVisionJob = null
        rideLabels = emptyList()
        cameraStreamer.jpegSink = null
        // Explore may still be using the camera; otherwise it is turned off.
        if (!explore.ui.open) cameraStreamer.stop()
        uiState = uiState.copy(camera = CameraShare.OFF, cameraSeen = emptyList())
        Log.i(TAG, "CAMERA stopped reason=$reason was=$was")

        val rideId = activeRideId
        if (tellServer && rideId != null) {
            viewModelScope.launch {
                if (was == CameraShare.LIVE) api.cameraStop(rideId, reason)
                else api.cameraAnswer(rideId, accept = false, reason = reason)
            }
        }
        if (was == CameraShare.LIVE && say != null) {
            viewModelScope.launch {
                yield()
                speakAroundMic(say)
            }
        }
    }

    private fun onCameraDeclinedByServer(event: RideEvent) {
        if (uiState.camera != CameraShare.ASKING) return
        // The server's own timeout; this phone's no is already handled before it echoes back.
        cameraAskJob?.cancel()
        awaitingCameraPermission = false
        cameraAskedInBackground = false
        uiState = uiState.copy(camera = CameraShare.OFF)
        if (event.string("reason") == "NO_ANSWER") {
            if (micPurpose == MicPurpose.CAMERA && stt?.isListening == true) {
                stt?.cancel()
                closeMic()
            }
            speakAroundMic("No answer, so your camera stays off.")
        }
    }

    private fun onCameraStoppedByServer(event: RideEvent) {
        val reason = event.string("reason")
        if (uiState.camera == CameraShare.ASKING) {
            stopCamera(reason, say = null, tellServer = false)
            return
        }
        val sentence = when {
            event.string("by") == "DRIVER" -> "Your driver has turned the camera off."
            reason == "TIME_LIMIT" -> "Three minutes are up, so the camera is off."
            else -> "Camera off."
        }
        stopCamera(reason, say = sentence, tellServer = false)
    }

    /**
     * Speaks without ever talking over an open microphone, then puts the right one back.
     *
     * Any listen in progress is cut the moment speech starts (the phone must not transcribe
     * itself), and afterwards the mic reopens for whatever is still open: the boarding code
     * at the kerb, otherwise the camera question. [then] replaces that when given.
     */
    private fun speakAroundMic(text: String, then: (() -> Unit)? = null) {
        speak(
            text,
            NarrationTier.QUEUED,
            onStart = {
                if (stt?.isListening == true && micPurpose != MicPurpose.CANCEL_WINDOW) {
                    stt?.cancel()
                    closeMic()
                }
            }
        ) {
            if (then != null) {
                then()
                return@speak
            }
            when {
                uiState.ride is RiderState.Arrived && !codeVerified -> {
                    armCodeListenTimeout()
                    openMic(MicPurpose.CODE_VERIFY)
                }
                uiState.camera == CameraShare.ASKING && appInForeground -> openMic(MicPurpose.CAMERA)
            }
        }
    }

    // =================================================================================
    //  Payment — in-app, sandbox gateway, end to end
    // =================================================================================
    //
    //  Ride completes  →  the app opens the payment panel by itself: amount + "Pay by UPI"
    //  →  customer taps PAY (or says "pay"; "card" / "net banking" changes the method;
    //     "decline" declines) — all inside this app, no browser, no other app
    //  →  the server's gateway decides the outcome and returns a 12-digit bank reference
    //  →  one spoken ending: paid (with receipt), failed (try again), or unknown.
    //
    //  A sighted companion can also pay by scanning the QR code on the DRIVER's screen; this
    //  app is watching the same order and announces that payment too.
    //
    //  Nothing on this phone can mark a payment as paid. The gateway on the server does.

    /**
     * The customer tapped the payment button, or said "pay".
     *
     * One button, meaning the obvious next thing: start a payment, confirm it, retry it, or —
     * once paid — read the receipt back.
     */
    fun onPayTapped() {
        when (uiState.payment?.phase) {
            null, PaymentPhase.ERROR, PaymentPhase.FAILED -> startPayment(announceFare = true)
            PaymentPhase.AWAITING -> authorizeAndPay()
            PaymentPhase.PAID -> uiState.payment?.let {
                speak(receiptSentence(it.amountRupees, it.bankRef), NarrationTier.INTERRUPT)
            }
            PaymentPhase.STARTING, PaymentPhase.PROCESSING -> Unit
        }
    }

    /** UPI, CARD or NETBANKING, chosen by tap or by voice. */
    fun onPaymentMethod(method: String) {
        val current = uiState.payment ?: return
        if (current.phase != PaymentPhase.AWAITING) return
        setPayment(current.copy(method = method))
        speak("Paying by ${spokenMethod(method)}.", NarrationTier.INTERRUPT)
    }

    /** The customer chose not to pay now. The gateway records a declined payment. */
    fun onDeclinePayment() {
        val current = uiState.payment ?: return
        if (current.phase != PaymentPhase.AWAITING) return
        setPayment(current.copy(phase = PaymentPhase.PROCESSING, message = "Declining…"))
        viewModelScope.launch {
            when (val result = api.simulatePayment(current.orderId, success = false,
                method = current.method, reason = "You declined the payment")) {
                is ApiResult.Ok -> onOrderFailed(result.value.amountRupees, result.value.failureReason)
                is ApiResult.Failed -> {
                    setPayment(current)
                    speak(result.spoken, NarrationTier.INTERRUPT)
                }
            }
        }
    }

    /**
     * Gets (or reuses) the order for this ride's fare and shows the confirm panel.
     *
     * Called automatically when the ride completes, so a blind customer is never left to find
     * a payment screen on their own — and by TRY AGAIN after a failure.
     */
    private fun startPayment(announceFare: Boolean) {
        val done = uiState.ride as? RiderState.Done
        val rideId = activeRideId ?: lastCompletedRideId
        if (done == null || rideId == null) {
            speak("There's no finished ride to pay for.", NarrationTier.QUEUED)
            return
        }

        paymentPollJob?.cancel()
        setPayment(PaymentUi(PaymentPhase.STARTING, done.fareRupees, message = "Preparing payment…"))

        viewModelScope.launch {
            when (val result = api.createPaymentOrder(rideId)) {
                is ApiResult.Ok -> {
                    val order = result.value
                    if (order.status == PaymentOrderStatus.PAID) {
                        onOrderPaid(order.amountRupees, order.bankRef, order.method)
                        return@launch
                    }
                    setPayment(
                        PaymentUi(
                            phase = PaymentPhase.AWAITING,
                            amountRupees = order.amountRupees,
                            orderId = order.orderId,
                            checkoutUrl = order.checkoutUrl,
                            method = "UPI",
                            message = if (order.isRazorpay)
                                "Tap Pay to open Razorpay checkout"
                            else
                                "Tap Pay or say \"pay\""
                        )
                    )
                    val fare = if (announceFare) "Your fare is ${order.amountRupees} rupees. " else ""
                    speak(
                        "${fare}Paying by UPI. Tap Pay, or say pay, to confirm.",
                        NarrationTier.QUEUED
                    )
                    // Watches for payment by any route — including a companion scanning the
                    // QR code on the driver's screen.
                    watchOrder(order.orderId)
                }

                is ApiResult.Failed -> {
                    // "Already paid" arrives as a refusal. Check first, so a customer who paid
                    // is told so rather than told something went wrong.
                    val snapshot = (api.paymentStatus(rideId) as? ApiResult.Ok<RideSnapshot>)?.value
                    if (snapshot?.paymentStatus == "CONFIRMED") {
                        onOrderPaid(snapshot.fareRupees, snapshot.paymentRef, "")
                        return@launch
                    }
                    setPayment(PaymentUi(PaymentPhase.ERROR, done.fareRupees, message = result.spoken))
                    engine.earcon(Earcon.ERROR)
                    speak("${result.spoken} Tap Pay to try again.", NarrationTier.INTERRUPT)
                }
            }
        }
    }

    /**
     * The owner confirms with fingerprint, face or screen lock, then the payment is sent.
     *
     * Nothing reaches the gateway until the phone's owner has confirmed. Cancelling costs
     * nothing and says so — "nothing was charged" is the sentence a customer who cannot see
     * the screen most needs to hear after backing out.
     */
    private fun authorizeAndPay() {
        val current = uiState.payment ?: return
        if (current.phase != PaymentPhase.AWAITING) return
        val request = paymentAuthRequest
        if (request == null) {
            confirmPayment()
            return
        }

        setPayment(current.copy(phase = PaymentPhase.PROCESSING, message = "Confirm with fingerprint or screen lock"))
        speak("Confirm with your fingerprint or screen lock.", NarrationTier.INTERRUPT)

        request(current.amountRupees) { result ->
            when (result) {
                BiometricGate.Result.Confirmed -> {
                    engine.earcon(Earcon.UNDERSTOOD)
                    setPayment(current)
                    confirmPayment()
                }

                BiometricGate.Result.Unavailable -> {
                    // No fingerprint, face or screen lock exists on this phone. Refusing would
                    // strand the customer at the end of the ride, so pay — and say why.
                    speak(
                        "This phone has no fingerprint or screen lock, so I'll pay without one.",
                        NarrationTier.QUEUED
                    )
                    setPayment(current)
                    confirmPayment()
                }

                BiometricGate.Result.Cancelled -> {
                    setPayment(current)
                    speak(
                        "Payment not confirmed. Nothing was charged. Tap Pay when you're ready.",
                        NarrationTier.INTERRUPT
                    )
                }

                is BiometricGate.Result.Failed -> {
                    setPayment(current)
                    engine.earcon(Earcon.ERROR)
                    speak(
                        "The fingerprint check didn't work. Nothing was charged. Tap Pay to try again.",
                        NarrationTier.INTERRUPT
                    )
                }
            }
        }
    }

    /** Sends the payment to the gateway and speaks what the gateway decided. */
    private fun confirmPayment() {
        val current = uiState.payment ?: return

        // Razorpay flow: open the checkout URL in the browser. The Razorpay checkout
        // page handles the actual payment (UPI, card, netbanking). The order watcher
        // detects the result.
        if (current.checkoutUrl.isNotBlank() && current.checkoutUrl.contains("/pay/")) {
            setPayment(current.copy(
                phase = PaymentPhase.PROCESSING,
                message = "Opening Razorpay checkout…"
            ))
            speak("Opening payment page. Complete the payment there.", NarrationTier.INTERRUPT)
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(current.checkoutUrl)
                )
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<android.app.Application>().startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "PAYMENT could not open checkout URL", e)
                setPayment(current.copy(message = "Could not open payment page"))
                speak("Could not open the payment page. Try again.", NarrationTier.INTERRUPT)
                return
            }
            // Keep watching — the watcher will detect when Razorpay settles the order.
            watchOrder(current.orderId)
            return
        }

        // Sandbox mock flow: call the simulate endpoint directly.
        setPayment(current.copy(phase = PaymentPhase.PROCESSING, message = "Processing payment…"))
        speak("Processing.", NarrationTier.INTERRUPT)

        viewModelScope.launch {
            when (val result = api.simulatePayment(current.orderId, success = true, method = current.method)) {
                is ApiResult.Ok -> {
                    val order = result.value
                    when (order.status) {
                        PaymentOrderStatus.PAID -> onOrderPaid(order.amountRupees, order.bankRef, order.method)
                        PaymentOrderStatus.FAILED, PaymentOrderStatus.EXPIRED ->
                            onOrderFailed(order.amountRupees, order.failureReason)
                        else -> Unit // the watcher will settle it
                    }
                }

                is ApiResult.Failed -> {
                    // The order may have been paid or expired meanwhile; the server's refusal
                    // says which. Otherwise put the confirm panel back.
                    val order = (api.paymentOrder(current.orderId) as? ApiResult.Ok<PaymentOrder>)?.value
                    when (order?.status) {
                        PaymentOrderStatus.PAID -> onOrderPaid(order.amountRupees, order.bankRef, order.method)
                        PaymentOrderStatus.FAILED, PaymentOrderStatus.EXPIRED ->
                            onOrderFailed(order.amountRupees, order.failureReason)
                        else -> {
                            setPayment(current)
                            engine.earcon(Earcon.ERROR)
                            speak("${result.spoken} Nothing was charged. Tap Pay to try again.",
                                NarrationTier.INTERRUPT)
                        }
                    }
                }
            }
        }
    }

    /**
     * Watches one order until it is final. Silent while waiting, and ends in exactly one
     * sentence. Also how a payment made from the driver's QR code reaches this customer.
     */
    private fun watchOrder(orderId: String) {
        paymentPollJob?.cancel()
        paymentPollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + PAYMENT_WATCH_TIMEOUT_MS

            while (System.currentTimeMillis() < deadline) {
                delay(PAYMENT_POLL_INTERVAL_MS)
                val order = (api.paymentOrder(orderId) as? ApiResult.Ok<PaymentOrder>)?.value ?: continue

                when (order.status) {
                    PaymentOrderStatus.PAID -> {
                        onOrderPaid(order.amountRupees, order.bankRef, order.method)
                        return@launch
                    }
                    PaymentOrderStatus.FAILED, PaymentOrderStatus.EXPIRED -> {
                        onOrderFailed(order.amountRupees, order.failureReason)
                        return@launch
                    }
                    else -> Unit
                }
            }

            if (uiState.payment?.phase == PaymentPhase.AWAITING) {
                speak("The payment is still waiting. Tap Pay when you're ready.", NarrationTier.QUEUED)
            }
        }
    }

    private fun onOrderPaid(amountRupees: Int, bankRef: String, method: String) {
        // Both the confirm call and the watcher can see PAID; say it once.
        if (uiState.payment?.phase == PaymentPhase.PAID) return
        paymentPollJob?.cancel()
        setPayment(
            PaymentUi(
                phase = PaymentPhase.PAID,
                amountRupees = amountRupees,
                method = method,
                bankRef = bankRef,
                message = "Payment received"
            )
        )
        engine.earcon(Earcon.UNDERSTOOD)
        // Paid is the end of this ride's business; the receipt leads straight into the optional
        // feedback question and then the next journey — never to a dead end.
        speak(receiptSentence(amountRupees, bankRef), NarrationTier.INTERRUPT) {
            if (uiState.ride is RiderState.Done) startFeedback()
        }
    }

    private fun onOrderFailed(amountRupees: Int, failureReason: String) {
        if (uiState.payment?.phase == PaymentPhase.FAILED) return
        paymentPollJob?.cancel()
        val reason = failureReason.ifBlank { "The payment was declined" }
        setPayment(PaymentUi(PaymentPhase.FAILED, amountRupees, message = reason))
        engine.earcon(Earcon.ERROR)
        speak(
            "That payment didn't go through. ${reason.trimEnd('.')}. No money was taken. " +
                "Tap Try again, or say pay.",
            NarrationTier.INTERRUPT
        )
    }

    private fun receiptSentence(amountRupees: Int, bankRef: String): String =
        if (bankRef.length >= 4) {
            "Payment of $amountRupees rupees received. Reference ending ${bankRef.lastFourSpoken()}. Thank you."
        } else {
            "Payment of $amountRupees rupees received. Thank you."
        }

    private fun spokenMethod(method: String): String = when (method) {
        "CARD" -> "card"
        "NETBANKING" -> "net banking"
        else -> "UPI"
    }

    private fun setPayment(payment: PaymentUi?) {
        uiState = uiState.copy(payment = payment)
    }

    private fun isPaymentSpeech(text: String): Boolean {
        val t = text.lowercase()
        return listOf(PAYMENT_WORDS, DECLINE_WORDS, CARD_WORDS, NETBANKING_WORDS, UPI_WORDS)
            .any { it.containsMatchIn(t) }
    }

    /**
     * Voice on the Done screen. Returns true when the words were about payment and were
     * handled, so they are never parsed as a new destination.
     */
    private fun handlePaymentSpeech(text: String): Boolean {
        val t = text.lowercase()
        val awaiting = uiState.payment?.phase == PaymentPhase.AWAITING

        if (awaiting && DECLINE_WORDS.containsMatchIn(t)) {
            onDeclinePayment()
            return true
        }

        val method = when {
            CARD_WORDS.containsMatchIn(t) -> "CARD"
            NETBANKING_WORDS.containsMatchIn(t) -> "NETBANKING"
            UPI_WORDS.containsMatchIn(t) -> "UPI"
            else -> null
        }
        val wantsPay = PAYMENT_WORDS.containsMatchIn(t)

        if (awaiting && method != null) {
            val current = uiState.payment ?: return true
            setPayment(current.copy(method = method))
            if (wantsPay) authorizeAndPay() else speak("Paying by ${spokenMethod(method)}.", NarrationTier.INTERRUPT)
            return true
        }
        if (wantsPay) {
            onPayTapped()
            return true
        }
        return false
    }

    /**
     * The rider's UPI app came back (the "UPI APP" button).
     *
     * What a UPI app returns is a claim from another app on this phone, so it is relayed to the
     * server as REPORTED and never spoken as "paid". The order watcher — already running —
     * speaks the real outcome when the gateway settles it. A FAILURE claim is believed at once:
     * a false failure costs one retry; a false success costs an unpaid fare nobody notices.
     *
     * @param response the raw `response` extra, e.g. `txnId=..&Status=SUCCESS&txnRef=..`,
     *   or null when no UPI app handled the request
     */
    fun onPaymentResult(response: String?) {
        val upi = UpiResponse.parse(response)
        Log.i(TAG, "PAYMENT upi-app status=\"${upi.status}\" ref=\"${upi.txnRef}\"")
        val rideId = activeRideId ?: lastCompletedRideId

        when (upi.status) {
            "FAILURE", "FAILED" -> {
                engine.earcon(Earcon.ERROR)
                speak(
                    "Your UPI app says the payment didn't go through. You can tap Pay now instead.",
                    NarrationTier.INTERRUPT
                )
            }
            "SUCCESS" -> speak(
                "Your UPI app reported success. I'm waiting for the gateway to confirm it.",
                NarrationTier.QUEUED
            )
            "SUBMITTED" -> speak("Payment submitted. I'm checking.", NarrationTier.QUEUED)
            else -> {
                speak(
                    "I didn't get a response from the UPI app. Nothing has been charged as far " +
                        "as I can tell. You can tap Pay now instead.",
                    NarrationTier.QUEUED
                )
                return
            }
        }

        if (rideId == null) {
            Log.w(TAG, "PAYMENT no ride id to report against")
            return
        }

        viewModelScope.launch {
            api.reportPayment(rideId, upi.status.ifBlank { "SUBMITTED" }, upi.txnRef)
        }
        // Keep (or restart) watching the open order: the gateway is the only source of "paid".
        val open = uiState.payment
        if (open != null && open.phase == PaymentPhase.AWAITING && paymentPollJob?.isActive != true) {
            watchOrder(open.orderId)
        }
    }

    /** The rider (or a helper) tapped "Code is right" instead of letting the app hear it. */
    fun onCodeConfirmed() {
        trace?.tapCount = (trace?.tapCount ?: 0) + 1
        settleBoardingCode()
        stt?.cancel()
        closeMic()
        engine.earcon(Earcon.UNDERSTOOD)
        val backAtStop = codeForStop || uiState.ride is RiderState.InTrip
        codeForStop = false
        speak(if (backAtStop) "Confirmed. Welcome back." else "Confirmed. Have a good trip.", NarrationTier.QUEUED)
        activeRideId?.let { rideId ->
            viewModelScope.launch { api.confirmCode(rideId, matched = true) }
        }
    }

    /** Triggered when the rider or helper taps "Verify Boarding Code" or "Repeat Code". */
    fun onVerifyBoardingCode() {
        if (uiState.ride is RiderState.Arrived) {
            uiState = uiState.copy(
                ride = (uiState.ride as RiderState.Arrived).copy(verificationFailed = false)
            )
            openMic(MicPurpose.CODE_VERIFY)
        }
    }

    /** Tapped "Call Driver" or requested driver phone / vehicle details. */
    fun onCallDriver() {
        val driver = currentDriver()
        if (driver == null) {
            speak("No driver assigned yet.", NarrationTier.QUEUED)
        } else {
            speak("${driver.name}, phone ${driver.phoneNumber}.", NarrationTier.QUEUED)
        }
    }

    /**
     * A button on the "Walks & my places" screen: runs exactly what saying [text] would.
     *
     * The walking routes and the mind map are voice features, and as voice-only features they
     * could not be found. These buttons make them visible and touchable; the dialogue that
     * follows is the same one, answered by holding the screen.
     *
     * A [text] starting with "say:" is a spoken hint instead (for commands that need the rider's
     * own words, such as the name of a place to remember).
     */
    fun onQuickCommand(text: String) {
        stt?.cancel()
        closeMic()
        engine.stopSpeaking()
        clearAwaiting()
        if (text.startsWith("say:")) {
            speak(text.removePrefix("say:").trim(), NarrationTier.QUEUED)
            return
        }
        if (uiState.ride !is RiderState.Idle || walkMode != WalkMode.NONE) {
            speak("That's available when no ride or walk is in progress.", NarrationTier.QUEUED)
            return
        }
        Log.i(TAG, "QUICK command \"$text\"")
        micPurpose = MicPurpose.BOOKING
        handleTranscript(text)
    }

    // =================================================================================
    //  The mind map screen: everything remembered, by module, and what it can do
    // =================================================================================

    var catalog by mutableStateOf<com.cabeye.rider.mindmap.MindMapCatalog.Catalog?>(null)
        private set

    /** The last thing the mind map screen said (shown as well as spoken). */
    var mapAnswer by mutableStateOf("")
        private set

    /**
     * Whether the mind map screen is showing. Opened by its button in the top bar, or by pressing
     * volume down three times quickly (the same again closes it); see MainActivity.
     */
    var mindMapOpen by mutableStateOf(false)
        private set

    /** Opens the mind map from the main screen; anywhere else it says why not. */
    fun openMindMap() {
        if (mindMapOpen) return
        if (uiState.ride !is RiderState.Idle || activeRideId != null) {
            speak("Your mind map opens from the main screen, when no ride is being booked.", NarrationTier.QUEUED)
            return
        }
        // Explore covers the whole screen: finish it (keeping the walk) so the map is what shows.
        if (explore.ui.open) closeExplore(save = true)
        stt?.cancel()
        closeMic()
        clearAwaiting()
        mindMapOpen = true
        speak("Your mind map. Press volume down three times to close it.", NarrationTier.INTERRUPT)
    }

    fun closeMindMap() {
        mindMapOpen = false
    }

    /** Volume down pressed three times quickly. */
    fun toggleMindMap() {
        if (mindMapOpen) {
            closeMindMap()
            speak("Mind map closed.", NarrationTier.INTERRUPT)
        } else {
            openMindMap()
        }
    }

    /**
     * The main screen double-tapped (from idle): explore. The first tap of the two was an
     * ordinary press and opened the microphone for a booking; it is dropped here, silently,
     * before it can hear anything — its own stop is not due for [MIN_CAPTURE_BEFORE_STOP_MS].
     */
    fun onDoubleTap() {
        stt?.cancel()
        closeMic()
        if (uiState.ride is RiderState.Listening && activeRideId == null) transition(RiderState.Idle, announce = false)
        openExplore()
    }

    /** Builds what the mind map screen shows; ride places are fetched fresh from the server too. */
    fun loadMindMap() {
        mapAnswer = ""
        rebuildCatalog()
        app.memory.refresh { onMain { rebuildCatalog() } }
    }

    private fun rebuildCatalog() {
        viewModelScope.launch {
            val here = runCatching { riderPosition() }.getOrNull()
            catalog = com.cabeye.rider.mindmap.MindMapCatalog.build(
                map = mindMap(),
                places = app.memory.current.places,
                routes = app.memory.current.routes,
                walks = walkStore.routes(),
                profile = walkStore.profile(),
                here = here,
                now = System.currentTimeMillis()
            )
        }
    }

    private fun sayOnMap(text: String) {
        mapAnswer = text
        engine.stopSpeaking()
        speak(text, NarrationTier.INTERRUPT)
    }

    /** "What's around" / "Hear" on a card. */
    fun explainNode(nodeId: String) {
        val map = mindMap()
        val node = map.node(nodeId) ?: return
        sayOnMap(com.cabeye.rider.mindmap.MindMapAgent.about(map, node, System.currentTimeMillis()))
    }

    /** "Book a ride" on a place card: straight into the usual booking (announcement, cancel window). */
    fun bookFromMap(card: com.cabeye.rider.mindmap.MindMapCatalog.PlaceCard): Boolean {
        if (activeRideId != null || uiState.ride !is RiderState.Idle) {
            sayOnMap("You already have a ride going. Finish or cancel it first.")
            return false
        }
        val lat = card.lat
        val lng = card.lng
        if (lat == null || lng == null) {
            sayOnMap("I don't know exactly where ${card.name} is yet, so I can't book it from here. Hold the screen and say take me to ${card.name}.")
            return false
        }
        stt?.cancel()
        closeMic()
        clearAwaiting()
        engine.stopSpeaking()
        trace = Telemetry.RideTrace(rideId = "local").also { it.tapCount++ }
        val placeId = card.placeId.takeUnless { it.startsWith("a:") || it.isBlank() }.orEmpty()
        beginOptimisticBooking(PlaceOption(card.name, lat, lng, 1f, placeId = placeId), preferredRideType())
        return true
    }

    /** "Book this route" on a saved multi-stop route. */
    fun bookRouteFromMap(name: String): Boolean {
        if (activeRideId != null || uiState.ride !is RiderState.Idle) {
            sayOnMap("You already have a ride going. Finish or cancel it first.")
            return false
        }
        onQuickCommand("take me on $name")
        return true
    }

    /** "Forget" on any card: from its own module's store (ride places from the server too). */
    fun forgetNode(nodeId: String) {
        val label = mindMap().node(nodeId)?.label ?: "that"
        if (nodeId.startsWith("place:")) {
            val key = nodeId.removePrefix("place:")
            viewModelScope.launch {
                api.forgetPlace(key)
                app.memory.refresh { onMain { rebuildCatalog() } }
            }
        }
        mindMapStore.saveAuthored(mindMapStore.authored().without(nodeId))
        sayOnMap("Forgotten: $label.")
        rebuildCatalog()
    }

    fun guideWalkFromMap(routeId: String): Boolean {
        val route = walkStore.routes().firstOrNull { it.id == routeId } ?: return false
        onQuickCommand("guide me to ${route.name}")
        return true
    }

    fun readWalkFromMap(routeId: String) {
        val route = walkStore.routes().firstOrNull { it.id == routeId } ?: return
        val now = System.currentTimeMillis()
        val level = WalkNarrator.levelFor(route, walkStore.profile())
        sayOnMap(WalkNarrator.narrate(route, level, now, com.cabeye.rider.mindmap.MindMapAgent.notesFor(mindMap(), route.id)))
    }

    fun forgetWalkFromMap(routeId: String) {
        val route = walkStore.routes().firstOrNull { it.id == routeId } ?: return
        walkStore.delete(routeId)
        sayOnMap("Forgotten the walk ${route.name}.")
        rebuildCatalog()
    }

    // =================================================================================
    //  Explore module (no ride): the camera, the rider's street, into the mind map
    // =================================================================================

    /** Opens explore; [then] is what to do first (for "find the door" said at the main question). */
    fun openExplore(then: com.cabeye.rider.explore.ExploreCommands.Command? = null) {
        if (activeRideId != null || isRideUnderway(uiState.ride)) {
            speak("Explore is for when you're not on a ride. Your ride's camera can be shared with your driver instead.", NarrationTier.QUEUED)
            return
        }
        if (!hasCameraPermission()) {
            val request = cameraPermissionRequest ?: return
            awaitingExplorePermission = true
            speak("Your phone will ask to use the camera, so I can look around for you. Choose Allow.", NarrationTier.INTERRUPT) { request() }
            return
        }
        stt?.cancel()
        closeMic()
        clearAwaiting()
        explore.open()
        if (then != null) runExplore(then)
    }

    fun exploreDescribe() = explore.describe()
    fun exploreRead() = explore.describe(read = true)
    fun exploreKeepTelling() = explore.continuous()
    fun exploreFind(target: String) = explore.find(target)
    fun exploreStop() = explore.stopLooking()
    fun exploreName(name: String) = explore.nameThisPlace(name)

    /** "Name this place" with no name yet: the rider holds the screen and says it. */
    fun exploreAskName() {
        speak("Hold the screen and say: this is, and then the name. For example, this is Ravi's tea shop.", NarrationTier.INTERRUPT)
    }

    fun closeExplore(save: Boolean) {
        stt?.cancel()
        closeMic()
        explore.close(save)
    }

    /** The live preview on the rider's own screen (explore, and the shared ride camera). */
    fun attachCameraPreview(provider: androidx.camera.core.Preview.SurfaceProvider?) {
        cameraStreamer.attachPreview(provider)
    }

    private fun handleExploreSpeech(text: String) {
        val cmd = com.cabeye.rider.explore.ExploreCommands.parse(text)
        if (cmd == null) {
            speak("Say describe, keep telling me, find the door, read this, this is and a name, or finish.", NarrationTier.QUEUED)
            return
        }
        runExplore(cmd)
    }

    private fun runExplore(cmd: com.cabeye.rider.explore.ExploreCommands.Command) {
        when (cmd) {
            com.cabeye.rider.explore.ExploreCommands.Command.Describe -> explore.describe()
            com.cabeye.rider.explore.ExploreCommands.Command.Read -> explore.describe(read = true)
            com.cabeye.rider.explore.ExploreCommands.Command.KeepTelling -> explore.continuous(on = true)
            com.cabeye.rider.explore.ExploreCommands.Command.Stop -> {
                explore.stopLooking()
                speak("Stopped.", NarrationTier.INTERRUPT)
            }
            is com.cabeye.rider.explore.ExploreCommands.Command.Find -> explore.find(cmd.target)
            is com.cabeye.rider.explore.ExploreCommands.Command.Name -> explore.nameThisPlace(cmd.name)
            com.cabeye.rider.explore.ExploreCommands.Command.Finish -> closeExplore(save = true)
            com.cabeye.rider.explore.ExploreCommands.Command.Discard -> {
                closeExplore(save = false)
                speak("Closed, without saving the walk. What the camera saw is still in your mind map.", NarrationTier.QUEUED)
            }
        }
    }

    // ---- Ride module: the shared camera also tells the rider what's around ------------

    private fun startRideVision() {
        rideVisionJob?.cancel()
        val recent = LinkedHashMap<String, Long>()
        rideVisionJob = viewModelScope.launch {
            // "Camera on…" is said first.
            delay(4_000)
            while (uiState.camera == CameraShare.LIVE) {
                val picture = frameTap.next() ?: continue
                val scene = runCatching { sceneDetector.quick(picture) }.getOrElse { com.cabeye.rider.vision.Scene() }
                picture.recycle()
                rideLabels = scene.objects
                if (uiState.camera != CameraShare.LIVE) break
                uiState = uiState.copy(cameraSeen = scene.objects)
                val now = System.currentTimeMillis()
                recent.entries.removeAll { now - it.value > 15_000L }
                val change = com.cabeye.rider.vision.SceneSpeech.changes(scene, recent.keys)
                scene.objects.forEach { recent[it.label.lowercase()] = now }
                if (change != null && !engine.isSpeaking && !uiState.micOpen) speak(change, NarrationTier.QUEUED)
                delay(1_500)
            }
        }
    }

    /** Tapped "Help" from idle or status. */
    fun onHelp() {
        speakHelp()
    }

    // =================================================================================
    //  Input from the surface
    // =================================================================================

    /** Rider pressed the surface, or a volume key. Opens the microphone. */
    fun onHoldStart() {
        // Already listening to this press (never stack two sessions).
        if (uiState.micOpen && stt?.isListening == true) return

        // Exploring: a press talks to the explore module ("find the door", "this is …").
        if (explore.ui.open) {
            clearAwaiting()
            engine.stopSpeaking()
            if (stt?.isListening == true) stt?.cancel()
            openMic(MicPurpose.EXPLORE, byPress = true)
            return
        }

        // A question is waiting for its answer: this press answers it.
        val waiting = awaitingAnswer
        clearAwaiting()
        if (waiting != null && uiState.ride::class == waiting.second && stillAsking(waiting.first)) {
            trace?.tapCount = (trace?.tapCount ?: 0) + 1
            engine.stopSpeaking()
            if (stt?.isListening == true) stt?.cancel()
            openMic(waiting.first, byPress = true)
            return
        }

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
        // With the camera question open or the camera on, a press means "let me answer" or
        // "let me stop it" — the mic opens for camera words only, so nothing can be misheard
        // as a booking. At the kerb (Arrived) the code listen below already understands them.
        if (uiState.camera != CameraShare.OFF && isRideUnderway(uiState.ride)) {
            trace?.tapCount = (trace?.tapCount ?: 0) + 1
            engine.stopSpeaking()
            if (stt?.isListening == true) stt?.cancel()
            openMic(MicPurpose.CAMERA, byPress = true)
            return
        }

        // In the cab: a press is "I'm back" at a WAIT stop, otherwise it opens the microphone for
        // ride words only — stop commands, "where am I", a cue ("speed bump"), a note — never
        // for a new booking. Saying nothing still answers "where are we".
        val trip = uiState.ride as? RiderState.InTrip
        if (trip != null) {
            trace?.tapCount = (trace?.tapCount ?: 0) + 1
            engine.stopSpeaking()
            if (stt?.isListening == true) stt?.cancel()
            val stop = currentStop()
            if (stop != null && stop.status == "WAITING" && !stop.riderBack) {
                listenForStopCode("Welcome back. Ask the driver to say the code.")
            } else {
                openMic(MicPurpose.TRIP_COMMAND, byPress = true)
            }
            return
        }

        if (isRideUnderway(uiState.ride)) {
            trace?.tapCount = (trace?.tapCount ?: 0) + 1
            engine.stopSpeaking()
            engine.earcon(Earcon.UNDERSTOOD)
            speakStatus()
            return
        }

        // Walking: a press is "let me tell you something" — a landmark, next, I'm there.
        if (uiState.ride is RiderState.Walking || walkMode != WalkMode.NONE) {
            engine.stopSpeaking()
            if (stt?.isListening == true) stt?.cancel()
            openMic(MicPurpose.WALK, byPress = true)
            return
        }

        // Planning a route: a press answers the question on screen.
        if (uiState.ride is RiderState.Planning) {
            engine.stopSpeaking()
            if (stt?.isListening == true) stt?.cancel()
            when {
                pendingRoutine != null -> openMic(MicPurpose.MEMORY_ANSWER, byPress = true)
                planStage == PlanStage.REVIEW -> openMic(MicPurpose.PLAN_REVIEW, byPress = true)
                planStage == PlanStage.KINDS -> openMic(MicPurpose.STOP_KIND, byPress = true)
                planStage == PlanStage.COLLECTING -> openMic(MicPurpose.PLAN_LEG, byPress = true)
                else -> openMic(MicPurpose.SLOT_ANSWER, byPress = true)
            }
            return
        }

        // Remembered so that saying "pay" from here can come back to this screen.
        doneBeforeListening = uiState.ride as? RiderState.Done

        trace = Telemetry.RideTrace(rideId = activeRideId ?: "local").also { it.tapCount++ }

        consecutiveSpeechErrors = 0
        failureCount = 0
        pendingNearMiss = null
        pendingOptions = null
        heldRideType = null
        pendingSpokenAs = ""
        pendingMemory = null
        pendingMemoryHeard = ""
        rejectedMemoryKeys.clear()
        memoryRescueUsed = false

        engine.stopSpeaking()
        listPlacesJob?.cancel()
        cancelWindowJob?.cancel()

        if (stt?.isListening == true) stt?.cancel()

        // While the driver is at the kerb, a press means "I want to check the code", not "book
        // me a new ride". Interpreting it as a fresh booking here would be the app ignoring the
        // most important thing happening to the rider.
        val purpose = when (uiState.ride) {
            is RiderState.Arrived -> MicPurpose.CODE_VERIFY
            // A press during the post-ride questions answers the question on screen.
            is RiderState.Feedback -> MicPurpose.FEEDBACK
            is RiderState.NextJourney -> MicPurpose.NEXT_JOURNEY
            else -> MicPurpose.BOOKING
        }
        openMic(purpose, byPress = true)
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
        // In the cab the ride screen stays up while the mic is open, so the check below would
        // ignore this release. On an ordinary ride a quick tap is "where are we", answered at
        // once as it always was; a hold is talking ("where am I", "speed bump"), and letting go
        // ends it, as at the main question.
        if (micPurpose == MicPurpose.TRIP_COMMAND && uiState.micOpen && uiState.ride is RiderState.InTrip &&
            activeStops.isEmpty()
        ) {
            val openedAt = micOpenedAtMillis
            val elapsed = System.currentTimeMillis() - openedAt
            when {
                elapsed < TAP_MS -> {
                    stt?.cancel()
                    closeMic()
                    engine.earcon(Earcon.UNDERSTOOD)
                    speakStatus()
                }
                elapsed >= MIN_CAPTURE_BEFORE_STOP_MS -> stt?.stop()
                else -> viewModelScope.launch {
                    delay(MIN_CAPTURE_BEFORE_STOP_MS - elapsed)
                    if (micOpenedAtMillis == openedAt) stt?.stop()
                }
            }
            return
        }
        if (!uiState.micOpen) return
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
        pendingOptions = null
        engine.stopSpeaking()
        stt?.cancel()
        closeMic()
        beginOptimisticBooking(option, heldRideType ?: RideType.AUTO)
    }

    /** Sighted-helper fallback for the near-miss question. */
    fun onNearMissAnswer(accepted: Boolean) {
        trace?.tapCount = (trace?.tapCount ?: 0) + 1
        engine.stopSpeaking()
        stt?.cancel()
        closeMic()
        if (pendingMemory != null) {
            if (accepted) acceptMemory() else rejectMemory()
            return
        }
        if (accepted) acceptNearMiss() else rejectNearMiss()
    }

    fun onCancel() {
        if (walkMode == WalkMode.RECORDING || guideRecording) {
            WalkRecordingService.stop(getApplication())
            WalkRecorder.finish()
        }
        resetWalk()
        resetPlan()
        bookingPlan = null
        pendingRoutine = null
        pendingTripAction = null
        addingStopMidTrip = null
        cancelWindowJob?.cancel()
        silenceJob?.cancel()
        demoRideJob?.cancel()
        // A simulated ride has no server ride to end: its guide stops here, keeping nothing.
        if (activeRideId == null) stopRideGuide(save = false)
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

    /**
     * Opens the microphone — but only for a press.
     *
     * Rider mode never listens on its own: found in use, an app that opened the microphone
     * after each question heard its own voice, background talk and the street, and acted on
     * them (a ride cancelled itself on hearing "say cancel to stop"). So a question asked out
     * loud is remembered ([awaitPress]) and the rider answers it by holding the screen or a
     * volume key, for as long as they are speaking.
     */
    private fun openMic(purpose: MicPurpose, byPress: Boolean = false) {
        // Any real open supersedes a pending delayed one, including the rider's own press.
        micReopenJob?.cancel()

        if (!byPress) {
            awaitPress(purpose)
            return
        }

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

        // A question that has been overtaken does not get to listen. Found on device: "did you
        // mean A or B?" was answered by a tap, the booking announcement began, and the old
        // question's "listen when I finish" opened the mic over it, which heard the app say
        // "say cancel to stop" and cancelled the ride.
        if (!stillAsking(purpose)) {
            Log.i(TAG, "MIC not opened for $purpose: that question is no longer open")
            return
        }

        micPurpose = purpose

        // Two states must stay on screen through their own open microphone: `confirming`, where
        // the countdown is the information, and `arrived`, where the rider is being asked to
        // wait for the driver to speak. Moving either to `Listening` would replace the thing
        // the rider needs with a generic listening screen.
        // The post-ride questions stay on screen too: their step lives in the state itself.
        if (purpose != MicPurpose.CANCEL_WINDOW && purpose != MicPurpose.CODE_VERIFY &&
            purpose != MicPurpose.FEEDBACK && purpose != MicPurpose.NEXT_JOURNEY &&
            purpose != MicPurpose.CAMERA && purpose != MicPurpose.TRIP_COMMAND &&
            purpose != MicPurpose.STOP_CONFIRM && purpose != MicPurpose.WALK && purpose != MicPurpose.NEARBY &&
            purpose != MicPurpose.INTERPRET && purpose != MicPurpose.EXPLORE &&
            !planPurpose(purpose)
        ) {
            transition(
                RiderState.Listening(isFollowUp = purpose != MicPurpose.BOOKING),
                announce = false
            )
        }

        uiState = uiState.copy(micOpen = true)
        lastPartial = ""
        micOpenedAtMillis = System.currentTimeMillis()
        recogniser.start(speechListener, listenOptionsFor(purpose))
        // Held: a pause does not end the answer; letting go does.
        recogniser.setHeld(true)
        startSilenceWatch(purpose)
    }

    /** The question waiting for a press to answer it, and the screen it was asked on. */
    private var awaitingAnswer: Pair<MicPurpose, kotlin.reflect.KClass<out RiderState>>? = null
    private var pressWaitJob: Job? = null

    /**
     * A question has been asked: wait for the rider to press to answer it. The screen says so,
     * and a question with a natural default (feedback, the camera, "anything else?") takes it
     * if no press comes, so nothing is left hanging.
     */
    private fun awaitPress(purpose: MicPurpose) {
        if (!stillAsking(purpose)) {
            Log.i(TAG, "MIC no press awaited for $purpose: that question is no longer open")
            return
        }
        // "Understanding your request" must not stay up while the app waits for an answer.
        if (uiState.ride is RiderState.Resolving) {
            transition(RiderState.Listening(isFollowUp = purpose != MicPurpose.BOOKING), announce = false)
        }
        awaitingAnswer = purpose to uiState.ride::class
        uiState = uiState.copy(holdToAnswer = true)
        Log.i(TAG, "MIC waiting for a press to answer $purpose")

        pressWaitJob?.cancel()
        val timeout = when (purpose) {
            MicPurpose.CAMERA -> CAMERA_ANSWER_MS
            MicPurpose.FEEDBACK, MicPurpose.NEXT_JOURNEY, MicPurpose.NEARBY, MicPurpose.INTERPRET -> NO_PRESS_DEFAULT_MS
            else -> return
        }
        pressWaitJob = viewModelScope.launch {
            delay(timeout)
            if (awaitingAnswer?.first != purpose || uiState.micOpen) return@launch
            clearAwaiting()
            Log.i(TAG, "NO_PRESS for $purpose after ${timeout}ms; taking the default")
            when (purpose) {
                MicPurpose.FEEDBACK -> finishFeedback(send = true, spokenThanks = false)
                MicPurpose.NEXT_JOURNEY -> finishPostRide("I'll wait. Hold anywhere when you need a ride.")
                MicPurpose.NEARBY -> endNearby()
                MicPurpose.INTERPRET -> interpretNoAnswer()
                MicPurpose.CAMERA -> if (uiState.camera == CameraShare.ASKING) declineCamera("NO_ANSWER")
                else -> Unit
            }
        }
    }

    private fun clearAwaiting() {
        awaitingAnswer = null
        pressWaitJob?.cancel()
        pressWaitJob = null
        if (uiState.holdToAnswer) uiState = uiState.copy(holdToAnswer = false)
    }

    /**
     * How patient the microphone is, per question.
     *
     * The recogniser keeps listening through pauses (see [ListenOptions]); what changes is how
     * long a pause ends the answer, and what counts as a complete answer that needs no pause
     * at all. A yes, a rating or a whole code ends the moment it is heard; a destination or a
     * spoken report waits for the rider to stop, so a "take me to… uh… RS Puram" is not cut
     * at the "uh".
     */
    private fun listenOptionsFor(purpose: MicPurpose): ListenOptions {
        val yesNo: (String) -> Boolean = { text ->
            val intent = Classifier.classify(text, rideActive = uiState.ride !is RiderState.Idle).intent
            intent is RiderIntent.Yes || intent is RiderIntent.No
        }
        return when (purpose) {
            MicPurpose.CODE_VERIFY -> {
                val expected = expectedCode.filter { it.isDigit() }
                ListenOptions(
                    // The driver may take a moment to reach the window and speak.
                    startTimeoutMs = CODE_LISTEN_MS,
                    endSilenceMs = 1_000L,
                    incompleteSilenceMs = 3_000L,
                    isComplete = { text ->
                        val heard = digitsFrom(text)
                        CameraConsent.answer(text) != null ||
                            (expected.isNotEmpty() && (heard.contains(expected) || heard.length >= expected.length))
                    }
                )
            }
            MicPurpose.CANCEL_WINDOW -> ListenOptions(
                startTimeoutMs = RiderState.CANCEL_WINDOW_MS + 1_000L,
                endSilenceMs = 800L
            )
            MicPurpose.NEAR_MISS_ANSWER, MicPurpose.MEMORY_ANSWER, MicPurpose.MEETING_ANSWER,
            MicPurpose.CONTACT_CONFIRM_ANSWER, MicPurpose.STOP_CONFIRM, MicPurpose.INTERPRET -> ListenOptions(
                endSilenceMs = 1_100L,
                isComplete = yesNo
            )
            MicPurpose.CAMERA -> ListenOptions(
                endSilenceMs = 1_000L,
                isComplete = { CameraConsent.answer(it) != null || CameraConsent.isStopCamera(it) }
            )
            MicPurpose.FEEDBACK -> when ((uiState.ride as? RiderState.Feedback)?.step) {
                FeedbackStep.RATING -> ListenOptions(
                    endSilenceMs = 1_200L,
                    isComplete = { FeedbackParser.intentOf(it) != FeedbackParser.Intent.NONE }
                )
                FeedbackStep.RATING_CHECK, FeedbackStep.CONFIRM -> ListenOptions(
                    endSilenceMs = 1_100L,
                    isComplete = { yesNo(it) || FeedbackParser.rating(it) != null || FeedbackParser.isSendCommand(it) }
                )
                // Saying what went wrong is a few sentences, with thinking between them.
                else -> ListenOptions(endSilenceMs = 2_200L, maxDurationMs = 60_000L)
            }
            else -> ListenOptions(endSilenceMs = 1_300L)
        }
    }

    /** False for a question the rider has already answered some other way, or that moved on. */
    private fun stillAsking(purpose: MicPurpose): Boolean = when (purpose) {
        MicPurpose.CLARIFY_ANSWER -> pendingOptions != null && uiState.ride is RiderState.Clarify
        MicPurpose.NEAR_MISS_ANSWER -> pendingNearMiss != null || pendingMemory != null
        MicPurpose.CANCEL_WINDOW -> uiState.ride is RiderState.Confirming
        else -> true
    }

    /**
     * True when [text] is the app's own voice coming back through the microphone: three or
     * more words that appear, in order, in something the app said in the last few seconds.
     * A rider's bare "cancel" is never mistaken for it; "say cancel to stop" always is.
     */
    private fun isEcho(text: String): Boolean {
        val words = echoWords(text)
        if (words.size < 3) return false
        val now = System.currentTimeMillis()
        return recentSpoken.any { (at, said) ->
            now - at < ECHO_WINDOW_MS && echoWords(said).windowed(words.size).any { it == words }
        }
    }

    private fun echoWords(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

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

            // Feedback is optional and the next-journey question is a courtesy: saying nothing
            // is an answer to both, and the app takes it rather than nagging.
            if (purpose == MicPurpose.FEEDBACK) {
                Log.i(TAG, "SILENCE on feedback — moving on")
                finishFeedback(send = true, spokenThanks = false)
                return@launch
            }
            if (purpose == MicPurpose.NEXT_JOURNEY) {
                Log.i(TAG, "SILENCE on next journey — finishing")
                finishPostRide("I'll wait. Hold anywhere when you need a ride.")
                return@launch
            }
            if (purpose == MicPurpose.WALK) {
                onWalkNoAnswer()
                return@launch
            }
            // Silence to the nearby-places offer is not a "no": it just ends.
            if (purpose == MicPurpose.NEARBY) {
                endNearby()
                return@launch
            }
            if (purpose == MicPurpose.INTERPRET) {
                interpretNoAnswer()
                return@launch
            }
            // Mid-ride stop words: silence changes nothing, and the ride screen stays. On an
            // ordinary ride a press with no words is still "where are we".
            if (purpose == MicPurpose.TRIP_COMMAND || purpose == MicPurpose.STOP_CONFIRM ||
                (purpose == MicPurpose.STOP_KIND && addingStopMidTrip != null)
            ) {
                pendingTripAction = null
                addingStopMidTrip = null
                if (purpose == MicPurpose.TRIP_COMMAND && activeStops.isEmpty() && uiState.ride is RiderState.InTrip) speakStatus()
                return@launch
            }
            // Silence to the camera question is a no; silence while it is on means nothing.
            if (purpose == MicPurpose.CAMERA) {
                if (uiState.camera == CameraShare.ASKING) declineCamera("NO_ANSWER")
                return@launch
            }

            if (!silenceRepromptUsed) {
                silenceRepromptUsed = true
                Log.i(TAG, "SILENCE timeoutMs=${RecoveryLadder.SILENCE_TIMEOUT_MS} action=REPROMPT")
                speak(RecoveryLadder.SILENCE_REPROMPT, NarrationTier.QUEUED) {
                    reopenMicAfterGap(purpose)
                }
            } else {
                Log.i(TAG, "SILENCE timeoutMs=${RecoveryLadder.SILENCE_TIMEOUT_MS} action=REST")
                silenceRepromptUsed = false
                resetPlan()
                pendingRoutine = null
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
            if (text.isNotBlank()) {
                startSilenceWatch(micPurpose)
                lastPartial = text
            }
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

        override fun onFinal(text: String) = onMain { finalTranscript(text) }

        // Several guesses: take the first one that fits the question being answered.
        override fun onFinalAlternatives(alternatives: List<String>) = onMain {
            if (alternatives.isEmpty()) return@onMain
            finalTranscript(chooseTranscript(alternatives))
        }

        override fun onError(error: SpeechError) = onMain {
            silenceJob?.cancel()
            closeMic()
            handleSpeechError(error)
        }
    }

    private fun finalTranscript(text: String) {
        // T1 — recogniser returned.
        trace?.mark(Telemetry.Stage.SPEECH_FINAL)
        silenceJob?.cancel()
        closeMic()
        if (isEcho(text)) {
            // The phone heard itself. Not the rider's answer: listen again for the real one.
            Log.w(TAG, "ECHO ignored heard=\"$text\" purpose=$micPurpose")
            reopenMicAfterGap(micPurpose)
            return
        }
        engine.earcon(Earcon.LISTENING_END)
        handleTranscript(text)
    }

    /**
     * The recogniser's guesses, best first; returns the first one that answers the question
     * the app actually asked. Asked for the boarding code, "zero three six" is worth more than
     * a higher-ranked "zero three sticks"; asked "yes or no", a "yes" in second place beats
     * "yet" in first. For free answers (a destination) the best guess stands.
     */
    private fun chooseTranscript(alternatives: List<String>): String {
        val best = alternatives.first()
        if (alternatives.size == 1) return best

        val camera = uiState.camera
        val yesNo: (String) -> Boolean = { text ->
            val intent = Classifier.classify(text, rideActive = uiState.ride !is RiderState.Idle).intent
            intent is RiderIntent.Yes || intent is RiderIntent.No
        }

        val chosen = when {
            camera != CameraShare.OFF && alternatives.any { CameraConsent.isStopCamera(it) } ->
                alternatives.first { CameraConsent.isStopCamera(it) }

            micPurpose == MicPurpose.CODE_VERIFY -> {
                val expected = expectedCode.filter { it.isDigit() }
                // Only the top three guesses may prove the code: the lower ones are the
                // recogniser reaching, and a reached-for match must not let a wrong car pass.
                alternatives.take(3).firstOrNull { expected.isNotEmpty() && digitsFrom(it).contains(expected) }
                    ?: alternatives.firstOrNull { camera == CameraShare.ASKING && CameraConsent.answer(it) != null }
                    ?: alternatives.firstOrNull { digitsFrom(it).isNotEmpty() }
            }

            micPurpose == MicPurpose.CAMERA ->
                alternatives.firstOrNull { CameraConsent.answer(it) != null }

            micPurpose == MicPurpose.STOP_KIND ->
                alternatives.firstOrNull { StopPlanParser.kindAnswer(it) != null }

            micPurpose == MicPurpose.TRIP_COMMAND ->
                alternatives.firstOrNull { StopPlanParser.tripCommand(it) != null || RideCommands.parse(it) != null }

            micPurpose == MicPurpose.PLAN_REVIEW ->
                alternatives.firstOrNull { StopPlanParser.planEdit(it) != null }

            micPurpose == MicPurpose.WALK && walkMode == WalkMode.RECORDING ->
                alternatives.firstOrNull { WalkCommands.record(it) != null }

            micPurpose == MicPurpose.WALK && walkMode == WalkMode.GUIDING ->
                alternatives.firstOrNull { WalkCommands.guide(it) != null }

            micPurpose == MicPurpose.FEEDBACK -> when ((uiState.ride as? RiderState.Feedback)?.step) {
                FeedbackStep.RATING -> alternatives.firstOrNull { FeedbackParser.intentOf(it) != FeedbackParser.Intent.NONE }
                FeedbackStep.RATING_CHECK -> alternatives.firstOrNull { yesNo(it) || FeedbackParser.rating(it) != null }
                FeedbackStep.CONFIRM -> alternatives.firstOrNull { yesNo(it) || FeedbackParser.isSendCommand(it) }
                else -> null
            }

            micPurpose == MicPurpose.NEAR_MISS_ANSWER || micPurpose == MicPurpose.MEMORY_ANSWER ||
                micPurpose == MicPurpose.MEETING_ANSWER || micPurpose == MicPurpose.CONTACT_CONFIRM_ANSWER ->
                alternatives.firstOrNull(yesNo)

            else -> null
        }

        if (chosen != null && chosen != best) {
            Log.i(TAG, "HEARD picked \"$chosen\" over \"$best\" for ${micPurpose.name}")
        }
        return chosen ?: best
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

        // Exploring: everything said is for the explore module.
        if (micPurpose == MicPurpose.EXPLORE) {
            handleExploreSpeech(text)
            return
        }

        // Camera words first: "stop camera" must never reach the classifier, where a leading
        // "stop" is the command that cancels the whole ride.
        if (handleCameraSpeech(text)) return

        // "What's in front of me", "explore", "find the door": the explore module, from the main
        // question, when no ride is under way.
        if (micPurpose == MicPurpose.BOOKING && uiState.ride is RiderState.Listening && activeRideId == null &&
            (com.cabeye.rider.explore.ExploreCommands.opens(text) || text.trim().lowercase().startsWith("find "))
        ) {
            transition(RiderState.Idle, announce = false)
            openExplore(then = com.cabeye.rider.explore.ExploreCommands.parse(text))
            return
        }

        // "Speak slower", "louder": only as a short command from the main question, so a
        // report like "the driver was going too fast" is never taken as a setting.
        if (micPurpose == MicPurpose.BOOKING && handleVoiceSettings(text)) return

        // The one purpose where the expected speaker is not the rider. Checked before anything
        // else, because a driver saying "four seven two" must not be parsed as a destination.
        if (micPurpose == MicPurpose.CODE_VERIFY) {
            verifyBoardingCode(text)
            return
        }

        // Multi-stop answers and in-ride stop words, before the classifier: "cancel stop two"
        // must change a stop, never cancel the ride.
        if (handleStopSpeech(text)) return

        // Held from the Done screen, "pay" means pay — not a destination called Pay. The mic
        // moved the screen to Listening, so the finished ride is restored before paying.
        val doneScreen = doneBeforeListening
        doneBeforeListening = null
        if (micPurpose == MicPurpose.BOOKING && doneScreen != null && isPaymentSpeech(text)) {
            transition(doneScreen, announce = false)
            if (!handlePaymentSpeech(text)) speak("Say pay to confirm, or decline.", NarrationTier.QUEUED)
            return
        }

        // Walking routes: "how do I get to the clinic", "record my walk", answers while walking.
        if (handleWalkSpeech(text)) return

        // The mind map: "what's around the clinic", "remember Ravi's tea shop, where I stop on
        // Fridays", "what's famous around here", and answers to the nearby-places offer.
        if (handleMindMapSpeech(text)) return

        val rideActive = uiState.ride !is RiderState.Idle
        val classification = Classifier.classify(text, rideActive)

        // T2 — intent extracted.
        trace?.mark(Telemetry.Stage.INTENT_PARSED)
        trace?.regexFastPathHit = classification.kind != UtteranceClass.UNKNOWN

        Log.i(TAG, "${classification.diagnostic} heard=\"$text\" micPurpose=${micPurpose.name}")

        // Cancel wins everywhere, and especially inside the cancel window. This is the one
        // promise the optimistic-booking design cannot break.
        // Free-text answers — a feedback report, a landmark, where a scheduled ride should go —
        // may mention cancelling ("he told me to cancel") without the rider meaning it. There,
        // only a short utterance ("cancel", "stop it") is the command.
        val freeText = micPurpose == MicPurpose.FEEDBACK || micPurpose == MicPurpose.NEXT_JOURNEY ||
            micPurpose == MicPurpose.LANDMARK_ANSWER
        val isShort = text.trim().split(Regex("\\s+")).size <= 3
        if (classification.intent is RiderIntent.Cancel && (!freeText || isShort)) {
            onCancel()
            return
        }

        // "go back" is honoured from every question the booking dialogue can ask — the
        // A-or-B question, "did you mean…?", the meeting-contact ladder and the cancel
        // window. It always lands on the same, previous step: "where would you like to go?".
        if (classification.intent is RiderIntent.Back) {
            goBack()
            return
        }

        // A route rather than a place: "pharmacy, then college", "I have a few stops",
        // or a saved route by name.
        if (handlePlanEntry(text)) return

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

            MicPurpose.MEMORY_ANSWER -> {
                resolveMemoryAnswer(classification, text)
                return
            }

            MicPurpose.FEEDBACK -> {
                resolveFeedback(classification, text)
                return
            }

            MicPurpose.NEXT_JOURNEY -> {
                resolveNextJourney(classification, text)
                return
            }

            MicPurpose.CODE_VERIFY -> return // handled above
            MicPurpose.CAMERA -> return // handled above
            MicPurpose.PLAN_LEG, MicPurpose.STOP_KIND, MicPurpose.PLAN_REVIEW,
            MicPurpose.TRIP_COMMAND, MicPurpose.STOP_CONFIRM, MicPurpose.WALK, MicPurpose.NEARBY,
            MicPurpose.INTERPRET -> return // handled above
            MicPurpose.EXPLORE -> return // handled above
            MicPurpose.BOOKING -> Unit
        }

        when (classification.kind) {
            UtteranceClass.GREETING -> handleGreeting()
            UtteranceClass.COMMAND ->
                // "simulate a ride to Koniamman Temple": the demo needs the words, not just the intent.
                if (classification.intent is RiderIntent.DemoRide) startDemoRide(text) else runCommand(classification.intent)
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
        pendingSpokenAs = rawPhrase
        transition(RiderState.Resolving(query), announce = false)

        // The rider's own words for a place they have been before — a learned alias like
        // "piece g", or the exact name — go straight to the normal booking announcement and
        // its cancel window, without a round trip to Places that might not understand them.
        if (app.memory.enabled) {
            val direct = PreferenceMemoryAgent.direct(rawPhrase, app.memory.current, memoryMoment())
            val option = direct?.place?.toOption()
            if (direct != null && option != null) {
                Log.i(TAG, "MEMORY direct heard=\"$rawPhrase\" place=\"${direct.place.name}\" sim=${direct.similarity}")
                heldRideType = rideType
                failureCount = 0
                engine.earcon(Earcon.UNDERSTOOD)
                beginOptimisticBooking(option, rideType, note = "one of your usual places")
                return
            }
        }
        heldRideType = rideType

        // A place the rider taught the app on the spot ("remember this as Ravi's tea shop"):
        // small shops and meeting points are often on no map, so the mind map is asked first.
        MindMapAgent.bookable(mindMap(), rawPhrase)?.let { n ->
            Log.i(TAG, "MINDMAP direct heard=\"$rawPhrase\" place=\"${n.label}\"")
            failureCount = 0
            engine.earcon(Earcon.UNDERSTOOD)
            beginOptimisticBooking(
                PlaceOption(name = n.label, latitude = n.lat!!, longitude = n.lng!!, score = 1f, coversWholeToken = true),
                rideType, note = "the place you told me about"
            )
            return
        }

        // Very short/empty queries should never spend a Places request. This also preserves the
        // original recovery behaviour for speech fragments such as "to".
        val substantive = Stopwords.strip(Gazetteer.normalise(rawPhrase))
        if (substantive.length < MatchGate.MIN_PHRASE_LENGTH) {
            handleResolvedCandidates(Gazetteer.score(query), rawPhrase, rideType, source = "local-short-query")
            return
        }

        viewModelScope.launch {
            val apiKeyConfigured = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()
            val location = app.locationProvider.current()
            pickupLatitude = location?.latitude
            pickupLongitude = location?.longitude
            val googleCandidates = if (apiKeyConfigured) {
                app.placeResolver.search(query, location).getOrNull().orEmpty()
            } else {
                emptyList()
            }

            // Google is the primary resolver when there is a key. Without one (no paid service),
            // or when it finds nothing: the built-in places, then the offline Coimbatore atlas —
            // real places from OpenStreetMap bundled in the app, scored by the same rules.
            val candidates = if (googleCandidates.isNotEmpty()) {
                googleCandidates
            } else {
                if (apiKeyConfigured) Log.w(TAG, "Places returned no usable result; falling back to Gazetteer")
                offlineCandidates(query, location?.latitude, location?.longitude)
            }

            handleResolvedCandidates(candidates, rawPhrase, rideType, source = if (googleCandidates.isNotEmpty()) "google" else "offline")
        }
    }

    /**
     * Destination search with no paid service: the hand-checked built-in places first (their
     * behaviour unchanged), then the bundled Coimbatore atlas. One place named in both is kept
     * once, as the built-in one.
     */
    private suspend fun offlineCandidates(query: String, lat: Double?, lng: Double?): List<PlaceOption> {
        val builtIn = Gazetteer.score(query)
        if (Gazetteer.activeCity != City.COIMBATORE) return builtIn
        val atlas = AtlasStore.get(getApplication())?.search(query, lat, lng).orEmpty()
            .filter { it.score >= Gazetteer.NEAR_MISS_FLOOR }
        val merged = builtIn + atlas.filter { a ->
            builtIn.none { b ->
                TextSimilarity.normalise(b.name) == TextSimilarity.normalise(a.name) &&
                    Gazetteer.distanceKm(a, b) < 1.5f
            }
        }
        return merged.sortedByDescending { it.score }
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
            is MatchGate.Decision.NearMiss -> {
                // Places is only half sure. If the rider's own history has a clearly better
                // candidate for these words, ask about that instead.
                val memory = memoryRepair(rawPhrase)
                if (memory != null && memory.confidence > decision.candidate.score &&
                    memory.place.placeId != decision.candidate.placeId
                ) {
                    askMemory(memory, heard = rawPhrase, prompt = repairPrompt(memory, heardClearly = true))
                } else {
                    askNearMiss(decision.candidate)
                }
            }
            is MatchGate.Decision.Reject -> {
                // Before the recovery ladder: does this sound like somewhere the rider has
                // been? "Brook fields" → "Did you mean Brookefields Mall, where you've been
                // three times?"
                val memory = memoryRepair(rawPhrase)
                if (memory != null) {
                    askMemory(memory, heard = rawPhrase, prompt = repairPrompt(memory, heardClearly = true))
                    return
                }
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
            // "Did you mean Peelamedu, or Peelamedu?" cannot be answered. Found on device, where
            // the offline map holds two places of that name. Same name: take the better match.
            val sameName = top.name.trim().equals(runnerUp.name.trim(), ignoreCase = true)
            val shouldAsk = !sameName && gap < RiderState.CONFIDENCE_DELTA && divergenceKm > RiderState.DIVERGENCE_KM

            Telemetry.logClarificationDecision(top.name, runnerUp.name, gap, divergenceKm, shouldAsk)

            if (shouldAsk) {
                trace?.clarificationTurns = (trace?.clarificationTurns ?: 0) + 1
                pendingOptions = top to runnerUp
                clarifyUnclear = 0
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
        if (top.isVague && chosenContact == null && chosenDropNote.isBlank() && planStage == null) {
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

    /**
     * "No" to "Did you mean Adyar?" goes back one step — ask for the place again — instead of
     * treating the rider's clear answer as a recognition failure. Only a second rejection in a
     * row escalates to the recovery ladder, which offers landmarks and the place list.
     */
    private fun rejectNearMiss() {
        val rejected = pendingNearMiss
        pendingNearMiss = null
        engine.earcon(Earcon.NOT_UNDERSTOOD)
        failureCount++
        if (failureCount >= 2) {
            failureCount-- // runLadder counts this failure itself
            runLadder(unrecognised = "")
            return
        }
        val which = rejected?.let { "not ${it.name}. " } ?: ""
        askDestinationAgain("Okay, ${which}Say the place again, or a nearby landmark.")
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

        val answer = ClarifyAnswer.interpret(text, a.name, b.name)
        Log.i(TAG, "CLARIFY answer=$answer heard=\"$text\"")

        when (answer) {
            ClarifyAnswer.Kind.A, ClarifyAnswer.Kind.B -> {
                val chosen = if (answer == ClarifyAnswer.Kind.A) a else b
                pendingOptions = null
                clarifyUnclear = 0
                failureCount = 0
                engine.earcon(Earcon.UNDERSTOOD)
                beginOptimisticBooking(chosen, heldRideType ?: RideType.AUTO)
            }

            // Back one step: the rider meant neither, so ask for the place again.
            ClarifyAnswer.Kind.NEITHER -> {
                pendingOptions = null
                clarifyUnclear = 0
                engine.earcon(Earcon.NOT_UNDERSTOOD)
                askDestinationAgain("Okay, neither. Say the place again, or a nearby landmark.")
            }

            ClarifyAnswer.Kind.WHICH -> speak("Which one: ${a.name}, or ${b.name}?", NarrationTier.QUEUED) {
                openMic(MicPurpose.CLARIFY_ANSWER)
            }

            ClarifyAnswer.Kind.UNCLEAR -> {
                if (clarifyUnclear == 0) {
                    clarifyUnclear++
                    speak("Sorry. ${a.name}, or ${b.name}? Or say neither.", NarrationTier.QUEUED) {
                        openMic(MicPurpose.CLARIFY_ANSWER)
                    }
                } else {
                    // Forward, not round in circles: twice not an option means the rider has
                    // most likely named a different place. Resolve what they said.
                    clarifyUnclear = 0
                    pendingOptions = null
                    val normalised = Stopwords.normalise(text)
                    resolveDestination(
                        query = Stopwords.strip(normalised),
                        rawPhrase = normalised,
                        rideType = heldRideType ?: RideType.AUTO
                    )
                }
            }
        }
    }

    /**
     * "go back" — return to the previous step of booking, which is always the destination
     * question. What the rider already settled (auto or cab) is kept; the half-asked question
     * and anything it had collected are dropped.
     */
    private fun goBack() {
        val ride = uiState.ride
        if (activeRideId != null || (isRideUnderway(ride) && ride !is RiderState.Done)) {
            speak("Your ride is already booked. Say cancel if you want to cancel it.", NarrationTier.QUEUED)
            return
        }

        // Inside the five-second window nothing has reached the server yet, so going back is
        // simply not booking — and the ride type the rider chose is carried back with them.
        val wasBooking = ride as? RiderState.Confirming
        cancelWindowJob?.cancel()
        if (wasBooking != null) heldRideType = wasBooking.rideType

        // A route: back to its read-back, not to the start.
        val confirmedPlan = bookingPlan
        if (wasBooking != null && confirmedPlan != null) {
            bookingPlan = null
            plan = confirmedPlan
            planRideType = wasBooking.rideType
            engine.earcon(Earcon.UNDERSTOOD)
            reviewPlan("Okay, not booking yet. ")
            return
        }
        // Mid-plan, inside one of the place questions: one step back within the plan.
        if (planStage != null) {
            pendingOptions = null
            pendingNearMiss = null
            pendingMemory = null
            planBack()
            return
        }
        pendingRoutine?.let { rejectedRoutines += it.signature }
        pendingRoutine = null

        pendingOptions = null
        pendingNearMiss = null
        pendingMemory?.let { rejectedMemoryKeys += it.place.placeKey }
        pendingMemory = null
        clarifyUnclear = 0
        pendingVaguePlace = null
        pendingVagueRideType = null
        meetingAsks = 0
        pendingContacts = emptyList()
        failureCount = 0
        uiState = uiState.copy(selectedDestination = null)
        engine.earcon(Earcon.UNDERSTOOD)
        Log.i(TAG, "BACK from=${ride::class.simpleName} purpose=${micPurpose.name}")

        askDestinationAgain(
            if (wasBooking != null) "Okay, not booking that. Where would you like to go?"
            else "Okay. Where would you like to go?"
        )
    }

    /** The previous step: ask for the destination, and listen for it. */
    private fun askDestinationAgain(prompt: String) {
        transition(RiderState.Idle, announce = false)
        speak(prompt, NarrationTier.QUEUED) { reopenMicAfterGap(MicPurpose.SLOT_ANSWER) }
    }

    // =================================================================================
    //  The Preference-Memory Agent
    //
    //  Three ways in, one way out:
    //    proactive  — idle, signed in, a habit for this moment    → "…like usual?"
    //    repair     — Places found nothing / was unsure / STT hazy → "Did you mean …?"
    //    direct     — the rider's own learned words for a place    → normal booking
    //  and every answer lands: yes → booking with its cancel window, no → the previous step
    //  ("where would you like to go?"), another place → that place.
    // =================================================================================

    private fun memoryMoment() = PreferenceMemoryAgent.Moment(
        now = System.currentTimeMillis(),
        zone = ZoneId.systemDefault(),
        pickupLatitude = pickupLatitude,
        pickupLongitude = pickupLongitude
    )

    private fun memoryThresholds() = PreferenceMemoryAgent.Thresholds.from(app.memory.current.stats)

    private fun memoryRepair(heard: String): PreferenceMemoryAgent.Suggestion? {
        if (!app.memory.enabled || heard.isBlank()) return null
        return PreferenceMemoryAgent.repair(
            heard, app.memory.current, memoryMoment(), memoryThresholds(), rejectedMemoryKeys
        )
    }

    private fun memoryGuess(): PreferenceMemoryAgent.Suggestion? {
        if (!app.memory.enabled) return null
        return PreferenceMemoryAgent.guessWhenUnheard(
            app.memory.current, memoryMoment(), memoryThresholds(), rejectedMemoryKeys
        )
    }

    private fun repairPrompt(s: PreferenceMemoryAgent.Suggestion, heardClearly: Boolean): String {
        val lead = if (heardClearly) "" else "I didn't catch that clearly. "
        return "${lead}Did you mean ${s.place.name}, ${s.reason}? Say yes, or no."
    }

    /**
     * Unprompted: "It's 8 40 AM. Going to PSG College, like most weekday mornings?"
     *
     * Offered only when nothing else is happening — idle, microphone closed, no ride — at most
     * once every [PROACTIVE_COOLDOWN_MS], and not at all once the rider has turned two down in
     * this session. A suggestion that interrupts is worse than none.
     */
    private fun offerProactive(trigger: String) {
        if (!app.memory.enabled || app.memory.current.isEmpty) return
        if (uiState.ride !is RiderState.Idle || uiState.micOpen || activeRideId != null) return
        if (proactiveDeclines >= MAX_PROACTIVE_DECLINES) return
        val now = System.currentTimeMillis()
        if (now - lastProactiveAt < PROACTIVE_COOLDOWN_MS) return

        viewModelScope.launch {
            // Where the rider is matters: no "college, like usual?" to someone at college.
            val location = runCatching { app.locationProvider.current() }.getOrNull()
            if (location != null) {
                pickupLatitude = location.latitude
                pickupLongitude = location.longitude
            }
            val moment = memoryMoment()
            val suggestion = PreferenceMemoryAgent.proactive(
                app.memory.current, moment, memoryThresholds(), rejectedMemoryKeys
            )
            val routine = RoutineAgent.proactive(app.memory.current, moment, memoryThresholds(), rejectedRoutines)
            // Re-check: the rider may have started talking while the location was fetched.
            if (uiState.ride !is RiderState.Idle || uiState.micOpen || activeRideId != null) return@launch
            if (RoutineAgent.preferRoutine(routine, suggestion)) {
                lastProactiveAt = System.currentTimeMillis()
                askRoutine(routine!!, moment)
                return@launch
            }
            if (suggestion == null) return@launch

            lastProactiveAt = System.currentTimeMillis()
            Log.i(TAG, "MEMORY proactive trigger=$trigger place=\"${suggestion.place.name}\" " +
                "confidence=${suggestion.confidence} threshold=${memoryThresholds().proactive}")
            askMemory(
                suggestion,
                heard = "",
                prompt = "It's ${PreferenceMemoryAgent.spokenTime(moment)}. Going to ${suggestion.place.name}, " +
                    "${suggestion.reason}? Say yes, or tell me another place."
            )
        }
    }

    private fun askMemory(s: PreferenceMemoryAgent.Suggestion, heard: String, prompt: String) {
        pendingMemory = s
        pendingMemoryHeard = heard
        pendingNearMiss = null
        pendingOptions = null
        val option = s.place.toOption() ?: PlaceOption(s.place.name, 0.0, 0.0, s.confidence)
        // The near-miss screen already is "Did you mean X? Yes / No" — the same question.
        transition(RiderState.Clarify(option, option, 0f, 0f, isNearMiss = true), announce = false)
        engine.earcon(Earcon.UNDERSTOOD)
        speak(prompt, NarrationTier.QUEUED) { openMic(MicPurpose.MEMORY_ANSWER) }
    }

    private fun resolveMemoryAnswer(classification: Classification, raw: String) {
        val routine = pendingRoutine
        if (routine != null) {
            when (classification.intent) {
                is RiderIntent.Yes -> acceptRoutine()
                is RiderIntent.No -> rejectRoutine()
                else -> {
                    // Another place entirely: an implicit no to the route, and a new request.
                    pendingRoutine = null
                    rejectedRoutines += routine.signature
                    proactiveDeclines++
                    app.memory.reportOutcome(routine.destinationKey, SuggestionKind.PROACTIVE, accepted = false, heard = "")
                    if (!handlePlanEntry(raw)) resolveSlotAnswer(classification, raw)
                }
            }
            return
        }
        when (classification.intent) {
            is RiderIntent.Yes -> acceptMemory()
            is RiderIntent.No -> rejectMemory()
            else -> {
                // Another place entirely: an implicit "no" to the suggestion, and a new request.
                val s = pendingMemory
                if (s != null) {
                    app.memory.reportOutcome(s.place.placeKey, s.kind, accepted = false, heard = pendingMemoryHeard)
                    rejectedMemoryKeys += s.place.placeKey
                    if (s.kind == SuggestionKind.PROACTIVE) proactiveDeclines++
                }
                pendingMemory = null
                resolveSlotAnswer(classification, raw)
            }
        }
    }

    private fun acceptMemory() {
        val s = pendingMemory ?: return
        pendingMemory = null
        failureCount = 0
        // The yes is evidence twice over: the suggestion was right, and — for a repair — the
        // misheard words are now this rider's name for the place.
        app.memory.reportOutcome(s.place.placeKey, s.kind, accepted = true, heard = pendingMemoryHeard)
        if (s.kind == SuggestionKind.REPAIR && pendingMemoryHeard.isNotBlank()) pendingSpokenAs = pendingMemoryHeard
        Log.i(TAG, "MEMORY accepted kind=${s.kind} place=\"${s.place.name}\"")
        engine.earcon(Earcon.UNDERSTOOD)

        val rideType = heldRideType ?: preferredRideType()
        val option = s.place.toOption()
        if (option != null) {
            beginOptimisticBooking(option, rideType)
        } else {
            // A remembered place without coordinates: look its name up like any other.
            resolveDestination(s.place.name, s.place.name, rideType)
        }
    }

    /** "No" — back one step, and this place is not offered again in this booking. */
    private fun rejectMemory() {
        val s = pendingMemory ?: return
        pendingMemory = null
        app.memory.reportOutcome(s.place.placeKey, s.kind, accepted = false, heard = pendingMemoryHeard)
        rejectedMemoryKeys += s.place.placeKey
        Log.i(TAG, "MEMORY rejected kind=${s.kind} place=\"${s.place.name}\"")
        engine.earcon(Earcon.NOT_UNDERSTOOD)
        if (s.kind == SuggestionKind.PROACTIVE) {
            proactiveDeclines++
            askDestinationAgain("Okay. Where would you like to go?")
        } else {
            askDestinationAgain("Okay, not ${s.place.name}. Say the place again, or a nearby landmark.")
        }
    }

    /** "my places": the three most visited, then listen for a choice. */
    private fun speakMyPlaces() {
        if (!app.memory.enabled) {
            speak("I only remember your places when you're signed in. Say sign in to set that up.",
                NarrationTier.QUEUED) { openMic(MicPurpose.BOOKING) }
            return
        }
        val top = app.memory.current.places.take(3)
        if (top.isEmpty()) {
            speak("You haven't finished a ride with me yet, so I don't have any places for you. " +
                "Where would you like to go?", NarrationTier.QUEUED) { openMic(MicPurpose.BOOKING) }
            return
        }
        val names = when (top.size) {
            1 -> top[0].name
            2 -> "${top[0].name}, and ${top[1].name}"
            else -> "${top[0].name}, ${top[1].name}, and ${top[2].name}"
        }
        speak("Your usual places are $names. Say one of them, or anywhere else.", NarrationTier.QUEUED) {
            openMic(MicPurpose.BOOKING)
        }
    }

    private fun forgetHistory() {
        // The way the rider rode is ride history too, kept on this phone for the ride guide.
        mindMapStore.clearTraces()
        if (!app.memory.enabled) {
            speak("I'm not keeping any history for you.", NarrationTier.QUEUED)
            return
        }
        app.memory.forgetAll { ok ->
            onMain {
                speak(
                    if (ok) "Done. I've forgotten your places and past trips."
                    else "I've forgotten them on this phone. I couldn't reach the server, so I'll need to try again later.",
                    NarrationTier.QUEUED
                )
            }
        }
    }

    private fun preferredRideType(): RideType =
        runCatching { RideType.valueOf(app.riderAccount.value?.rider?.preferredRideType ?: "AUTO") }
            .getOrDefault(RideType.AUTO)

    private fun VisitedPlace.toOption(): PlaceOption? {
        val lat = latitude ?: return null
        val lng = longitude ?: return null
        return PlaceOption(
            name = name,
            latitude = lat,
            longitude = lng,
            score = 1f,
            coversWholeToken = true,
            formattedAddress = address,
            placeId = placeId
        )
    }

    // =================================================================================
    //  Multi-stop rides — planned by voice, Uber/Rapido style
    //
    //   "pharmacy, then Gandhipuram, then home"  ─▶ each place looked up in turn, through the
    //        same resolver, clarify and memory-repair questions as any booking
    //   ─▶ "At the pharmacy, will you get out and come back, or…?"  (only where not said)
    //   ─▶ numbered read-back ─▶ edits ("remove stop two", "swap one and two", "add a stop",
    //        "save this as Monday errands") ─▶ yes ─▶ the normal booking and cancel window
    //
    //  During the ride every stop is announced; at a WAIT stop the rider presses (or says
    //  "I'm back") and the phone hears the boarding code again — the car cannot leave until
    //  it does. "Skip the next stop", "what are my stops", "add a stop at …" work mid-ride.
    // =================================================================================

    private enum class PlanStage { COLLECTING, RESOLVING, KINDS, REVIEW }

    /** Something said mid-ride that needs a yes before it happens. */
    private sealed interface TripAction {
        data class Skip(val stop: StopInfo) : TripAction
        data class Add(val leg: PlannedLeg) : TripAction
    }

    private var plan: TripPlan? = null
    private var planStage: PlanStage? = null
    private var planLegIndex = 0
    private var planRideType: RideType = RideType.AUTO
    private var planMisses = 0
    private var planHintsGiven = false
    private val collectedLegs = mutableListOf<PlannedLeg>()
    private var collectingDestination = false
    /** The confirmed plan, between "yes" and the server accepting it. */
    private var bookingPlan: TripPlan? = null
    /** Set when the plan came from a saved route, so its use is counted. */
    private var planFromRoute: SavedRoute? = null

    /** The ride's stops as the server last reported them. */
    private var activeStops: List<StopInfo> = emptyList()
    /** The boarding-code listen is for a rider getting back in at a WAIT stop. */
    private var codeForStop = false
    private var pendingTripAction: TripAction? = null
    /** Mid-ride "add a stop": the place is found, its kind is being asked. */
    private var addingStopMidTrip: PlannedLeg? = null

    private var pendingRoutine: RoutineAgent.Routine? = null
    private val rejectedRoutines = mutableSetOf<String>()

    private fun planPurpose(p: MicPurpose) =
        p == MicPurpose.PLAN_LEG || p == MicPurpose.STOP_KIND || p == MicPurpose.PLAN_REVIEW

    /**
     * Multi-stop speech that must be heard before the classifier: answers to the planning
     * questions, and stop commands during the ride. @return true when the words were handled.
     */
    private fun handleStopSpeech(text: String): Boolean {
        val shortCancel = com.cabeye.rider.intent.IntentParser.CANCEL.containsMatchIn(text) && text.trim().split(Regex("\\s+")).size <= 2
        when (micPurpose) {
            MicPurpose.PLAN_LEG, MicPurpose.STOP_KIND, MicPurpose.PLAN_REVIEW -> {
                if (addingStopMidTrip != null && (shortCancel ||
                        Classifier.classify(text, true).intent is RiderIntent.Back)
                ) {
                    // Mid-ride: "cancel" drops the stop being added, never the ride.
                    addingStopMidTrip = null
                    speak("Okay, no change.", NarrationTier.QUEUED)
                    return true
                }
                if (shortCancel) { onCancel(); return true }
                if (Classifier.classify(text, false).intent is RiderIntent.Back) { planBack(); return true }
                when (micPurpose) {
                    MicPurpose.PLAN_LEG -> resolveCollect(text)
                    MicPurpose.STOP_KIND -> if (addingStopMidTrip != null) resolveMidTripKind(text) else resolveStopKind(text)
                    else -> resolvePlanReview(text)
                }
                return true
            }
            MicPurpose.TRIP_COMMAND -> { handleTripCommand(text); return true }
            MicPurpose.STOP_CONFIRM -> { resolveTripAction(text); return true }
            else -> return false
        }
    }

    /**
     * A new booking that is really a route: a saved route by name, several places in one
     * breath, or "I have a few stops". @return true when it started a plan.
     */
    private fun handlePlanEntry(text: String): Boolean {
        if (micPurpose != MicPurpose.BOOKING && micPurpose != MicPurpose.SLOT_ANSWER) return false
        if (activeRideId != null || planStage != null || uiState.ride is RiderState.Confirming) return false
        if (app.memory.enabled) {
            RoutineAgent.named(text, app.memory.current.routes)?.let { startPlanFromRoute(it); return true }
        }
        if (StopPlanParser.tooManyStops(text)) {
            engine.earcon(Earcon.NOT_UNDERSTOOD)
            speak("A ride can have up to ${TripPlan.MAX_STOPS} stops before the destination. " +
                "Say your stops again, then where you finish.", NarrationTier.QUEUED) { openMic(MicPurpose.BOOKING) }
            return true
        }
        StopPlanParser.parseTrip(text)?.let { parsed ->
            val type = if (parsed.rideTypeWasExplicit) parsed.rideType else heldRideType ?: preferredRideType()
            startPlan(parsed.toPlan(), type)
            return true
        }
        if (StopPlanParser.isPlanStart(text)) {
            startCollecting()
            return true
        }
        return false
    }

    // ---- Step by step: "I have a few stops" ---------------------------------------------

    private fun startCollecting() {
        resetPlan()
        planStage = PlanStage.COLLECTING
        planRideType = heldRideType ?: preferredRideType()
        engine.earcon(Earcon.UNDERSTOOD)
        showPlan("Where is your first stop?")
        speak("Okay. Where is your first stop?", NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_LEG) }
    }

    private fun resolveCollect(text: String) {
        if (StopPlanParser.isDoneAdding(text) && !collectingDestination) {
            if (collectedLegs.isEmpty()) {
                speak("You haven't said a stop yet. Where is your first stop?", NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_LEG) }
            } else {
                collectingDestination = true
                showPlan("Where do you finish?")
                speak("And where do you finish?", NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_LEG) }
            }
            return
        }
        val leg = StopPlanParser.parseLeg(text)
        if (leg == null) {
            planMisses++
            if (planMisses >= 3) { resetPlan(); runLadder(unrecognised = ""); return }
            speak("I didn't catch a place. Say it again.", NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_LEG) }
            return
        }
        planMisses = 0
        engine.earcon(Earcon.UNDERSTOOD)
        if (collectingDestination) {
            startPlan(TripPlan(collectedLegs.toList(), leg.copy(kind = null)), planRideType, intro = "Got it.")
            return
        }
        collectedLegs += leg
        if (collectedLegs.size >= TripPlan.MAX_STOPS) {
            collectingDestination = true
            showPlan("Where do you finish?")
            speak("That's ${TripPlan.MAX_STOPS} stops, the most a ride can have. Where do you finish?", NarrationTier.QUEUED) {
                openMic(MicPurpose.PLAN_LEG)
            }
        } else {
            showPlan("Next stop, or say that's all.")
            speak("Next stop? Or say that's all.", NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_LEG) }
        }
    }

    // ---- Resolving each place, then the kinds, then the read-back ------------------------

    private fun startPlan(p: TripPlan, rideType: RideType, intro: String? = null) {
        val keepRoute = planFromRoute
        resetPlan()
        planFromRoute = keepRoute
        plan = p
        planRideType = rideType
        planStage = PlanStage.RESOLVING
        engine.earcon(Earcon.UNDERSTOOD)
        Log.i(TAG, "PLAN start stops=${p.stops.size} destination=\"${p.destination.query}\"")
        val n = p.stops.size
        val line = intro ?: "$n ${if (n == 1) "stop" else "stops"}, then ${p.destination.name}."
        speak(line, NarrationTier.QUEUED) { resolveNextLeg() }
    }

    private fun resolveNextLeg() {
        val p = plan ?: return
        val idx = p.nextUnresolved
        if (idx == null) { askNextKind(); return }
        planLegIndex = idx
        planStage = PlanStage.RESOLVING
        val leg = p.legs[idx]
        resolveDestination(leg.query, leg.spoken, planRideType)
    }

    private fun legLabel(idx: Int, p: TripPlan) =
        if (idx < p.stops.size) "Stop ${TripPlan.ordinalWord(idx + 1)}" else "Destination"

    /** Called instead of booking while a plan is being resolved: the place belongs to a leg. */
    private fun onPlanLegResolved(place: PlaceOption) {
        val p = plan ?: return
        val idx = planLegIndex.coerceIn(0, p.legs.size - 1)
        val leg = p.legs[idx]
        plan = p.withLeg(idx, leg.copy(place = place, spoken = pendingSpokenAs.ifBlank { leg.spoken }))
        pendingSpokenAs = ""
        failureCount = 0
        pendingMemory = null
        engine.earcon(Earcon.UNDERSTOOD)
        showPlan("${legLabel(idx, p)}: ${place.name}")
        speak("${legLabel(idx, p)}: ${place.name}.", NarrationTier.QUEUED) { resolveNextLeg() }
    }

    private fun askNextKind() {
        val p = plan ?: return
        val i = p.nextWithoutKind
        if (i == null) { reviewPlan(); return }
        planStage = PlanStage.KINDS
        planLegIndex = i
        showPlan("At ${p.stops[i].name}: wait, drop off, or pick up?")
        speak(
            "At ${p.stops[i].name}, will you get out and come back, so the driver waits? " +
                "Or are you dropping someone off, or picking someone up?",
            NarrationTier.QUEUED
        ) { openMic(MicPurpose.STOP_KIND) }
    }

    private fun resolveStopKind(text: String) {
        val p = plan ?: return
        val i = p.nextWithoutKind ?: run { reviewPlan(); return }
        var kind = StopPlanParser.kindAnswer(text)
        var said = ""
        if (kind == null) {
            planMisses++
            if (planMisses < 2) {
                speak("Say wait, drop off, or pick up.", NarrationTier.QUEUED) { openMic(MicPurpose.STOP_KIND) }
                return
            }
            // Never guessed in the unsafe direction: a WAIT stop keeps the car until the rider's
            // phone hears the code again, so nobody can be left behind.
            kind = StopKind.WAIT
            said = "I'll ask the driver to wait for you there. "
        }
        planMisses = 0
        plan = p.withLeg(i, p.stops[i].copy(kind = kind))
        engine.earcon(Earcon.UNDERSTOOD)
        if (said.isNotEmpty()) speak(said, NarrationTier.QUEUED) { askNextKind() } else askNextKind()
    }

    private fun reviewPlan(lead: String = "") {
        val p = plan ?: return
        planStage = PlanStage.REVIEW
        planMisses = 0
        val hint = if (!planHintsGiven) {
            planHintsGiven = true
            " Say yes to book it. Or change it: remove stop two, swap one and two, add a stop, or save it with a name."
        } else " Shall I book it?"
        showPlan("Book this trip?")
        speak("$lead${p.readBack()}$hint", NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_REVIEW) }
    }

    private fun resolvePlanReview(text: String) {
        val p = plan ?: return
        when (val edit = StopPlanParser.planEdit(text)) {
            null -> when (Classifier.classify(text, false).intent) {
                is RiderIntent.Yes -> bookPlan()
                is RiderIntent.No -> {
                    showPlan("What would you like to change?")
                    speak("What would you like to change? Say remove, swap, add a stop, or cancel.", NarrationTier.QUEUED) {
                        openMic(MicPurpose.PLAN_REVIEW)
                    }
                }
                else -> {
                    planMisses++
                    if (planMisses >= 3) { onCancel(); return }
                    speak("Say yes to book it, or tell me what to change.", NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_REVIEW) }
                }
            }
            is StopPlanParser.PlanEdit.Remove -> {
                val n = when {
                    edit.stop == -1 -> p.stops.size
                    edit.stop != null -> edit.stop
                    else -> p.stopNamed(edit.words) ?: -99
                }
                applyEdit(if (n == -99) TripPlan.Edit.Refused("I couldn't tell which stop. Say remove stop one, two or three.") else p.remove(n))
            }
            is StopPlanParser.PlanEdit.Swap -> applyEdit(p.swap(resolveN(edit.a, p), resolveN(edit.b, p)))
            is StopPlanParser.PlanEdit.SetKind -> applyEdit(p.setKind(resolveN(edit.stop, p), edit.kind))
            is StopPlanParser.PlanEdit.Change -> {
                val n = resolveN(edit.stop, p)
                if (n !in 1..p.stops.size) applyEdit(p.remove(n))
                else applyEdit(TripPlan.Edit.Ok(p.withLeg(n - 1, edit.leg.copy(kind = edit.leg.kind ?: p.stops[n - 1].kind, note = edit.leg.note.ifBlank { p.stops[n - 1].note })), ""))
            }
            is StopPlanParser.PlanEdit.Add -> applyEdit(p.add(edit.leg, edit.afterStop?.let { resolveN(it, p) }))
            is StopPlanParser.PlanEdit.ChangeDestination -> applyEdit(TripPlan.Edit.Ok(p.copy(destination = edit.leg), ""))
            is StopPlanParser.PlanEdit.SaveAs -> saveRouteFromPlan(edit.name)
            StopPlanParser.PlanEdit.ReadAgain -> reviewPlan()
        }
    }

    private fun resolveN(n: Int, p: TripPlan) = if (n == -1) p.stops.size else n

    private fun applyEdit(result: TripPlan.Edit) {
        when (result) {
            is TripPlan.Edit.Refused -> speak(result.sentence, NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_REVIEW) }
            is TripPlan.Edit.Ok -> {
                plan = result.plan
                engine.earcon(Earcon.UNDERSTOOD)
                val lead = if (result.said.isNotBlank()) result.said + " " else ""
                when {
                    result.plan.nextUnresolved != null -> speak(lead.ifBlank { "Okay." }, NarrationTier.QUEUED) { resolveNextLeg() }
                    result.plan.nextWithoutKind != null -> speak(lead.ifBlank { "Okay." }, NarrationTier.QUEUED) { askNextKind() }
                    else -> reviewPlan(lead)
                }
            }
        }
    }

    private fun bookPlan() {
        val p = plan ?: return
        val dest = p.destination.place ?: run { resolveNextLeg(); return }
        if (!p.isComplete) { resolveNextLeg(); return }
        val type = planRideType
        val route = planFromRoute
        bookingPlan = p
        resetPlan()
        route?.let { app.memory.routeUsed(it.name) }
        pendingSpokenAs = p.destination.spoken
        Log.i(TAG, "PLAN book stops=${p.stops.size} destination=\"${dest.name}\"")
        beginOptimisticBooking(dest, type)
    }

    /** "Go back" while planning: one step, never the whole plan. */
    private fun planBack() {
        val p = plan
        when {
            planStage == PlanStage.COLLECTING -> {
                if (collectingDestination) collectingDestination = false
                else if (collectedLegs.isNotEmpty()) collectedLegs.removeAt(collectedLegs.size - 1)
                speak(if (collectedLegs.isEmpty()) "Okay. Where is your first stop?" else "Okay. Next stop, or say that's all.",
                    NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_LEG) }
            }
            p != null && planStage == PlanStage.RESOLVING -> {
                speak("Okay. Say ${legLabel(planLegIndex, p).lowercase()} again.", NarrationTier.QUEUED) {
                    openMic(MicPurpose.SLOT_ANSWER)
                }
            }
            p != null && p.isComplete -> reviewPlan()
            else -> { resetPlan(); askDestinationAgain("Okay. Where would you like to go?") }
        }
    }

    /** Forgets the plan being built (not a booking already confirmed). */
    private fun resetPlan() {
        plan = null
        planStage = null
        planLegIndex = 0
        planMisses = 0
        collectedLegs.clear()
        collectingDestination = false
        planFromRoute = null
    }

    private fun showPlan(prompt: String) {
        val p = plan
        val lines = when {
            p != null -> p.stops.mapIndexed { i, s ->
                "${i + 1}. ${s.name}" + (s.kind?.let { " — ${it.driverLabel}" } ?: "")
            } + "Then ${p.destination.name}"
            else -> collectedLegs.mapIndexed { i, s -> "${i + 1}. ${s.name}" }
        }
        transition(RiderState.Planning(lines, prompt), announce = false)
    }

    // ---- Saved routes and learned routines ----------------------------------------------

    private fun startPlanFromRoute(route: SavedRoute) {
        fun leg(r: RouteLeg) = PlannedLeg(
            query = r.name, spoken = r.name,
            kind = r.kind.takeIf { it.isNotBlank() }?.let { StopKind.parse(it) },
            note = r.note,
            place = if (r.latitude != null && r.longitude != null)
                PlaceOption(r.name, r.latitude, r.longitude, 1f, coversWholeToken = true,
                    formattedAddress = r.address, placeId = r.placeId) else null
        )
        val p = TripPlan(route.stops.map(::leg), leg(route.destination).copy(kind = null))
        planFromRoute = route
        Log.i(TAG, "MEMORY named route \"${route.name}\"")
        startPlan(p, runCatching { RideType.valueOf(route.rideType) }.getOrDefault(preferredRideType()), intro = "${route.name}.")
    }

    private fun saveRouteFromPlan(name: String) {
        val p = plan ?: return
        if (!app.memory.enabled) {
            speak("I can only save routes when you're signed in. Shall I book it?", NarrationTier.QUEUED) { openMic(MicPurpose.PLAN_REVIEW) }
            return
        }
        if (!p.isComplete) {
            speak("Let's finish the plan first.", NarrationTier.QUEUED) { resolveNextLeg() }
            return
        }
        fun leg(l: PlannedLeg, kind: StopKind?) = RouteLeg(
            name = l.name, address = l.place?.formattedAddress.orEmpty(),
            latitude = l.place?.latitude, longitude = l.place?.longitude, placeId = l.place?.placeId.orEmpty(),
            kind = kind?.name.orEmpty(), note = l.note
        )
        val route = SavedRoute(
            name = name, key = TripPlan.norm(name),
            stops = p.stops.map { leg(it, it.kind) }, destination = leg(p.destination, null),
            rideType = planRideType.name
        )
        app.memory.saveRoute(route) { refused ->
            onMain {
                speak(refused ?: "Saved as $name. Next time just say book $name. Shall I book it now?", NarrationTier.QUEUED) {
                    openMic(MicPurpose.PLAN_REVIEW)
                }
            }
        }
    }

    /** "It's 8 40 AM. Your usual route, like most Monday mornings: the pharmacy, then college." */
    private fun askRoutine(r: RoutineAgent.Routine, moment: PreferenceMemoryAgent.Moment) {
        pendingRoutine = r
        pendingMemory = null
        engine.earcon(Earcon.UNDERSTOOD)
        Log.i(TAG, "MEMORY routine \"${r.spokenRoute()}\" support=${r.support} confidence=${r.confidence}")
        transition(RiderState.Planning(r.stops.mapIndexed { i, s -> "${i + 1}. ${s.name}" } + "Then ${r.destinationName}",
            "Your usual route?"), announce = false)
        speak(
            "It's ${PreferenceMemoryAgent.spokenTime(moment)}. Your usual route, ${r.reason}: ${r.spokenRoute()}. " +
                "Shall I plan it? Say yes, or tell me another place.",
            NarrationTier.QUEUED
        ) { openMic(MicPurpose.MEMORY_ANSWER) }
    }

    private fun acceptRoutine() {
        val r = pendingRoutine ?: return
        pendingRoutine = null
        app.memory.reportOutcome(r.destinationKey, SuggestionKind.PROACTIVE, accepted = true, heard = "")
        val places = app.memory.current.places
        fun leg(key: String, name: String, kind: StopKind?) = PlannedLeg(
            query = name, spoken = name, kind = kind,
            place = places.firstOrNull { it.placeKey == key }?.toOption()
        )
        val p = TripPlan(
            stops = r.stops.map { leg(it.placeKey, it.name, StopKind.parse(it.kind)) },
            destination = leg(r.destinationKey, r.destinationName, null)
        )
        startPlan(p, heldRideType ?: preferredRideType(), intro = "Okay.")
    }

    private fun rejectRoutine() {
        val r = pendingRoutine ?: return
        pendingRoutine = null
        rejectedRoutines += r.signature
        proactiveDeclines++
        app.memory.reportOutcome(r.destinationKey, SuggestionKind.PROACTIVE, accepted = false, heard = "")
        engine.earcon(Earcon.NOT_UNDERSTOOD)
        askDestinationAgain("Okay. Where would you like to go?")
    }

    // ---- During the ride ----------------------------------------------------------------

    private fun refreshStops(rideId: String) {
        viewModelScope.launch {
            val result = api.snapshot(rideId)
            if (result is ApiResult.Ok && rideId == activeRideId) setStops(result.value.stops)
        }
    }

    private fun setStops(stops: List<StopInfo>) {
        activeStops = stops
        val ride = uiState.ride
        if (ride is RiderState.InTrip) uiState = uiState.copy(ride = ride.copy(stops = stops))
    }

    private fun currentStop(): StopInfo? = activeStops.firstOrNull { it.isOpen }

    private fun nextLegLine(event: RideEvent): String {
        val next = event.payload.optJSONObject("next") ?: return ""
        val name = next.optString("name")
        return if (next.optString("type") == "STOP")
            "Next, stop ${TripPlan.ordinalWord(next.optInt("index"))}, $name."
        else "Next, $name."
    }

    private fun onStopEvent(event: RideEvent) {
        val name = event.string("name", "the stop")
        val index = TripPlan.ordinalWord(event.int("index", 1))
        val note = event.string("note")
        activeRideId?.let(::refreshStops)
        when (event.type) {
            RideEventType.STOP_ARRIVED -> when (event.string("kind")) {
                "WAIT" -> {
                    val minutes = (event.int("waitLimitSeconds", 600) + 59) / 60
                    narrate(event, "Stop $index, $name. Your driver will wait up to $minutes minutes. " +
                        "When you're back at the car, press the screen and let the driver say the code.")
                }
                "PICKUP" -> narrate(event, "Stop $index, $name. Picking up ${note.ifBlank { "your companion" }}.")
                else -> narrate(event, "Stop $index, $name. ${note.ifBlank { "Your companion" }.replaceFirstChar { it.uppercase() }} can get out here.")
            }
            RideEventType.STOP_DONE -> narrate(event, "Leaving $name. ${nextLegLine(event)}")
            RideEventType.STOP_SKIPPED -> narrate(event, "Skipped $name. ${nextLegLine(event)}")
            RideEventType.STOPS_CHANGED -> narrate(event, "Your stops are updated. ${nextLegLine(event)}")
            RideEventType.WAIT_WARNING -> {
                if (currentStop()?.riderBack == true) return
                val left = event.int("secondsLeft", 60)
                narrate(event, if (left <= 60) "One minute left. Your driver is waiting at $name."
                else "${(left + 59) / 60} minutes left before your driver needs you back at $name.")
            }
            RideEventType.WAIT_OVERDUE -> narrate(event,
                "Your driver has been waiting a long time at $name. Cab Eye support has been told and may call you. " +
                    "If you need help, press S O S.")
            // This phone's own confirmation; already spoken when the code was heard.
            else -> Unit
        }
    }

    /** Mid-ride words: only stop commands mean anything; anything else answers "where are we". */
    private fun handleTripCommand(text: String) {
        when (val cmd = StopPlanParser.tripCommand(text)) {
            StopPlanParser.TripCommand.ImBack -> {
                val stop = currentStop()
                if (stop?.status == "WAITING" && !stop.riderBack) listenForStopCode("Welcome back. Ask the driver to say the code.")
                else speak("You're already on the way.", NarrationTier.QUEUED)
            }
            is StopPlanParser.TripCommand.Skip -> {
                val target = if (cmd.stop == null) currentStop()
                else activeStops.firstOrNull { it.index == (if (cmd.stop == -1) activeStops.lastOrNull { s -> s.isOpen }?.index else cmd.stop) && it.isOpen }
                when {
                    target == null -> speak("There are no more stops on this ride.", NarrationTier.QUEUED)
                    target.status == "WAITING" -> speak("You're at that stop now. Get back in and the driver will move on.", NarrationTier.QUEUED)
                    else -> {
                        pendingTripAction = TripAction.Skip(target)
                        speak("Skip ${target.name}? Say yes or no.", NarrationTier.QUEUED) { openMic(MicPurpose.STOP_CONFIRM) }
                    }
                }
            }
            StopPlanParser.TripCommand.ReadStops -> speak(remainingStopsLine(), NarrationTier.QUEUED)
            is StopPlanParser.TripCommand.AddStop -> addStopMidTrip(cmd.leg)
            // In the car: "tell me the walk" previews the walk from the drop-off; "where am I",
            // a cue the rider marks, or a note go to the ride guide; anything else is status.
            null -> if (!handleWalkCommand(text) && !handleRideSpeech(text)) speakStatus()
        }
    }

    private fun remainingStopsLine(): String {
        val open = activeStops.filter { it.isOpen }
        val dest = (uiState.ride as? RiderState.InTrip)?.destination ?: lastBooking?.first ?: "your destination"
        if (open.isEmpty()) return "No more stops. Next, $dest."
        return open.joinToString(" ") { s ->
            "Stop ${TripPlan.ordinalWord(s.index)}, ${s.name}, ${StopKind.parse(s.kind).spoken}."
        } + " Then $dest."
    }

    private fun listenForStopCode(prompt: String) {
        codeForStop = true
        codeVerified = false
        speak(prompt, NarrationTier.QUEUED) { openMic(MicPurpose.CODE_VERIFY) }
    }

    private fun addStopMidTrip(leg: PlannedLeg) {
        val used = activeStops.count { it.status != "SKIPPED" }
        if (used >= TripPlan.MAX_STOPS) {
            speak("This ride already has ${TripPlan.MAX_STOPS} stops, which is the most it can have.", NarrationTier.QUEUED)
            return
        }
        viewModelScope.launch {
            val place = findPlaceQuietly(leg)
            onMain {
                if (place == null) {
                    speak("I couldn't find ${leg.query}. Try again with a nearby landmark.", NarrationTier.QUEUED)
                } else {
                    addingStopMidTrip = leg.copy(place = place)
                    speak("Add ${place.name} as a stop. Will you get out and come back, so the driver waits? " +
                        "Or is someone getting off, or getting on?", NarrationTier.QUEUED) { openMic(MicPurpose.STOP_KIND) }
                }
            }
        }
    }

    private fun resolveMidTripKind(text: String) {
        val leg = addingStopMidTrip ?: return
        val kind = StopPlanParser.kindAnswer(text) ?: StopKind.WAIT
        addingStopMidTrip = null
        pendingTripAction = TripAction.Add(leg.copy(kind = kind))
        speak("${leg.name}, ${kind.spoken}. Add it? Say yes or no.", NarrationTier.QUEUED) { openMic(MicPurpose.STOP_CONFIRM) }
    }

    private fun resolveTripAction(text: String) {
        val action = pendingTripAction ?: return
        pendingTripAction = null
        val rideId = activeRideId ?: return
        if (Classifier.classify(text, true).intent !is RiderIntent.Yes) {
            speak("Okay, no change.", NarrationTier.QUEUED)
            return
        }
        viewModelScope.launch {
            val result = when (action) {
                is TripAction.Skip -> api.skipStop(rideId, action.stop.stopId)
                is TripAction.Add -> {
                    val upcoming = org.json.JSONArray()
                    activeStops.filter { it.status == "PENDING" }.forEach { s ->
                        upcoming.put(org.json.JSONObject().put("stopId", s.stopId).put("name", s.name).put("kind", s.kind)
                            .put("note", s.note).put("address", s.address)
                            .apply { s.latitude?.let { put("latitude", it) }; s.longitude?.let { put("longitude", it) } })
                    }
                    upcoming.put(stopsJson(listOf(action.leg)).getJSONObject(0))
                    api.replaceStops(rideId, upcoming)
                }
            }
            onMain {
                when (result) {
                    is ApiResult.Ok -> setStops(result.value.stops) // the server's event says what changed
                    is ApiResult.Failed -> speak(result.spoken, NarrationTier.QUEUED)
                }
            }
        }
    }

    /** A place for a mid-ride stop, found without leaving the ride screen. */
    private suspend fun findPlaceQuietly(leg: PlannedLeg): PlaceOption? {
        if (app.memory.enabled) {
            PreferenceMemoryAgent.direct(leg.spoken, app.memory.current, memoryMoment())?.place?.toOption()?.let { return it }
        }
        val candidates = if (BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()) {
            val location = runCatching { app.locationProvider.current() }.getOrNull()
            app.placeResolver.search(leg.query, location).getOrNull().orEmpty()
        } else emptyList()
        val all = candidates.ifEmpty { Gazetteer.score(leg.query) }
        return (MatchGate.evaluate(leg.spoken, all) as? MatchGate.Decision.Proceed)?.top
    }

    // =================================================================================
    //  Walking routes — "the last hundred metres"
    //
    //  The cab gets the rider to the kerb; this gets them from the kerb to the door.
    //
    //   "record my walk to the clinic door" ─▶ consent (once) ─▶ steps, compass and GPS
    //       recorded on this phone; the rider presses and says what they notice ("kerb here",
    //       "bakery smell on my left", "turning left") ─▶ "I'm there" ─▶ O&M-style stretches,
    //       read back and saved under a name
    //   "how do I get to the clinic" ─▶ the whole route, at the detail familiarity allows
    //   "guide me to the clinic"     ─▶ one part at a time: next / repeat / more or less detail
    //       / I'm there; the walk is re-recorded silently, so a changed route is noticed and
    //       the rider is asked about a landmark that may be gone ("was the bakery smell still
    //       there?")
    //   In the cab: "tell me the walk" previews it before getting out. After the ride the app
    //   says when it has a walk from this drop-off.
    //
    //  Everything stays on the phone (WalkStore). It never says the way is clear.
    // =================================================================================

    private enum class WalkMode { NONE, CONSENT, RECORDING, NAMING, UPDATE_CONFIRM, GUIDING, VERIFY }

    private val walkStore by lazy { WalkStore(getApplication()) }
    private var walkMode = WalkMode.NONE
    private var walkName = ""
    /** The cab destination a new walk is linked to (recorded soon after a ride there). */
    private var walkPlace: Pair<String, String>? = null
    private var walkBuilt: RouteBuilder.Built? = null
    private var walkOld: WalkRoute? = null
    private var guideRoute: WalkRoute? = null
    private var guidePart = 0
    private var guideHelp = 0
    private var guideRecording = false
    private var verifyRoute: WalkRoute? = null
    private var verifyLandmark: Landmark? = null
    private var lastRideEndedAt = 0L
    private var lastRidePlace: PlaceOption? = null

    private fun walkLog(routeId: String, event: String, detail: String = "") =
        walkStore.log(WalkLogEntry(System.currentTimeMillis(), routeId, event, detail))

    /**
     * Walking-route words. @return true when handled. Called before the cab classifier, so
     * "how do I get to the clinic" is never booked as a ride.
     */
    private fun handleWalkSpeech(text: String): Boolean {
        if (micPurpose == MicPurpose.WALK) {
            handleWalkAnswer(text)
            return true
        }
        // "Guide me" answers the after-ride question too: the walk to the door comes first.
        if (micPurpose == MicPurpose.NEXT_JOURNEY && WalkCommands.parse(text) != null) {
            endPostRide(null)
            return handleWalkCommand(text)
        }
        if (micPurpose != MicPurpose.BOOKING && micPurpose != MicPurpose.SLOT_ANSWER) return false
        if (uiState.ride is RiderState.Confirming || planStage != null) return false
        return handleWalkCommand(text)
    }

    private fun handleWalkCommand(text: String): Boolean {
        val cmd = WalkCommands.parse(text) ?: return false
        val inCar = activeRideId != null
        engine.earcon(Earcon.UNDERSTOOD)
        when (cmd) {
            is WalkCommands.Command.Record -> when {
                inCar -> speak("I'll record your walk once you're out of the car. Say record my walk then.", NarrationTier.QUEUED)
                !walkStore.profile().consentGiven -> {
                    walkMode = WalkMode.CONSENT
                    walkName = cmd.name
                    speak(
                        "I'll record this walk's steps, direction and position on this phone only, to build a route " +
                            "you can ask for later. Nothing leaves the phone. Shall I start?",
                        NarrationTier.QUEUED
                    ) { openMic(MicPurpose.WALK) }
                }
                else -> startWalkRecording(cmd.name)
            }
            is WalkCommands.Command.Recall -> recallWalk(cmd.name, cmd.guided)
            WalkCommands.Command.List -> {
                val names = walkStore.routes().map { it.name }
                speak(
                    when (names.size) {
                        0 -> "You haven't saved a walking route yet. When you're at the start, say record my walk."
                        1 -> "You have one walking route: ${names[0]}."
                        else -> "You have ${names.size} walking routes: ${names.joinToString(", ")}."
                    },
                    NarrationTier.QUEUED
                )
            }
            is WalkCommands.Command.Forget -> {
                val r = RouteMemory.find(walkStore.routes(), cmd.name)
                if (r == null) speak("I don't have a walking route called ${cmd.name}.", NarrationTier.QUEUED)
                else {
                    walkStore.delete(r.id)
                    walkLog(r.id, "FORGET")
                    speak("Done. I've forgotten the route to ${r.name}.", NarrationTier.QUEUED)
                }
            }
            WalkCommands.Command.ForgetAll -> {
                walkStore.clear()
                speak("Done. I've forgotten all your walking routes.", NarrationTier.QUEUED)
            }
            WalkCommands.Command.Export -> exportWalkLog()
        }
        return true
    }

    // ---- recording ----------------------------------------------------------------------

    private fun startWalkRecording(name: String) {
        val now = System.currentTimeMillis()
        walkName = name
        // Recorded within half an hour of a ride: this is the walk from that drop-off.
        walkPlace = lastRidePlace?.takeIf { now - lastRideEndedAt < 30 * 60_000L }
            ?.let { RouteMemory.placeKey(it.placeId, it.name) to it.name }
        WalkRecorder.begin(now)
        val started = WalkRecordingService.start(getApplication())
        if (!started) {
            WalkRecorder.finish()
            speak("I couldn't start recording on this phone.", NarrationTier.QUEUED)
            return
        }
        walkMode = WalkMode.RECORDING
        walkLog("", "RECORD_START", name)
        showWalk(recording = true, prompt = "Recording your walk")
        speak(
            "Recording. Walk as you normally would. Press the screen and tell me what you notice: a kerb, a smell, " +
                "a sound, a door — or say turning left. Say I'm there at the end.",
            NarrationTier.QUEUED
        )
    }

    private fun finishWalkRecording() {
        WalkRecordingService.stop(getApplication())
        val events = WalkRecorder.finish()
        val now = System.currentTimeMillis()
        val built = RouteBuilder.build(events, now, idPrefix = "lm${now.toString(36)}")
        if (built.segments.isEmpty() || built.segments.sumOf { it.metres } < 3.0) {
            resetWalk()
            transition(RiderState.Idle, announce = false)
            speak("I didn't get enough of the walk to save it. Keep the phone with you and try again.", NarrationTier.QUEUED)
            return
        }
        walkBuilt = built
        val routes = walkStore.routes()
        val existing = (if (walkName.isNotBlank()) RouteMemory.find(routes, walkName) else null)
            ?: walkPlace?.let { RouteMemory.forPlace(routes, it.first) }
        if (existing != null) {
            compareWithStored(existing, built, help = 0)
            return
        }
        if (walkName.isBlank()) {
            walkMode = WalkMode.NAMING
            val draft = draftRoute("this route", built, now)
            speak("${WalkNarrator.narrate(draft, DetailLevel.BRIEF, now)} What should I call this route?", NarrationTier.QUEUED) {
                openMic(MicPurpose.WALK)
            }
        } else {
            saveNewWalk(walkName)
        }
    }

    private fun draftRoute(name: String, built: RouteBuilder.Built, now: Long) = WalkRoute(
        id = "walk-${now.toString(36)}",
        name = name,
        placeKey = walkPlace?.first.orEmpty(),
        placeName = walkPlace?.second.orEmpty(),
        start = if (walkPlace != null) "where the cab stops" else "where you started",
        segments = built.segments,
        createdAt = now,
        updatedAt = now,
        lastWalkedAt = now,
        walks = 1,
        indoor = built.indoor
    )

    private fun saveNewWalk(name: String) {
        val built = walkBuilt ?: return
        val now = System.currentTimeMillis()
        val route = draftRoute(name, built, now)
        walkStore.save(route)
        walkLog(route.id, "RECORD_SAVED", "segments=${route.segments.size} metres=${route.totalMetres.toInt()} indoor=${route.indoor}")
        resetWalk()
        transition(RiderState.Idle, announce = false)
        engine.earcon(Earcon.BOOKING_CONFIRMED)
        speak(
            "Saved as $name. ${WalkNarrator.cueLine(route).replaceFirstChar { it.uppercase() }}. " +
                "Ask me how do I get to $name, or say guide me to $name, whenever you need it.",
            NarrationTier.QUEUED
        )
    }

    /** A walk along a stored route just ended (recorded or guided): same, or changed? */
    private fun compareWithStored(stored: WalkRoute, built: RouteBuilder.Built, help: Int) {
        val now = System.currentTimeMillis()
        when (val c = RouteMemory.compare(stored, built.segments)) {
            RouteMemory.Comparison.Same -> {
                var r = RouteMemory.rewalked(stored, built.segments, now)
                if (help > 0) r = r.copy(helpRequests = minOf(stored.helpRequests + help, 3))
                walkStore.save(r)
                walkLog(r.id, "WALK_DONE", "help=$help same=true")
                resetWalk()
                afterWalk(r, "That matched your route to ${r.name}.")
            }
            is RouteMemory.Comparison.Changed -> {
                walkOld = RouteMemory.walked(stored, help, now)
                walkBuilt = built
                walkMode = WalkMode.UPDATE_CONFIRM
                walkLog(stored.id, "CHANGE_DETECTED", c.sentence)
                speak("This walk was different from the route I saved. ${c.sentence} Shall I update the saved route?",
                    NarrationTier.QUEUED) { openMic(MicPurpose.WALK) }
            }
        }
    }

    /** After a walk: one landmark worth checking, else done. */
    private fun afterWalk(route: WalkRoute, lead: String) {
        val now = System.currentTimeMillis()
        val ask = RouteMemory.toVerify(route, now)
        transition(RiderState.Idle, announce = false)
        if (ask == null) {
            speak(lead, NarrationTier.QUEUED)
            return
        }
        walkMode = WalkMode.VERIFY
        verifyRoute = route
        verifyLandmark = ask
        speak("$lead One question: was ${WalkNarrator.phrase(ask)} still there? Say yes, no, or tell me what's there now.",
            NarrationTier.QUEUED) { openMic(MicPurpose.WALK) }
    }

    // ---- recall and guidance ------------------------------------------------------------

    private fun walkForContext(): WalkRoute? {
        val routes = walkStore.routes()
        val place = (if (activeRideId != null) lastBookingPlace else lastRidePlace)
        return place?.let { RouteMemory.forPlace(routes, RouteMemory.placeKey(it.placeId, it.name)) }
            ?: routes.singleOrNull()
    }

    private fun recallWalk(name: String, guided: Boolean) {
        val routes = walkStore.routes()
        val route = if (name.isBlank()) walkForContext() else RouteMemory.find(routes, name)
        if (route == null) {
            speak(
                when {
                    routes.isEmpty() -> "You haven't saved a walking route yet. When you're at the start, say record my walk."
                    name.isBlank() -> "Which route? You have ${routes.joinToString(", ") { it.name }}."
                    else -> "I don't have a walking route to $name. When you're there, say record my walk to $name."
                },
                NarrationTier.QUEUED
            )
            return
        }
        val now = System.currentTimeMillis()
        val level = WalkNarrator.levelFor(route, walkStore.profile())
        if (!guided || activeRideId != null) {
            walkLog(route.id, if (activeRideId != null) "PREVIEW" else "RECALL", "level=$level")
            val tail = when {
                activeRideId != null -> " Say guide me after you get out, and I'll take you one part at a time."
                else -> " Say guide me to go one part at a time."
            }
            speak(WalkNarrator.narrate(route, level, now, walkNotes(route)) + tail, NarrationTier.QUEUED)
            return
        }
        // Guided: one part at a time, re-recording silently when the rider has agreed to recording.
        guideRoute = route
        guidePart = 0
        guideHelp = 0
        guideRecording = walkStore.profile().consentGiven && WalkRecordingService.start(getApplication())
        if (guideRecording) WalkRecorder.begin(now)
        walkMode = WalkMode.GUIDING
        walkLog(route.id, "GUIDE_START", "level=$level recording=$guideRecording")
        showWalk(recording = false, prompt = "Part 1 of ${route.segments.size}")
        speak(
            "Guiding you to ${route.name}, ${route.segments.size} ${if (route.segments.size == 1) "part" else "parts"}. " +
                "Press the screen and say next at each turn, repeat, more detail, or I'm there. " +
                WalkNarrator.segment(route, 0, level, now, walkNotes(route)),
            NarrationTier.QUEUED
        )
    }

    private fun speakGuidePart(lead: String = "") {
        val route = guideRoute ?: return
        val now = System.currentTimeMillis()
        val level = WalkNarrator.levelFor(route.copy(helpRequests = route.helpRequests + guideHelp), walkStore.profile())
        showWalk(recording = false, prompt = "Part ${guidePart + 1} of ${route.segments.size}")
        speak(lead + WalkNarrator.segment(route, guidePart, level, now, walkNotes(route)), NarrationTier.QUEUED)
    }

    private fun finishGuide(stopped: Boolean) {
        val route = guideRoute ?: return
        val events = if (guideRecording) { WalkRecordingService.stop(getApplication()); WalkRecorder.finish() } else emptyList()
        val now = System.currentTimeMillis()
        if (stopped) {
            walkLog(route.id, "GUIDE_STOPPED", "part=${guidePart + 1} help=$guideHelp")
            resetWalk()
            transition(RiderState.Idle, announce = false)
            speak("Okay, I've stopped guiding.", NarrationTier.QUEUED)
            return
        }
        val built = if (events.isNotEmpty()) RouteBuilder.build(events, now, idPrefix = "lm${now.toString(36)}") else null
        val help = guideHelp
        resetWalk()
        if (built != null && built.segments.isNotEmpty() && built.segments.sumOf { it.metres } >= 3.0) {
            compareWithStored(route, built, help)
        } else {
            val r = RouteMemory.walked(route, help, now)
            walkStore.save(r)
            walkLog(r.id, "WALK_DONE", "help=$help recorded=false")
            afterWalk(r, "You're at ${route.name}.")
        }
    }

    // ---- answers ------------------------------------------------------------------------

    private fun handleWalkAnswer(text: String) {
        val yesNo = Classifier.classify(text, false).intent
        val shortCancel = com.cabeye.rider.intent.IntentParser.CANCEL.containsMatchIn(text) &&
            text.trim().split(Regex("\\s+")).size <= 3
        when (walkMode) {
            WalkMode.CONSENT -> when {
                yesNo is RiderIntent.Yes -> {
                    walkStore.saveProfile(walkStore.profile().copy(consentGiven = true))
                    walkLog("", "CONSENT", "yes")
                    startWalkRecording(walkName)
                }
                else -> { resetWalk(); speak("Okay, I won't record.", NarrationTier.QUEUED) }
            }
            WalkMode.RECORDING -> when (val input = WalkCommands.record(text)) {
                WalkCommands.RecordInput.Finish -> finishWalkRecording()
                WalkCommands.RecordInput.Cancel -> {
                    WalkRecordingService.stop(getApplication()); WalkRecorder.finish()
                    resetWalk(); transition(RiderState.Idle, announce = false)
                    speak("Okay, I've discarded that walk.", NarrationTier.QUEUED)
                }
                is WalkCommands.RecordInput.Turn -> {
                    WalkRecorder.add(WalkEvent.Turn(System.currentTimeMillis(), input.direction))
                    speak("${input.direction.spoken.replaceFirstChar { it.uppercase() }}.", NarrationTier.QUEUED)
                }
                is WalkCommands.RecordInput.Mark -> {
                    WalkRecorder.add(WalkEvent.Mark(System.currentTimeMillis(), input.spoken))
                    val r = input.result
                    speak("Noted: the ${r.text}${if (r.side != Side.NONE) " ${r.side.spoken}" else ""}.", NarrationTier.QUEUED)
                }
                is WalkCommands.RecordInput.Visual -> {
                    walkLog("", "VISUAL_REFUSED", input.text)
                    speak("I'll only keep things you can feel, hear or smell — a sign or a colour won't help when you " +
                        "walk it. What can you feel, hear or smell here?", NarrationTier.QUEUED) { openMic(MicPurpose.WALK) }
                }
                null -> {
                    val note = MindMapCommands.parse(text) as? MindMapCommands.Command.Remember
                    val help = { speak("Tell me what you notice — a kerb, a smell, a sound — or say turning left, or I'm there.",
                        NarrationTier.QUEUED) }
                    if (note != null) rememberAnchor(note.anchor)
                    else if (!tryInterpret(text, help)) help()
                }
            }
            WalkMode.NAMING -> {
                val name = text.lowercase()
                    .replace(Regex("^(?:call it|name it|save it as|it'?s|the|my)\\s+"), "")
                    .replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
                if (shortCancel) { resetWalk(); transition(RiderState.Idle, announce = false); speak("Okay, not saved.", NarrationTier.QUEUED) }
                else if (name.isBlank()) speak("Say a short name, like clinic door.", NarrationTier.QUEUED) { openMic(MicPurpose.WALK) }
                else saveNewWalk(name)
            }
            WalkMode.UPDATE_CONFIRM -> {
                val old = walkOld ?: return
                val built = walkBuilt ?: return
                val now = System.currentTimeMillis()
                resetWalk()
                if (yesNo is RiderIntent.Yes) {
                    val r = RouteMemory.replaced(old, built.segments, now)
                    walkStore.save(r)
                    walkLog(r.id, "ROUTE_UPDATED")
                    transition(RiderState.Idle, announce = false)
                    speak("Updated. ${WalkNarrator.cueLine(r).replaceFirstChar { it.uppercase() }}.", NarrationTier.QUEUED)
                } else {
                    walkStore.save(old)
                    walkLog(old.id, "ROUTE_KEPT")
                    transition(RiderState.Idle, announce = false)
                    speak("Okay, I kept the saved route.", NarrationTier.QUEUED)
                }
            }
            WalkMode.GUIDING -> {
                val route = guideRoute ?: return
                when (WalkCommands.guide(text)) {
                    WalkCommands.Guide.NEXT -> {
                        walkLog(route.id, "NEXT", "part=${guidePart + 1}")
                        // "Next" is said at the turn: record it, so the re-walk keeps the turn the
                        // compass may have smoothed over.
                        val turn = route.segments[guidePart].turn
                        if (guideRecording && turn != TurnDirection.NONE) {
                            WalkRecorder.add(WalkEvent.Turn(System.currentTimeMillis(), turn))
                        }
                        if (guidePart + 1 < route.segments.size) { guidePart++; speakGuidePart() } else finishGuide(stopped = false)
                    }
                    WalkCommands.Guide.REPEAT -> { guideHelp++; walkLog(route.id, "REPEAT", "part=${guidePart + 1}"); speakGuidePart() }
                    WalkCommands.Guide.PREVIOUS -> { guidePart = (guidePart - 1).coerceAtLeast(0); speakGuidePart() }
                    WalkCommands.Guide.MORE_DETAIL -> {
                        guideHelp++
                        val p = walkStore.profile()
                        walkStore.saveProfile(p.copy(detailBias = (p.detailBias + 1).coerceAtMost(2)))
                        walkLog(route.id, "MORE_DETAIL")
                        speakGuidePart("More detail. ")
                    }
                    WalkCommands.Guide.LESS_DETAIL -> {
                        val p = walkStore.profile()
                        walkStore.saveProfile(p.copy(detailBias = (p.detailBias - 1).coerceAtLeast(-2)))
                        walkLog(route.id, "LESS_DETAIL")
                        speakGuidePart("Less detail. ")
                    }
                    WalkCommands.Guide.ARRIVED -> finishGuide(stopped = false)
                    WalkCommands.Guide.STOP -> finishGuide(stopped = true)
                    null -> {
                        // A correction or a new landmark said mid-walk.
                        val now = System.currentTimeMillis()
                        val fixed = RouteMemory.correct(route, text, null, now, "lm${now.toString(36)}")
                        when {
                            fixed != null -> {
                                guideRoute = fixed.first
                                walkStore.save(fixed.first)
                                walkLog(route.id, "CORRECTION", text)
                                speak(fixed.second, NarrationTier.QUEUED)
                            }
                            guideRecording && WalkCommands.record(text) is WalkCommands.RecordInput.Mark -> {
                                WalkRecorder.add(WalkEvent.Mark(now, text))
                                speak("Noted.", NarrationTier.QUEUED)
                            }
                            MindMapCommands.parse(text) is MindMapCommands.Command.Remember ->
                                rememberAnchor((MindMapCommands.parse(text) as MindMapCommands.Command.Remember).anchor)
                            else -> {
                                val help = { speak("Say next, repeat, more detail, or I'm there.", NarrationTier.QUEUED) }
                                if (!tryInterpret(text, help)) help()
                            }
                        }
                    }
                }
            }
            WalkMode.VERIFY -> {
                val route = verifyRoute ?: return
                val l = verifyLandmark ?: return
                val now = System.currentTimeMillis()
                resetWalk()
                when {
                    yesNo is RiderIntent.Yes -> {
                        walkStore.save(RouteMemory.confirm(route, l.id, now))
                        walkLog(route.id, "VERIFY_YES", l.text)
                        speak("Thanks, noted.", NarrationTier.QUEUED)
                    }
                    yesNo is RiderIntent.No || Regex("\\b(gone|not there|wasn'?t there|isn'?t there|missing|removed|closed)\\b")
                        .containsMatchIn(text.lowercase()) -> {
                        val (r, removed) = RouteMemory.miss(route, l.id, now)
                        walkStore.save(r)
                        walkLog(route.id, "VERIFY_NO", l.text)
                        speak(if (removed) "I've taken ${WalkNarrator.phrase(l, withSide = false)} off the route."
                        else "Okay. I'll say it may be gone, and ask again next time.", NarrationTier.QUEUED)
                    }
                    else -> {
                        val fixed = RouteMemory.correct(route, text, l, now, "lm${now.toString(36)}")
                        if (fixed != null) {
                            walkStore.save(fixed.first)
                            walkLog(route.id, "CORRECTION", text)
                            speak(fixed.second, NarrationTier.QUEUED)
                        } else speak("Okay.", NarrationTier.QUEUED)
                    }
                }
            }
            WalkMode.NONE -> speak("Say record my walk, or how do I get to a place.", NarrationTier.QUEUED)
        }
    }

    /** Silence or a recognition error while the walk mic was open. */
    private fun onWalkNoAnswer() {
        when (walkMode) {
            WalkMode.CONSENT -> { resetWalk(); speak("Okay, I won't record.", NarrationTier.QUEUED) }
            WalkMode.NAMING -> saveNewWalk(walkPlace?.second?.lowercase() ?: "walk ${walkStore.routes().size + 1}")
            WalkMode.UPDATE_CONFIRM -> handleWalkAnswer("no")
            WalkMode.VERIFY -> resetWalk()
            // Recording and guidance go on: the rider presses again when they need to.
            WalkMode.RECORDING, WalkMode.GUIDING, WalkMode.NONE -> Unit
        }
    }

    /** Screen buttons on the walking screen, for a helper or a TalkBack user. */
    private fun onWalkAction(action: String) {
        when (action) {
            "walk-mark" -> openMic(MicPurpose.WALK)
            "walk-left" -> if (walkMode == WalkMode.RECORDING) handleWalkAnswer("turning left")
            "walk-right" -> if (walkMode == WalkMode.RECORDING) handleWalkAnswer("turning right")
            "walk-done" -> when (walkMode) {
                WalkMode.RECORDING -> finishWalkRecording()
                WalkMode.GUIDING -> finishGuide(stopped = false)
                else -> Unit
            }
            "walk-next" -> if (walkMode == WalkMode.GUIDING) handleWalkAnswer("next")
            "walk-repeat" -> if (walkMode == WalkMode.GUIDING) handleWalkAnswer("repeat")
            "walk-stop" -> when (walkMode) {
                WalkMode.RECORDING -> handleWalkAnswer("cancel recording")
                WalkMode.GUIDING -> finishGuide(stopped = true)
                else -> Unit
            }
        }
    }

    private fun showWalk(recording: Boolean, prompt: String) {
        val route = guideRoute
        val lines = if (route != null) route.segments.mapIndexed { i, s ->
            (if (i == guidePart) "▶ " else "") + "${i + 1}. about ${WalkNarrator.roundMetres(s.metres)} m" +
                (if (s.turn != TurnDirection.NONE) ", then ${s.turn.spoken.removePrefix("turn ")}" else "") +
                (s.landmarks.firstOrNull()?.let { " · ${it.text}" } ?: "")
        } else listOf("Steps so far: ${WalkRecorder.steps()}")
        transition(RiderState.Walking(prompt, lines, recording, guiding = route != null), announce = false)
    }

    private fun resetWalk() {
        walkMode = WalkMode.NONE
        walkName = ""
        walkPlace = null
        walkBuilt = null
        walkOld = null
        guideRoute = null
        guidePart = 0
        guideHelp = 0
        guideRecording = false
        verifyRoute = null
        verifyLandmark = null
    }

    /** "export my walking log": the field-study measures, as CSV, through the share sheet. */
    private fun exportWalkLog() {
        val entries = walkStore.logEntries()
        if (entries.isEmpty()) {
            speak("There's nothing in the walking log yet.", NarrationTier.QUEUED)
            return
        }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Cab Eye walking log")
            .putExtra(android.content.Intent.EXTRA_TEXT, RouteMemory.csv(entries))
        runCatching {
            getApplication<android.app.Application>().startActivity(
                android.content.Intent.createChooser(send, "Share walking log")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        speak("The walking log has ${entries.size} entries. Choose where to send it.", NarrationTier.QUEUED)
    }

    // =================================================================================
    //  The personal mind map — places, walks, rides and what they mean to the rider
    //
    //  One connected memory (see mindmap/MindMapModels.kt): the cab destinations, the walks
    //  from them, the landmarks on those walks, and the rider's own notes ("Ravi's tea shop,
    //  where I stop on Fridays"). Asked "what's around the clinic?" it gives a spoken mental
    //  map. In the cab, the ride guide says the cues the rider marked on earlier rides as they
    //  come up, names the areas, and says once if the cab is not on the usual way. After a ride
    //  to somewhere new it offers the few best-known places close by.
    //
    //  Everything stays on the phone (MindMapStore), like the walking routes.
    // =================================================================================

    private val mindMapStore by lazy { MindMapStore(getApplication()) }
    private val areaNamer by lazy { AreaNamer(getApplication()) }
    private var rideGuide: RideGuideAgent? = null
    private var rideGuideJob: Job? = null
    /** Decided when the trip starts, from the memory as it was before this ride. */
    private var rideToNewPlace = false
    private var nearbyPicks: List<Pair<NearbyPlace, Double>> = emptyList()
    private var nearbyFrom: Pair<Double, Double>? = null
    private var nearbyNear: Node? = null
    /** Fetched after a ride to a new place, said when the post-ride questions are over. */
    private var nearbyOfferWaiting = false
    private var nearbyFetchJob: Job? = null

    private fun mindMap(): MindMap =
        MindMapBuilder.build(mindMapStore.authored(), walkStore.routes(), app.memory.current.places)

    /** A cab destination as a mind-map node: the one the map has, or a new one to keep. */
    private fun placeNode(map: MindMap, place: PlaceOption): Node {
        val key = RouteMemory.placeKey(place.placeId, place.name)
        val norm = TextSimilarity.normalise(place.name)
        return map.node(MindMapBuilder.placeId(key))
            ?: map.nodes.firstOrNull { it.kind == NodeKind.PLACE && it.source == "name:$norm" }
            ?: map.nodes.firstOrNull {
                it.kind == NodeKind.PLACE && !it.id.startsWith("walk:") && TextSimilarity.normalise(it.label) == norm
            }
            ?: Node(
                MindMapBuilder.placeId(key), NodeKind.PLACE, place.name, place.latitude, place.longitude,
                source = key, createdAt = System.currentTimeMillis(), lastConfirmedAt = System.currentTimeMillis()
            )
    }

    /**
     * Where the rider is, as the map knows it: the walk being guided, or the drop-off they are
     * still near. With a GPS fix, a drop-off the rider has since walked away from is not "here".
     */
    private fun hereNode(map: MindMap, lat: Double? = null, lng: Double? = null): Node? {
        guideRoute?.let { r -> map.node(MindMapBuilder.walkId(r.id))?.let { return it } }
        if (activeRideId != null) return null // in the cab, "here" is moving
        val place = lastRidePlace?.takeIf { System.currentTimeMillis() - lastRideEndedAt < HERE_FOR_MS } ?: return null
        if (lat != null && lng != null && Geo.metres(lat, lng, place.latitude, place.longitude) > HERE_NEAR_M) return null
        return placeNode(map, place)
    }

    /** Keeps a place node the derived map lacks (a guest rider has no ride memory), so links to it last. */
    private fun withPlace(authored: MindMap, map: MindMap, node: Node?): MindMap =
        if (node != null && node.kind == NodeKind.PLACE && map.node(node.id) == null) authored.upsert(listOf(node)) else authored

    /** Where the rider is now: the cab's last fix in a ride, else a fresh fix from the phone. */
    private suspend fun riderPosition(): Pair<Double, Double>? =
        rideGuide?.position ?: runCatching { app.locationProvider.fresh() }.getOrNull()?.let { it.latitude to it.longitude }

    /**
     * Mind-map words at the main question or after a ride: "what's around the clinic",
     * "remember Ravi's tea shop, where I stop on Fridays". @return true when handled.
     */
    private fun handleMindMapSpeech(text: String): Boolean {
        if (micPurpose == MicPurpose.NEARBY) {
            handleNearbyAnswer(text)
            return true
        }
        if (micPurpose == MicPurpose.INTERPRET) {
            handleInterpretAnswer(text)
            return true
        }
        if (micPurpose != MicPurpose.BOOKING && micPurpose != MicPurpose.NEXT_JOURNEY) return false
        if (uiState.ride is RiderState.Confirming || planStage != null) return false
        // At both questions a place may be named as a destination: only "remember …" makes
        // "the mall where I bought shoes" a note there.
        val cmd = MindMapCommands.parse(text, requireLead = true)
        if (cmd == null) {
            // "Remember …" that the rules couldn't follow: the one case at the main question
            // where the sentence is clearly meant for the mind map, not a destination.
            if (!REMEMBER_LEAD.containsMatchIn(text.trim().lowercase())) return false
            leaveQuestionScreen()
            if (!tryInterpret(text) { speak(REMEMBER_HELP, NarrationTier.QUEUED) }) speak(REMEMBER_HELP, NarrationTier.QUEUED)
            return true
        }
        leaveQuestionScreen()
        runMindMapCommand(cmd)
        return true
    }

    /** The question the rider was answering is over: back to the idle screen. */
    private fun leaveQuestionScreen() {
        when (uiState.ride) {
            is RiderState.NextJourney -> endPostRide(null)
            is RiderState.Listening -> transition(RiderState.Idle, announce = false)
            else -> Unit
        }
    }

    private fun runMindMapCommand(cmd: MindMapCommands.Command) {
        engine.earcon(Earcon.UNDERSTOOD)
        val now = System.currentTimeMillis()
        val map = mindMap()
        when (cmd) {
            is MindMapCommands.Command.Around -> if (cmd.name.isBlank()) aroundHere() else {
                val target = MindMapAgent.find(map, cmd.name, setOf(NodeKind.PLACE, NodeKind.ANCHOR, NodeKind.AREA), near = hereNode(map))
                speak(
                    target?.let { MindMapAgent.around(map, it, now) }
                        ?: "I don't know a place called ${cmd.name} yet. It joins your mind map once you ride or walk there.",
                    NarrationTier.QUEUED
                )
            }
            MindMapCommands.Command.Nearby -> offerNearby(newHere = false)
            is MindMapCommands.Command.About -> {
                val n = MindMapAgent.find(map, cmd.name, near = hereNode(map))
                speak(n?.let { MindMapAgent.about(map, it, now) } ?: "I don't have anything about ${cmd.name} yet.", NarrationTier.QUEUED)
            }
            is MindMapCommands.Command.Remember -> rememberAnchor(cmd.anchor)
            is MindMapCommands.Command.VisualOnly -> speak(
                "I'll only keep what you can feel, hear or smell, or what a place means to you. A sign or a colour " +
                    "won't help you find it.", NarrationTier.QUEUED
            )
            is MindMapCommands.Command.Forget -> {
                val a = MindMapAgent.findAnchor(map, cmd.name)
                if (a == null) speak("I don't have a note about ${cmd.name}.", NarrationTier.QUEUED)
                else {
                    mindMapStore.saveAuthored(mindMapStore.authored().without(a.id))
                    speak("Done. I've forgotten ${MindMapAgent.display(a)}.", NarrationTier.QUEUED)
                }
            }
            MindMapCommands.Command.ForgetAll -> {
                mindMapStore.clear()
                speak(
                    "Done. I've forgotten your personal notes, ride cues and remembered places. " +
                        "Your walking routes and ride history are kept.", NarrationTier.QUEUED
                )
            }
            MindMapCommands.Command.Summary -> speak(MindMapAgent.summary(map), NarrationTier.QUEUED)
            is MindMapCommands.Command.Suggestions -> {
                mindMapStore.saveNearbyGuard(NearbySuggestionAgent.Guard(declines = 0, off = !cmd.on))
                speak(
                    if (cmd.on) "Okay. After a ride somewhere new, I'll offer well-known places nearby."
                    else "Okay, I won't offer places by myself. Ask what's famous around here any time.",
                    NarrationTier.QUEUED
                )
            }
        }
    }

    /**
     * "What's around here?" Near the last drop-off or on a guided walk: that place's spoken map.
     * Anywhere else: the area and the places the rider knows nearby, from the phone's position.
     * Knowing nothing there: the well-known places instead.
     */
    private fun aroundHere() {
        viewModelScope.launch {
            val fix = riderPosition()
            val map = mindMap()
            val now = System.currentTimeMillis()
            val here = hereNode(map, fix?.first, fix?.second)
            if (here != null) {
                speak(MindMapAgent.around(map, here, now), NarrationTier.QUEUED)
                return@launch
            }
            if (fix == null) {
                speak("I can't tell where you are. Allow location for Cab Eye, then ask again.", NarrationTier.QUEUED)
                return@launch
            }
            val area = areaName(fix.first, fix.second, orCity = true)
            val said = MindMapAgent.nearPoint(map, fix.first, fix.second, area, now)
            if (said == null) offerNearby(newHere = false)
            else speak("$said Ask what's famous around here for well-known places.", NarrationTier.QUEUED)
        }
    }

    /**
     * A personal note, joined to what it is about. Said on foot, in the cab, or at the main
     * question. Only a note about "this place" takes the rider's position — "Ravi's tea shop,
     * where I stop on Fridays" said at home must not put Ravi's shop at home.
     */
    private fun rememberAnchor(a: AnchorParser.Anchor) {
        viewModelScope.launch {
            val fix = if (a.here) riderPosition() else null
            val map = mindMap()
            val now = System.currentTimeMillis()
            val here = hereNode(map, fix?.first, fix?.second)
            if (a.here && here == null && fix == null) {
                speak(
                    "I can't tell where here is. Allow location for Cab Eye, or name the place, like Ravi's tea shop, " +
                        "where I stop on Fridays.", NarrationTier.QUEUED
                )
                return@launch
            }
            val r = MindMapAgent.remember(map, a, here, fix?.first, fix?.second, now, now.toString(36))
            mindMapStore.saveAuthored(withPlace(mindMapStore.authored(), map, here).upsert(r.nodes, r.edges))
            walkLog("", "ANCHOR", a.words)
            val saved = r.nodes.single()
            // A spot the rider named on the spot can be booked by that name next time.
            val bookTip = if (a.here && here == null && saved.located && saved.sense == null)
                " To come back by cab, say: take me to ${AnchorParser.firstPerson(saved.label)}." else ""
            speak(r.spoken + bookTip, NarrationTier.QUEUED)
        }
    }

    /** The rider's notes for a walk's landmarks, for the narrator. */
    private fun walkNotes(route: WalkRoute): Map<String, String> =
        runCatching { MindMapAgent.notesFor(mindMap(), route.id) }.getOrDefault(emptyMap())

    /**
     * Books straight to a place the mind map knows exactly — a nearby place the rider picked, or
     * a spot they taught the app. The normal announcement and cancel window still apply.
     */
    private fun bookKnownPlace(name: String, lat: Double, lng: Double, placeId: String, note: String, from: Pair<Double, Double>?) {
        val rideType = preferredRideType()
        pendingSpokenAs = name
        heldRideType = rideType
        failureCount = 0
        if (from != null) {
            pickupLatitude = from.first
            pickupLongitude = from.second
        }
        engine.earcon(Earcon.UNDERSTOOD)
        beginOptimisticBooking(
            PlaceOption(name = name, latitude = lat, longitude = lng, score = 1f, coversWholeToken = true, placeId = placeId),
            rideType, note
        )
    }

    // ---- the ride guide ---------------------------------------------------------------

    /** @param synthetic a simulated ride feeds the guide itself: the phone's real GPS is not followed. */
    private fun startRideGuide(resumed: Boolean = false, synthetic: Boolean = false) {
        val place = lastBookingPlace ?: return
        val key = RouteMemory.placeKey(place.placeId, place.name)
        // The same ride's start heard twice (a reconnect): keep following it, don't start over.
        if (rideGuide?.placeKey == key) return
        stopRideGuide(save = false)
        // A new ride: an offer left from the last one is no longer about where the rider is.
        nearbyOfferWaiting = false
        val map = mindMap()
        val dest = placeNode(map, place)
        val cues = map.nodes.filter { n ->
            n.kind == NodeKind.RIDE_CUE && (n.source == key || map.edges.any { it.from == n.id && it.to == dest.id })
        }
        rideToNewPlace = NearbySuggestionAgent.isNew(
            key, app.memory.current.places, hasWalk = RouteMemory.forPlace(walkStore.routes(), key) != null,
            name = place.name, countsThisVisit = false
        )
        val guide = RideGuideAgent(
            key, place.name, mindMapStore.traces(), cues, quiet = mindMapStore.rideQuiet,
            destLat = place.latitude, destLng = place.longitude
        )
        rideGuide = guide
        if (!resumed) {
            guide.preview()?.let { speak(it, NarrationTier.QUEUED) }
            // Said once, on the first ride: nothing on screen would ever tell a blind rider this.
            if (!mindMapStore.rideIntroDone) {
                mindMapStore.rideIntroDone = true
                speak(
                    // On a multi-stop ride a tap already opens the microphone for stop words.
                    (if (activeStops.isEmpty()) "On the way, tap the screen to hear where we are. Hold it" else "On the way, press the screen") +
                        " and tell me what you notice, like speed bump or flyover, and I'll remind you next time.",
                    NarrationTier.QUEUED
                )
            }
        }
        if (synthetic) return
        rideGuideJob = viewModelScope.launch {
            var fixes = 0
            try {
                app.locationProvider.updates(RIDE_FIX_MS).collect { loc ->
                    if (loc.hasAccuracy() && loc.accuracy > RIDE_FIX_ACCURACY_M) return@collect
                    val t = System.currentTimeMillis()
                    guide.onFix(loc.latitude, loc.longitude, t).forEach(::sayRideGuide)
                    // The area every fourth fix (about 20 s): enough to notice a change, few lookups.
                    if (fixes++ % 4 == 0) {
                        areaName(loc.latitude, loc.longitude)?.let { area -> guide.onArea(area, t)?.let(::sayRideGuide) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "RIDE_GUIDE gps stopped: ${e.message}")
            }
        }
    }

    private fun sayRideGuide(say: RideGuideAgent.Say) {
        if (uiState.ride !is RiderState.InTrip || rideGuide == null) return
        // While the rider is talking, a cue or an area name would only be heard by the
        // microphone. Safety and arrival are said anyway.
        if (uiState.micOpen && (say.kind == RideGuideAgent.Kind.CUE || say.kind == RideGuideAgent.Kind.AREA)) return
        Log.i(TAG, "RIDE_GUIDE ${say.kind} \"${say.text}\"")
        val text = if (say.kind == RideGuideAgent.Kind.ARRIVING) say.text + walkAhead() else say.text
        speak(text, NarrationTier.QUEUED)
    }

    /** Said with "almost there": the first moves of the saved walk from this drop-off, so the rider can get ready. */
    private fun walkAhead(): String {
        val place = lastBookingPlace ?: return " Get ready to step out."
        val walk = RouteMemory.forPlace(walkStore.routes(), RouteMemory.placeKey(place.placeId, place.name))
            ?: return " Get ready to step out."
        return " Your walk to ${walk.name}: ${WalkNarrator.cueLine(walk)}. Say guide me when you're out."
    }

    /** Ends the guide. A completed ride keeps its trace and the areas it passed through. */
    private fun stopRideGuide(save: Boolean) {
        // Read by the nearby offer before the ride ends; never carried into the next ride.
        rideToNewPlace = false
        rideGuideJob?.cancel()
        rideGuideJob = null
        val g = rideGuide ?: return
        rideGuide = null
        if (!save) return
        val now = System.currentTimeMillis()
        val tr = g.trace(now) ?: return
        mindMapStore.addTrace(tr)
        val map = mindMap()
        val dest = lastRidePlace?.let { placeNode(map, it) } ?: return
        val areas = tr.areas.distinct().map { name ->
            val id = "area:" + TextSimilarity.normalise(name).replace(' ', '-')
            (map.node(id) ?: Node(id, NodeKind.AREA, name, createdAt = now)).copy(lastConfirmedAt = now)
        }
        val edges = areas.map { Edge(it.id, dest.id, EdgeKind.ON_RIDE) } +
            listOfNotNull(areas.lastOrNull()?.let { Edge(dest.id, it.id, EdgeKind.IN_AREA) })
        mindMapStore.saveAuthored(withPlace(mindMapStore.authored(), map, dest).upsert(areas, edges))
    }

    /**
     * Ride words that are not stop commands: "where am I", "quiet ride", "undo", a note, or a
     * cue the rider marks ("speed bump", "that was the flyover"). @return true when handled.
     */
    private fun handleRideSpeech(text: String): Boolean {
        val g = rideGuide
        when (RideCommands.parse(text)) {
            RideCommands.Command.WHERE_AM_I -> {
                speakWhereAmI()
                return true
            }
            RideCommands.Command.QUIET -> {
                mindMapStore.rideQuiet = true
                g?.quiet = true
                speak("Okay, a quiet ride. I'll still tell you if the cab leaves your usual way, and when you're almost there.",
                    NarrationTier.QUEUED)
                return true
            }
            RideCommands.Command.TALK -> {
                mindMapStore.rideQuiet = false
                g?.quiet = false
                speak("Okay. I'll tell you your cues as we go.", NarrationTier.QUEUED)
                return true
            }
            RideCommands.Command.PREVIEW -> {
                speak(
                    g?.preview() ?: ("This is the first ride there I've followed, so I don't know your usual way yet. " +
                        "Hold the screen and tell me what you notice, like a speed bump, a flyover or a sound, and I'll remember it."),
                    NarrationTier.QUEUED
                )
                return true
            }
            RideCommands.Command.UNDO -> {
                val u = g?.undo()
                when {
                    u?.removed != null -> {
                        mindMapStore.saveAuthored(mindMapStore.authored().without(u.removed.id))
                        speak("Okay, I've taken out ${MindMapAgent.the(u.removed.label)}.", NarrationTier.QUEUED)
                    }
                    u?.restored != null -> {
                        mindMapStore.saveAuthored(mindMapStore.authored().upsert(listOf(u.restored)))
                        speak("Okay, undone.", NarrationTier.QUEUED)
                    }
                    else -> speak("There's nothing to undo on this ride.", NarrationTier.QUEUED)
                }
                return true
            }
            null -> Unit
        }
        when (val cmd = MindMapCommands.parse(text)) {
            null -> Unit
            // In the cab "here" is moving and no ride may be booked from the offer: say where we are.
            MindMapCommands.Command.Nearby -> { speakWhereAmI(); return true }
            is MindMapCommands.Command.Around -> {
                if (cmd.name.isBlank()) speakWhereAmI() else runMindMapCommand(cmd)
                return true
            }
            else -> { runMindMapCommand(cmd); return true }
        }
        if (g == null) return tryInterpret(text) { speakStatus() }
        if (markRideCue(text)) return true
        return tryInterpret(text) { speakStatus() }
    }

    private fun speakWhereAmI() {
        speak(
            rideGuide?.whereAmI(tripMeters.toDouble().takeIf { it > 0 })
                ?: "I can't follow this ride, so I can't say where we are.", NarrationTier.QUEUED
        )
    }

    /** A cue the rider marks in the cab. @return false when the words are not a cue. */
    private fun markRideCue(text: String): Boolean {
        val g = rideGuide ?: return false
        val now = System.currentTimeMillis()
        when (val m = g.mark(text, now, now.toString(36))) {
            is RideGuideAgent.Marked.Cue -> {
                engine.earcon(Earcon.UNDERSTOOD)
                val map = mindMap()
                val dest = lastBookingPlace?.let { placeNode(map, it) }
                val edges = listOfNotNull(dest?.let { Edge(m.node.id, it.id, EdgeKind.ON_RIDE) })
                mindMapStore.saveAuthored(withPlace(mindMapStore.authored(), map, dest).upsert(listOf(m.node), edges))
                speak(m.spoken, NarrationTier.QUEUED)
            }
            is RideGuideAgent.Marked.VisualOnly -> speak(
                "I'll only keep what you can feel, hear or smell on the way, like a bump, a climb or a sound.", NarrationTier.QUEUED
            )
            null -> return false
        }
        return true
    }

    // ---- well-known places nearby ------------------------------------------------------

    /** The one place the rider is talking about, after "tell me about the temple". */
    private var nearbyFocus: NearbyPlace? = null
    /** "Yes" to the offer is not one of its answers: asked once what they'd like. */
    private var nearbyReprompted = false

    /**
     * After a ride to a new place: fetch the offer now and say it when the post-ride questions
     * end — or straight away, if they are already over by the time the answer comes back.
     */
    private fun prepareNearbyAfterRide(place: PlaceOption?) {
        nearbyOfferWaiting = false
        nearbyFetchJob?.cancel()
        if (place == null || !rideToNewPlace || !mindMapStore.nearbyGuard().offersProactively) return
        nearbyFetchJob = viewModelScope.launch {
            val picks = fetchNearby(place.latitude, place.longitude, place.name)
            if (picks.isEmpty()) return@launch
            nearbyPicks = picks
            nearbyFrom = place.latitude to place.longitude
            nearbyNear = placeNode(mindMap(), place)
            nearbyOfferWaiting = true
            if (uiState.ride is RiderState.Idle && activeRideId == null && !uiState.micOpen &&
                uiState.payment == null && walkMode == WalkMode.NONE
            ) finishPostRide(null)
        }
    }

    /** The post-ride questions are done: say the waiting offer, or just [line]. */
    private fun finishPostRide(line: String?) {
        val from = nearbyFrom
        val fresh = System.currentTimeMillis() - lastRideEndedAt < NEARBY_OFFER_FOR_MS
        if (!nearbyOfferWaiting || from == null || nearbyPicks.isEmpty() || !fresh) {
            nearbyOfferWaiting = false
            if (line != null) endPostRide(line)
            return
        }
        nearbyOfferWaiting = false
        nearbyFocus = null
        nearbyReprompted = false
        if (uiState.ride is RiderState.NextJourney) endPostRide(null)
        speak(NearbySuggestionAgent.offer(nearbyPicks, from.first, from.second, newHere = true), NarrationTier.QUEUED) {
            openMic(MicPurpose.NEARBY)
        }
    }

    /** "What's famous around here": where the rider is, or the last drop-off. */
    private fun offerNearby(newHere: Boolean) {
        nearbyFetchJob?.cancel()
        nearbyFetchJob = viewModelScope.launch {
            val fix = riderPosition()
            val lat = fix?.first ?: lastRidePlace?.latitude
            val lng = fix?.second ?: lastRidePlace?.longitude
            if (lat == null || lng == null) {
                speak("I can't tell where you are. Allow location for Cab Eye, then ask again.", NarrationTier.QUEUED)
                return@launch
            }
            // The map servers can take a while: say so, and keep the wait audible.
            speak("Looking for well-known places nearby.", NarrationTier.QUEUED)
            engine.heartbeat(true)
            val picks = try { fetchNearby(lat, lng, hereName = "") } finally { engine.heartbeat(false) }
            nearbyPicks = picks
            nearbyFrom = lat to lng
            nearbyNear = hereNode(mindMap(), lat, lng)
            nearbyFocus = null
            nearbyReprompted = false
            speak(NearbySuggestionAgent.offer(picks, lat, lng, newHere), NarrationTier.QUEUED) {
                if (picks.isNotEmpty()) openMic(MicPurpose.NEARBY)
            }
        }
    }

    /**
     * The neighbourhood a point is in: the phone's own geocoder first (free, on most phones
     * Google's), then OpenStreetMap through the backend when the phone can't name it — no key
     * either way. [orCity] lets the phone fall back to the city name, for "what's around here".
     */
    private suspend fun areaName(lat: Double, lng: Double, orCity: Boolean = false): String? {
        // In and around Coimbatore: the bundled atlas — no network, no key.
        AtlasStore.get(getApplication())?.takeIf { it.covers(lat, lng) }?.let { atlas ->
            atlas.areaAt(lat, lng)?.let { return it }
        }
        areaNamer.areaAt(lat, lng)?.let { return it }
        (api.placesArea(lat, lng) as? ApiResult.Ok)?.value?.let { return it }
        return if (orCity) areaNamer.areaAt(lat, lng, orCity = true) else null
    }

    private suspend fun fetchNearby(lat: Double, lng: Double, hereName: String): List<Pair<NearbyPlace, Double>> {
        val live = if (BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()) {
            app.placeResolver.searchNearby(lat, lng, NearbySuggestionAgent.RADIUS_M).getOrNull().orEmpty()
        } else emptyList()
        val picks = NearbySuggestionAgent.pick(lat, lng, live, hereName)
        if (picks.isNotEmpty()) return picks
        // In and around Coimbatore: the bundled atlas of real places — offline, no key.
        val atlas = AtlasStore.get(getApplication())?.nearby(lat, lng, NearbySuggestionAgent.RADIUS_M).orEmpty()
        val fromAtlas = NearbySuggestionAgent.pick(lat, lng, atlas, hereName)
        if (fromAtlas.isNotEmpty()) return fromAtlas
        // No Google key, or nothing well rated: real places from OpenStreetMap, through the
        // backend (no key; ranked by what the map says about them).
        val osm = (api.placesNearby(lat, lng, NearbySuggestionAgent.RADIUS_M.toInt()) as? ApiResult.Ok)?.value.orEmpty()
        val fromOsm = NearbySuggestionAgent.pick(lat, lng, osm, hereName)
        if (fromOsm.isNotEmpty()) return fromOsm
        // Offline: the app's own list of well-known places.
        val curated = Gazetteer.places.map { NearbyPlace(it.name, it.latitude, it.longitude, curated = true) }
        return NearbySuggestionAgent.pick(lat, lng, curated, hereName, radiusM = 1_000.0)
    }

    private fun handleNearbyAnswer(text: String) {
        val from = nearbyFrom ?: return
        val picks = nearbyPicks
        val t = text.lowercase().trim()
        val guard = mindMapStore.nearbyGuard()
        // "it", "there", "that one": the place just talked about.
        val pointed = nearbyFocus?.takeIf { NEARBY_THAT.containsMatchIn(t) }
        when {
            NEARBY_MORE.containsMatchIn(t) -> {
                val about = NearbySuggestionAgent.choose(picks, t)?.let { c -> picks.filter { it.first == c } } ?: picks
                if (about.size == 1) nearbyFocus = about.single().first
                speak(
                    NearbySuggestionAgent.more(about, from.first, from.second) +
                        (if (about.size == 1) " Say take me there, or remember it." else " Say take me to one, or remember them."),
                    NarrationTier.QUEUED
                ) { openMic(MicPurpose.NEARBY) }
            }
            NEARBY_REMEMBER.containsMatchIn(t) -> {
                val chosen = pointed ?: NearbySuggestionAgent.choose(picks, t)
                val keep = if (chosen != null && !NEARBY_ALL.containsMatchIn(t)) picks.filter { it.first == chosen } else picks
                val (nodes, edges) = NearbySuggestionAgent.remember(keep, nearbyNear, System.currentTimeMillis())
                mindMapStore.saveAuthored(withPlace(mindMapStore.authored(), mindMap(), nearbyNear).upsert(nodes, edges))
                mindMapStore.saveNearbyGuard(guard.copy(declines = 0))
                endNearby()
                speak(
                    "Saved ${MindMapAgent.spokenList(keep.map { it.first.name })} to your mind map. " +
                        (nearbyNear?.let { "Ask what's around ${it.label} to hear them again." } ?: "Ask what's around here to hear them again."),
                    NarrationTier.QUEUED
                )
            }
            NEARBY_GO.containsMatchIn(t) -> {
                val chosen = pointed ?: NearbySuggestionAgent.choose(picks, t) ?: picks.singleOrNull()?.first ?: nearbyFocus
                if (chosen == null) {
                    speak("Which one? ${picks.joinToString(", ") { it.first.name }}.", NarrationTier.QUEUED) {
                        openMic(MicPurpose.NEARBY)
                    }
                    return
                }
                mindMapStore.saveNearbyGuard(guard.copy(declines = 0))
                endNearby()
                bookKnownPlace(chosen.name, chosen.lat, chosen.lng, chosen.placeId, note = "", from = from)
            }
            Classifier.classify(text, false).intent is RiderIntent.No || NEARBY_NO.containsMatchIn(t) -> {
                val declines = guard.declines + 1
                mindMapStore.saveNearbyGuard(guard.copy(declines = declines))
                endNearby()
                speak(
                    if (declines >= NearbySuggestionAgent.QUIET_AFTER_DECLINES && !guard.off)
                        "Okay. I won't offer places by myself any more. Ask what's famous around here whenever you like."
                    else "Okay.", NarrationTier.QUEUED
                )
            }
            else -> {
                val chosen = NearbySuggestionAgent.choose(picks, t)
                when {
                    chosen != null -> {
                        nearbyFocus = chosen
                        speak(
                            NearbySuggestionAgent.more(picks.filter { it.first == chosen }, from.first, from.second) +
                                " Say take me there, or remember it.", NarrationTier.QUEUED
                        ) { openMic(MicPurpose.NEARBY) }
                    }
                    !nearbyReprompted -> {
                        nearbyReprompted = true
                        speak("Say tell me more, take me to one of them, remember them, or no.", NarrationTier.QUEUED) {
                            openMic(MicPurpose.NEARBY)
                        }
                    }
                    else -> {
                        endNearby()
                        speak("Okay. Ask what's famous around here any time.", NarrationTier.QUEUED)
                    }
                }
            }
        }
    }

    private fun endNearby() {
        nearbyOfferWaiting = false
        nearbyFocus = null
        nearbyReprompted = false
        // Never during a ride: the ride screen is not this offer's to change.
        if (activeRideId == null && uiState.ride !is RiderState.InTrip) transition(RiderState.Idle, announce = false)
    }

    // ---- the optional free-form reader -------------------------------------------------

    /** The sentence waiting on the consent question, and what to do if it can't be read. */
    private var interpretPending: Pair<String, () -> Unit>? = null
    /** A reading the agent was unsure of, waiting for "yes". */
    private var interpretConfirm: AnchorParser.Anchor? = null
    /** The server has no Grok key: don't ask again this session. */
    private var interpretUnavailable = false
    /** Asked and not answered (silence): not asked again this session, and asked another day. */
    private var interpretAskedThisSession = false

    /**
     * The rules didn't understand [text]. With the rider's agreement, one sentence goes to the
     * server to be read. @return true when the sentence was taken; [fallback] then runs if it
     * still can't be read. False means "do what you did before".
     */
    private fun tryInterpret(text: String, fallback: () -> Unit): Boolean {
        val t = text.trim().lowercase()
        if (interpretUnavailable || t.split(Regex("\\s+")).size < 3 || QUESTION_START.containsMatchIn(t)) return false
        if (!mindMapStore.interpretConsent) {
            if (mindMapStore.interpretAsked || interpretAskedThisSession) return false
            interpretAskedThisSession = true
            interpretPending = text to fallback
            speak(
                "I didn't follow that. I can send just that sentence, nothing else, to the Cab Eye server so it can " +
                    "work out what you meant. Shall I, now and next time?", NarrationTier.QUEUED
            ) { openMic(MicPurpose.INTERPRET) }
            return true
        }
        runInterpret(text, fallback)
        return true
    }

    private fun runInterpret(text: String, fallback: () -> Unit) {
        // The server may take a few seconds: a sound now, so the wait is never silent.
        engine.earcon(Earcon.UNDERSTOOD)
        viewModelScope.launch {
            val map = mindMap()
            when (val r = api.interpret(MindMapInterpreter.request(text, map, hereNode(map)))) {
                is ApiResult.Ok -> when (val reading = MindMapInterpreter.read(r.value, text)) {
                    is MindMapInterpreter.Reading.Note ->
                        if (reading.confidence >= MindMapInterpreter.SURE) rememberAnchor(reading.anchor)
                        else {
                            interpretConfirm = reading.anchor
                            speak("Did you mean: ${MindMapAgent.displayLabel(reading.anchor.spoken())}? Say yes to save it.",
                                NarrationTier.QUEUED) { openMic(MicPurpose.INTERPRET) }
                        }
                    is MindMapInterpreter.Reading.Cue -> if (!markRideCue(reading.words)) fallback()
                    MindMapInterpreter.Reading.Nothing -> fallback()
                }
                is ApiResult.Failed -> {
                    if (r.detail.startsWith("HTTP 404")) interpretUnavailable = true
                    fallback()
                }
            }
        }
    }

    private fun handleInterpretAnswer(text: String) {
        val intent = Classifier.classify(text, false).intent
        val yes = intent is RiderIntent.Yes
        val confirm = interpretConfirm
        if (confirm != null) {
            interpretConfirm = null
            if (yes) rememberAnchor(confirm) else speak("Okay, not saved.", NarrationTier.QUEUED)
            return
        }
        val (sentence, fallback) = interpretPending ?: return
        interpretPending = null
        // An unclear answer is not consent; it is asked again another day.
        if (!yes && intent !is RiderIntent.No) {
            fallback()
            return
        }
        mindMapStore.interpretAsked = true
        mindMapStore.interpretConsent = yes
        if (yes) runInterpret(sentence, fallback)
        else {
            speak("Okay, I'll keep everything on this phone.", NarrationTier.QUEUED)
            fallback()
        }
    }

    /** Silence to the question: nothing is sent, and a reading is not saved. */
    private fun interpretNoAnswer() {
        interpretConfirm = null
        interpretPending?.second?.invoke()
        interpretPending = null
    }

    // =================================================================================
    //  After the ride: optional feedback, then the next journey
    //
    //     paid ─▶ "How was your ride with Karthik?"  (1–5 / report a problem / skip / silence)
    //               │ low rating or "report" ─▶ "What went wrong?" ─▶ read back ─▶ yes ─▶ sent
    //               ▼
    //            "Book another ride now, schedule one for later, or are you done?"
    //               ├─ now      ─▶ "Where would you like to go?"
    //               ├─ later    ─▶ "When?" ─▶ "Where to?" ─▶ read back ─▶ yes ─▶ scheduled
    //               └─ done / silence ─▶ idle, with how to come back
    // =================================================================================

    private fun startFeedback() {
        postRideMisses = 0
        val name = lastDriverName.ifBlank { "your driver" }
        transition(RiderState.Feedback(driverName = name), announce = false)
        speak(
            "How was your ride with $name? Say a number from one to five, say report a problem, or say skip.",
            NarrationTier.QUEUED
        ) { openMic(MicPurpose.FEEDBACK) }
    }

    private fun resolveFeedback(classification: Classification, text: String) {
        val state = uiState.ride as? RiderState.Feedback ?: run {
            finishFeedback(send = false, spokenThanks = false)
            return
        }
        val intent = FeedbackParser.intentOf(text)

        when (state.step) {
            FeedbackStep.RATING -> when {
                // "One. There was no OTP verification": a rating and the report in one breath.
                // Keep both — found in the demo, where the words after the number were lost.
                intent == FeedbackParser.Intent.RATING && FeedbackParser.wordCount(
                    FeedbackParser.cleanReport(text, FeedbackParser.rating(text))
                ) >= 3 -> {
                    val rating = FeedbackParser.rating(text) ?: 3
                    confirmReport(state.copy(rating = rating), FeedbackParser.cleanReport(text, rating), withRating = true)
                }
                intent == FeedbackParser.Intent.RATING -> takeRating(state, FeedbackParser.rating(text) ?: 3)
                intent == FeedbackParser.Intent.REPORT ->
                    askFeedback(state.copy(step = FeedbackStep.REPORT), "Tell me what happened.")
                intent == FeedbackParser.Intent.SKIP || classification.intent is RiderIntent.No ->
                    finishFeedback(send = false, spokenThanks = false)
                // A sentence rather than a number is usually the report itself.
                text.trim().split(Regex("\\s+")).size >= 4 -> confirmReport(state, FeedbackParser.cleanReport(text))
                else -> {
                    postRideMisses++
                    if (postRideMisses >= 2) finishFeedback(send = false, spokenThanks = false)
                    else askFeedback(state, "Say a number from one to five, or say skip.")
                }
            }

            // "One out of five. Is that right?"
            FeedbackStep.RATING_CHECK -> {
                val said = FeedbackParser.rating(text)
                when {
                    // "No, five": the corrected number wins over the no.
                    said != null && said != state.rating -> takeRating(state, said)
                    classification.intent is RiderIntent.Yes || (said != null && said == state.rating) -> {
                        engine.earcon(Earcon.UNDERSTOOD)
                        askFeedback(state.copy(step = FeedbackStep.REPORT),
                            "Sorry it wasn't good. What went wrong? Say it now, or say skip.")
                    }
                    classification.intent is RiderIntent.No ->
                        askFeedback(state.copy(rating = null, step = FeedbackStep.RATING),
                            "Okay. Say a number from one to five.")
                    intent == FeedbackParser.Intent.SKIP -> finishFeedback(send = false, spokenThanks = false)
                    else -> {
                        postRideMisses++
                        if (postRideMisses >= 2) finishFeedback(send = false, spokenThanks = false)
                        else askFeedback(state, "Say yes if ${state.rating} out of 5 is right, or say the number again.")
                    }
                }
            }

            FeedbackStep.REPORT -> {
                val report = FeedbackParser.cleanReport(text, state.rating)
                if ((intent == FeedbackParser.Intent.SKIP && text.trim().split(Regex("\\s+")).size <= 3) || report.isBlank()) {
                    finishFeedback(send = state.rating != null, spokenThanks = state.rating != null)
                } else {
                    confirmReport(state, report)
                }
            }

            FeedbackStep.CONFIRM -> when {
                classification.intent is RiderIntent.Yes && FeedbackParser.wordCount(FeedbackParser.cleanReport(text)) < 3 -> {
                    uiState = uiState.copy(ride = state.copy(step = FeedbackStep.SENT))
                    finishFeedback(send = true, spokenThanks = true)
                }
                // "Send it" / "send this as feedback" means yes. Anything more they said with it
                // is added and read back once more, so nothing is sent unheard.
                FeedbackParser.isSendCommand(text) -> {
                    val extra = FeedbackParser.cleanReport(text)
                    if (FeedbackParser.wordCount(extra) >= 3) {
                        confirmReport(state, "${state.report}. $extra".trim('.', ' '))
                    } else {
                        uiState = uiState.copy(ride = state.copy(step = FeedbackStep.SENT))
                        finishFeedback(send = true, spokenThanks = true)
                    }
                }
                // "Yes, and he was also on his phone": add it and read back once more.
                classification.intent is RiderIntent.Yes ->
                    confirmReport(state, "${state.report}. ${FeedbackParser.afterYes(text)}".trim('.', ' '))
                classification.intent is RiderIntent.No -> {
                    postRideMisses++
                    if (postRideMisses >= 2) finishFeedback(send = state.rating != null, spokenThanks = false)
                    else askFeedback(state.copy(step = FeedbackStep.REPORT, category = "", report = ""),
                        "Okay. Tell me again, or say skip.")
                }
                intent == FeedbackParser.Intent.SKIP -> finishFeedback(send = state.rating != null, spokenThanks = false)
                // More words: the rider is adding to or correcting the report.
                else -> {
                    val extra = FeedbackParser.cleanReport(text)
                    if (extra.isBlank()) askFeedback(state, "Say yes to send it, or no to change it.")
                    else confirmReport(state, "${state.report}. $extra".trim('.', ' '))
                }
            }

            FeedbackStep.SENT -> finishFeedback(send = false, spokenThanks = false)
        }
    }

    /**
     * A rating was heard. High ones are thanked with the number said back, so a mishearing is
     * audible; low ones are read back and confirmed before anything is sent.
     */
    private fun takeRating(state: RiderState.Feedback, rating: Int) {
        postRideMisses = 0
        engine.earcon(Earcon.UNDERSTOOD)
        if (rating <= 2) {
            askFeedback(state.copy(rating = rating, step = FeedbackStep.RATING_CHECK),
                "$rating out of 5. Is that right?")
        } else {
            uiState = uiState.copy(ride = state.copy(rating = rating, step = FeedbackStep.SENT))
            finishFeedback(send = true, spokenThanks = true)
        }
    }

    /**
     * Reads the report back exactly as it will be sent — the admin sees these same words — and
     * asks before sending. With [withRating] the rating heard in the same sentence is read too.
     */
    private fun confirmReport(state: RiderState.Feedback, words: String, withRating: Boolean = false) {
        val category = FeedbackParser.categorise(words)
        engine.earcon(Earcon.UNDERSTOOD)
        val lead = if (withRating && state.rating != null) "${state.rating} out of 5. " else ""
        askFeedback(
            state.copy(step = FeedbackStep.CONFIRM, category = category.name, report = words.trim()),
            "${lead}I'll report this as ${category.spoken}: ${words.trim()}. Shall I send it?"
        )
    }

    private fun askFeedback(next: RiderState.Feedback, question: String) {
        transition(next, announce = false)
        speak(question, NarrationTier.QUEUED) { openMic(MicPurpose.FEEDBACK) }
    }

    /**
     * Sends whatever feedback there is (if asked to), then moves on to the next journey.
     * The send is fire-and-forget: a rider is never kept waiting on a server to say goodbye.
     */
    private fun finishFeedback(send: Boolean, spokenThanks: Boolean) {
        val state = uiState.ride as? RiderState.Feedback
        val rideId = lastCompletedRideId
        val urgent = state?.category == FeedbackParser.Category.SAFETY.name
        if (send && state != null && rideId != null && (state.rating != null || state.report.isNotBlank())) {
            viewModelScope.launch {
                val result = api.submitFeedback(
                    rideId, state.rating, state.category.ifBlank { null }, state.report.ifBlank { null }
                )
                if (result is ApiResult.Failed) Log.w(TAG, "FEEDBACK not sent: ${result.detail}")
            }
            Log.i(TAG, "FEEDBACK ride=$rideId rating=${state.rating} category=${state.category.ifBlank { "-" }}")
        }
        val thanks = when {
            urgent && send -> "I've sent it and marked it urgent. If you are in danger now, press the S O S button. "
            spokenThanks && state?.report?.isNotBlank() == true -> "Sent. Thank you for telling me. "
            spokenThanks && state?.rating != null -> "${state.rating} out of 5. Thank you. "
            spokenThanks -> "Thank you. "
            else -> ""
        }
        askNextJourney(thanks)
    }

    private fun askNextJourney(lead: String = "") {
        postRideMisses = 0
        draftAt = null
        draftPhrase = ""
        draftPlace = null
        transition(RiderState.NextJourney(NextStep.CHOOSE), announce = false)
        speak(
            "${lead}Would you like to book another ride now, schedule one for later, or are you done?",
            NarrationTier.QUEUED
        ) { openMic(MicPurpose.NEXT_JOURNEY) }
    }

    private fun resolveNextJourney(classification: Classification, text: String) {
        val state = uiState.ride as? RiderState.NextJourney ?: return
        when (state.step) {
            NextStep.CHOOSE -> {
                // Said it all at once: "tomorrow at 8 30".
                val at = SpokenTime.parse(text, ZonedDateTime.now(SpokenTime.zone()))
                if (at != null) {
                    draftAt = at
                    askWhere()
                    return
                }
                // Or named a place: "take me to Gandhipuram" — that is "now", with the answer.
                // ("book another ride" also parses as a booking, to a place called "other
                // ride"; words like that are not a destination.)
                if (classification.kind == UtteranceClass.BOOKING) {
                    val book = classification.intent as RiderIntent.Book
                    val real = book.destinationQuery.split(" ")
                        .filter { it.isNotBlank() && it !in NOT_A_PLACE }
                    if (real.isNotEmpty()) {
                        endPostRide(null)
                        resolveDestination(book.destinationQuery, book.rawDestination, book.rideType)
                        return
                    }
                }
                when (NextJourneyChoice.classify(text)) {
                    NextJourneyChoice.Kind.NOW -> {
                        endPostRide(null)
                        askDestinationAgain("Where would you like to go?")
                    }
                    NextJourneyChoice.Kind.LATER -> startScheduling(fromIdle = false)
                    NextJourneyChoice.Kind.DONE -> finishPostRide("Okay. Hold anywhere when you need a ride.")
                    NextJourneyChoice.Kind.NONE -> {
                        postRideMisses++
                        if (postRideMisses >= 2) finishPostRide("I'll wait. Hold anywhere when you need a ride.")
                        else askNext(state, "Say book now, schedule, or done.")
                    }
                }
            }

            NextStep.WHEN -> {
                if (classification.intent is RiderIntent.Back || classification.intent is RiderIntent.No) {
                    askNextJourney()
                    return
                }
                val at = SpokenTime.parse(text, ZonedDateTime.now(SpokenTime.zone()))
                if (at == null) {
                    postRideMisses++
                    if (postRideMisses >= 3) endPostRide("Let's leave it for now. Say schedule a ride any time.")
                    else askNext(state, "I didn't get a time. Say something like tomorrow at 8 30, or in two hours.")
                    return
                }
                postRideMisses = 0
                draftAt = at
                askWhere()
            }

            NextStep.WHERE -> {
                if (classification.intent is RiderIntent.Back) {
                    startScheduling(fromIdle = false)
                    return
                }
                val extraction = com.cabeye.rider.intent.IntentParser.extract(text)
                val phrase = extraction.rawDestination.ifBlank { Stopwords.normalise(text) }.trim()
                if (Stopwords.strip(phrase).isBlank()) {
                    postRideMisses++
                    if (postRideMisses >= 3) endPostRide("Let's leave it for now. Say schedule a ride any time.")
                    else askNext(state, "Where should the ride go?")
                    return
                }
                postRideMisses = 0
                draftPhrase = phrase
                // Look it up in the rider's places so the confirmation names the real place;
                // anything else is resolved normally when the time comes.
                draftPlace = if (app.memory.enabled) {
                    val moment = memoryMoment()
                    (PreferenceMemoryAgent.direct(phrase, app.memory.current, moment)
                        ?: PreferenceMemoryAgent.repair(phrase, app.memory.current, moment, memoryThresholds()))?.place
                } else null
                val when_ = draftAt?.let { SpokenTime.spoken(it, ZonedDateTime.now(SpokenTime.zone())) } ?: ""
                val to = draftPlace?.name ?: phrase
                askNext(
                    RiderState.NextJourney(NextStep.CONFIRM, scheduledAtText = when_, scheduledTo = to),
                    "A ride to $to, $when_. Shall I schedule it?"
                )
            }

            NextStep.CONFIRM -> when {
                classification.intent is RiderIntent.Yes -> saveSchedule()
                classification.intent is RiderIntent.No || classification.intent is RiderIntent.Back -> {
                    speak("Okay, let's try again.", NarrationTier.QUEUED)
                    startScheduling(fromIdle = false)
                }
                else -> {
                    postRideMisses++
                    if (postRideMisses >= 2) endPostRide("I didn't schedule it. Say schedule a ride any time.")
                    else askNext(state, "Say yes to schedule it, or no to change it.")
                }
            }

            NextStep.SCHEDULED -> endPostRide(null)
        }
    }

    /** "schedule a ride" from anywhere, or "later" from the next-journey question. */
    private fun startScheduling(fromIdle: Boolean) {
        postRideMisses = 0
        draftAt = null
        draftPhrase = ""
        draftPlace = null
        if (fromIdle && activeRideId != null) {
            speak("You're on a ride now. Ask me again when it's finished.", NarrationTier.QUEUED)
            return
        }
        askNext(
            RiderState.NextJourney(NextStep.WHEN),
            "When should I book it? For example, tomorrow at 8 30, or in two hours."
        )
    }

    private fun askWhere() {
        val when_ = draftAt?.let { SpokenTime.spoken(it, ZonedDateTime.now(SpokenTime.zone())) } ?: ""
        askNext(
            RiderState.NextJourney(NextStep.WHERE, scheduledAtText = when_),
            "$when_. Where should the ride go?"
        )
    }

    private fun askNext(next: RiderState.NextJourney, question: String) {
        transition(next, announce = false)
        speak(question, NarrationTier.QUEUED) { openMic(MicPurpose.NEXT_JOURNEY) }
    }

    private fun saveSchedule() {
        val at = draftAt ?: return startScheduling(fromIdle = false)
        val place = draftPlace
        val saved = app.scheduler.add(
            ScheduledRide(
                id = "",
                at = at.toInstant().toEpochMilli(),
                destinationPhrase = draftPhrase,
                placeName = place?.name.orEmpty(),
                latitude = place?.latitude,
                longitude = place?.longitude,
                address = place?.address.orEmpty(),
                placeId = place?.placeId.orEmpty(),
                rideType = preferredRideType().name
            )
        )
        val spokenAt = SpokenTime.spoken(at, ZonedDateTime.now(SpokenTime.zone()))
        engine.earcon(Earcon.BOOKING_CONFIRMED)
        uiState = uiState.copy(
            ride = RiderState.NextJourney(NextStep.SCHEDULED, scheduledAtText = spokenAt, scheduledTo = saved.spokenDestination)
        )
        endPostRide(
            "Scheduled for $spokenAt. When it's time I'll tell you and book your ride to " +
                "${saved.spokenDestination}, with the usual chance to cancel.",
            keepScreen = true
        )
    }

    /**
     * Leaves the post-ride questions. [line] is said on the way out (null for silence), and the
     * screen returns to idle once it has been said.
     */
    private fun endPostRide(line: String?, keepScreen: Boolean = false) {
        postRideMisses = 0
        setPayment(null)
        if (line == null) {
            transition(RiderState.Idle, announce = false)
            return
        }
        if (!keepScreen) transition(RiderState.Idle, announce = false)
        speak(line, NarrationTier.QUEUED) {
            if (uiState.ride is RiderState.NextJourney) transition(RiderState.Idle, announce = false)
        }
    }

    // ---------------------------------------------------------------------------------
    //  Scheduled rides coming due
    // ---------------------------------------------------------------------------------

    private fun watchSchedules() {
        viewModelScope.launch {
            app.dueScheduledRide.collect { id -> if (id != null) startDueRide() }
        }
    }

    /**
     * A scheduled ride is due. Started only from a quiet moment — idle, or at the end of the
     * post-ride questions — and never on top of a ride in progress. If the moment is not quiet
     * the id stays pending and is picked up the next time the app returns to idle.
     */
    private fun startDueRide() {
        val id = app.dueScheduledRide.value ?: return
        val ride = uiState.ride
        // A finished ride that is still waiting to be paid is not a quiet moment: starting a
        // new booking there would throw away the payment panel the rider is using.
        val paidOrNoFare = ride is RiderState.Done &&
            (uiState.payment?.phase == PaymentPhase.PAID || ride.fareRupees == 0)
        val quiet = activeRideId == null && !uiState.micOpen &&
            (ride is RiderState.Idle || ride is RiderState.NextJourney || paidOrNoFare)
        if (!quiet) return
        // Opened cold from the notification, the voice may still be warming up; the first
        // sentence here is the only warning the rider gets that a booking is starting.
        if (!app.audioReady) {
            viewModelScope.launch {
                val deadline = System.currentTimeMillis() + 6_000L
                while (!app.audioReady && System.currentTimeMillis() < deadline) delay(100)
                if (app.audioReady || System.currentTimeMillis() >= deadline) startDueRideNow()
            }
            return
        }
        startDueRideNow()
    }

    private fun startDueRideNow() {
        val id = app.dueScheduledRide.value ?: return

        app.dueScheduledRide.value = null
        val scheduled = app.scheduler.find(id) ?: return
        app.scheduler.remove(id)
        Log.i(TAG, "SCHEDULE starting id=$id to=\"${scheduled.spokenDestination}\"")

        setPayment(null)
        val rideType = runCatching { RideType.valueOf(scheduled.rideType) }.getOrDefault(RideType.AUTO)
        val lat = scheduled.latitude
        val lng = scheduled.longitude
        speak("It's time for your scheduled ride to ${scheduled.spokenDestination}.", NarrationTier.INTERRUPT) {
            if (lat != null && lng != null) {
                beginOptimisticBooking(
                    PlaceOption(
                        name = scheduled.placeName.ifBlank { scheduled.destinationPhrase },
                        latitude = lat, longitude = lng, score = 1f, coversWholeToken = true,
                        formattedAddress = scheduled.address, placeId = scheduled.placeId
                    ),
                    rideType,
                    note = "as scheduled"
                )
            } else {
                resolveDestination(
                    Stopwords.strip(scheduled.destinationPhrase), scheduled.destinationPhrase, rideType
                )
            }
        }
    }

    /**
     * The post-ride buttons, for a sighted helper or a TalkBack user:
     * "skip" / "report" on the feedback screen, "now" / "schedule" / "done" on the next one.
     */
    fun onPostRideAction(action: String) {
        stt?.cancel()
        closeMic()
        engine.stopSpeaking()
        val ride = uiState.ride
        when (action) {
            "skip" -> if (ride is RiderState.Feedback) finishFeedback(send = ride.rating != null, spokenThanks = false)
            "report" -> if (ride is RiderState.Feedback) askFeedback(ride.copy(step = FeedbackStep.REPORT), "Tell me what happened.")
            "send" -> if (ride is RiderState.Feedback) finishFeedback(send = true, spokenThanks = true)
            "rating-yes" -> if (ride is RiderState.Feedback && ride.step == FeedbackStep.RATING_CHECK) {
                askFeedback(ride.copy(step = FeedbackStep.REPORT), "Sorry it wasn't good. What went wrong? Say it now, or say skip.")
            }
            "rating-no" -> if (ride is RiderState.Feedback && ride.step == FeedbackStep.RATING_CHECK) {
                askFeedback(ride.copy(rating = null, step = FeedbackStep.RATING), "Okay. Say a number from one to five.")
            }
            "now" -> { endPostRide(null); askDestinationAgain("Where would you like to go?") }
            "schedule" -> startScheduling(fromIdle = false)
            "confirm" -> if (ride is RiderState.NextJourney && ride.step == NextStep.CONFIRM) saveSchedule()
            "done" -> endPostRide("Okay. Hold anywhere when you need a ride.")
            // Walking-route buttons.
            "walk-mark", "walk-left", "walk-right", "walk-done", "walk-next", "walk-repeat", "walk-stop" -> onWalkAction(action)
            // Multi-stop buttons, for a sighted helper.
            "im-back" -> {
                val stop = currentStop()
                if (stop != null && stop.status == "WAITING" && !stop.riderBack) {
                    listenForStopCode("Welcome back. Ask the driver to say the code.")
                }
            }
            "plan-yes" -> when {
                pendingRoutine != null -> acceptRoutine()
                planStage == PlanStage.REVIEW -> bookPlan()
            }
            "plan-no" -> when {
                pendingRoutine != null -> rejectRoutine()
                planStage == PlanStage.REVIEW -> {
                    showPlan("What would you like to change?")
                    speak("What would you like to change? Say remove, swap, add a stop, or cancel.", NarrationTier.QUEUED) {
                        openMic(MicPurpose.PLAN_REVIEW)
                    }
                }
            }
        }
    }

    private fun speakScheduled() {
        val upcoming = app.scheduler.upcoming().filter { it.at > System.currentTimeMillis() }
        val now = ZonedDateTime.now(SpokenTime.zone())
        val line = when (upcoming.size) {
            0 -> "You have no rides scheduled. Say schedule a ride to book one for later."
            1 -> "You have one ride scheduled: to ${upcoming[0].spokenDestination}, " +
                "${SpokenTime.spoken(upcoming[0].atZoned(), now)}."
            else -> "You have ${upcoming.size} rides scheduled. " + upcoming.take(3).joinToString(" ") {
                "To ${it.spokenDestination}, ${SpokenTime.spoken(it.atZoned(), now)}."
            }
        }
        speak(line, NarrationTier.QUEUED)
    }

    private fun cancelScheduled() {
        val count = app.scheduler.upcoming().size
        app.scheduler.clear()
        speak(
            if (count == 0) "You have no scheduled rides." else
                "Cancelled ${if (count == 1) "your scheduled ride" else "all $count scheduled rides"}.",
            NarrationTier.QUEUED
        )
    }

    private fun ScheduledRide.atZoned(): ZonedDateTime =
        java.time.Instant.ofEpochMilli(at).atZone(SpokenTime.zone())

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
            is RiderIntent.Back -> goBack()
            is RiderIntent.MyPlaces -> speakMyPlaces()
            is RiderIntent.GiveFeedback -> {
                if (lastCompletedRideId == null) {
                    speak("There's no finished ride to give feedback on yet.", NarrationTier.QUEUED)
                } else startFeedback()
            }
            is RiderIntent.ScheduleRide -> startScheduling(fromIdle = true)
            is RiderIntent.ListScheduled -> speakScheduled()
            is RiderIntent.CancelScheduled -> cancelScheduled()
            is RiderIntent.ForgetHistory -> forgetHistory()
            is RiderIntent.SignOut, is RiderIntent.SignIn -> {
                // Either way the sign-in screen comes next: signing out shows it for a new
                // number, and a guest asking to sign in is shown it for the first time.
                app.auth.riderIsGuest = false
                speak(
                    if (intent is RiderIntent.SignOut) "Signing out." else "Okay, let's sign you in.",
                    NarrationTier.INTERRUPT
                )
                app.signOut(com.cabeye.rider.net.AppRole.RIDER)
            }
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
    private fun beginOptimisticBooking(place: PlaceOption, rideType: RideType, note: String = "") {
        // While a route is being planned, a found place belongs to the leg being asked about.
        if (planStage == PlanStage.RESOLVING && plan != null) {
            onPlanLegResolved(place)
            return
        }
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
            bookingPlan?.let { planAnnouncement(it, place, rideType) } ?: buildBookingAnnouncement(place, rideType, note),
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

    /**
     * @param note why this place, when the memory chose it ("one of your usual places"). Said
     *   so the rider knows the app is going on their history rather than on what it heard.
     */
    private fun buildBookingAnnouncement(place: PlaceOption, rideType: RideType, note: String = ""): String {
        val address = place.formattedAddress.takeIf { it.isNotBlank() }
        val why = if (note.isNotBlank()) ", $note" else ""
        return if (address != null) {
            "Booking ${rideType.spokenName} to ${place.name}$why, $address. To stop it, hold the screen and say cancel."
        } else {
            "Booking ${rideType.spokenName} to ${place.name}$why. To stop it, hold the screen and say cancel."
        }
    }

    /** "Booking auto to PSG College, with 2 stops: Apollo Pharmacy, then Gandhipuram. Say cancel to stop." */
    private fun planAnnouncement(p: TripPlan, place: PlaceOption, rideType: RideType): String {
        val n = p.stops.size
        val stops = p.stops.joinToString(", then ") { it.name }
        return "Booking ${rideType.spokenName} to ${place.name}, with $n ${if (n == 1) "stop" else "stops"}: $stops. To stop it, hold the screen and say cancel."
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
                dropNote = chosenDropNote,
                spokenAs = pendingSpokenAs.also { pendingSpokenAs = "" },
                stops = bookingPlan?.stops.orEmpty()
            )) {
                is ApiResult.Ok -> {
                    val snapshot = result.value
                    activeRideId = snapshot.rideId
                    activeStops = snapshot.stops
                    bookingPlan = null
                    expectedCode = snapshot.boardingCode
                    // A new ride: the last ride's payment panel and watcher belong to it, not this.
                    paymentPollJob?.cancel()
                    setPayment(null)
                    lastSpokenPhase = RidePhase.REQUESTED

                    Log.i(TAG, "RIDE created id=${snapshot.rideId} code=${snapshot.boardingCode}")
                    Telemetry.logSocket("RIDE_CREATED", snapshot.rideId, "dest=${place.name}")

                    socket.subscribe(snapshot.rideId)
                }

                is ApiResult.Failed -> {
                    // The booking failed, and this is the moment a silent app would be at its
                    // most dangerous: the rider has been told a cab is coming. It is not.
                    Log.w(TAG, "RIDE creation failed: ${result.detail}")
                    bookingPlan = null
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
    /**
     * "5.6 kilometres" / "about 5.2 kilometres", or "" when nothing was measured. One decimal,
     * written with a dot so every TTS voice reads it as "five point six".
     */
    private fun spokenDistance(metres: Int, estimate: Boolean): String {
        if (metres <= 0) return ""
        val km = "%.1f".format(java.util.Locale.ROOT, metres / 1000.0)
        return (if (estimate) "about " else "") + "$km kilometres"
    }

    private fun endRide(reason: String) {
        stopCamera("RIDE_ENDED")
        stopRideGuide(save = reason == "completed")
        socket.unsubscribe(reason)
        lastCompletedRideId = activeRideId
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
        activeStops = emptyList()
        codeForStop = false
        pendingTripAction = null
        addingStopMidTrip = null
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
     *
     * The trip itself is a simulated ride through the real ride guide: "simulate a ride to
     * Koniamman Temple" finds the place in the offline atlas, starts from where the phone is
     * (or Saibaba Colony, outside Coimbatore), and plays made-up GPS along the way — a fix every
     * 0.7 s standing for 15 s of driving — so cues, area names, "where am I", "almost there" and
     * the nearby offer can all be heard and tried. Simulate the same ride twice to hear a repeat
     * ride: the preview, your cues coming up, the usual time.
     */
    private fun startDemoRide(said: String = "") {
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

        demoRideJob = viewModelScope.launch {
            val atlas = AtlasStore.get(getApplication())
            val wanted = Regex("\\b(?:ride|mode)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).find(said.trim())?.groupValues?.get(1)
            val here = runCatching { app.locationProvider.current() }.getOrNull()
            val fromLat = here?.latitude?.takeIf { atlas?.covers(it, here.longitude) == true } ?: DEMO_START.first
            val fromLng = here?.longitude?.takeIf { atlas?.covers(here.latitude, it) == true } ?: DEMO_START.second
            val dest: PlaceOption = wanted?.let { atlas?.search(it, fromLat, fromLng)?.firstOrNull { o -> o.score >= MatchGate.CONFIDENCE_FLOOR } }
                ?: atlas?.search("Coimbatore Junction")?.firstOrNull()
                ?: Gazetteer.activeCity.places.first().let { PlaceOption(it.name, it.latitude, it.longitude, 1f) }
            val destination = dest.name
            if (wanted != null && !dest.name.contains(wanted, ignoreCase = true)) {
                Log.i(TAG, "DEMO heard=\"$wanted\" using=\"${dest.name}\"")
            }

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

            // ---- the trip: synthetic GPS through the real ride guide ----------------------
            lastBookingPlace = dest
            val route = SyntheticRide.route(fromLat, fromLng, dest.latitude, dest.longitude, System.currentTimeMillis())
            val minutes = ((route.last().t - route.first().t) / 60_000).toInt()
            transition(RiderState.InTrip(destination, etaMinutes = minutes, driver = driver))
            speak("On the way to $destination. This is a simulated ride, about $minutes minutes, played fast.", NarrationTier.QUEUED)
            engine.heartbeat(true)
            startRideGuide(synthetic = true)
            val guide = rideGuide
            route.forEachIndexed { i, fix ->
                delay(DEMO_FIX_MS)
                if (guide == null || rideGuide !== guide) return@forEachIndexed
                tripMeters = guide.metres.toInt()
                guide.onFix(fix.lat, fix.lng, fix.t).forEach(::sayRideGuide)
                if (i % 4 == 0) areaName(fix.lat, fix.lng)?.let { area -> guide.onArea(area, fix.t)?.let(::sayRideGuide) }
            }

            engine.heartbeat(false)
            val metres = SyntheticRide.metres(route).toInt()
            lastRidePlace = dest
            lastRideEndedAt = System.currentTimeMillis()
            transition(RiderState.Done(destination, fareRupees = 25 + metres * 15 / 1000, durationMinutes = minutes, distanceMeters = metres))
            speak("You've arrived. ${spokenDistance(metres, estimate = false)}. Simulated ride, no fare.", NarrationTier.QUEUED)
            prepareNearbyAfterRide(dest)
            stopRideGuide(save = true)
            tripMeters = 0
            delay(4_000)
            // No payment or feedback in a simulation: straight to what comes after them.
            if (uiState.ride is RiderState.Done) {
                transition(RiderState.Idle, announce = false)
                finishPostRide(null)
            }
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
        recentSpoken.addLast(System.currentTimeMillis() to text)
        while (recentSpoken.size > 6) recentSpoken.removeFirst()
        engine.speak(
            text,
            tier,
            onStart = onStart?.let { cb -> { onMain { cb() } } },
            onDone = onDone?.let { cb -> { onMain { cb() } } }
        )
    }

    /**
     * The narrator's speed and volume, by voice. Speed is remembered on this phone; volume is
     * the phone's own media volume, the same one the volume keys change.
     */
    private fun handleVoiceSettings(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.split(Regex("\\s+")).size > 5) return false

        val rateChange = when {
            Regex("\\b(slower|slow down|more slowly|too fast)\\b").containsMatchIn(t) -> -0.15f
            Regex("\\b(faster|speed up|more quickly|too slow)\\b").containsMatchIn(t) -> 0.15f
            else -> null
        }
        if (rateChange != null) {
            val rate = (prefs.speechRate + rateChange).coerceIn(0.6f, 1.6f)
            prefs.speechRate = rate
            engine.setSpeechRate(rate)
            Log.i(TAG, "SETTINGS speechRate=$rate")
            engine.earcon(Earcon.UNDERSTOOD)
            speak(
                when {
                    rateChange < 0 && rate <= 0.6f -> "This is as slow as I go."
                    rateChange > 0 && rate >= 1.6f -> "This is as fast as I go."
                    rateChange < 0 -> "Okay, I'll speak slower. Is this better?"
                    else -> "Okay, I'll speak faster. Is this better?"
                },
                NarrationTier.QUEUED
            )
            return true
        }

        val volume = when {
            Regex("\\b(louder|speak up|volume up|can'?t hear|cannot hear)\\b").containsMatchIn(t) ->
                android.media.AudioManager.ADJUST_RAISE
            Regex("\\b(quieter|softer|volume down|too loud)\\b").containsMatchIn(t) ->
                android.media.AudioManager.ADJUST_LOWER
            else -> null
        }
        if (volume != null) {
            val audio = getApplication<Application>().getSystemService(android.media.AudioManager::class.java)
            repeat(2) { audio?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, 0) }
            Log.i(TAG, "SETTINGS volume ${if (volume == android.media.AudioManager.ADJUST_RAISE) "up" else "down"}")
            speak(
                if (volume == android.media.AudioManager.ADJUST_RAISE) "Louder. Is this better?"
                else "Quieter. Is this better?",
                NarrationTier.QUEUED
            )
            return true
        }
        return false
    }

    private fun speakHelp() {
        speak(
            "I cover ${Gazetteer.activeCity.displayName}. Say: take me to a place. " +
                    "Or say my places, list places, status, repeat, go back, cancel, call driver, book again, " +
                    "switch city, or yellow theme. To change how I sound, say speak slower, speak faster, " +
                    "louder, or quieter. A short press on the volume keys also changes the volume. " +
                    "Double tap the screen to explore around you with the camera. " +
                    "Press volume down three times quickly to open your mind map. " +
                    "To teach me places, say remember this as Ravi's tea shop, or ask what's around here. " +
                    "In the cab, hold the screen and say speed bump, or where am I.",
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
            // The meter is only mentioned once the server has measured something; before that,
            // "zero kilometres" would be a number that means "not known yet".
            is RiderState.InTrip ->
                (if (tripMeters > 0) {
                    "On the way to ${ride.destination}. ${spokenDistance(tripMeters, estimate = false)} so far" +
                        (if (tripFareSoFar > 0) ", about $tripFareSoFar rupees." else ".")
                } else "On the way to ${ride.destination}.") +
                    // The ride guide's area and, on a ride taken before, the usual time left.
                    (rideGuide?.briefStatus()?.let { " $it" } ?: "")
            is RiderState.Done -> "That ride is finished."
            is RiderState.Feedback -> "I'm asking about your last ride. Say skip to move on."
            is RiderState.NextJourney -> "Say book now, schedule, or done."
            else -> "Working on it."
        }
        speak(status, NarrationTier.QUEUED)
    }

    private fun handleSpeechError(error: SpeechError) {
        if (micPurpose == MicPurpose.EXPLORE) {
            if (error == SpeechError.NO_MATCH || error == SpeechError.NO_SPEECH) {
                speak("I didn't catch that. Hold and say describe, find the door, read this, or this is and a name.", NarrationTier.QUEUED)
            }
            return
        }
        if (micPurpose == MicPurpose.WALK) {
            onWalkNoAnswer()
            return
        }
        if (micPurpose == MicPurpose.NEARBY) {
            endNearby()
            return
        }
        if (micPurpose == MicPurpose.INTERPRET) {
            interpretNoAnswer()
            return
        }
        // An ordinary ride: a press with nothing heard answers "where are we", as it always did.
        if (micPurpose == MicPurpose.TRIP_COMMAND && activeStops.isEmpty() && uiState.ride is RiderState.InTrip &&
            (error == SpeechError.NO_MATCH || error == SpeechError.NO_SPEECH)
        ) {
            speakStatus()
            return
        }
        // Mid-ride stop words: never a reason to leave the ride screen.
        if (micPurpose == MicPurpose.TRIP_COMMAND || micPurpose == MicPurpose.STOP_CONFIRM ||
            (micPurpose == MicPurpose.STOP_KIND && addingStopMidTrip != null) ||
            (micPurpose == MicPurpose.CODE_VERIFY && codeForStop)
        ) {
            pendingTripAction = null
            addingStopMidTrip = null
            if (error == SpeechError.NO_MATCH || error == SpeechError.NO_SPEECH) {
                speak("I didn't catch that. Press the screen to try again.", NarrationTier.QUEUED)
            }
            return
        }
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

        // Not heard while answering the camera question: ask once more, then take it as a no.
        if (micPurpose == MicPurpose.CAMERA) {
            if (uiState.camera == CameraShare.ASKING) reaskCamera()
            return
        }

        // Not heard clearly while naming a destination: before the generic "I didn't catch
        // that", let the memory try — once per booking. It uses the half-heard partial if
        // there was one, and otherwise the rider's habit for this time of day: "I didn't catch
        // that. Were you going to PSG College, like most weekday mornings?"
        if ((micPurpose == MicPurpose.BOOKING || micPurpose == MicPurpose.SLOT_ANSWER) &&
            (error == SpeechError.NO_MATCH || error == SpeechError.NO_SPEECH) &&
            !memoryRescueUsed && app.memory.enabled
        ) {
            val heard = lastPartial
            val rescue = if (heard.isNotBlank()) memoryRepair(heard) else memoryGuess()
            if (rescue != null) {
                memoryRescueUsed = true
                Log.i(TAG, "MEMORY rescue error=$error partial=\"$heard\" place=\"${rescue.place.name}\"")
                askMemory(rescue, heard = heard, prompt = repairPrompt(rescue, heardClearly = false))
                return
            }
        }

        consecutiveSpeechErrors++

        if (consecutiveSpeechErrors > MAX_CONSECUTIVE_ERRORS) {
            consecutiveSpeechErrors = 0
            resetPlan()
            pendingRoutine = null
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
                    speak("Sorry. ${options.first.name}, or ${options.second.name}? Or say neither.", NarrationTier.QUEUED) {
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

            MicPurpose.FEEDBACK, MicPurpose.NEXT_JOURNEY -> {
                postRideMisses++
                if (postRideMisses >= 2) {
                    if (micPurpose == MicPurpose.FEEDBACK) finishFeedback(send = true, spokenThanks = false)
                    else finishPostRide("I'll wait. Hold anywhere when you need a ride.")
                } else {
                    speak("Sorry, I didn't catch that. ${lastSpoken}", NarrationTier.QUEUED) {
                        openMic(micPurpose)
                    }
                }
                return
            }

            MicPurpose.PLAN_LEG, MicPurpose.STOP_KIND, MicPurpose.PLAN_REVIEW -> {
                speak("Sorry, I didn't catch that. $lastSpoken", NarrationTier.QUEUED) { openMic(micPurpose) }
                return
            }

            MicPurpose.TRIP_COMMAND, MicPurpose.STOP_CONFIRM, MicPurpose.WALK, MicPurpose.NEARBY,
            MicPurpose.INTERPRET, MicPurpose.EXPLORE -> return

            MicPurpose.MEMORY_ANSWER -> {
                val routine = pendingRoutine
                if (routine != null) {
                    speak("Sorry. ${routine.spokenRoute()}? Yes or no.", NarrationTier.QUEUED) {
                        openMic(MicPurpose.MEMORY_ANSWER)
                    }
                    return
                }
                val pending = pendingMemory
                if (pending != null) {
                    speak("Sorry. ${pending.place.name}? Yes or no.", NarrationTier.QUEUED) {
                        openMic(MicPurpose.MEMORY_ANSWER)
                    }
                    return
                }
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

            MicPurpose.CANCEL_WINDOW, MicPurpose.CODE_VERIFY, MicPurpose.CAMERA -> return
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
        activeStops = snapshot.stops

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
            RidePhase.SEATED, RidePhase.IN_TRIP -> {
                // Back after a dropped connection: pick the meter up from the server.
                tripMeters = maxOf(tripMeters, snapshot.tripDistanceMeters)
                if (snapshot.liveFareRupees > 0) tripFareSoFar = snapshot.liveFareRupees
                // The app was restarted mid-trip: follow the rest of the ride, without the preview.
                if (snapshot.phase == RidePhase.IN_TRIP && rideGuide == null) startRideGuide(resumed = true)
                RiderState.InTrip(snapshot.destination, snapshot.etaMinutes, driver, snapshot.stops)
            }
            RidePhase.COMPLETED -> RiderState.Done(
                snapshot.destination, snapshot.fareRupees, snapshot.durationMinutes,
                distanceMeters = snapshot.tripDistanceMeters
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
        val wasFinding = uiState.ride is RiderState.Finding
        if (awaitingAnswer != null && awaitingAnswer?.second != next::class) clearAwaiting()
        uiState = uiState.copy(ride = next)
        if (next is RiderState.Finding && !wasFinding) startFindingWatch()
        // A scheduled ride that came due while the app was busy starts at the next quiet moment.
        if (next is RiderState.Idle && app.dueScheduledRide.value != null) {
            viewModelScope.launch { startDueRide() }
        }
    }

    private var findingJob: Job? = null

    /**
     * While searching for a driver: the elapsed time on screen, and a spoken update now and
     * then, so a long search never sounds like a stalled app. Ends by itself the moment the
     * state moves on (a driver accepted, a cancel, a failure).
     */
    private fun startFindingWatch() {
        findingJob?.cancel()
        val started = System.currentTimeMillis()
        findingJob = viewModelScope.launch {
            var spokenAt = 0L
            while (true) {
                delay(1_000)
                val current = uiState.ride as? RiderState.Finding ?: return@launch
                val elapsed = System.currentTimeMillis() - started
                uiState = uiState.copy(ride = current.copy(elapsedMillis = elapsed))
                val due = when {
                    spokenAt == 0L && elapsed >= FINDING_FIRST_UPDATE_MS -> true
                    spokenAt > 0L && elapsed - spokenAt >= FINDING_UPDATE_EVERY_MS -> true
                    else -> false
                }
                if (due && !uiState.micOpen) {
                    spokenAt = elapsed
                    val minutes = (elapsed / 60_000).toInt()
                    val waited = if (minutes < 1) "half a minute" else if (minutes == 1) "a minute" else "$minutes minutes"
                    speak(
                        "Still looking for a driver. It's been $waited. No driver has accepted yet; " +
                            "I'll tell you the moment one does.",
                        NarrationTier.QUEUED
                    )
                }
            }
        }
    }

    /** TTS callbacks arrive on a binder thread; state updates must not. */
    private inline fun onMain(crossinline block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun onCleared() {
        cameraStreamer.stop()
        cancelWindowJob?.cancel()
        silenceJob?.cancel()
        micReopenJob?.cancel()
        codeListenJob?.cancel()
        paymentPollJob?.cancel()
        listPlacesJob?.cancel()
        demoRideJob?.cancel()
        findingJob?.cancel()
        rideVisionJob?.cancel()
        if (explore.ui.open) explore.close(save = true)
        runCatching { sceneDetector.close() }
        stt?.cancel()
        engine.heartbeat(false)
        // The socket is application-scoped and deliberately NOT closed here. A rotation or a
        // brief backgrounding destroys this view model; tearing the ride's connection down with
        // it would cost the rider a "Connection lost / Connected" pair for an event that was
        // not an outage at all.
        super.onCleared()
    }
}
