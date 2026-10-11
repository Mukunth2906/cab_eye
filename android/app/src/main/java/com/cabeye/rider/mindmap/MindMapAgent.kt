package com.cabeye.rider.mindmap

import com.cabeye.rider.memory.TextSimilarity
import com.cabeye.rider.walk.Side
import kotlin.math.roundToInt

/**
 * Reasons over the mind map and says what it finds, in the rider's terms.
 *
 *  - "What's around the clinic?" → a spoken mental map: the walk from the drop-off, the
 *    landmarks on it and what they mean to the rider, places they know nearby with distance and
 *    direction, and the area it is in.
 *  - "What do I know about Ravi's shop?" → everything joined to that one thing.
 *  - "Remember Ravi's tea shop, where I stop on Fridays" → a personal anchor, joined to the
 *    landmark or place it is about when the agent can tell which one that is.
 *
 * Like the other memory agents it is rule-based and explainable. It never says a way is clear,
 * and anything it has not been told about recently is said with a "check for it".
 */
object MindMapAgent {

    const val NEAR_M = 1_000.0
    const val FIND_SIMILARITY = 0.8f
    /** A note this old is read back as something said a while ago, not as fact. */
    const val STALE_DAYS = 60.0
    const val MAX_ITEMS = 4

    // ---- finding ------------------------------------------------------------------------

    /**
     * The node the rider means, or null when nothing (or more than one thing) clearly matches.
     * A tie is settled by [near] — where the rider is — when one of the candidates is on its walk
     * or within [LandmarkLinker.MAX_APART_M] of it: "the bakery smell" said at the clinic means
     * the clinic's bakery.
     */
    fun find(map: MindMap, heard: String, kinds: Set<NodeKind> = NodeKind.entries.toSet(), near: Node? = null): Node? {
        val best = rankedMatches(map, heard, kinds)
        if (best.size <= 1) return best.firstOrNull()
        if (near == null) return null
        val walks = setOf(near.id) + map.neighbours(near.id, EdgeKind.LEADS_TO).map { it.id }
        best.filter { n -> map.edges.any { it.kind == EdgeKind.ON_WALK && it.from == n.id && it.to in walks } }
            .singleOrNull()?.let { return it }
        val local = best.filter { n ->
            n.located && near.located && Geo.metres(n.lat!!, n.lng!!, near.lat!!, near.lng!!) <= LandmarkLinker.MAX_APART_M
        }
        return local.singleOrNull() ?: local.firstOrNull()?.takeIf { first ->
            local.all { it.id == first.id || map.edges.any { e -> e.kind == EdgeKind.SAME_AS && setOf(e.from, e.to) == setOf(first.id, it.id) } }
        }
    }

    /** The best match, or every candidate tied with it (empty when nothing matches). */
    private fun rankedMatches(map: MindMap, heard: String, kinds: Set<NodeKind>): List<Node> {
        val words = TextSimilarity.normalise(heard).replace(Regex("^(the|my)\\s+"), "")
            .replace(Regex("\\s+(door|entrance)$"), "").trim()
        if (words.isBlank()) return emptyList()
        val scored = map.nodes.filter { it.kind in kinds }.map { n ->
            val names = listOf(n.label, n.person).filter { it.isNotBlank() }
            n to (names.maxOfOrNull { name ->
                val t = TextSimilarity.normalise(name).replace(Regex("^the\\s+"), "").replace(Regex("\\s+(door|entrance)$"), "")
                when {
                    t == words -> 1f
                    t.contains(words) && words.length >= 4 -> 0.9f
                    else -> TextSimilarity.similarity(words, t)
                }
            } ?: 0f)
        }.filter { it.second >= FIND_SIMILARITY }.sortedWith(compareByDescending<Pair<Node, Float>> { it.second }.thenBy { rank(it.first) })
        val top = scored.firstOrNull() ?: return emptyList()
        // Two different things that sound the same: say nothing rather than the wrong one.
        // The same landmark on two walks is one memory, so that is not a tie.
        val tied = scored.drop(1).filter { top.second - it.second < 0.05f && rank(it.first) == rank(top.first) }
            .filter { other -> map.edges.none { it.kind == EdgeKind.SAME_AS && setOf(it.from, it.to) == setOf(top.first.id, other.first.id) } }
        return listOf(top.first) + tied.map { it.first }
    }

