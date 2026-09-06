package com.cabeye.rider.state

/**
 * The eleven states of the single rider surface.
 *
 * The flow is:
 * ```
 * idle -> listening -> resolving -> [clarify] -> confirming -> finding
 *      -> assigned -> approaching -> arrived -> intrip -> done
 * ```
 * `clarify` is bracketed because it is skipped whenever the confidence gate says the
 * question is not worth the user's time (see [Clarify]).
 *
 * ## Why a sealed interface rather than an enum
 * Each state carries different data, and modelling that as an enum plus a bag of nullable
 * fields would make it possible to be in `Idle` while holding a driver's ETA. A sealed
 * hierarchy makes the illegal combinations unrepresentable, and lets the `when` in
 * `RiderSurface` be exhaustive without an `else` branch — so adding a twelfth state later
 * becomes a compile error at every place that must handle it, instead of a silent
 * fall-through to a blank screen.
 *
 * ## Note on the SOS overlay
 * SOS is deliberately **not** a member of this hierarchy. It is an overlay that can be
 * raised on top of any state, and folding it in would mean either duplicating every state
 * or losing the underlying one while SOS is active — and losing it is unacceptable, since
 * dismissing SOS must return the rider to exactly where they were. It lives on
 * [RiderUiState.sosActive] instead.
 */
sealed interface RiderState {

    /** Nothing in progress. The whole screen is a press target waiting for a booking. */
    data object Idle : RiderState

    /**
     * Microphone is open and capturing.
     *
     * @param partialTranscript streaming partial result from on-device recognition; shown
     *   for sighted observers only, never spoken back (principle 3: speech time is task time)
     * @param inputLevel normalised 0..1 mic level, drives the visual ring only
     * @param isFollowUp true when this listen was opened to answer a question the app just
     *   asked. Principle 2 — if the app asked out loud, it must be listening — so this flag
     *   exists to make an unanswered prompt detectable rather than invisible.
     */
    data class Listening(
        val partialTranscript: String = "",
        val inputLevel: Float = 0f,
        val isFollowUp: Boolean = false
    ) : RiderState

    /**
     * Transcript captured; intent extraction and place lookup are running.
     *
     * @param transcript the final recognised utterance
     * @param usedLlm false while the regex fast path is being tried, true once the request
     *   has been escalated to the LLM. Logged per ride as evaluation data.
     */
    data class Resolving(
        val transcript: String,
        val usedLlm: Boolean = false
    ) : RiderState

    /**
     * Two candidate destinations were close enough in score, and far enough apart on the
     * map, that guessing wrong would cost the rider real time.
     *
     * The gate is:
     * ```
     * gap < DELTA (0.16)  &&  distanceKm > DIVERGE (1.5)  ->  ask
     * gap < DELTA         &&  distanceKm <= DIVERGE       ->  don't ask, just book
     * ```
     * Both numbers are carried here rather than recomputed, so the decision that produced
     * this state can be logged verbatim alongside it.
     *
     * @param optionA higher-scoring candidate
     * @param optionB runner-up. Equal to [optionA] when [isNearMiss] is true, where there is
     *   only ever one candidate on the table.
     * @param scoreGap `optionA.score - optionB.score`; below [CONFIDENCE_DELTA] to reach here
     * @param divergenceKm straight-line distance between the two candidates; above
     *   [DIVERGENCE_KM] to reach here
     * @param isNearMiss true when this is the **near-miss** question — a single candidate that
     *   scored in the 0.30–0.50 band, plausible but not strong enough to act on, asked as
     *   "Did you mean Adyar?" with a yes or no answer.
     *
     *   Folded into this state rather than given a twelfth of its own because it is the same
     *   thing from the rider's point of view: the app is unsure and is asking rather than
     *   guessing. Splitting it would mean a twelfth screen, a twelfth announcement and a
     *   twelfth haptic, all to express a distinction only the scorer cares about.
     */
    data class Clarify(
        val optionA: PlaceOption,
        val optionB: PlaceOption,
        val scoreGap: Float,
        val divergenceKm: Float,
        val isNearMiss: Boolean = false
    ) : RiderState

