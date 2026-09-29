package dev.slagathore.curbroute.core

import dev.slagathore.curbroute.core.parse.RouteSheetParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteSheetParserTest {
    private val parser = RouteSheetParser()
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText().lines()

    @Test
    fun `reads every row of the 9-29 sheet`() {
        val result = parser.parse(fixture("sheet-2026-09-29.txt"))
        val stops = result.stops

        assertEquals(19, stops.size)
        assertEquals((10..190 step 10).toList(), stops.map { it.stopNumber })
        assertTrue(result.warnings.isEmpty(), "unexpected warnings: ${result.warnings}")

        val first = stops.first()
        assertEquals("4211 Cobblestone Cir, Mansfield, TX 76063", first.singleLine) // "Cr" -> "Cir"
        assertEquals("9000010", first.customerNumber)
        assertEquals("Alex Sample", first.customerName)
        assertEquals("9/29/2026 6:12 AM", first.appointment)

        val woodLake = stops.single { it.houseNumber == "2955" }
        assertEquals("Wood Lake Trl", woodLake.street) // "Tr" -> "Trl"
        assertEquals("Grand Prairie", woodLake.city) // two-word city
        assertEquals("5110 Shelter Point Ct", stops.single { it.houseNumber == "5110" }.let { "${it.houseNumber} ${it.street}" })
        assertEquals("9/29/2026 10:56 AM", stops.last().appointment)
    }

    @Test
    fun `survives OCR noise, handwriting, and appointments read as a separate column`() {
        val clean = parser.parse(fixture("sheet-2026-09-29.txt")).stops
        val messy = parser.parse(fixture("sheet-2026-09-29-messy.txt"))

        // Same 19 houses in the same order, despite "42l1", "76O63", missing commas, check marks...
        assertEquals(clean.map { it.dedupeKey }, messy.stops.map { it.dedupeKey })
        assertEquals(clean.map { it.appointment }, messy.stops.map { it.appointment })
        assertEquals(clean.map { it.customerNumber }, messy.stops.map { it.customerNumber })
        assertEquals("Alex Sample", messy.stops[0].customerName) // trailing "X" mark removed
        assertEquals("Taylor Fixture", messy.stops[5].customerName) // merged phone number removed

        // Circled numbers that fit the sequence are picked up, whether read before or after their row...
        assertEquals(listOf(10, 20, 30, 40), messy.stops.take(4).map { it.stopNumber })
        // ...and none of the hand-written 114/127/150/140/120 were mistaken for stop numbers.
        assertTrue(messy.stops.drop(4).all { it.stopNumber == null }, messy.stops.map { it.stopNumber }.toString())
    }

    @Test
    fun `drops rows photographed twice on overlapping pages`() {
        val lines = fixture("sheet-2026-09-29.txt")
        val page1 = lines.take(60)
        val page2 = lines.drop(50) // 10 lines of overlap
        val result = parser.parse(page1 + page2)
        assertEquals(19, result.stops.size)
        assertTrue(result.warnings.any { "duplicate" in it })
    }

    @Test
    fun `warns when a row is missing from the sequence`() {
        val lines = fixture("sheet-2026-09-29.txt").filterNot { "5210 Vista Lago" in it }
        val result = parser.parse(lines)
        assertEquals(18, result.stops.size)
        assertTrue(result.warnings.any { "130" in it }, result.warnings.toString())
    }

    @Test
    fun `city names that contain street words`() {
        val r = parser.parse(listOf("123 Main St Cedar Hill, TX 75104", "55 Shelter Point Ct Springfield, IL 62701"))
        assertEquals("Main St" to "Cedar Hill", r.stops[0].street to r.stops[0].city) // known city
        assertEquals("Shelter Point Ct" to "Springfield", r.stops[1].street to r.stops[1].city) // suffix rule
    }

    @Test
    fun `ignores lines that only look address-ish`() {
        val r = parser.parse(
            listOf(
                "9/29/2026 9/29/2026 Route Schedule For WS2",
                "Cust # 9000010 Alex Sample (555) 010-0000 LCG - 6L Appt. 9/29/2026 6:12 AM",
                "\$50.00 4.00 \$50.00 4.00 CallAhead :",
            ),
        )
        assertTrue(r.stops.isEmpty())
    }
}