    /** Places first, then the rider's own notes, then landmarks: a place name is the likelier meaning. */
    private fun rank(n: Node) = when (n.kind) {
        NodeKind.PLACE -> 0; NodeKind.ANCHOR -> 1; NodeKind.AREA -> 2; NodeKind.LANDMARK -> 3; NodeKind.RIDE_CUE -> 4
    }

    // ---- "what's around X" ----------------------------------------------------------------

    fun around(map: MindMap, target: Node, now: Long, heading: Double? = null): String {
        val lines = mutableListOf<String>()
        val name = display(target)

        // The ride, then the walk, then the door.
        val walks = (if (target.id.startsWith("walk:")) listOf(target) else emptyList()) +
            map.edges.filter { it.kind == EdgeKind.LEADS_TO && it.from == target.id }.mapNotNull { map.node(it.to) }
        for (w in walks.take(2)) {
            val lms = map.edges.filter { it.kind == EdgeKind.ON_WALK && it.to == w.id }.mapNotNull { map.node(it.from) }
            val lead = if (w.id == target.id) "On your walk to ${w.label}" else "From the drop-off, you have a walk to ${w.label}"
            lines += if (lms.isEmpty()) "$lead." else
                "$lead, you pass ${lms.take(MAX_ITEMS).joinToString(", ") { landmark(map, it, now) }}."
        }

        // The rider's own notes about this place, or about places close to it.
        val notes = map.anchorsFor(target.id) +
            map.neighbours(target.id, EdgeKind.NEAR).filter { it.kind == NodeKind.ANCHOR }
        notes.distinctBy { it.id }.take(MAX_ITEMS).forEach { lines += "Your note: ${anchor(it, now)}." }

        // Things the rider knows that are close by, with distance and direction.
        if (target.located) {
            val skip = walks.map { it.id }.toSet() + target.id + notes.map { it.id }
            val near = map.near(target.lat!!, target.lng!!, NEAR_M, setOf(NodeKind.PLACE, NodeKind.ANCHOR))
                .filter { (n, d) -> n.id !in skip && d >= 30 && !n.id.startsWith("walk:") && standalone(map, n) }
                .distinctBy { TextSimilarity.normalise(it.first.label) }
                .take(3)
            if (near.isNotEmpty()) {
                lines += "Places you know nearby: " + near.joinToString("; ") { (n, d) ->
                    val dir = Geo.bearing(target.lat, target.lng, n.lat!!, n.lng!!)
                    "${display(n)}, ${Geo.spokenDistance(d)} ${heading?.let { Geo.clock(dir, it) } ?: Geo.compass(dir)}"
                } + "."
            }
        }

        // Ride cues on the way there.
        val cues = map.edges.filter { it.kind == EdgeKind.ON_RIDE && it.to == target.id }
            .mapNotNull { map.node(it.from) }.filter { it.kind == NodeKind.RIDE_CUE }
            .sortedBy { it.atMetres ?: 0.0 }
        if (cues.isNotEmpty()) lines += "On the ride there you've noted ${cues.take(MAX_ITEMS).joinToString(", then ") { the(it.label) }}."

        map.neighbours(target.id, EdgeKind.IN_AREA).firstOrNull { it.kind == NodeKind.AREA }?.let {
            lines += "It's in ${it.label}."
        }

        if (lines.isEmpty()) {
            return "I don't know much around $name yet. When you're there, say record my walk, " +
                "or tell me what you notice, like remember Ravi's tea shop, where I stop on Fridays."
        }
        return "Around $name. " + lines.joinToString(" ")
    }

