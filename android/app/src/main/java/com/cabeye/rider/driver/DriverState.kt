package com.cabeye.rider.driver

/**
 * The seven driver screens.
 *
 * ## Why this is a separate hierarchy from `RiderState`
 * They describe the same ride from two sides, and the two sides genuinely diverge. The driver
 * has an `Offline` state and a `Request` state the rider has no equivalent for; the rider has
 * `Listening`, `Resolving` and `Clarify`, none of which exist for someone holding a steering
 * wheel. Folding both into one type would produce a state machine in which most states are
 * illegal for whichever surface is currently showing — which is the same as having no state
 * machine at all.
 *
 * The flow is:
 * ```
 * offline -> online -> request -> navigating -> arrived -> seated -> inTrip -> complete
 * ```
 *
 * ## The one thing this screen must never do
 * Speak. A driver is driving. Every one of these states is silent, and that is enforced
 * structurally rather than by convention — in driver mode the app holds a
 * [com.cabeye.rider.audio.SilentAudioEngine] and the real engine has been released. See
 * [com.cabeye.rider.audio.AudioSession].
 */
sealed interface DriverState {

    /** Not accepting rides. The only state with no ride attached. */
    data object Offline : DriverState

    /** Online and waiting. A request may arrive at any moment. */
    data class Online(val waitingSeconds: Int = 0) : DriverState

    /**
     * A ride request is on offer.
     *
     * Carries [visuallyImpaired] because this is the first of the two screens that must show
     * the non-dismissable badge. The driver learns before accepting, not after arriving — a
     * driver who discovers at the kerb that their passenger cannot see them is a driver who
     * was not given the chance to prepare.
     */
    data class Request(
        val rideId: String,
        val destination: String,
        val rideType: String,
        val visuallyImpaired: Boolean = true,
        // Carried from the ride snapshot so the driver screen can hand off to Google Maps.
        // Nullable on purpose: a ride resolved through the offline Gazetteer fallback has a
        // name but no coordinates, and that must degrade to "no Navigate button" rather than
        // to a button that opens a map at 0,0 in the Gulf of Guinea.
        val destinationLatitude: Double? = null,
        val destinationLongitude: Double? = null,
        val destinationAddress: String = "",
        val pickupLatitude: Double? = null,
        val pickupLongitude: Double? = null,
        // Someone waiting at the drop-off, when the destination was a road rather than a
        // building. Blank on an ordinary ride.
        val contactName: String = "",
        val contactPhone: String = "",
        val dropNote: String = ""
    ) : DriverState

    /**
     * Accepted, navigating to the pickup point.
     *
     * The position presets and the audio beacon live on this screen. Both exist because the
     * usual solution — phoning the passenger and describing where you are — assumes the
     * passenger can then look. This one cannot.
     */
    data class Navigating(
        val rideId: String,
        val destination: String,
        val distanceMeters: Int = 0,
        val bearingDegrees: Float = 0f,
        val visuallyImpaired: Boolean = true,
        // On THIS screen the driver is heading to the rider, so the pickup pair is what the
        // Navigate button uses. The destination pair is carried through only so it survives
        // into the in-trip screen without a second round trip to the server.
        val pickupLatitude: Double? = null,
        val pickupLongitude: Double? = null,
        val destinationLatitude: Double? = null,
        val destinationLongitude: Double? = null,
        val destinationAddress: String = "",
        val contactName: String = "",
        val contactPhone: String = "",
        val dropNote: String = ""
    ) : DriverState

    /**
     * At the pickup point. **This screen displays the boarding code.**
     *
     * The code runs backwards from the usual design and this is the reason the driver screen
     * exists at all: the driver reads it aloud, the rider's app verifies it. The rider's phone
     * never announces a secret over a loudspeaker to a street where anyone could be listening,
     * and hearing the correct code from the car is exactly the verification a blind rider
     * cannot perform by looking at a number plate.
     *
     * @param boardingCode shown large, and issued by the server — never generated on either phone
     * @param codeConfirmed set once the rider's app reports a match
     */
    data class Arrived(
        val rideId: String,
        val boardingCode: String,
        val codeConfirmed: Boolean = false
    ) : DriverState

