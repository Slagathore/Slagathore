package dev.slagathore.curbroute.core

import kotlinx.serialization.Serializable
import java.util.Locale

/** A plain latitude/longitude pair (WGS84, same as GPS / Google Maps). */
@Serializable
data class LatLng(val lat: Double, val lng: Double) {
    /** "lat,lng" - the order Google Maps URLs use. */
    fun toLatLngString(): String = String.format(Locale.US, "%.6f,%.6f", lat, lng)

    /** "lng,lat" - the (reversed!) order OSRM uses. Mixing these two up is the #1 mapping bug. */
    fun toLngLatString(): String = String.format(Locale.US, "%.6f,%.6f", lng, lat)
}

/** One row of the paper route sheet, after OCR + parsing. */
@Serializable
data class SheetStop(
    /** The circled 10/20/30... number. Null when OCR didn't catch it (it's only used for display + gap checks). */
    val stopNumber: Int?,
    val houseNumber: String,
    /** Street with a normalized suffix, e.g. "Cobblestone Cr" -> "Cobblestone Cir". */
    val street: String,
    val city: String,
    val state: String,
    val zip: String,
    val customerNumber: String? = null,
    val customerName: String? = null,
    /** Raw appointment text, e.g. "9/29/2026 6:12 AM". Kept for the future time-window feature. */
    val appointment: String? = null,
    /** Position on the sheet (0-based, across all pages). This is the "paper order" we compare against. */
    val sheetIndex: Int = 0,
) {
    /** "4211 Cobblestone Cir, Mansfield, TX 76063" - what we send to geocoders and Google Maps. */
    val singleLine: String get() = "$houseNumber $street, $city, $state $zip"

    /** Same house photographed twice (overlapping pages) => same key. */
    val dedupeKey: String get() = "$houseNumber|${street.lowercase(Locale.US)}|$zip"

    /** Short label for lists: "#40 5010 Lake Park Dr". */
    val label: String get() = (stopNumber?.let { "#$it " } ?: "") + "$houseNumber $street"
}

/** What to minimize. You asked for distance; time is there for when traffic/speed limits matter more. */
enum class Objective { DISTANCE, TIME }
