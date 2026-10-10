package com.cabeye.rider.walk

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A recorded walk → short segments (distance, landmarks, turn), as an O&M specialist would
 * dictate them.
 *
 * Distances come from the rider's own steps, with the stride calibrated against GPS when the
 * walk had a good outdoor fix (indoors, or under a roof, GPS drops out and the default stride
 * is used — pedestrian dead reckoning). Per-segment GPS is too noisy at this scale; steps are
 * not.
 *
 * Turns come from the rider first ("turning left here"), then from the compass: a heading
 * change of at least [TURN_MIN_DEG] held for [TURN_HOLD_MS] while the rider is stepping.
 * A phone turned in the hand while standing still is not a turn.
 */
object RouteBuilder {

    const val DEFAULT_STRIDE_M = 0.7
    const val MAX_ACCURACY_M = 20f
    const val TURN_MIN_DEG = 50.0
    const val TURN_HOLD_MS = 3_000L
    const val SMOOTH_MS = 600L
    const val SPOKEN_TURN_WINDOW_MS = 6_000L
    const val MIN_SEGMENT_M = 2.0
    /** A landmark this close to the end of a segment that ends in a turn is that turn's cue. */
    const val CUE_WITHIN_M = 3.0

    data class Built(
        val segments: List<Segment>,
        val strideMetres: Double,
        val indoor: Boolean,
        /** Spoken marks that were not landmarks a blind traveller can sense. */
        val refused: List<String>
    )

    private class Open(val startT: Long) {
        val stepTimes = mutableListOf<Long>()
        val marks = mutableListOf<Pair<Long, LandmarkParser.Result.Found>>()
        var refHeading: Double? = null
    }

    private data class Closed(
        val startT: Long,
        val endT: Long,
        val stepTimes: List<Long>,
        val marks: List<Pair<Long, LandmarkParser.Result.Found>>,
        val turn: TurnDirection,
        val spoken: Boolean
    )

