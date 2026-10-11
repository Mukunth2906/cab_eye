package com.cabeye.rider.mindmap

import com.cabeye.rider.memory.SavedRoute
import com.cabeye.rider.memory.VisitedPlace
import com.cabeye.rider.walk.WalkNarrator
import com.cabeye.rider.walk.WalkProfile
import com.cabeye.rider.walk.WalkRoute

/**
 * Everything in the rider's mind map, laid out for the screen: what came from rides and what came
 * from walking and exploring, each with what it can do (book a ride, guide a walk). Pure, so the
 * layout is a JVM test.
 */
object MindMapCatalog {

    /** A place that can be booked: a cab destination, or a spot the rider named on foot. */
    data class PlaceCard(
        val nodeId: String,
        val name: String,
        val module: MindModule,
        val kindLabel: String,
        val lat: Double?,
        val lng: Double?,
        val visits: Int = 0,
        val aliases: List<String> = emptyList(),
        val distanceM: Double? = null,
        val direction: String = "",
        val notes: List<String> = emptyList(),
        val walks: List<String> = emptyList(),
        val walkIds: List<String> = emptyList(),
        val cues: List<String> = emptyList(),
        val area: String = "",
        val seenHere: List<String> = emptyList(),
        val placeId: String = ""
    )

    data class WalkCard(
        val routeId: String,
        val name: String,
        val metres: Int,
        val parts: Int,
        val landmarks: List<String>,
        val checkFor: List<String>,
        val detail: String,
        val from: String,
        val walked: Int,
        val explored: Boolean
    )

    data class NoteCard(val nodeId: String, val title: String, val detail: String, val module: MindModule, val kind: NodeKind)

    data class Sighting(val nodeId: String, val label: String, val times: Int, val distanceM: Double?, val direction: String, val side: String)

    data class RouteCard(val name: String, val stops: List<String>, val destination: String, val uses: Int)

    /** A dot on the radar: bearing and distance from the rider. */
    data class Dot(val nodeId: String, val label: String, val module: MindModule, val kind: NodeKind, val bearing: Double, val distanceM: Double)

    data class Catalog(
        val ridePlaces: List<PlaceCard>,
        val rideNotes: List<NoteCard>,
        val routes: List<RouteCard>,
        val spots: List<PlaceCard>,
        val walks: List<WalkCard>,
        val notes: List<NoteCard>,
        val sightings: List<Sighting>,
        val dots: List<Dot>,
        val centre: Pair<Double, Double>?,
        val summary: String
    ) {
        val isEmpty: Boolean get() = ridePlaces.isEmpty() && spots.isEmpty() && walks.isEmpty() && notes.isEmpty() && sightings.isEmpty()
    }

