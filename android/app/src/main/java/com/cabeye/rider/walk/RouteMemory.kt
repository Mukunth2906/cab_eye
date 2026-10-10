package com.cabeye.rider.walk

import com.cabeye.rider.memory.TextSimilarity
import kotlin.math.abs

/**
 * What happens to stored walking routes over time: finding one by the rider's words, checking
 * a landmark is still there, taking a correction, noticing a route has changed, and counting
 * familiarity.
 *
 * A route remembered once is not trusted for ever (Memory-Maze: routes recalled from memory
 * carry errors and omissions). So every landmark keeps a confirmation count and the time it
 * was last confirmed; the agent asks about the stalest one, drops one that is missing twice,
 * and re-walking a route either confirms it or shows where it changed.
 */
object RouteMemory {

    /** A landmark older than this, or less certain than [VERIFY_CONFIDENCE], is worth asking about. */
    const val VERIFY_AFTER_DAYS = 14.0
    const val VERIFY_CONFIDENCE = 0.6
    /** Missing this many times in a row: removed. */
    const val MISSES_TO_REMOVE = 2
    const val NAME_SIMILARITY = 0.8f
    /** A re-walked stretch this different (and at least [CHANGE_MIN_M]) means the route changed. */
    const val CHANGE_RATIO = 0.4
    const val CHANGE_MIN_M = 8.0
    /** Re-marked landmark of the same kind within this distance on the same stretch = the same one. */
    const val SAME_LANDMARK_M = 8.0

    // ---- finding ------------------------------------------------------------------------

    /** A route by the rider's words ("the clinic", "clinic door", "work"); null unless one clearly matches. */
    fun find(routes: List<WalkRoute>, heard: String): WalkRoute? {
        val words = TextSimilarity.normalise(heard)
            .replace(Regex("^(the|my)\\s+"), "")
            .replace(Regex("\\s+(door|entrance|gate)$"), "")
            .trim()
        if (words.isBlank() || routes.isEmpty()) return null
        val scored = routes.map { r ->
            val names = listOf(r.name, r.placeName) + r.aliases
            r to names.filter { it.isNotBlank() }.maxOf { n ->
                val target = TextSimilarity.normalise(n).replace(Regex("\\s+(door|entrance|gate)$"), "")
                if (target == words) 1f else TextSimilarity.similarity(words, target)
            }
        }.sortedByDescending { it.second }
        val top = scored.first()
        if (top.second < NAME_SIMILARITY) return null
        val second = scored.getOrNull(1)
        if (second != null && second.second >= NAME_SIMILARITY && top.second - second.second < 0.05f) return null
        return top.first
    }

    /** The route that starts where a cab ride to [placeKey] ends. */
    fun forPlace(routes: List<WalkRoute>, placeKey: String): WalkRoute? =
        if (placeKey.isBlank()) null else routes.filter { it.placeKey == placeKey }.maxByOrNull { it.updatedAt }

    fun placeKey(placeId: String, name: String): String =
        placeId.ifBlank { TextSimilarity.normalise(name) }

    // ---- checking landmarks ---------------------------------------------------------------

    /** The one landmark most worth asking about, or null when the route is fresh. */
    fun toVerify(route: WalkRoute, now: Long): Landmark? =
        route.landmarks
            .filter { it.ageDays(now) >= VERIFY_AFTER_DAYS || it.confidence < VERIFY_CONFIDENCE }
            .minByOrNull { maxOf(it.lastConfirmedAt, it.recordedAt) }

    fun confirm(route: WalkRoute, landmarkId: String, now: Long): WalkRoute =
        mapLandmark(route, now) { if (it.id == landmarkId) it.copy(confirmations = it.confirmations + 1, misses = 0, lastConfirmedAt = now) else it }

    /** "No, it wasn't there." @return the route, and whether the landmark was removed. */
    fun miss(route: WalkRoute, landmarkId: String, now: Long): Pair<WalkRoute, Boolean> {
        val l = route.landmarks.firstOrNull { it.id == landmarkId } ?: return route to false
        val misses = l.misses + 1
        if (misses >= MISSES_TO_REMOVE) return remove(route, landmarkId, now) to true
        return mapLandmark(route, now) { if (it.id == landmarkId) it.copy(misses = misses) else it } to false
    }

