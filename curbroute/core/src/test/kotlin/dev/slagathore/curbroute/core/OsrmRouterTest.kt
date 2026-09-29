package dev.slagathore.curbroute.core

import dev.slagathore.curbroute.core.net.HttpGet
import dev.slagathore.curbroute.core.routing.OsrmRouter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Offline tests: a fake "OSRM server" whose distance from point a to point b is a formula we can check.
 * Point k is placed at longitude k/1000, so the fake can tell which point is which from the URL alone.
 */
class OsrmRouterTest {
    private fun fakeDistance(a: Int, b: Int, curbAtB: Boolean) =
        if (a == b) 0.0 else 100.0 * kotlin.math.abs(a - b) + (if (b < a) 7.0 else 0.0) + (if (curbAtB) 50.0 else 0.0)

    private val requests = mutableListOf<String>()

    private val fakeServer = HttpGet { url ->
        requests += url
        val path = url.substringBefore('?')
        val params = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val ids = path.substringAfterLast('/').split(';').map { Math.round(it.substringBefore(',').toDouble() * 1000).toInt() }
        val approaches = params.getValue("approaches").split(';')
        val sources = params["sources"]?.split(';')?.map(String::toInt) ?: ids.indices.toList()
        val destinations = params["destinations"]?.split(';')?.map(String::toInt) ?: ids.indices.toList()
        val rows = sources.joinToString(",", "[", "]") { s ->
            destinations.joinToString(",", "[", "]") { d ->
                String.format(Locale.US, "%.1f", fakeDistance(ids[s], ids[d], approaches[d] == "curb"))
            }
        }
        """{"code":"Ok","distances":$rows,"durations":$rows}"""
    }

    private fun point(k: Int) = LatLng(32.5, k / 1000.0)

    @Test
    fun `big matrices are tiled under the coordinate limit and stitched back correctly`() {
        val n = 13
        val router = OsrmRouter(fakeServer, "http://fake", maxTableCoordinates = 6)
        val curb = List(n) { it % 2 == 0 }
        val m = router.matrix(List(n) { point(it) }, curb)

        for (i in 0 until n) for (j in 0 until n) {
            assertEquals(fakeDistance(i, j, curb[j]), m.meters[i][j], 1e-6, "cell $i->$j")
        }
        assertTrue(requests.size > 1, "should have tiled")
        assertTrue(requests.all { it.substringBefore('?').substringAfterLast('/').split(';').size <= 6 })
    }

    @Test
    fun `naive legs depart curb-side and arrive unrestricted`() {
        val router = OsrmRouter(fakeServer, "http://fake", maxTableCoordinates = 4)
        val legs = listOf(point(1) to point(4), point(4) to point(2), point(2) to point(3))
        val out = router.naiveLegMeters(legs, departCurb = listOf(true, true, false))
        assertEquals(listOf(300.0, 207.0, 100.0), out.toList()) // no +50 curb surcharge on arrival
    }
}
