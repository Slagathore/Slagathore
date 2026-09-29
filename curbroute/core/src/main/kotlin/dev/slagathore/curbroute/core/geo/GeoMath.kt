package dev.slagathore.curbroute.core.geo

import dev.slagathore.curbroute.core.LatLng
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Straight-line ("as the crow flies") distance in meters - the haversine formula. */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /**
     * Walks backwards from the END of a driving path by [meters] and returns that spot.
     * Used to drop a "come in from this direction" pass-through point just before a house.
     */
    fun pointBeforeEnd(path: List<LatLng>, meters: Double): LatLng {
        require(path.isNotEmpty()) { "empty path" }
        var remaining = meters
        for (i in path.lastIndex downTo 1) {
            val to = path[i]
            val from = path[i - 1]
            val segment = distanceMeters(from, to)
            if (segment > 0 && segment >= remaining) {
                val t = remaining / segment // fraction of the way back along this segment
                return LatLng(to.lat + (from.lat - to.lat) * t, to.lng + (from.lng - to.lng) * t)
            }
            remaining -= segment
        }
        return path.first() // path shorter than [meters]: the start of the leg is the best we can do
    }
}
