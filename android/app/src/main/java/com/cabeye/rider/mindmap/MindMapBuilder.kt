package com.cabeye.rider.mindmap

import com.cabeye.rider.memory.TextSimilarity
import com.cabeye.rider.memory.VisitedPlace
import com.cabeye.rider.walk.WalkRoute

/**
 * Joins what the app already remembers into one graph, every time it is needed:
 *
 *  - each cab destination ([VisitedPlace]) → a PLACE node, located;
 *  - each walking route → a PLACE node for where it ends, reached from the drop-off it starts
 *    at (LEADS_TO), so the ride, the walk and the door are one journey;
 *  - each walk landmark → a LANDMARK node ON_WALK to that end, placed (roughly) where the walk
 *    starts — walks are the last hundred metres, so that is close enough to say "near";
 *  - the same landmark on two walks → SAME_AS ([LandmarkLinker]);
 *  - then the rider's own nodes and edges ([authored]) on top.
 *
 * Derived ids are stable ("place:<key>", "walk:<route id>", "lm:<landmark id>") so an anchor the
 * rider attached to a landmark still finds it after the walk is re-recorded.
 */
object MindMapBuilder {

    fun placeId(placeKey: String) = "place:$placeKey"
    fun walkId(routeId: String) = "walk:$routeId"
    fun landmarkId(landmarkId: String) = "lm:$landmarkId"

    fun build(authored: MindMap, routes: List<WalkRoute>, places: List<VisitedPlace>): MindMap {
        val nodes = mutableListOf<Node>()
        val edges = mutableListOf<Edge>()

        for (p in places) {
            nodes += Node(
                id = placeId(p.placeKey), kind = NodeKind.PLACE, label = p.name,
                lat = p.latitude, lng = p.longitude, source = p.placeKey,
                createdAt = p.lastVisitedAt, lastConfirmedAt = p.lastVisitedAt,
                confirmations = maxOf(1, p.visitCount)
            )
        }

        for (r in routes) {
            val drop = r.placeKey.takeIf { it.isNotBlank() }?.let { key -> findPlace(places, key, r.placeName) }
            // A walk linked to a drop-off we no longer have in ride memory still gets its own node.
            if (r.placeKey.isNotBlank() && drop == null) {
                nodes += Node(id = placeId(r.placeKey), kind = NodeKind.PLACE,
                    label = r.placeName.ifBlank { r.name }, source = r.placeKey, createdAt = r.createdAt)
            }
            val startId = drop?.let { placeId(it.placeKey) } ?: r.placeKey.takeIf { it.isNotBlank() }?.let(::placeId)
            val lat = drop?.latitude
            val lng = drop?.longitude
            val endId = walkId(r.id)
            nodes += Node(
                id = endId, kind = NodeKind.PLACE, label = r.name, lat = lat, lng = lng,
                source = r.id, createdAt = r.createdAt, lastConfirmedAt = r.lastWalkedAt,
                confirmations = maxOf(1, r.walks)
            )
            if (startId != null) edges += Edge(startId, endId, EdgeKind.LEADS_TO, r.name)
            for (l in r.landmarks) {
                nodes += Node(
                    id = landmarkId(l.id), kind = NodeKind.LANDMARK, label = l.text,
                    lat = lat, lng = lng, sense = l.kind, side = l.side, source = r.id,
                    createdAt = l.recordedAt, lastConfirmedAt = l.lastConfirmedAt,
                    confirmations = l.confirmations, misses = l.misses
                )
                edges += Edge(landmarkId(l.id), endId, EdgeKind.ON_WALK, r.name)
            }
        }

        val derived = MindMap(nodes.distinctBy { it.id }, edges)
        val linked = derived.copy(edges = derived.edges + LandmarkLinker.link(derived.nodes))
        // The rider's own nodes win over nothing: derived ids and authored ids never collide
        // (authored ids are "a:", "cue:", "area:", "near:").
        return linked.plus(authored).let { m ->
            // Drop authored edges whose ends are gone (a walk was forgotten).
            m.copy(edges = m.edges.filter { m.node(it.from) != null && m.node(it.to) != null })
        }
    }

    private fun findPlace(places: List<VisitedPlace>, key: String, name: String): VisitedPlace? =
        places.firstOrNull { it.placeKey == key || (it.placeId.isNotBlank() && it.placeId == key) }
            ?: name.takeIf { it.isNotBlank() }?.let { n ->
                places.firstOrNull { TextSimilarity.normalise(it.name) == TextSimilarity.normalise(n) }
            }
}

/**
 * The same landmark on two different walks becomes one memory.
 *
 * Joined only when the evidence is strong, because a wrong join tells the rider a kerb is
 * somewhere it is not:
 *  - the same kind of landmark (a smell is never the same as a door);
 *  - words that name *this* one, not just the kind — "kerb" and "kerb" are two kerbs, "bakery
 *    smell" and "the bakery smell" are one bakery;
 *  - those words agree (similarity ≥ [MIN_SIMILARITY]);
 *  - and when both walks are located, they start within [MAX_APART_M] of each other.
 */
object LandmarkLinker {
    const val MIN_SIMILARITY = 0.85f
    const val MAX_APART_M = 400.0

    private val FILLER = setOf("the", "a", "an", "of", "on", "at", "by", "near", "my", "your", "left", "right", "side", "big", "small", "little")

    fun link(nodes: List<Node>): List<Edge> {
        // Only landmarks of the same kind with distinctive words can match: group by kind and
        // work the words out once, so 50 walks stay cheap enough to rebuild on every question.
        val out = mutableListOf<Edge>()
        nodes.filter { it.kind == NodeKind.LANDMARK && it.sense != null }
            .map { it to distinctive(it) }
            .filter { it.second.isNotBlank() }
            .groupBy { it.first.sense }
            .values.forEach { group ->
                for (i in group.indices) for (j in i + 1 until group.size) {
                    val (a, da) = group[i]
                    val (b, db) = group[j]
                    if (a.source != b.source && same(a, da, b, db)) out += Edge(a.id, b.id, EdgeKind.SAME_AS)
                }
            }
        return out
    }

    fun same(a: Node, b: Node): Boolean = same(a, distinctive(a), b, distinctive(b))

    private fun same(a: Node, da: String, b: Node, db: String): Boolean {
        if (a.sense == null || a.sense != b.sense) return false
        if (da.isBlank() || db.isBlank()) return false
        if (TextSimilarity.similarity(da, db) < MIN_SIMILARITY) return false
        if (a.located && b.located && Geo.metres(a.lat!!, a.lng!!, b.lat!!, b.lng!!) > MAX_APART_M) return false
        return true
    }

    /** The words of a landmark that are not just its kind: "bakery smell" → "bakery". */
    fun distinctive(n: Node): String {
        val kindRe = com.cabeye.rider.walk.LandmarkParser.KINDS.firstOrNull { it.first == n.sense }?.second
        var t = TextSimilarity.normalise(n.label)
        if (kindRe != null) t = kindRe.replace(t, " ")
        return t.split(' ').filter { it.length >= 3 && it !in FILLER }.joinToString(" ")
    }
}