    fun build(
        map: MindMap,
        places: List<VisitedPlace>,
        routes: List<SavedRoute>,
        walks: List<WalkRoute>,
        profile: WalkProfile,
        here: Pair<Double, Double>?,
        now: Long
    ): Catalog {
        val visits = places.associateBy { MindMapBuilder.placeId(it.placeKey) }

        fun dist(n: Node): Pair<Double?, String> {
            if (here == null || !n.located) return null to ""
            val d = Geo.metres(here.first, here.second, n.lat!!, n.lng!!)
            return d to Geo.compass(Geo.bearing(here.first, here.second, n.lat, n.lng))
        }

        fun card(n: Node, module: MindModule, kindLabel: String): PlaceCard {
            val (d, dir) = dist(n)
            val v = visits[n.id]
            val walkNodes = map.edges.filter { it.kind == EdgeKind.LEADS_TO && it.from == n.id }.mapNotNull { map.node(it.to) }
            val notes = (map.anchorsFor(n.id) + map.neighbours(n.id, EdgeKind.NEAR).filter { it.kind == NodeKind.ANCHOR })
                .distinctBy { it.id }.filter { it.id != n.id }
                .map { a -> listOf(a.label, a.habit, a.meaning).filter { it.isNotBlank() }.joinToString(" — ") }
            val cues = map.edges.filter { it.kind == EdgeKind.ON_RIDE && it.to == n.id }
                .mapNotNull { map.node(it.from) }.filter { it.kind == NodeKind.RIDE_CUE }.map { it.label }
            val area = map.neighbours(n.id, EdgeKind.IN_AREA).firstOrNull { it.kind == NodeKind.AREA }?.label.orEmpty()
            val seen = if (n.located) map.near(n.lat!!, n.lng!!, MindMapAgent.SEEN_NEAR_M, setOf(NodeKind.OBSERVED))
                .map { it.first.label }.distinct().take(5) else emptyList()
            return PlaceCard(
                nodeId = n.id, name = n.label, module = module, kindLabel = kindLabel, lat = n.lat, lng = n.lng,
                visits = v?.visitCount ?: 0, aliases = v?.aliases.orEmpty(), distanceM = d, direction = dir,
                notes = notes, walks = walkNodes.map { it.label }, walkIds = walkNodes.map { it.source },
                cues = cues, area = area, seenHere = seen, placeId = v?.placeId ?: n.source
            )
        }

        val ridePlaces = map.nodes
            .filter { it.kind == NodeKind.PLACE && MindModule.of(it.id) == MindModule.RIDES && !it.id.startsWith("walk:") }
            .map { card(it, MindModule.RIDES, if (it.id.startsWith("near:")) "Well-known place" else "Ride destination") }
            .sortedWith(compareByDescending<PlaceCard> { it.visits }.thenBy { it.distanceM ?: Double.MAX_VALUE })

        val spots = map.nodes
            .filter { it.kind == NodeKind.ANCHOR && it.located && it.sense == null }
            .map { a ->
                card(a, MindModule.EXPLORE, "Named spot").copy(
                    notes = listOf(a.habit, a.meaning).filter { it.isNotBlank() }
                )
            }
            .sortedBy { it.distanceM ?: Double.MAX_VALUE }

        val rideNotes = map.nodes.filter { it.kind == NodeKind.RIDE_CUE || it.kind == NodeKind.AREA }.map { n ->
            when (n.kind) {
                NodeKind.RIDE_CUE -> {
                    val to = map.neighbours(n.id, EdgeKind.ON_RIDE).firstOrNull()?.label
                    NoteCard(n.id, n.label, "Felt on the ride" + (to?.let { " to $it" } ?: "") +
                        (n.atMetres?.let { ", ${Geo.spokenDistance(it)} in" } ?: ""), MindModule.RIDES, n.kind)
                }
                else -> {
                    val ps = map.neighbours(n.id).filter { it.kind == NodeKind.PLACE }.map { it.label }.distinct()
                    NoteCard(n.id, n.label, "Area you ride through" + if (ps.isNotEmpty()) ": ${ps.take(3).joinToString(", ")}" else "",
                        MindModule.RIDES, n.kind)
                }
            }
        }

        val notes = map.nodes.filter { it.kind == NodeKind.ANCHOR && !(it.located && it.sense == null) }.map { a ->
            val about = (map.edges.filter { it.from == a.id && (it.kind == EdgeKind.MEANS || it.kind == EdgeKind.NEAR) })
                .mapNotNull { map.node(it.to) }.map { MindMapAgent.display(it) }
            NoteCard(
                a.id, a.label,
                listOf(a.habit, a.meaning).filter { it.isNotBlank() }.joinToString("; ") +
                    if (about.isNotEmpty()) " (about ${about.joinToString(", ")})" else "",
                MindModule.EXPLORE, a.kind
            )
        }

        val walkCards = walks.sortedByDescending { it.lastWalkedAt }.map { r ->
            val level = WalkNarrator.levelFor(r, profile)
            WalkCard(
                routeId = r.id, name = r.name, metres = r.totalMetres.toInt(), parts = r.segments.size,
                landmarks = r.landmarks.map { it.text },
                checkFor = r.landmarks.filter { it.confidence < 0.6 || now - maxOf(it.lastConfirmedAt, it.recordedAt) > 45 * 86_400_000L }.map { it.text },
                detail = level.name.lowercase().replaceFirstChar { it.uppercase() },
                from = r.placeName.ifBlank { r.start },
                walked = r.walks,
                explored = r.id.startsWith("walk-x")
            )
        }

        val sightings = map.nodes.filter { it.kind == NodeKind.OBSERVED }.map { o ->
            val (d, dir) = dist(o)
            Sighting(o.id, o.label, o.confirmations, d, dir, o.side.spoken)
        }.sortedWith(compareBy<Sighting> { it.distanceM ?: Double.MAX_VALUE }.thenByDescending { it.times }).take(60)

        val savedRoutes = routes.map { r ->
            RouteCard(r.name, r.stops.map { s -> s.name + if (s.kind.isNotBlank()) " (${s.kind.lowercase()})" else "" }, r.destination.name, r.useCount)
        }

        // The radar is centred on the rider, or on the place they use most when the phone can't tell.
        val centre = here ?: ridePlaces.firstOrNull { it.lat != null }?.let { it.lat!! to it.lng!! }
        val dots = if (centre == null) emptyList() else map.nodes
            .filter { it.located && !it.id.startsWith("walk:") && it.kind in setOf(NodeKind.PLACE, NodeKind.ANCHOR, NodeKind.OBSERVED, NodeKind.RIDE_CUE) }
            .map { n ->
                Dot(n.id, n.label, MindModule.of(n.id), n.kind,
                    Geo.bearing(centre.first, centre.second, n.lat!!, n.lng!!),
                    Geo.metres(centre.first, centre.second, n.lat, n.lng))
            }
            .filter { it.distanceM <= 25_000 }

        return Catalog(
            ridePlaces = ridePlaces, rideNotes = rideNotes, routes = savedRoutes,
            spots = spots, walks = walkCards, notes = notes, sightings = sightings,
            dots = dots, centre = centre, summary = MindMapAgent.summary(map)
        )
    }
}
