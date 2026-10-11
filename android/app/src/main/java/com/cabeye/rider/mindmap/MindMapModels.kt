package com.cabeye.rider.mindmap

import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.Side
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The rider's personal mind map: one connected memory of the places, landmarks, rides and walks
 * they know, and what those things mean to them.
 *
 * The walking-route agent keeps each walk as a separate list and the ride memory keeps cab
 * destinations as a separate list. The mind map joins them up the way a person's own memory
 * does: the bakery smell on the clinic walk is the same bakery near the bus stop; the temple
 * bell means Amma's house is close; the drop-off, the walk from it and the door are one journey.
 *
 * Two kinds of content:
 *  - **derived** nodes and edges, rebuilt from the walk routes and ride memory every time
 *    ([MindMapBuilder]), so they never go out of date;
 *  - **authored** nodes and edges the rider created (anchors, ride cues, areas, remembered
 *    places), stored on this phone only ([MindMapStore]).
 *
 * Pure Kotlin: no Android types, so every rule is a JVM test.
 */

enum class NodeKind {
    /** A place: a cab destination, a walk's end, a place the rider asked to remember. */
    PLACE,
    /** A landmark on a walk (a kerb, a smell, a door). */
    LANDMARK,
    /** A personal meaning the rider attached: "Ravi's tea shop, where I stop on Fridays". */
    ANCHOR,
    /** A neighbourhood ("Gandhipuram"), learned on rides. */
    AREA,
    /** Something felt or heard on a ride ("the flyover", "speed bump near the school"). */
    RIDE_CUE
}

enum class EdgeKind {
    /** landmark → walk end: it is on the walk to there. */
    ON_WALK,
    /** ride cue / area → destination: it is on the ride to there. */
    ON_RIDE,
    /** the same landmark seen on two walks. */
    SAME_AS,
    /** place → area. */
    IN_AREA,
    /** anchor → the node it gives meaning to. */
    MEANS,
    /** drop-off place → walk end: the ride, then the walk, then the door. */
    LEADS_TO,
    /** two things the rider knows are close to each other. */
    NEAR
}

/**
 * One thing in the mind map.
 *
 * @param label what is read back ("bakery smell", "Ravi's tea shop", "Gandhipuram")
 * @param source where a derived node came from (walk route id, place key), or the destination
 *   place key a ride cue belongs to
 * @param person who the rider connects it with ("Ravi"), anchors only
 * @param habit the rider's own habit, already in the second person ("you stop on Fridays")
 * @param meaning what it tells the rider ("you're near Amma's house")
 * @param words the rider's words, verbatim, kept so a correction can be checked against them
 * @param atMetres for a ride cue, how far into the ride it was marked
 */
data class Node(
    val id: String,
    val kind: NodeKind,
    val label: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val sense: LandmarkKind? = null,
    val side: Side = Side.NONE,
    val source: String = "",
    val person: String = "",
    val habit: String = "",
    val meaning: String = "",
    val words: String = "",
    val atMetres: Double? = null,
    val createdAt: Long = 0,
    val lastConfirmedAt: Long = 0,
    val confirmations: Int = 1,
    val misses: Int = 0
) {
    /** Same formula as a walk landmark: starts at 2/3, rises with each confirmation. */
    val confidence: Double get() = (confirmations + 1.0) / (confirmations + misses + 2.0)

    val located: Boolean get() = lat != null && lng != null

    fun ageDays(now: Long): Double = (now - maxOf(lastConfirmedAt, createdAt)) / 86_400_000.0
}

data class Edge(val from: String, val to: String, val kind: EdgeKind, val note: String = "")

/** Nodes plus edges, with the lookups the agents need. Immutable; build a new one to change it. */
data class MindMap(val nodes: List<Node> = emptyList(), val edges: List<Edge> = emptyList()) {

    private val byId: Map<String, Node> by lazy { nodes.associateBy { it.id } }

    fun node(id: String): Node? = byId[id]

