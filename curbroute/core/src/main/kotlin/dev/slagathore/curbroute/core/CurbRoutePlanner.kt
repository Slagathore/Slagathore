package dev.slagathore.curbroute.core

import dev.slagathore.curbroute.core.geo.GeoMath
import dev.slagathore.curbroute.core.geo.Geocoder
import dev.slagathore.curbroute.core.nav.GoogleMapsLinks
import dev.slagathore.curbroute.core.routing.CurbRouter
import dev.slagathore.curbroute.core.routing.RouteLeg
import dev.slagathore.curbroute.core.routing.UNREACHABLE
import dev.slagathore.curbroute.core.solve.RouteSolver
import kotlinx.serialization.Serializable
import java.io.IOException

/** One stop in the optimized route. */
@Serializable
data class PlannedStop(
    val sheet: SheetStop,
    val location: LatLng,
    /** Drive from the previous stop (or your start) to here. 0 for the first stop when there's no GPS start. */
    val legMeters: Double,
    val legSeconds: Double,
    /**
     * Non-null when plain Google Maps would likely bring you in on the WRONG side for this stop.
     * It's a point ~60 m before the house on the correct approach, and the Maps link routes through it.
     */
    val leadIn: LatLng?,
)

@Serializable
data class RoutePlan(
    val stops: List<PlannedStop>,
    /** Addresses no geocoder could find. Show these to the driver, don't drop them silently. */
    val unresolved: List<SheetStop>,
    val totalMeters: Double,
    val totalSeconds: Double,
    /** Same start and same curb-side rule, but in the paper's order. "How much did we save?" */
    val sheetOrderMeters: Double,
    val sheetOrderSeconds: Double,
    val mapsLinks: List<String>,
    /** Full road geometry, for drawing on a map later. Empty if the geometry request failed. */
    val path: List<LatLng>,
    val warnings: List<String>,
)

/**
 * The whole pipeline: geocode -> curb-side matrix -> optimize -> find wrong-side risks -> Google Maps links.
 * All blocking calls. On Android, run it off the main thread (the ViewModel does this with Dispatchers.IO).
 */
