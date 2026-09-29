package dev.slagathore.curbroute.core

import dev.slagathore.curbroute.core.geo.GeoMath
import dev.slagathore.curbroute.core.nav.GoogleMapsLinks
import dev.slagathore.curbroute.core.nav.GoogleMapsLinks.Point
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleMapsLinksTest {
    private fun query(url: String, key: String): String? =
        url.substringAfter('?').split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == key }?.let { URLDecoder.decode(it[1], "UTF-8") }

    @Test
    fun `splits into links of at most 9 waypoints plus a destination`() {
        val points = (1..25).map { Point("$it Main St, Mansfield, TX 76063") }
        val links = GoogleMapsLinks.build(points)
        assertEquals(3, links.size)
        assertEquals("10 Main St, Mansfield, TX 76063", query(links[0], "destination"))
        assertEquals(9, query(links[0], "waypoints")!!.split('|').size)
        assertEquals("25 Main St, Mansfield, TX 76063", query(links[2], "destination"))
        assertTrue(links.all { "dir_action=navigate" in it && "travelmode=driving" in it })
        assertFalse(links.any { "origin=" in it }, "origin omitted => Maps starts from current GPS")
    }

    @Test
    fun `a lead-in point never gets separated from its house`() {
        // 9 houses, then a lead-in + house: a naive split would end link 1 on the lead-in.
        val points = (1..9).map { Point("house $it") } + Point("32.5,-97.0", isLeadIn = true) + Point("house 10")
        val links = GoogleMapsLinks.build(points)
        assertEquals("house 9", query(links[0], "destination"))
        assertEquals("house 10", query(links[1], "destination"))
        assertEquals("32.5,-97.0", query(links[1], "waypoints"))
    }

    @Test
    fun `lead-in point sits the requested distance before the house`() {
        // A straight 200 m street heading north; the house is at the north end.
        val south = LatLng(32.5600, -97.0500)
        val north = LatLng(32.5600 + 200 / 111_195.0, -97.0500)
        val lead = GeoMath.pointBeforeEnd(listOf(south, north), 60.0)
        assertEquals(60.0, GeoMath.distanceMeters(lead, north), 0.5)
        assertEquals(140.0, GeoMath.distanceMeters(south, lead), 0.5)
    }
}
