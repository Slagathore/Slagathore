package dev.slagathore.curbroute.core.parse

import dev.slagathore.curbroute.core.SheetStop
import java.util.Locale

/**
 * Turns OCR text lines from one or more photos of the route sheet into [SheetStop]s.
 *
 * Why line-based regexes instead of "AI": the sheet is machine-printed in a fixed layout, so each address
 * sits on one line like `4211 Cobblestone Cr  Mansfield, TX 76063`. A regex that looks for
 * "number + words + , + STATE + ZIP" is fast, free, works offline, and fails loudly (a warning) instead of
 * silently hallucinating an address.
 *
 * Things it defends against (all seen on real sheets or in ML Kit output):
 *  - OCR confusing letters/digits inside numbers: "12O7" -> 1207, "76O63" -> 76063
 *  - hand-written marks next to the text (check marks, X's, the 114/127/150 numbers)
 *  - the circled stop number being glued to the front of the line ("10 4211 Cobblestone...")
 *  - multi-word cities ("Grand Prairie") and street names that contain suffix words ("Shelter Point Ct")
 *  - the same row appearing on two overlapping photos
 */
class RouteSheetParser(
    /** Cities recognised by exact match first. Anything else falls back to the "last street suffix" rule. */
    private val knownCities: List<String> = DEFAULT_CITIES,
) {
    data class Result(val stops: List<SheetStop>, val warnings: List<String>)

    fun parse(lines: List<String>): Result {
        val warnings = mutableListOf<String>()
        val rows = mutableListOf<Row>()
        var pendingStopNumber: Int? = null // a circled number read BEFORE its address line

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // 1) Address line?
            val address = ADDRESS.find(line)
            if (address != null) {
                val row = buildRow(address, warnings) ?: continue
                val lastKnown = rows.lastOrNull { it.stopNumber != null }?.stopNumber ?: 0
                if (row.stopNumber != null && row.stopNumber!! <= lastKnown) row.stopNumber = null // out of sequence
                if (row.stopNumber == null) row.stopNumber = pendingStopNumber
                pendingStopNumber = null
                rows += row
                continue
            }

            // 2) Customer line: sits right under its address in the same column -> belongs to the latest row.
            CUSTOMER.find(line)?.let { m ->
                val row = rows.lastOrNull()
                if (row != null && row.customerNumber == null) {
                    row.customerNumber = fixDigits(m.groupValues[1])
                    row.customerName = cleanName(m.groupValues[2])
                }
            }

            // 3) Appointment: lives in a different column, and OCR may read that column as a separate batch,
            //    so hand them out first-come-first-served instead of "to the latest row".
            APPOINTMENT.findAll(line).forEach { m ->
                rows.firstOrNull { it.appointment == null }?.appointment =
                    "${m.groupValues[1]} ${normalizeTime(m.groupValues[2])}"
            }

            // 4) A lone number: either a circled stop number, or hand-writing (114, 127, 150...).
            //    They look identical to OCR, so only accept a number that fits the 10-20-30 sequence
            //    exactly where we are on the sheet. Hand-written values almost never land on that slot.
            LONE_NUMBER.matchEntire(line)?.let { m ->
                val n = m.groupValues[1].toInt()
                val lastNumberedIdx = rows.indexOfLast { it.stopNumber != null }
                val lastNumber = if (lastNumberedIdx >= 0) rows[lastNumberedIdx].stopNumber!! else 0
                val rowsSince = rows.size - 1 - lastNumberedIdx // rows after the last numbered one
                when {
                    // belongs to the newest row (number was read just AFTER its address)
                    rowsSince >= 1 && rows.last().stopNumber == null && n == lastNumber + 10 * rowsSince ->
                        rows.last().stopNumber = n
                    // belongs to the next row (number was read just BEFORE its address)
                    n == lastNumber + 10 * (rowsSince + 1) -> pendingStopNumber = n
                }
            }
        }

        // De-duplicate rows that were photographed twice (overlapping pages).
        val unique = LinkedHashMap<String, Row>()
        for (row in rows) {
            val existing = unique[row.key]
            if (existing == null) {
                unique[row.key] = row
            } else {
                existing.mergeMissingFrom(row)
                warnings += "Skipped a duplicate of ${row.houseNumber} ${row.street} (overlapping photos?)."
            }
        }
        val stops = unique.values.mapIndexed { i, r -> r.toStop(i) }

        // Gap check: stops count up by 10. A missing number usually means OCR skipped a row.
        val numbers = stops.mapNotNull { it.stopNumber }.sorted()
        if (numbers.size >= 2 && numbers.size >= stops.size / 2) {
            val missing = (numbers.first()..numbers.last() step 10).filter { it !in numbers }
            if (missing.isNotEmpty()) {
                warnings += "Stop number(s) ${missing.joinToString()} not found - a row may have been missed. " +
                    "Check the photo or add it by hand."
            }
        }
        if (stops.isEmpty()) warnings += "No addresses found. Is the photo in focus and the whole sheet in frame?"
        return Result(stops, warnings)
    }

    // ---------------------------------------------------------------------------------------------

    private class Row(
        var stopNumber: Int?,
        val houseNumber: String,
        val street: String,
        val city: String,
        val state: String,
        val zip: String,
    ) {
        var customerNumber: String? = null
        var customerName: String? = null
        var appointment: String? = null
        val key get() = "$houseNumber|${street.lowercase(Locale.US)}|$zip"

        fun mergeMissingFrom(other: Row) {
            if (stopNumber == null) stopNumber = other.stopNumber
            if (customerNumber == null) customerNumber = other.customerNumber
            if (customerName == null) customerName = other.customerName
            if (appointment == null) appointment = other.appointment
        }

        fun toStop(index: Int) = SheetStop(
            stopNumber, houseNumber, street, city, state, zip,
            customerNumber, customerName, appointment, sheetIndex = index,
        )
    }

    private fun buildRow(m: MatchResult, warnings: MutableList<String>): Row? {
        val stopNumber = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt()?.takeIf { it % 10 == 0 }
        val house = fixDigits(m.groupValues[2])
        val blob = m.groupValues[3].replace(WHITESPACE, " ").trim().trimEnd(',')
        val state = m.groupValues[4].uppercase(Locale.US)
        val zip = fixDigits(m.groupValues[5])
        if (state !in US_STATES) return null // "Dr 12345" etc. - not really an address
        val (street, city) = splitStreetAndCity(blob) ?: run {
            warnings += "Couldn't tell street from city in \"$blob\" - please check that stop."
            val parts = blob.split(' ')
            parts.dropLast(1).joinToString(" ") to parts.last()
        }
        return Row(stopNumber, house, street, city, state, zip)
    }

    /** "Shelter Point Ct Mansfield" -> ("Shelter Point Ct", "Mansfield"). */
    private fun splitStreetAndCity(blob: String): Pair<String, String>? {
        // 1) Known city at the end wins (handles "Cedar Hill", where "Hill" is also a street suffix).
        for (city in knownCities.sortedByDescending { it.length }) {
            if (blob.endsWith(" $city", ignoreCase = true)) {
                val street = blob.dropLast(city.length + 1).trim()
                if (street.isNotEmpty()) return normalizeSuffix(street) to city
            }
        }
        // 2) Otherwise split after the LAST street-suffix word that still leaves something for the city.
        val tokens = blob.split(' ')
        for (i in tokens.size - 2 downTo 1) {
            if (tokens[i].trimEnd('.').lowercase(Locale.US) in STREET_SUFFIXES) {
                return normalizeSuffix(tokens.subList(0, i + 1).joinToString(" ")) to
                    tokens.subList(i + 1, tokens.size).joinToString(" ")
            }
        }
        return null
    }

    companion object {
        // [stop#] house# street+city , ST ZIP     (ZIP+4 allowed; trailing junk ignored)
        // The street must start with a letter. That's what stops "1O 4211 Cobblestone" (a mis-read stop number)
        // from being parsed as house "10" on street "4211 Cobblestone". Trade-off: numbered streets
        // like "123 2nd St" aren't recognised - add them by hand if your area has them.
        private val ADDRESS = Regex(
            """(?:^|\s)(?:\(?(\d{1,3})\)?\s+)?(\d[\dOoIlS]{0,5})\s+([A-Za-z].*?)\s*,?\s+([A-Za-z]{2})\.?\s+(\d[\dOoIlS]{4})(?:-\d{4})?(?!\w)""",
        )
        private val CUSTOMER = Regex("""Cust\s*#?\s*:?\s*([\dOoIlS]{5,9})\s*(.*)""", RegexOption.IGNORE_CASE)
        private val APPOINTMENT = Regex(
            """Appt\.?\s*(\d{1,2}/\d{1,2}/\d{2,4})\s+(\d{1,2}:\d{2}\s*[AaPp]\.?[Mm]\.?)""",
            RegexOption.IGNORE_CASE,
        )
        private val LONE_NUMBER = Regex("""\W*(\d{2,3})\W*""")
        private val PHONE_TAIL = Regex("""\(?\d{3}\)?[\s-]*\d{3}-\d{4}.*$""")
        private val WHITESPACE = Regex("""\s+""")

        /** Fix common OCR letter-for-digit swaps, only ever applied to fields that must be numeric. */
        fun fixDigits(s: String): String = buildString {
            for (ch in s) append(
                when (ch) {
                    'O', 'o' -> '0'
                    'I', 'l' -> '1'
                    'S' -> '5'
                    else -> ch
                },
            )
        }

        /** "6:12am" / "6:12 A.M." -> "6:12 AM" */
        private fun normalizeTime(raw: String): String {
            val compact = raw.uppercase(Locale.US).replace(".", "").replace(" ", "")
            return compact.dropLast(2) + " " + compact.takeLast(2)
        }

        private fun cleanName(raw: String): String? =
            raw.replace(PHONE_TAIL, "")
                .replace(Regex("""[^A-Za-z .'&-]"""), " ")
                .replace(WHITESPACE, " ")
                .trim()
                // strip stray single letters left by check marks / X's at the end ("Alex Sample X")
                .replace(Regex("""(\s+[A-Za-z])+$"""), "")
                .takeIf { it.length >= 2 }

        /** Replace the final word with the USPS abbreviation ("Cr" -> "Cir", "Trail" -> "Trl"). */
        fun normalizeSuffix(street: String): String {
            val words = street.split(' ').toMutableList()
            val canonical = STREET_SUFFIXES[words.last().trimEnd('.').lowercase(Locale.US)]
            if (canonical != null) words[words.lastIndex] = canonical
            return words.joinToString(" ")
        }

        /** Seed list; add your own service area. Matching is case-insensitive. */
        val DEFAULT_CITIES = listOf(
            "Mansfield", "Grand Prairie", "Arlington", "Fort Worth", "Kennedale", "Cedar Hill", "Midlothian",
            "Burleson", "Venus", "Duncanville", "Dallas", "Irving", "Euless", "Bedford", "Hurst", "Crowley",
            "Rendon", "Everman", "Forest Hill", "Glenn Heights", "DeSoto", "Lancaster", "Waxahachie",
        )

        /** Lower-case spelling seen on sheets -> USPS standard suffix. "Cr" is how this sheet writes Circle. */
        val STREET_SUFFIXES: Map<String, String> = buildMap {
            fun add(canonical: String, vararg spellings: String) {
                put(canonical.lowercase(Locale.US), canonical)
                spellings.forEach { put(it, canonical) }
            }
            add("Dr", "drive", "drv")
            add("Ln", "lane")
            add("Ct", "court", "crt")
            add("Cir", "cr", "circle", "circ")
            add("Way", "wy")
            add("St", "street", "str")
            add("Trl", "tr", "trail")
            add("Rd", "road")
            add("Ave", "av", "avenue")
            add("Blvd", "boulevard", "bl")
            add("Pl", "place")
            add("Cv", "cove")
            add("Pkwy", "parkway", "pky")
            add("Ter", "terrace", "terr")
            add("Trce", "trace")
            add("Xing", "crossing")
            add("Loop")
            add("Path")
            add("Pass")
            add("Run")
            add("Row")
            add("Pt", "point")
            add("Hwy", "highway")
            add("Bnd", "bend")
            add("Gln", "glen")
            add("Hl", "hill")
            add("Hts", "heights")
            add("Holw", "hollow")
            add("Rdg", "ridge")
            add("Sq", "square")
            add("Walk")
            add("Aly", "alley")
            add("Cres", "crescent")
            add("Crk", "creek")
            add("Park", "pk")
            add("Vw", "view")
            add("Cyn", "canyon")
            add("Crst", "crest")
            add("Mdw", "meadow")
            add("Mdws", "meadows")
            add("Spur")
            add("Vis", "vista")
            add("Ests", "estates")
        }

        private val US_STATES = setOf(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY",
            "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND",
            "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC",
        )
    }
}