    /**
     * "What's around here?" away from any place the map has as a node: the [area] the phone
     * names, and the places and notes the rider knows within [NEAR_M], nearest first. Null when
     * there is neither — the caller then offers the well-known places instead.
     */
    fun nearPoint(map: MindMap, lat: Double, lng: Double, area: String?, now: Long, heading: Double? = null): String? {
        val near = map.near(lat, lng, NEAR_M, setOf(NodeKind.PLACE, NodeKind.ANCHOR))
            .filter { (n, _) -> !n.id.startsWith("walk:") && standalone(map, n) }
            .distinctBy { TextSimilarity.normalise(it.first.label) }
            .take(3)
        if (area == null && near.isEmpty()) return null
        val parts = mutableListOf<String>()
        area?.let { parts += "You're in $it." }
        if (near.isNotEmpty()) {
            parts += "Places you know nearby: " + near.joinToString("; ") { (n, d) ->
                val dir = Geo.bearing(lat, lng, n.lat!!, n.lng!!)
                val note = if (n.kind == NodeKind.ANCHOR) meaningOf(n) else ""
                "${display(n)}$note, ${if (d < 30) "right here" else "${Geo.spokenDistance(d)} ${heading?.let { Geo.clock(dir, it) } ?: Geo.compass(dir)}"}"
            } + "."
        }
        return parts.joinToString(" ")
    }

    // ---- "what do I know about X" ---------------------------------------------------------

    fun about(map: MindMap, n: Node, now: Long): String {
        val out = mutableListOf<String>()
        when (n.kind) {
            NodeKind.ANCHOR -> {
                out += "${anchor(n, now).replaceFirstChar { it.uppercase() }}."
                map.edges.filter { it.from == n.id && it.kind == EdgeKind.MEANS }.mapNotNull { map.node(it.to) }
                    .forEach { out += "It's about ${describeLink(map, it)}." }
                map.edges.filter { it.from == n.id && it.kind == EdgeKind.NEAR }.mapNotNull { map.node(it.to) }
                    .forEach { out += "It's near ${display(it)}." }
            }
            NodeKind.LANDMARK -> {
                val walks = (listOf(n) + map.neighbours(n.id, EdgeKind.SAME_AS))
                    .flatMap { map.neighbours(it.id, EdgeKind.ON_WALK) }.distinctBy { it.id }
                out += "${the(n.label).replaceFirstChar { it.uppercase() }}${sideOf(n)} is on your walk to " +
                    walks.joinToString(" and ") { it.label } + "."
                caution(n, now)?.let { out += it }
            }
            NodeKind.RIDE_CUE -> {
                val dest = map.neighbours(n.id, EdgeKind.ON_RIDE).firstOrNull()
                out += "${the(n.label).replaceFirstChar { it.uppercase() }} is on your ride" +
                    (dest?.let { " to ${display(it)}" } ?: "") +
                    (n.atMetres?.let { ", ${Geo.spokenDistance(it)} in" } ?: "") + "."
            }
            NodeKind.AREA -> {
                val places = map.neighbours(n.id, EdgeKind.IN_AREA).filter { it.kind == NodeKind.PLACE }
                out += if (places.isEmpty()) "You've passed through ${n.label} on rides."
                else "${n.label}: ${places.take(MAX_ITEMS).joinToString(", ") { display(it) }}."
            }
            NodeKind.PLACE -> return around(map, n, now)
        }
        map.anchorsFor(n.id).filter { n.kind != NodeKind.ANCHOR }.forEach { out += "Your note: ${anchor(it, now)}." }
        return out.joinToString(" ")
    }