    fun remove(route: WalkRoute, landmarkId: String, now: Long): WalkRoute = route.copy(
        segments = route.segments.map { s -> s.copy(landmarks = s.landmarks.filter { it.id != landmarkId }) },
        updatedAt = now
    )

    /**
     * A correction in the rider's words.
     *
     * "The bakery smell is the cue for the left turn" moves (or adds) that landmark to the end
     * of the stretch that ends in a left turn. Anything else that names a landmark replaces
     * [about] when given, or is added to the stretch [about] is on.
     *
     * @return the new route and the sentence to say, or null when nothing usable was heard
     */
    fun correct(route: WalkRoute, spoken: String, about: Landmark?, now: Long, newId: String): Pair<WalkRoute, String>? {
        val parsed = LandmarkParser.parse(spoken) as? LandmarkParser.Result.Found ?: return null
        val cue = parsed.cueFor
        if (cue != null) {
            val turns = route.segments.withIndex().filter { sameSide(it.value.turn, cue) }
            if (turns.isEmpty()) return null
            val target = turns.firstOrNull { (_, s) -> s.landmarks.none { it.isTurnCue } } ?: turns.first()
            val existing = route.landmarks.firstOrNull { similar(it, parsed) } ?: about?.takeIf { it.kind == parsed.kind }
            val moved = (existing ?: newLandmark(newId, parsed, now)).copy(
                kind = parsed.kind, text = parsed.text, side = if (parsed.side != Side.NONE) parsed.side else existing?.side ?: Side.NONE,
                atMetres = target.value.metres, isTurnCue = true, lastConfirmedAt = now
            )
            val segments = route.segments.mapIndexed { i, s ->
                val without = s.landmarks.filter { it.id != moved.id }
                if (i == target.index) s.copy(landmarks = without + moved) else s.copy(landmarks = without)
            }
            return route.copy(segments = segments, updatedAt = now) to
                "Got it. ${WalkNarrator.phrase(moved, withSide = false).replaceFirstChar { it.uppercase() }} marks the ${cue.spoken.removePrefix("turn ")} turn in part ${target.index + 1}."
        }
        if (about != null) {
            val updated = mapLandmark(route, now) {
                if (it.id == about.id) it.copy(kind = parsed.kind, text = parsed.text,
                    side = if (parsed.side != Side.NONE) parsed.side else it.side,
                    confirmations = 1, misses = 0, lastConfirmedAt = now) else it
            }
            return updated to "Updated: ${WalkNarrator.phrase(updated.landmarks.first { it.id == about.id })}."
        }
        return null
    }

    // ---- re-walking -----------------------------------------------------------------------

    sealed interface Comparison {
        data object Same : Comparison
        data class Changed(val part: Int, val sentence: String) : Comparison
    }