    /**
     * Optimistic execution: the ride is **already being booked**. This is not a
     * confirmation question — it is a cancellation window.
     *
     * The app says "Booking auto to Anna Nagar East. Say cancel to stop." and keeps the
     * microphone open for [cancelWindowMillisRemaining]. Saying "cancel" genuinely aborts.
     *
     * @param destination resolved place name, as it will be spoken
     * @param rideType vehicle class
     * @param cancelWindowMillisRemaining counts down from [CANCEL_WINDOW_MS] to 0
     */
    data class Confirming(
        val destination: String,
        val rideType: RideType,
        val cancelWindowMillisRemaining: Long = CANCEL_WINDOW_MS
    ) : RiderState

    /**
     * Searching for a driver. A quiet system-alive heartbeat runs throughout — principle 4,
     * silence must mean something.
     */
    data class Finding(
        val elapsedMillis: Long = 0L
    ) : RiderState

    /** A driver accepted. Tier-1 narration: queued, does not interrupt. */
    data class Assigned(
        val driver: DriverInfo,
        val etaMinutes: Int
    ) : RiderState

    /**
     * Driver is en route to the pickup point.
     *
     * **This phase is wordless.** A tone every ~1.5 s rises in pitch as the car nears and is
     * panned to the driver's real bearing. Narrating distance every few seconds would eat
     * the user's attention for information an earcon conveys instantly.
     *
     * @param distanceMeters drives earcon pitch
     * @param bearingDegrees driver's bearing relative to device heading, 0..360; drives pan
     */
    data class Approaching(
        val driver: DriverInfo,
        val distanceMeters: Int,
        val bearingDegrees: Float
    ) : RiderState

    /**
     * Driver has arrived. Tier-0 (interrupting) announcement plus a distinct haptic.
     *
     * @param expectedCode the boarding code the **driver** will read aloud. The reversal is
     *   the point: the rider's phone never announces a secret over a loudspeaker to a street
     *   where anyone could be listening. The driver says it, the rider's app verifies it,
     *   and hearing the correct code is exactly the check a blind rider cannot do visually.
     *   Spoken through the earpiece only when headphones are connected.
     * @param headphonesConnected gates whether the expected code may be spoken at all
     */
    data class Arrived(
        val driver: DriverInfo,
        val expectedCode: String,
        val headphonesConnected: Boolean = false
    ) : RiderState

    /** Passenger seated, journey underway. Heartbeat runs again. */
    data class InTrip(
        val destination: String,
        val etaMinutes: Int,
        val driver: DriverInfo
    ) : RiderState

    /** Ride complete. */
    data class Done(
        val destination: String,
        val fareRupees: Int,
        val durationMinutes: Int
    ) : RiderState

    companion object {
        /**
         * Score gap below which two candidates count as tied.
         * Specified by the brief as DELTA = 0.16.
         */
        const val CONFIDENCE_DELTA = 0.16f

        /**
         * Distance in km above which two tied candidates are "far enough apart that being
         * wrong would cost them". Specified by the brief as DIVERGE = 1.5 km.
         */
        const val DIVERGENCE_KM = 1.5f

        /** Real, honoured cancellation window for optimistic booking. */
        const val CANCEL_WINDOW_MS = 5_000L
    }
}

/**
 * The complete UI state: the ride state plus anything orthogonal to it.
 *
 * @param ride where the ride currently is
 * @param sosActive whether the SOS overlay is raised on top of [ride]
 * @param isSpeaking true while TTS is producing audio. The microphone must stay closed
 *   for this whole period, otherwise the recogniser hears the phone's own voice and
 *   transcribes the app talking to itself.
 * @param handsFreeEnabled when true the rider can simply speak, with no press at all
 * @param connected WebSocket health. A disconnect has to be audible, because otherwise it
 *   presents as silence — and silence is supposed to mean "working".
 * @param micOpen whether the microphone is **genuinely** capturing right now.
 *
 *   This is a separate flag rather than `ride is Listening` because the two are not the same
 *   thing, and the difference is exactly what the brief's pulsing indicator is for. The mic is
 *   also open throughout [RiderState.Confirming] — that is the cancellation window — and
 *   during the recovery ladder's re-prompts. Driving the indicator off the ride state would
 *   show a still screen at the very moments a sighted helper most needs to know the app is
 *   waiting for speech.
 * @param activeCityName the city the gazetteer is currently scoped to, shown on `idle` so the
 *   answer to "why doesn't it know this place" is on screen before it has to be asked
 */