    fun summary(map: MindMap): String {
        val count = { k: NodeKind -> map.nodes.count { it.kind == k } }
        val walks = map.nodes.count { it.id.startsWith("walk:") }
        val places = count(NodeKind.PLACE) - walks
        if (map.isEmpty) return "Your mind map is empty so far. It grows as you ride, walk, and tell me what places mean to you."
        val parts = listOfNotNull(
            places.takeIf { it > 0 }?.let { plural(it, "place") },
            walks.takeIf { it > 0 }?.let { plural(it, "walk") },
            count(NodeKind.LANDMARK).takeIf { it > 0 }?.let { plural(it, "landmark") },
            count(NodeKind.ANCHOR).takeIf { it > 0 }?.let { plural(it, "personal note") },
            count(NodeKind.RIDE_CUE).takeIf { it > 0 }?.let { plural(it, "ride cue") },
            count(NodeKind.AREA).takeIf { it > 0 }?.let { plural(it, "area") }
        )
        val shared = map.edges.count { it.kind == EdgeKind.SAME_AS }
        return "Your mind map has ${parts.joinToString(", ")}." +
            (if (shared > 0) " ${plural(shared, "landmark")} ${if (shared == 1) "is" else "are"} shared between walks." else "") +
            " Ask what's around a place, or what do I know about something."
    }

    // ---- walk narration -------------------------------------------------------------------

    /**
     * The rider's notes for the landmarks of one walk, keyed by the walk landmark's own id, for
     * [com.cabeye.rider.walk.WalkNarrator]: "the bakery smell, where you buy bread for Amma".
     * Also says when a landmark is the same one the rider knows from another walk.
     */
    fun notesFor(map: MindMap, routeId: String): Map<String, String> =
        map.nodes.filter { it.kind == NodeKind.LANDMARK && it.source == routeId }.mapNotNull { lm ->
            val own = map.anchorsFor(lm.id).firstOrNull()
            val shared = map.neighbours(lm.id, EdgeKind.SAME_AS)
                .flatMap { map.neighbours(it.id, EdgeKind.ON_WALK) }.firstOrNull()
            val note = when {
                own != null && own.habit.isNotBlank() -> "where ${own.habit}"
                own != null && own.meaning.isNotBlank() -> "which means ${own.meaning}"
                shared != null -> "the one on your walk to ${shared.label}"
                else -> null
            } ?: return@mapNotNull null
            lm.id.removePrefix("lm:") to note
        }.toMap()

    // ---- remembering -----------------------------------------------------------------------

    data class Remembered(val nodes: List<Node>, val edges: List<Edge>, val spoken: String, val anchorId: String)

