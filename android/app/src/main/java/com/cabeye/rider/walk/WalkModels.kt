package com.cabeye.rider.walk

/**
 * The walking-route memory: "the last hundred metres" — from where the cab stops to the door,
 * and from the door to the pickup point. Clew3D-style capture → segments → O&M narration,
 * built for an Android phone at zero cost:
 *
 *  - capture is GPS outdoors plus step counting and compass heading (pedestrian dead
 *    reckoning), so short indoor walks still get distances and turns without LiDAR or beacons;
 *  - landmarks are the rider's OWN words ("bakery smell on my left"), parsed by rules and kept
 *    only when they can be felt, heard or smelt — a sign or a colour is refused;
 *  - narration is template-based, the way an O&M specialist dictates a route;
 *  - everything stays on this phone.
 *
 * Pure Kotlin: no Android types, so every rule is a JVM test.
 */

/** What a landmark is, by the sense a blind traveller uses to find it. */
enum class LandmarkKind(val spoken: String) {
    DOOR("door"),
    GATE("gate"),
    KERB("kerb"),
    STEPS("steps"),
    RAMP("ramp"),
    TEXTURE("change underfoot"),
    TACTILE("tactile paving"),
    RAILING("railing"),
    POLE("pole"),
    WALL("wall"),
    CROSSING("crossing"),
    SOUND("sound"),
    SMELL("smell"),
    OTHER("landmark");
}

enum class Side(val spoken: String) { LEFT("on your left"), RIGHT("on your right"), AHEAD("ahead"), NONE("") }

enum class TurnDirection(val spoken: String) {
    LEFT("turn left"),
    RIGHT("turn right"),
    SLIGHT_LEFT("bear left"),
    SLIGHT_RIGHT("bear right"),
    AROUND("turn around"),
    NONE("");

    companion object {
        /** [delta] in degrees, positive = clockwise (right). */
        fun of(delta: Double): TurnDirection {
            val a = kotlin.math.abs(delta)
            return when {
                a < 30 -> NONE
                a < 60 -> if (delta > 0) SLIGHT_RIGHT else SLIGHT_LEFT
                a < 140 -> if (delta > 0) RIGHT else LEFT
                else -> AROUND
            }
        }
    }
}

/**
 * A landmark on a stored route.
 *
 * @param text the rider's own words, cleaned ("the bakery smell") — what is read back
 * @param atMetres how far along its segment, from the segment's start
 * @param recordedAt when it was first marked
 * @param lastConfirmedAt when the rider last said it was still there (or re-marked it)
 */
data class Landmark(
    val id: String,
    val kind: LandmarkKind,
    val text: String,
    val side: Side = Side.NONE,
    val atMetres: Double = 0.0,
    /** Marked as the cue for the turn that ends its segment ("the bakery smell is the cue for the left turn"). */
    val isTurnCue: Boolean = false,
    val recordedAt: Long = 0,
    val lastConfirmedAt: Long = 0,
    val confirmations: Int = 1,
    val misses: Int = 0
) {
    /** 0..1: how much the stored route can be trusted on this one. Starts at 2/3. */
    val confidence: Double get() = (confirmations + 1.0) / (confirmations + misses + 2.0)

    fun ageDays(now: Long): Double = (now - maxOf(lastConfirmedAt, recordedAt)) / 86_400_000.0
}

/**
 * One straight stretch: walk [metres], passing [landmarks], then [turn] at its end.
 * [steps] is the rider's own step count for it when the pedometer had one.
 */
data class Segment(
    val metres: Double,
    val steps: Int? = null,
    val turn: TurnDirection = TurnDirection.NONE,
    val landmarks: List<Landmark> = emptyList()
)

/**
 * A saved walking route.
 *
 * @param name what the rider calls it ("clinic door")
 * @param aliases other words the rider used for it
 * @param placeKey the cab destination it starts or ends at, when linked to a ride
 * @param start how the rider described the starting point ("where the auto drops me")
 * @param walks walks completed along it, with or without guidance — familiarity
 * @param helpRequests "repeat" / "more detail" during recent walks — the opposite signal
 */
data class WalkRoute(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val placeKey: String = "",
    val placeName: String = "",
    val start: String = "",
    val segments: List<Segment>,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastWalkedAt: Long = 0,
    val walks: Int = 0,
    val helpRequests: Int = 0,
    val indoor: Boolean = false
) {
    val totalMetres: Double get() = segments.sumOf { it.metres }
    val landmarks: List<Landmark> get() = segments.flatMap { it.landmarks }
}

/** How much the narrator says. */
enum class DetailLevel { FULL, BRIEF, LANDMARKS }

/**
 * The rider's lasting preference, set by "more detail" / "less detail". 0 = let familiarity
 * decide; positive = more than familiarity would give; negative = less.
 */
data class WalkProfile(
    val detailBias: Int = 0,
    val consentGiven: Boolean = false
)

/** Raw capture, in order. Times are epoch millis. */
sealed interface WalkEvent {
    val t: Long

    /** A GPS fix. [accuracy] in metres; fixes worse than [RouteBuilder.MAX_ACCURACY_M] are ignored. */
    data class Fix(override val t: Long, val latitude: Double, val longitude: Double, val accuracy: Float) : WalkEvent

    /** Compass heading of the phone, degrees clockwise from north. */
    data class Heading(override val t: Long, val degrees: Double) : WalkEvent

    /** One detected step. */
    data class Step(override val t: Long) : WalkEvent

    /** The rider said a landmark here. */
    data class Mark(override val t: Long, val spoken: String) : WalkEvent

    /** The rider said they are turning here ("turning left"). Overrides the compass. */
    data class Turn(override val t: Long, val direction: TurnDirection) : WalkEvent
}

/** A local, exportable log of how the rider uses routes — the field-study measures. */
data class WalkLogEntry(
    val t: Long,
    val routeId: String,
    val event: String,
    val detail: String = ""
)
