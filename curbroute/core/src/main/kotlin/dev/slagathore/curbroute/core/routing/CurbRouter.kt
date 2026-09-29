package dev.slagathore.curbroute.core.routing

import dev.slagathore.curbroute.core.LatLng
import dev.slagathore.curbroute.core.Objective

/** Big-but-finite cost for "can't get there" (unreachable pairs), so the math never hits infinity. */
const val UNREACHABLE = 1.0e9

/** meters[i][j] / seconds[i][j] = cost of driving from point i to point j. Not symmetric! */
class CostMatrix(val meters: Array<DoubleArray>, val seconds: Array<DoubleArray>) {
    val size: Int get() = meters.size
    fun of(objective: Objective): Array<DoubleArray> = if (objective == Objective.DISTANCE) meters else seconds
}

/** One driven leg of the final route, with its road geometry. */
class RouteLeg(val meters: Double, val seconds: Double, val path: List<LatLng>)

/**
 * Anything that can answer "how far is it from A to B if B has to end up on my passenger side?".
 * OSRM implements it today. Mapbox (Matrix API `approaches=curb`) or Google (Routes `sideOfRoad`)
 * could implement the same interface later without touching the optimizer.
 */
interface CurbRouter {
    /**
     * Full N x N matrix. When curb[i] is true, point i must be ARRIVED at with it on the passenger side,
     * and when leaving it you DEPART the way you were parked (no U-turn with the hose out).
     */
    fun matrix(points: List<LatLng>, curb: List<Boolean>): CostMatrix

    /**
     * For each leg: how far a curb-UNAWARE navigator (plain Google Maps) would drive. You leave `from` facing
     * the way you parked, then take the shortest path to `to`, arriving on whichever side is shorter.
     * If that's shorter than the curb-side leg, Maps will likely bring you in on the wrong side.
     */
    fun naiveLegMeters(legs: List<Pair<LatLng, LatLng>>, departCurb: List<Boolean>): DoubleArray

    /** Road geometry for driving [ordered] in that order, curb-side where curb[i]. One leg per consecutive pair. */
    fun route(ordered: List<LatLng>, curb: List<Boolean>): List<RouteLeg>
}