    /**
     * The passenger is confirmed to be physically in the vehicle.
     *
     * A separate state rather than a flag, because the trip cannot start without passing
     * through it — and that is enforced on the server too, which returns 409 to a start
     * request that has not been preceded by a seated confirmation. A blind passenger who has
     * not finished getting in is the one person who cannot see a car begin to move.
     */
    data class Seated(val rideId: String) : DriverState

    /** Journey underway. */
    data class InTrip(
        val rideId: String,
        val destination: String,
        val etaMinutes: Int,
        // Now the drop-off is where the driver is going, so the Navigate button switches to
        // the destination pair.
        val destinationLatitude: Double? = null,
        val destinationLongitude: Double? = null,
        val destinationAddress: String = "",
        val contactName: String = "",
        val contactPhone: String = "",
        val dropNote: String = ""
    ) : DriverState

    /** Journey finished. */
    data class Complete(
        val rideId: String,
        val fareRupees: Int,
        val durationMinutes: Int
    ) : DriverState
}

/**
 * The complete driver UI state.
 *
 * @param connected socket health. Shown on screen only — the driver can see it, so unlike the
 *   rider's side there is nothing to announce and nothing that needs announcing.
 * @param banner a transient line of feedback for actions that succeeded or failed. The driver's
 *   equivalent of the rider's narration, and it is text precisely because the driver can read
 *   and must not be spoken to.
 */
data class DriverUiState(
    val state: DriverState = DriverState.Offline,
    val connected: Boolean = false,
    val banner: String = "",
    val lastPresetSent: String = ""
)

/**
 * The canned position phrases.
 *
 * ## Why presets rather than free text
 * The driver taps one and the rider's phone speaks it. The driver never speaks it and the rider
 * never reads it — which is the whole point. A driver typing a message is a driver looking at a
 * keyboard instead of the road, and a driver speaking into a phone is a driver whose words
 * arrive as audio that a blind passenger's app cannot re-time, re-pace or repeat on request.
 *
 * ## Why these particular phrases
 * Each one is a *direction and a landmark*, never a map reference. "Near the gate" is something
 * a person can walk toward; "at coordinates 13.08, 80.27" is not. Left and right are given from
 * the rider's own facing, not the driver's, because the rider is the one who has to move.
 *
 * @param spoken exactly what the rider's phone says. Written to be heard: no abbreviations, no
 *   digits that a TTS engine might read as a year.
 */
enum class PositionPreset(val label: String, val spoken: String) {

    LEFT_NEAR_GATE(
        "20 m to your left, near the gate",
        "I'm twenty metres to your left, near the gate."
    ),

    RIGHT_NEAR_GATE(
        "20 m to your right, near the gate",
        "I'm twenty metres to your right, near the gate."
    ),

    DIRECTLY_AHEAD(
        "Directly in front of you",
        "I'm directly in front of you."
    ),

    BEHIND_YOU(
        "Just behind you",
        "I'm just behind you. Turn around."
    ),

    ACROSS_ROAD(
        "Across the road",
        "I'm across the road from you. Please wait for a safe crossing."
    ),

    WAITING_CORNER(
        "Waiting at the corner",
        "I'm waiting at the corner, a short walk to your right."
    ),

    STAY_PUT(
        "Stay where you are, coming to you",
        "Stay where you are. I'm walking over to you now."
    ),

    /**
     * The one preset that is not about position.
     *
     * A rider who cannot see the car cannot see the door either, and which side is safe depends
     * on which way the car is parked — something only the driver knows.
     */
    DOOR_SIDE(
        "Door is on your right",
        "The door is on your right, away from the traffic."
    )
}
