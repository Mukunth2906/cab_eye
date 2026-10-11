package com.cabeye.rider.mindmap

import kotlin.math.ceil
import kotlin.math.cos

/**
 * Made-up GPS for trying the ride guide without a cab: fixes every [stepM] metres from a real
 * start to a real destination, bowed a little to one side the way roads bend, [secondsPerFix]
 * apart in ride time. Deterministic, so the same two places always give the same "usual way" —
 * the second simulated ride is a repeat ride, with cues and the usual time.
 *
 * Only the movement is synthetic. The places, and the area names the guide says along the way,
 * are real (the bundled Coimbatore atlas).
 */
object SyntheticRide {

    data class Fix(val lat: Double, val lng: Double, val t: Long)

    fun route(
        fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, start: Long,
        stepM: Double = 100.0, secondsPerFix: Long = 15, bow: Double = 0.08
    ): List<Fix> {
        val total = Geo.metres(fromLat, fromLng, toLat, toLng)
        val n = maxOf(2, ceil(total * (1 + bow) / stepM).toInt())
        // A quadratic curve through a control point pushed sideways from the middle of the line.
        val midLat = (fromLat + toLat) / 2
        val midLng = (fromLng + toLng) / 2
        val dLat = toLat - fromLat
        val dLng = (toLng - fromLng) * cos(Math.toRadians(midLat))
        val ctrlLat = midLat - dLng * bow * 2
        val ctrlLng = midLng + dLat * bow * 2 / cos(Math.toRadians(midLat))
        return (0..n).map { i ->
            val s = i.toDouble() / n
            val a = (1 - s) * (1 - s)
            val b = 2 * (1 - s) * s
            val c = s * s
            Fix(a * fromLat + b * ctrlLat + c * toLat, a * fromLng + b * ctrlLng + c * toLng, start + i * secondsPerFix * 1000)
        }
    }

    /** Length of a route in metres, as the guide's odometer would count it. */
    fun metres(route: List<Fix>): Double =
        route.zipWithNext().sumOf { (p, q) -> Geo.metres(p.lat, p.lng, q.lat, q.lng) }
}