    /**
     * A new personal anchor, joined to what it is about.
     *
     * @param map the whole mind map (derived + authored), to find what the anchor is about
     * @param here where the rider is, when known (the drop-off, a walk end): "this place" means it
     * @param lat where the rider is now — only passed when the anchor is said on the spot
     */
    fun remember(
        map: MindMap,
        a: AnchorParser.Anchor,
        here: Node?,
        lat: Double?,
        lng: Double?,
        now: Long,
        newId: String
    ): Remembered {
        val about = when {
            a.here -> here
            else -> find(map, a.subject, setOf(NodeKind.LANDMARK, NodeKind.PLACE, NodeKind.RIDE_CUE), near = here)
        }
        val existing = map.nodes.firstOrNull { n ->
            n.kind == NodeKind.ANCHOR && (
                (about != null && map.edges.any { it.kind == EdgeKind.MEANS && it.from == n.id && it.to == about.id } &&
                    (a.subject.isBlank() || TextSimilarity.similarity(a.subject, n.label) >= 0.85f)) ||
                    (a.subject.isNotBlank() && TextSimilarity.similarity(a.subject, n.label) >= 0.9f))
        }
        // "Remember this as the place I meet Priya" away from any known place: named by the habit.
        // The rider's words arrive in lower case: a person's name is said back as a name.
        val named: (String) -> String = { s ->
            if (a.person.isBlank() || a.person.startsWith("your ")) s
            else s.replace(Regex("\\b${Regex.escape(a.person.lowercase())}\\b"), a.person)
        }
        val label = named(displayLabel(
            a.subject.ifBlank { about?.label ?: a.habit.takeIf { it.isNotBlank() }?.let { "where $it" } ?: "this spot" }
        ))
        val node = Node(
            id = existing?.id ?: "a:$newId",
            kind = NodeKind.ANCHOR,
            label = label,
            lat = existing?.lat ?: about?.lat ?: lat,
            lng = existing?.lng ?: about?.lng ?: lng,
            sense = a.sense ?: existing?.sense ?: about?.sense,
            side = if (a.side != Side.NONE) a.side else existing?.side ?: Side.NONE,
            person = a.person.ifBlank { existing?.person.orEmpty() },
            habit = merge(existing?.habit, named(a.habit)),
            meaning = merge(existing?.meaning, named(a.meaning)),
            words = a.words,
            createdAt = existing?.createdAt ?: now,
            lastConfirmedAt = now,
            confirmations = (existing?.confirmations ?: 0) + 1
        )
        val edges = mutableListOf<Edge>()
        if (about != null) edges += Edge(node.id, about.id, EdgeKind.MEANS)
        val towards = a.relatesTo.takeIf { it.isNotBlank() }?.let { find(map, it, setOf(NodeKind.PLACE, NodeKind.ANCHOR)) }
            ?.takeIf { it.id != node.id && it.id != about?.id }
        if (towards != null) edges += Edge(node.id, towards.id, EdgeKind.NEAR)
        // Said on the spot, about nothing the map already has: it is near where the rider stands.
        if (about == null && here != null && !a.here) edges += Edge(node.id, here.id, EdgeKind.NEAR)

        val linked = when {
            about != null && about.kind == NodeKind.LANDMARK ->
                " I've joined it to ${the(about.label)} on your walk to ${map.neighbours(about.id, EdgeKind.ON_WALK).firstOrNull()?.label ?: "there"}."
            about != null -> " I've joined it to ${display(about)}."
            here != null && !a.here -> " I've noted it near ${display(here)}."
            else -> ""
        }
        val towardsText = towards?.let { " I'll mention it when you ask about ${display(it)}." } ?: ""
        val spoken = (if (existing != null) "Updated your note. " else "Got it. ") +
            "${anchor(node, now).replaceFirstChar { it.uppercase() }}.$linked$towardsText"
        return Remembered(listOf(node), edges, spoken, node.id)
    }

    /** "Forget the note about Ravi's shop" / "… about the flyover". The note or ride cue that best matches, or null. */
    fun findAnchor(map: MindMap, heard: String): Node? = find(map, heard, setOf(NodeKind.ANCHOR, NodeKind.RIDE_CUE))
        ?: find(map, heard, setOf(NodeKind.LANDMARK, NodeKind.PLACE))?.let { map.anchorsFor(it.id).singleOrNull() }

    const val BOOK_SIMILARITY = 0.9f

    /**
     * A place the rider taught the app — "Ravi's tea shop", "where I meet Priya" — that a cab
     * can be booked to: it has a position, it is the rider's own (a note or a kept place, not a
     * sound or a smell), and it is clearly the one meant. Small shops and meeting spots are
     * often not on any map; this is how "take me to Ravi's tea shop" still works.
     */
    fun bookable(map: MindMap, phrase: String): Node? {
        val heard = TextSimilarity.normalise(phrase).replace(Regex("^(?:the|my)\\s+"), "")
        if (heard.isBlank() || TextSimilarity.isGenericOnly(heard)) return null
        // The rider says "where I meet Priya"; the note is stored as "where you meet Priya".
        val second = TextSimilarity.normalise(AnchorParser.second(heard))
        // Both ways round: "tea shop" is all inside "Ravi's tea shop", but it does not name it.
        fun both(a: String, b: String) = minOf(TextSimilarity.similarity(a, b), TextSimilarity.similarity(b, a))
        val scored = map.nodes
            .filter { it.located && it.sense == null && (it.kind == NodeKind.ANCHOR || it.id.startsWith("near:")) }
            .map { n -> n to maxOf(both(heard, n.label), both(second, n.label)) }
            .filter { it.second >= BOOK_SIMILARITY }
            .sortedByDescending { it.second }
        val top = scored.firstOrNull() ?: return null
        val rival = scored.getOrNull(1)
        if (rival != null && top.second - rival.second < 0.05f &&
            Geo.metres(top.first.lat!!, top.first.lng!!, rival.first.lat!!, rival.first.lng!!) > 100
        ) return null
        return top.first
    }

