package dev.slagathore.curbroute.core.nav

import java.net.URLEncoder

/**
 * Builds Google Maps "directions" links that open straight into turn-by-turn navigation.
 *
 * Limits (developers.google.com/maps/documentation/urls/get-started):
 *  - The Android Maps app takes max 9 waypoints + 1 destination per link (mobile BROWSERS only take 3).
 *  - There is no parameter for "arrive on this side of the road", and no "pass-through" waypoint type.
 *
 * So to force the approach direction, the app inserts a normal waypoint ~60 m BEFORE the house along the
 * correct direction of travel. Maps has to drive through it, which lines you up curb-side. Side effect:
 * Maps announces "arrived at waypoint" there. Just keep rolling.
 *
 * Leaving out `origin` makes Maps start from your current GPS position, which is what we want: link #2
 * is opened wherever link #1 finished.
 */
object GoogleMapsLinks {
    const val MAX_WAYPOINTS = 9

    /** [query] = an address or "lat,lng". [isLeadIn] = a direction-forcing point that belongs to the next house. */
    data class Point(val query: String, val isLeadIn: Boolean = false)

    fun build(points: List<Point>, maxWaypoints: Int = MAX_WAYPOINTS): List<String> {
        require(maxWaypoints >= 1)
        val links = mutableListOf<String>()
        var from = 0
        while (from < points.size) {
            var to = minOf(points.size, from + maxWaypoints + 1) // exclusive; +1 for the destination
            // Never end a link on a lead-in point: it must be in the same link as its house.
            if (to < points.size && points[to - 1].isLeadIn && to - 1 > from) to--
            links += toUrl(points.subList(from, to))
            from = to
        }
        return links
    }

    fun toUrl(chunk: List<Point>): String {
        require(chunk.isNotEmpty())
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate")
        sb.append("&destination=").append(enc(chunk.last().query))
        if (chunk.size > 1) {
            sb.append("&waypoints=").append(enc(chunk.dropLast(1).joinToString("|") { it.query }))
        }
        return sb.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