    fun edgesOf(id: String): List<Edge> = edges.filter { it.from == id || it.to == id }

    /** Nodes joined to [id] by an edge of [kind] (any kind when null), in either direction. */
    fun neighbours(id: String, kind: EdgeKind? = null): List<Node> =
        edgesOf(id).filter { kind == null || it.kind == kind }
            .mapNotNull { node(if (it.from == id) it.to else it.from) }
            .distinctBy { it.id }

    /** Anchors that give [id] a meaning. */
    fun anchorsFor(id: String): List<Node> =
        edges.filter { it.kind == EdgeKind.MEANS && it.to == id }.mapNotNull { node(it.from) }

    /** Located nodes within [radiusM] of a point, nearest first. */
    fun near(lat: Double, lng: Double, radiusM: Double, kinds: Set<NodeKind> = NodeKind.entries.toSet()): List<Pair<Node, Double>> =
        nodes.filter { it.kind in kinds && it.located }
            .map { it to Geo.metres(lat, lng, it.lat!!, it.lng!!) }
            .filter { it.second <= radiusM }
            .sortedBy { it.second }

    /** Replace nodes with the same id, add the rest; add edges not already there. */
    fun upsert(newNodes: List<Node>, newEdges: List<Edge> = emptyList()): MindMap {
        val ids = newNodes.map { it.id }.toSet()
        return MindMap(nodes.filter { it.id !in ids } + newNodes, (edges + newEdges).distinct())
    }

    /** Without [id] and every edge touching it. */
    fun without(id: String): MindMap = MindMap(nodes.filter { it.id != id }, edges.filter { it.from != id && it.to != id })

    fun plus(other: MindMap): MindMap {
        val ids = nodes.map { it.id }.toSet()
        return MindMap(
            nodes + other.nodes.filter { it.id !in ids },
            (edges + other.edges).distinct()
        )
    }

    val isEmpty: Boolean get() = nodes.isEmpty()
}

/**
 * Small geometry, spoken. Distances are said rounded, directions as eight compass points —
 * a blind rider oriented by the cab's drop point can use "north" no better than "ahead", so
 * [RelativeTo] turns a compass bearing into a clock face when the rider's heading is known.
 */
object Geo {
    private const val R = 6_371_000.0

    fun metres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Degrees clockwise from north, 0..360. */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private val POINTS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

    fun compass(bearing: Double): String = POINTS[(((bearing % 360) + 360) % 360 / 45.0).roundToInt() % 8]

    /** A bearing relative to the way the rider faces → "at 3 o'clock". */
    fun clock(bearing: Double, heading: Double): String {
        val rel = ((bearing - heading) % 360 + 360) % 360
        val hour = (rel / 30.0).roundToInt() % 12
        return "at ${if (hour == 0) 12 else hour} o'clock"
    }

    /** "about 200 metres" / "about 1.5 kilometres". */
    fun spokenDistance(m: Double): String = when {
        m < 20 -> "a few metres"
        m < 100 -> "about ${(m / 10).roundToInt() * 10} metres"
        m < 1_000 -> "about ${(m / 50).roundToInt() * 50} metres"
        m < 10_000 -> String.format(java.util.Locale.ROOT, "%.1f", m / 1000).removeSuffix(".0")
            .let { km -> "about $km ${if (km == "1") "kilometre" else "kilometres"}" }
        else -> "about ${(m / 1000).roundToInt()} kilometres"
    }

    /** Shortest distance from a point to a polyline, in metres (flat-earth within a city). */
    fun metresToPath(lat: Double, lng: Double, path: List<Pair<Double, Double>>): Double {
        if (path.isEmpty()) return Double.MAX_VALUE
        if (path.size == 1) return metres(lat, lng, path[0].first, path[0].second)
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val ax = (path[i].second - lng) * kx; val ay = (path[i].first - lat) * ky
            val bx = (path[i + 1].second - lng) * kx; val by = (path[i + 1].first - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            best = minOf(best, sqrt(px * px + py * py))
        }
        return best
    }
}