    // ---- phrases ---------------------------------------------------------------------------

    /** "Ravi's tea shop, where you stop on Fridays" / "the temple bell, which means you're near Amma's house" */
    fun anchor(n: Node, now: Long): String {
        val body = display(n) + meaningOf(n)
        return if (n.ageDays(now) >= STALE_DAYS) "$body — you told me that ${months(n.ageDays(now))}, so check it still holds" else body
    }

    /** ", where you stop on Fridays, and which means …" — empty when the label already says it all. */
    private fun meaningOf(n: Node): String {
        // "where you meet Priya" is already the habit: not "where you meet Priya, where you meet Priya".
        val habit = n.habit.takeIf { it.isNotBlank() && !n.label.equals("where ${n.habit}", ignoreCase = true) }
        val parts = listOfNotNull(habit?.let { "where $it" }, n.meaning.takeIf { it.isNotBlank() }?.let { "which means $it" })
        return if (parts.isEmpty()) "" else ", " + parts.joinToString(", and ")
    }

    /** A place or a note of the rider's own that stands for a spot — not a note about a landmark, and not a sound or smell. */
    private fun standalone(map: MindMap, n: Node): Boolean = n.kind == NodeKind.PLACE ||
        (n.kind == NodeKind.ANCHOR && n.sense == null && map.edges.none { it.from == n.id && it.kind == EdgeKind.MEANS })

    /** "the temple, the park and the mall" */
    fun spokenList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun landmark(map: MindMap, n: Node, now: Long): String {
        val note = map.anchorsFor(n.id).firstOrNull()?.let { a -> meaningOf(a).takeIf { it.isNotEmpty() } } ?: ""
        val stale = if (n.confidence <= 0.5) " (it wasn't there last time)" else if (n.ageDays(now) >= 30) " (check for it)" else ""
        return the(n.label) + sideOf(n) + note + stale
    }

    private fun caution(n: Node, now: Long): String? = when {
        n.confidence <= 0.5 -> "It wasn't there last time, so don't rely on it."
        n.ageDays(now) >= 30 -> "It was last confirmed ${months(n.ageDays(now))}, so check for it."
        else -> null
    }

    private fun describeLink(map: MindMap, n: Node): String = when (n.kind) {
        NodeKind.LANDMARK -> "${the(n.label)} on your walk to ${map.neighbours(n.id, EdgeKind.ON_WALK).firstOrNull()?.label ?: "a saved route"}"
        else -> display(n)
    }

    private fun sideOf(n: Node) = if (n.side != Side.NONE) " ${n.side.spoken}" else ""

    fun display(n: Node): String =
        if (n.kind == NodeKind.LANDMARK || n.kind == NodeKind.RIDE_CUE || (n.kind == NodeKind.ANCHOR && n.sense != null)) the(n.label)
        else n.label

    fun the(label: String): String {
        val first = label.split(' ').firstOrNull().orEmpty()
        return if (first.endsWith("'s") || first.firstOrNull()?.isDigit() == true || first in setOf("the", "a", "an", "your")) label
        else "the $label"
    }

    /** "ravi's tea shop" → "Ravi's tea shop": names said back as names. */
    fun displayLabel(s: String): String =
        s.split(' ').joinToString(" ") { w -> if (w.endsWith("'s") && w.length > 2) w.replaceFirstChar { it.uppercase() } else w }

    private fun merge(old: String?, new: String): String = when {
        new.isBlank() -> old.orEmpty()
        old.isNullOrBlank() -> new
        TextSimilarity.similarity(old, new) >= 0.85f || old.contains(new) -> old
        else -> "$old, and $new"
    }

    private fun months(days: Double) = when {
        days < 60 -> "${(days / 7).roundToInt()} weeks ago"
        days < 730 -> "${(days / 30).roundToInt()} months ago"
        else -> "${(days / 365).roundToInt()} years ago"
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}