    /** Is a fresh walk the same route as the stored one? Turns first, then each stretch's length. */
    fun compare(old: WalkRoute, fresh: List<Segment>): Comparison {
        val a = old.segments.map { fold(it.turn) }
        val b = fresh.map { fold(it.turn) }
        val turnsA = a.filter { it != TurnDirection.NONE }
        val turnsB = b.filter { it != TurnDirection.NONE }
        if (turnsA != turnsB) {
            val i = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] } ?: minOf(a.size, b.size) - 1
            return Comparison.Changed(i.coerceAtLeast(0), "The turns were different from part ${i.coerceAtLeast(0) + 1}" +
                " — this time ${describeTurns(turnsB)}, I had ${describeTurns(turnsA)}.")
        }
        for (i in 0 until minOf(old.segments.size, fresh.size)) {
            val was = old.segments[i].metres
            val now = fresh[i].metres
            if (abs(was - now) >= CHANGE_MIN_M && abs(was - now) > CHANGE_RATIO * was) {
                return Comparison.Changed(i, "Part ${i + 1} was about ${WalkNarrator.roundMetres(now)} metres this time; " +
                    "I had about ${WalkNarrator.roundMetres(was)}.")
            }
        }
        return Comparison.Same
    }

    /**
     * The same route walked again: landmarks the rider marked again are confirmed (same kind,
     * same stretch, close by), new ones are added, nothing else is touched. Counts as a walk.
     */
    fun rewalked(old: WalkRoute, fresh: List<Segment>, now: Long): WalkRoute {
        val segments = old.segments.mapIndexed { i, s ->
            val marks = fresh.getOrNull(i)?.landmarks.orEmpty()
            val confirmed = s.landmarks.map { l ->
                if (marks.any { it.kind == l.kind && abs(it.atMetres - l.atMetres) <= SAME_LANDMARK_M })
                    l.copy(confirmations = l.confirmations + 1, misses = 0, lastConfirmedAt = now) else l
            }
            val added = marks.filter { m -> s.landmarks.none { it.kind == m.kind && abs(it.atMetres - m.atMetres) <= SAME_LANDMARK_M } }
            s.copy(landmarks = confirmed + added)
        }
        return walked(old.copy(segments = segments), helped = 0, now = now)
    }

    /**
     * The route changed and the rider said to update it: the new walk's stretches, with the old
     * landmarks carried onto the same-numbered stretch (they are not thrown away — the rider
     * can confirm or drop them later) unless the new walk re-marked one like it. Familiarity
     * drops back, because this is a new route to learn.
     */
    fun replaced(old: WalkRoute, fresh: List<Segment>, now: Long): WalkRoute {
        val segments = fresh.mapIndexed { i, s ->
            val carried = old.segments.getOrNull(i)?.landmarks.orEmpty()
                .filter { l -> s.landmarks.none { it.kind == l.kind } }
                .map { it.copy(atMetres = minOf(it.atMetres, s.metres)) }
            s.copy(landmarks = carried + s.landmarks)
        }
        return old.copy(segments = segments, updatedAt = now, lastWalkedAt = now,
            walks = minOf(old.walks, 1), helpRequests = 0)
    }

    /** A walk finished. Help asked for keeps the detail up; a walk without help lets it fall. */
    fun walked(route: WalkRoute, helped: Int, now: Long): WalkRoute = route.copy(
        walks = route.walks + 1,
        helpRequests = if (helped > 0) minOf(route.helpRequests + helped, 3) else maxOf(route.helpRequests - 1, 0),
        lastWalkedAt = now,
        updatedAt = now
    )

    // ---- the study log ----------------------------------------------------------------------

    fun csv(log: List<WalkLogEntry>): String = buildString {
        append("time_utc_ms,route_id,event,detail\n")
        for (e in log) {
            append(e.t).append(',').append(q(e.routeId)).append(',').append(q(e.event)).append(',').append(q(e.detail)).append('\n')
        }
    }

    private fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---- helpers ---------------------------------------------------------------------------

    private fun mapLandmark(route: WalkRoute, now: Long, f: (Landmark) -> Landmark) = route.copy(
        segments = route.segments.map { s -> s.copy(landmarks = s.landmarks.map(f)) },
        updatedAt = now
    )

    private fun similar(l: Landmark, f: LandmarkParser.Result.Found): Boolean =
        l.kind == f.kind && (TextSimilarity.similarity(f.text, l.text) >= 0.75f ||
            TextSimilarity.normalise(l.text).contains(TextSimilarity.normalise(f.text)) ||
            TextSimilarity.normalise(f.text).contains(TextSimilarity.normalise(l.text)))

    private fun newLandmark(id: String, f: LandmarkParser.Result.Found, now: Long) =
        Landmark(id = id, kind = f.kind, text = f.text, side = f.side, recordedAt = now, lastConfirmedAt = now)

    private fun fold(t: TurnDirection) = when (t) {
        TurnDirection.SLIGHT_LEFT -> TurnDirection.LEFT
        TurnDirection.SLIGHT_RIGHT -> TurnDirection.RIGHT
        else -> t
    }

    private fun sameSide(turn: TurnDirection, cue: TurnDirection) = fold(turn) == fold(cue) && turn != TurnDirection.NONE

    private fun describeTurns(turns: List<TurnDirection>): String =
        if (turns.isEmpty()) "no turns" else turns.joinToString(", then ") { it.spoken.removePrefix("turn ") }
}