    fun build(events: List<WalkEvent>, now: Long, idPrefix: String = "lm"): Built {
        val sorted = events.sortedBy { it.t }
        if (sorted.isEmpty()) return Built(emptyList(), DEFAULT_STRIDE_M, indoor = true, refused = emptyList())

        // ---- stride, from the whole walk -------------------------------------------------
        val fixes = sorted.filterIsInstance<WalkEvent.Fix>().filter { it.accuracy <= MAX_ACCURACY_M }
        val allSteps = sorted.count { it is WalkEvent.Step }
        val gpsMetres = pathMetres(fixes)
        val indoor = fixes.size < 3
        val stride = if (!indoor && gpsMetres >= 30 && allSteps >= 30) {
            (gpsMetres / allSteps).takeIf { it in 0.45..0.95 } ?: DEFAULT_STRIDE_M
        } else DEFAULT_STRIDE_M

        // ---- split into segments -----------------------------------------------------------
        val closed = mutableListOf<Closed>()
        val refused = mutableListOf<String>()
        var open = Open(sorted.first().t)
        val headings = ArrayDeque<WalkEvent.Heading>()
        var deviationSince: Long? = null

        fun close(at: Long, turn: TurnDirection, spoken: Boolean) {
            val keepSteps = open.stepTimes.filter { it <= at }
            val moveSteps = open.stepTimes.filter { it > at }
            val keepMarks = open.marks.filter { it.first <= at }
            val moveMarks = open.marks.filter { it.first > at }
            closed += Closed(open.startT, at, keepSteps, keepMarks, turn, spoken)
            open = Open(at).also { it.stepTimes += moveSteps; it.marks += moveMarks }
            deviationSince = null
        }

        for (e in sorted) {
            when (e) {
                is WalkEvent.Step -> open.stepTimes += e.t
                is WalkEvent.Mark -> when (val r = LandmarkParser.parse(e.spoken)) {
                    is LandmarkParser.Result.Found -> open.marks += e.t to r
                    is LandmarkParser.Result.TurnHere -> spokenTurn(e.t, r.direction, closed, open)?.let { close(it.first, it.second, true) }
                    is LandmarkParser.Result.VisualOnly -> refused += r.text
                    null -> Unit
                }
                is WalkEvent.Turn -> spokenTurn(e.t, e.direction, closed, open)?.let { close(it.first, it.second, true) }
                is WalkEvent.Fix -> Unit
                is WalkEvent.Heading -> {
                    headings.addLast(e)
                    while (headings.isNotEmpty() && e.t - headings.first().t > SMOOTH_MS) headings.removeFirst()
                    val smooth = circularMean(headings.map { it.degrees })
                    // The reference is the direction of travel once the rider is walking.
                    if (open.refHeading == null) {
                        if (open.stepTimes.count { it >= open.startT } >= 2) open.refHeading = smooth
                        continue
                    }
                    // A spoken turn a moment ago already covers what the compass is seeing.
                    val lastSpoken = closed.lastOrNull()?.takeIf { it.spoken }?.endT
                    if (lastSpoken != null && e.t - lastSpoken < SPOKEN_TURN_WINDOW_MS) {
                        open.refHeading = smooth
                        continue
                    }
                    val delta = angleDiff(open.refHeading!!, smooth)
                    if (abs(delta) >= TURN_MIN_DEG) {
                        val since = deviationSince
                        if (since == null) {
                            deviationSince = e.t
                        } else if (e.t - since >= TURN_HOLD_MS) {
                            // Only a turn if the rider kept walking through it.
                            val stepping = open.stepTimes.count { it in since..e.t } >= 2
                            // Not walking: the phone moved in the hand, or the rider is turning on
                            // the spot. Keep watching — it becomes a turn only once they walk on
                            // in the new direction; turning back makes it nothing.
                            if (stepping) {
                                close(since, TurnDirection.of(delta), false)
                                open.refHeading = smooth
                            }
                        }
                    } else {
                        deviationSince = null
                    }
                }
            }
        }
        closed += Closed(open.startT, sorted.last().t, open.stepTimes.toList(), open.marks.toList(), TurnDirection.NONE, false)

        // ---- measure, merge, attach landmarks ----------------------------------------------
        var n = 0
        val segments = mutableListOf<Segment>()
        for (c in closed) {
            val metres = c.stepTimes.size * stride
            val landmarks = c.marks.map { (t, f) ->
                val at = c.stepTimes.count { it <= t } * stride
                val cue = f.cueFor != null || (c.turn != TurnDirection.NONE && metres - at <= CUE_WITHIN_M)
                Landmark(
                    id = "$idPrefix-${n++}",
                    kind = f.kind,
                    text = f.text,
                    side = f.side,
                    atMetres = round1(at),
                    isTurnCue = cue,
                    recordedAt = now,
                    lastConfirmedAt = now
                )
            }
            val seg = Segment(round1(metres), c.stepTimes.size.takeIf { it > 0 }, c.turn, landmarks)
            val prev = segments.lastOrNull()
            if (seg.metres < MIN_SEGMENT_M && prev != null) {
                // Too short to say: fold into the previous stretch, keeping the later turn.
                segments[segments.size - 1] = prev.copy(
                    metres = round1(prev.metres + seg.metres),
                    steps = sumSteps(prev.steps, seg.steps),
                    turn = if (seg.turn != TurnDirection.NONE) seg.turn else prev.turn,
                    landmarks = prev.landmarks + seg.landmarks.map { it.copy(atMetres = round1(prev.metres + it.atMetres)) }
                )
            } else if (seg.metres < MIN_SEGMENT_M && seg.landmarks.isEmpty() && seg.turn == TurnDirection.NONE) {
                continue
            } else {
                segments += seg
            }
        }
        // A leading sliver (standing at the cab while turning) is folded forward too.
        if (segments.size >= 2 && segments[0].metres < MIN_SEGMENT_M) {
            val a = segments.removeAt(0)
            val b = segments[0]
            segments[0] = b.copy(
                metres = round1(a.metres + b.metres),
                steps = sumSteps(a.steps, b.steps),
                landmarks = a.landmarks + b.landmarks.map { it.copy(atMetres = round1(a.metres + it.atMetres)) }
            )
        }
        return Built(segments, round2(stride), indoor, refused)
    }

    /**
     * A spoken turn: closes the segment here, or — when the compass already closed one in the
     * last few seconds and the rider has barely moved since — corrects that turn's direction
     * instead of inventing a second one. Returns (time, direction) to close at, or null.
     */
    private fun spokenTurn(
        t: Long,
        dir: TurnDirection,
        closed: MutableList<Closed>,
        open: Open
    ): Pair<Long, TurnDirection>? {
        val last = closed.lastOrNull()
        if (last != null && !last.spoken && t - last.endT < SPOKEN_TURN_WINDOW_MS && open.stepTimes.size < 4) {
            closed[closed.size - 1] = last.copy(turn = dir, spoken = true)
            return null
        }
        return t to dir
    }

    // ---- geometry ---------------------------------------------------------------------

    fun pathMetres(fixes: List<WalkEvent.Fix>): Double =
        fixes.zipWithNext().sumOf { (a, b) -> metresBetween(a.latitude, a.longitude, b.latitude, b.longitude) }

    fun metresBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Signed smallest difference b − a, in (−180, 180]. Positive = clockwise. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d <= -180) d += 360
        if (d > 180) d -= 360
        return d
    }

    fun circularMean(degrees: List<Double>): Double {
        if (degrees.isEmpty()) return 0.0
        val s = degrees.sumOf { sin(Math.toRadians(it)) }
        val c = degrees.sumOf { cos(Math.toRadians(it)) }
        val m = Math.toDegrees(atan2(s, c))
        return (m + 360) % 360
    }

    private fun sumSteps(a: Int?, b: Int?): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
    private fun round1(x: Double) = (x * 10).roundToInt() / 10.0
    private fun round2(x: Double) = (x * 100).roundToInt() / 100.0
}