data class RiderUiState(
    val ride: RiderState = RiderState.Idle,
    val sosActive: Boolean = false,
    val isSpeaking: Boolean = false,
    val handsFreeEnabled: Boolean = false,
    val connected: Boolean = false,
    val micOpen: Boolean = false,
    val activeCityName: String = "",
    /** The real destination selected by Places, kept separately so the map can render it. */
    val selectedDestination: PlaceOption? = null
)

/**
 * One candidate destination from the hardcoded gazetteer.
 *
 * @param name spoken form, e.g. "Anna Nagar East"
 * @param latitude decimal degrees
 * @param longitude decimal degrees
 * @param score 0..1 match confidence against the transcript
 * @param coversWholeToken whether the match aligned to a **complete word** of the place name
 *   rather than to a fragment of one. This is gate 4 of the matching gates, and it is what
 *   structurally forbids the class of bug where "to" matches "T Nagar" on a single-letter
 *   prefix: a high score alone is not evidence, because a short query scores well against a
 *   short name for reasons that have nothing to do with the rider's intent.
 * @param cityName which city this place belongs to. Carried so a failure can name it —
 *   "Adyar is in Chennai" is a diagnosis; "I don't know that place" is an apology.
 */
data class PlaceOption(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val score: Float,
    val coversWholeToken: Boolean = false,
    val cityName: String = "",
    /** Human-readable address returned by Google Places, when available. */
    val formattedAddress: String = "",
    /** Stable Google Place ID. Empty for legacy hardcoded fallback places. */
    val placeId: String = "",
    /**
     * True when Google classified this as a road rather than a building.
     *
     * A road is not an address. "Thadagam Road" is five kilometres long, and dropping a rider
     * who cannot see somewhere along it is not the same as taking them to a door. This flag is
     * what makes the app ask who is meeting them, and it asks only here — a hospital or a
     * college is a point, and asking about those would add a turn to every ordinary booking.
     */
    val isVague: Boolean = false
)

/** Vehicle class. [spokenName] is what the narrator actually says. */
enum class RideType(val spokenName: String) {
    AUTO("auto"),
    CAB("cab"),
    BIKE("bike")
}

/**
 * Which of the three visual themes is active.
 *
 * This lives in `state` rather than in `ui.theme` on purpose: it is switched **by voice**, so
 * the intent parser and the view model both need to name it, and neither of those should have
 * to reach into the UI layer to do so. The mapping from a choice to actual colours stays in
 * `ui.theme.Theme.kt`, which remains the single source of truth for the values themselves.
 *
 * @param spokenName how the narrator confirms the switch
 */
enum class ThemeChoice(val spokenName: String) {
    /** True black with near-white text. The default: least glare, and free on OLED. */
    DEEP_DARK("deep dark"),

    /** True black with yellow text — the classic low-vision pairing. */
    HIGH_CONTRAST_YELLOW("high contrast yellow"),

    /** White with near-black text, for users who need maximum brightness. */
    HIGH_CONTRAST_LIGHT("high contrast light")
}

/**
 * The driver, as far as the rider's app needs to know.
 *
 * @param vehiclePlate kept for the sighted-helper case and for the SOS payload; it is
 *   never read aloud during a normal ride, because a plate number is a long string of
 *   characters that costs seconds and helps a blind rider not at all.
 */
data class DriverInfo(
    val name: String,
    val vehicleModel: String,
    val vehiclePlate: String,
    val phoneNumber: String,
    val rating: Float
)
