package dev.slagathore.curbroute.core

import dev.slagathore.curbroute.core.geo.GeoHit
import dev.slagathore.curbroute.core.geo.Geocoder
import dev.slagathore.curbroute.core.routing.CostMatrix
import dev.slagathore.curbroute.core.routing.CurbRouter
import dev.slagathore.curbroute.core.routing.RouteLeg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end planner test with a fake geocoder and a fake curb-aware router. No network. */
class CurbRoutePlannerTest {
    // Four houses along one east-west street, x = 0, 100, 200, 300 m. Curb side = driving EAST.
    // Driving east between them costs the gap. Going west (wrong way) costs +500 for the lap around the block.
    private val xs = mapOf("10" to 300.0, "20" to 0.0, "30" to 200.0, "40" to 100.0) // sheet order is scrambled

    private fun stop(n: String, i: Int) = SheetStop(n.toInt(), n, "Test St", "Mansfield", "TX", "76063", sheetIndex = i)
    private val stops = listOf("10", "20", "30", "40").mapIndexed { i, n -> stop(n, i) }
    private fun xOf(p: LatLng) = p.lng * 100_000 // fake projection: 1e-5 degrees = 1 m

    private val geocoder = Geocoder { address ->
        val n = address.substringBefore(' ')
        xs[n]?.let { GeoHit(LatLng(32.5, it / 100_000), "fake") }
    }

    private val router = object : CurbRouter {
        fun curbCost(a: LatLng, b: LatLng): Double {
            val d = xOf(b) - xOf(a)
            return if (d >= 0) d else -d + 500
        }

        override fun matrix(points: List<LatLng>, curb: List<Boolean>): CostMatrix {
            val m = Array(points.size) { i -> DoubleArray(points.size) { j -> if (i == j) 0.0 else curbCost(points[i], points[j]) } }
            return CostMatrix(m, Array(points.size) { i -> DoubleArray(points.size) { j -> m[i][j] / 10 } })
        }

        // A curb-unaware app would just drive straight there, whichever side the house is on.
        override fun naiveLegMeters(legs: List<Pair<LatLng, LatLng>>, departCurb: List<Boolean>) =
            legs.map { (a, b) -> kotlin.math.abs(xOf(b) - xOf(a)) }.toDoubleArray()

        override fun route(ordered: List<LatLng>, curb: List<Boolean>) =
            ordered.zipWithNext().map { (a, b) ->
                RouteLeg(curbCost(a, b), curbCost(a, b) / 10, listOf(LatLng(a.lat, (xOf(b) - 100) / 100_000), b))
            }
    }

    @Test
    fun `orders stops so every house is reached driving the curb-side direction`() {
        val plan = CurbRoutePlanner(geocoder, router).plan(stops)

        assertEquals(listOf("20", "40", "30", "10"), plan.stops.map { it.sheet.houseNumber }) // x = 0,100,200,300
        assertEquals(300.0, plan.totalMeters, 1e-9)
        // Paper order 10(300) -> 20(0) -> 30(200) -> 40(100): 800 + 200 + 600 = 1600
        assertEquals(1600.0, plan.sheetOrderMeters, 1e-9)
        assertTrue(plan.stops.all { it.leadIn == null }, "nothing to force when the route already flows east")
        assertEquals(1, plan.mapsLinks.size)
    }

    @Test
    fun `flags wrong-side risk and inserts a lead-in when the start forces it`() {
        // Truck starts at x=150. Best plan: one lap around the block to get to x=0 (650 m), then sweep east
        // 0 -> 100 -> 200 -> 300 (950 m total). But a naive app would just drive 150 m west to x=0 and
        // arrive with the house on the WRONG side, so that first stop needs a lead-in point.
        val start = LatLng(32.5, 150.0 / 100_000)
        val plan = CurbRoutePlanner(geocoder, router).plan(stops, start = start)

        val first = plan.stops.first()
        assertNotNull(first.leadIn, "first leg should be forced: ${plan.stops.map { it.sheet.houseNumber }}")
        assertTrue(plan.stops.drop(1).all { it.leadIn == null })
        val waypoints = plan.mapsLinks.single().substringAfter("waypoints=").substringBefore('&')
        assertTrue(waypoints.startsWith(first.leadIn!!.toLatLngString().replace(",", "%2C")), plan.mapsLinks.single())
    }

    @Test
    fun `a plan survives being saved to disk and loaded back`() {
        // The Android app saves the plan as JSON, because Android may kill the app while you're in Google Maps.
        val plan = CurbRoutePlanner(geocoder, router).plan(stops, start = LatLng(32.5, 150.0 / 100_000))
        val text = kotlinx.serialization.json.Json.encodeToString(RoutePlan.serializer(), plan)
        assertEquals(plan, kotlinx.serialization.json.Json.decodeFromString(RoutePlan.serializer(), text))
    }

    @Test
    fun `unfindable addresses are reported, not dropped silently`() {
        val plan = CurbRoutePlanner(geocoder, router).plan(stops + stop("99", 4))
        assertEquals(listOf("99"), plan.unresolved.map { it.houseNumber })
        assertEquals(4, plan.stops.size)
        assertNull(plan.stops.first().leadIn)
    }
}
