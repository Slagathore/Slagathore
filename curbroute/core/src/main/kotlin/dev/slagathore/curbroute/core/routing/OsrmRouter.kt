package dev.slagathore.curbroute.core.routing

import dev.slagathore.curbroute.core.LatLng
import dev.slagathore.curbroute.core.net.HttpGet
import dev.slagathore.curbroute.core.net.array
import dev.slagathore.curbroute.core.net.double
import dev.slagathore.curbroute.core.net.get
import dev.slagathore.curbroute.core.net.string
import dev.slagathore.curbroute.core.net.toJson
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/**
 * Talks to an OSRM server (open-source routing engine on OpenStreetMap data).
 * The magic parameter is `approaches=curb`: "arrive so this point is on the driver's curb side", which is
 * the passenger side in the US.
 *
 * Servers:
 *  - [PUBLIC_DEMO]: free, great for testing, but the policy is "non-commercial, max 1 request/second,
 *    no guarantees". A daily work tool should move off it.
 *  - Self-hosted: `docker run osrm/osrm-backend` with a Texas extract on any box with ~8 GB RAM. Free,
 *    unlimited, private. See the README for the exact commands.
 */
class OsrmRouter(
    private val http: HttpGet,
    baseUrl: String = PUBLIC_DEMO,
    private val profile: String = "driving",
    /** Max coordinates per table request (the server's --max-table-size, 100 by default and on the demo server). */
    private val maxTableCoordinates: Int = 100,
    /** Politeness delay between requests. The demo server asks for at most 1 request/second. */
    private val minIntervalMs: Long = if (baseUrl.trimEnd('/') == PUBLIC_DEMO) 1_000 else 0,
) : CurbRouter {
    private val base = baseUrl.trimEnd('/')
    private var lastCallAt = 0L

    override fun matrix(points: List<LatLng>, curb: List<Boolean>): CostMatrix {
        require(points.size == curb.size) { "points and curb flags must line up" }
        val n = points.size
        val meters = Array(n) { DoubleArray(n) }
        val seconds = Array(n) { DoubleArray(n) }
        // Small problems fit in one request. Bigger ones get tiled: each request takes a block of "from" rows
        // and a block of "to" columns, and together they must stay under the coordinate limit.
        val block = if (n <= maxTableCoordinates) n else maxTableCoordinates / 2
        for (rowStart in 0 until n step block) {
            for (colStart in 0 until n step block) {
                val rows = (rowStart until minOf(n, rowStart + block)).toList()
                val cols = (colStart until minOf(n, colStart + block)).toList()
                val coords = (rows + cols).distinct() // point indexes sent in this request
                val url = "$base/table/v1/$profile/" + coords.joinToString(";") { points[it].toLngLatString() } +
                    "?annotations=distance,duration" +
                    "&approaches=" + coords.joinToString(";") { approach(curb[it]) } +
                    "&sources=" + rows.joinToString(";") { coords.indexOf(it).toString() } +
                    "&destinations=" + cols.joinToString(";") { coords.indexOf(it).toString() }
                val body = call(url)
                rows.forEachIndexed { r, i ->
                    cols.forEachIndexed { c, j ->
                        meters[i][j] = body["distances"][r][c].double ?: UNREACHABLE
                        seconds[i][j] = body["durations"][r][c].double ?: UNREACHABLE
                    }
                }
            }
        }
        for (i in 0 until n) { meters[i][i] = 0.0; seconds[i][i] = 0.0 }
        return CostMatrix(meters, seconds)
    }

    override fun naiveLegMeters(legs: List<Pair<LatLng, LatLng>>, departCurb: List<Boolean>): DoubleArray {
        require(legs.size == departCurb.size)
        val out = DoubleArray(legs.size)
        // Trick: send every "from" (with its parking direction) and every "to" (unrestricted) as separate
        // coordinates, ask for from[k] -> to[k], and read the diagonal. One request per ~50 legs.
        val chunk = maxTableCoordinates / 2
        for (start in legs.indices step chunk) {
            val part = legs.subList(start, minOf(legs.size, start + chunk))
            val m = part.size
            val coords = part.map { it.first } + part.map { it.second }
            val approaches = (0 until m).map { approach(departCurb[start + it]) } + List(m) { "unrestricted" }
            val url = "$base/table/v1/$profile/" + coords.joinToString(";") { it.toLngLatString() } +
                "?annotations=distance" +
                "&approaches=" + approaches.joinToString(";") +
                "&sources=" + (0 until m).joinToString(";") +
                "&destinations=" + (m until 2 * m).joinToString(";")
            val body = call(url)
            for (k in 0 until m) out[start + k] = body["distances"][k][k].double ?: UNREACHABLE
        }
        return out
    }

    override fun route(ordered: List<LatLng>, curb: List<Boolean>): List<RouteLeg> {
        require(ordered.size == curb.size)
        if (ordered.size < 2) return emptyList()
        val legs = mutableListOf<RouteLeg>()
        // Chunk long routes; consecutive chunks share their boundary point so no leg is lost.
        val maxPerRequest = 100
        var from = 0
        while (from < ordered.lastIndex) {
            val to = minOf(ordered.lastIndex, from + maxPerRequest - 1)
            val idx = (from..to).toList()
            val url = "$base/route/v1/$profile/" + idx.joinToString(";") { ordered[it].toLngLatString() } +
                "?overview=false&steps=true&geometries=geojson&continue_straight=true" +
                "&approaches=" + idx.joinToString(";") { approach(curb[it]) }
            val body = call(url)
            for (leg in body["routes"][0]["legs"].array) {
                val path = mutableListOf<LatLng>()
                for (step in leg["steps"].array) {
                    for (pt in step["geometry"]["coordinates"].array) {
                        val p = LatLng(pt[1].double ?: continue, pt[0].double ?: continue) // GeoJSON is [lng, lat]
                        if (path.lastOrNull() != p) path += p
                    }
                }
                legs += RouteLeg(leg["distance"].double ?: 0.0, leg["duration"].double ?: 0.0, path)
            }
            from = to
        }
        return legs
    }

    private fun approach(curb: Boolean) = if (curb) "curb" else "unrestricted"

    private fun call(url: String): JsonElement {
        synchronized(this) {
            val wait = lastCallAt + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCallAt = System.currentTimeMillis()
        }
        val body = http.get(url).toJson()
        val code = body["code"].string
        if (code != "Ok") throw IOException("OSRM said $code: ${body["message"].string}")
        return body
    }

    companion object {
        const val PUBLIC_DEMO = "https://router.project-osrm.org"
    }
}