class CurbRoutePlanner(
    private val geocoder: Geocoder,
    private val router: CurbRouter,
    private val solver: RouteSolver = RouteSolver(),
    private val options: Options = Options(),
) {
    data class Options(
        val objective: Objective = Objective.DISTANCE,
        /** A leg is "at risk" when arriving on the wrong side would save Maps more than this. */
        val wrongSideThresholdMeters: Double = 25.0,
        /** How far before the house the direction-forcing point goes. */
        val leadInMeters: Double = 60.0,
        val maxWaypointsPerLink: Int = GoogleMapsLinks.MAX_WAYPOINTS,
        /** true = Maps shows "123 Main St" (readable). false = raw coordinates (exactly what we planned with). */
        val addressesInLinks: Boolean = true,
    )

    fun plan(
        stops: List<SheetStop>,
        start: LatLng? = null,
        end: LatLng? = null,
        progress: (String) -> Unit = {},
    ): RoutePlan {
        val warnings = mutableListOf<String>()

        // 1) Addresses -> coordinates ------------------------------------------------------------------
        val located = mutableListOf<Pair<SheetStop, LatLng>>()
        val unresolved = mutableListOf<SheetStop>()
        stops.forEachIndexed { i, stop ->
            progress("Finding address ${i + 1} of ${stops.size}...")
            val hit = try {
                geocoder.geocode(stop.singleLine)
            } catch (e: IOException) {
                warnings += "Lookup failed for ${stop.label}: ${e.message}"
                null
            }
            if (hit == null) unresolved += stop else located += stop to hit.location
        }
        if (located.isEmpty()) {
            return RoutePlan(emptyList(), unresolved, 0.0, 0.0, 0.0, 0.0, emptyList(), emptyList(), warnings)
        }

        // 2) Curb-side cost matrix. Layout: [start?] + stops + [end?]. Start/end can be left any way. ----
        val points = buildList { start?.let(::add); addAll(located.map { it.second }); end?.let(::add) }
        val curb = buildList { if (start != null) add(false); repeat(located.size) { add(true) }; if (end != null) add(false) }
        val first = if (start != null) 1 else 0 // index of the first stop in `points`
        progress("Measuring curb-side drive distances...")
        val matrix = router.matrix(points, curb)
        val cost = matrix.of(options.objective)

        // 3) Optimize ------------------------------------------------------------------------------------
        progress("Optimizing the order...")
        val order: List<Int> = if (start != null) {
            solver.solve(cost, start = 0, end = end?.let { points.lastIndex }).toList()
        } else {
            // No GPS start: add a zero-cost virtual start so the solver can begin at whichever stop is best.
            val n = points.size
            val withVirtual = Array(n + 1) { i ->
                DoubleArray(n + 1) { j ->
                    when {
                        i == 0 -> 0.0
                        j == 0 -> UNREACHABLE
                        else -> cost[i - 1][j - 1]
                    }
                }
            }
            solver.solve(withVirtual, start = 0, end = end?.let { n }).drop(1).map { it - 1 }
        }
        val stopOrder = order.filter { it in first until first + located.size }
        val legPairs = order.zipWithNext() // (from, to) point indexes, in driving order
        if (legPairs.any { (a, b) -> matrix.meters[a][b] >= UNREACHABLE }) {
            warnings += "Some stops look unreachable by road. Check their pins."
        }

        // 4) Which legs would plain Google Maps get wrong? ------------------------------------------------
        val naive = try {
            router.naiveLegMeters(legPairs.map { (a, b) -> points[a] to points[b] }, legPairs.map { (a, _) -> curb[a] })
        } catch (e: IOException) {
            warnings += "Couldn't check for wrong-side arrivals: ${e.message}"
            null
        }
        val atRisk = legPairs.indices.filter { k ->
            val (a, b) = legPairs[k]
            curb[b] && naive != null && matrix.meters[a][b] - naive[k] > options.wrongSideThresholdMeters
        }.map { legPairs[it].second }.toSet()

        // 5) Road geometry -> lead-in points for risky stops --------------------------------------------
        val legs: List<RouteLeg> = try {
            progress("Drawing the route...")
            router.route(order.map { points[it] }, order.map { curb[it] })
        } catch (e: IOException) {
            warnings += "Couldn't fetch the route shape (lead-in points skipped): ${e.message}"
            emptyList()
        }
        val leadIns = mutableMapOf<Int, LatLng>()
        legPairs.forEachIndexed { k, (_, b) ->
            val path = legs.getOrNull(k)?.path
            if (b in atRisk && path != null && path.size >= 2) {
                leadIns[b] = GeoMath.pointBeforeEnd(path, options.leadInMeters)
            }
        }

        // 6) Assemble -------------------------------------------------------------------------------------
        val incoming = legPairs.associate { (a, b) -> b to a }
        val planned = stopOrder.map { idx ->
            val (sheet, loc) = located[idx - first]
            val prev = incoming[idx] // null = first stop after the virtual start
            PlannedStop(
                sheet = sheet,
                location = loc,
                legMeters = prev?.let { matrix.meters[it][idx] } ?: 0.0,
                legSeconds = prev?.let { matrix.seconds[it][idx] } ?: 0.0,
                leadIn = leadIns[idx],
            )
        }
        val sheetOrder = listOfNotNull(if (start != null) 0 else null) +
            (first until first + located.size) + listOfNotNull(end?.let { points.lastIndex })
        val mapsPoints = buildList {
            for (p in planned) {
                p.leadIn?.let { add(GoogleMapsLinks.Point(it.toLatLngString(), isLeadIn = true)) }
                add(GoogleMapsLinks.Point(if (options.addressesInLinks) p.sheet.singleLine else p.location.toLatLngString()))
            }
            end?.let { add(GoogleMapsLinks.Point(it.toLatLngString())) }
        }
        return RoutePlan(
            stops = planned,
            unresolved = unresolved,
            totalMeters = pathCost(order, matrix.meters),
            totalSeconds = pathCost(order, matrix.seconds),
            sheetOrderMeters = pathCost(sheetOrder, matrix.meters),
            sheetOrderSeconds = pathCost(sheetOrder, matrix.seconds),
            mapsLinks = GoogleMapsLinks.build(mapsPoints, options.maxWaypointsPerLink),
            path = legs.flatMap { it.path },
            warnings = warnings,
        )
    }

    private fun pathCost(order: List<Int>, m: Array<DoubleArray>) = order.zipWithNext().sumOf { (a, b) -> m[a][b] }
}
