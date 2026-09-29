package dev.slagathore.curbroute.cli

import dev.slagathore.curbroute.core.CurbRoutePlanner
import dev.slagathore.curbroute.core.LatLng
import dev.slagathore.curbroute.core.Objective
import dev.slagathore.curbroute.core.RoutePlan
import dev.slagathore.curbroute.core.geo.CachingGeocoder
import dev.slagathore.curbroute.core.geo.CensusGeocoder
import dev.slagathore.curbroute.core.geo.FallbackGeocoder
import dev.slagathore.curbroute.core.geo.NominatimGeocoder
import dev.slagathore.curbroute.core.net.UrlConnectionHttp
import dev.slagathore.curbroute.core.parse.RouteSheetParser
import dev.slagathore.curbroute.core.routing.OsrmRouter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE = """
CurbRoute CLI - plan a passenger-side route from OCR text (or any text with one address per line).

  ./gradlew :cli:run --args="<sheet.txt> [options]"

Options:
  --start LAT,LNG     where the truck starts (default: let the optimizer pick the best first stop)
  --end LAT,LNG       where you must finish (e.g. the shop). Default: wherever the last stop is.
  --osrm URL          OSRM server (default: public demo ${OsrmRouter.PUBLIC_DEMO})
  --time              minimize drive time instead of distance
  --geojson FILE      also write the route as GeoJSON (drag it onto https://geojson.io to see it)
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help")) {
        println(USAGE.trimIndent())
        return
    }
    val input = File(args[0])
    if (!input.exists()) fail("No such file: ${input.absolutePath}")
    val flags = args.drop(1)
    fun flag(name: String): String? = flags.indexOf(name).takeIf { it >= 0 }?.let { flags.getOrNull(it + 1) ?: fail("$name needs a value") }
    fun latLng(name: String): LatLng? = flag(name)?.split(',')?.let { parts ->
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
        val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
        if (lat == null || lng == null) fail("$name must look like 32.5601,-97.0581")
        LatLng(lat, lng)
    }

    // 1) Parse -------------------------------------------------------------------------------------------
    val parsed = RouteSheetParser().parse(input.readLines())
    println("Found ${parsed.stops.size} stops in ${input.name}:")
    parsed.stops.forEach { s ->
        println(String.format(Locale.US, "  %-5s %-38s %-12s %s", s.stopNumber ?: "?", s.singleLine, s.customerNumber ?: "", s.appointment ?: ""))
    }
    parsed.warnings.forEach { println("  ! $it") }
    if (parsed.stops.isEmpty()) exitProcess(1)

    // 2) Plan --------------------------------------------------------------------------------------------
    val http = UrlConnectionHttp()
    val geocoder = CachingGeocoder(
        FallbackGeocoder(listOf(CensusGeocoder(http), NominatimGeocoder(http))),
        File(".curbroute-cache.json"), // gitignored: it holds customer addresses
    )
    val router = OsrmRouter(http, flag("--osrm") ?: OsrmRouter.PUBLIC_DEMO)
    val options = CurbRoutePlanner.Options(objective = if ("--time" in flags) Objective.TIME else Objective.DISTANCE)
    println()
    val plan = CurbRoutePlanner(geocoder, router, options = options)
        .plan(parsed.stops, start = latLng("--start"), end = latLng("--end")) { System.err.println("  ... $it") }

    printPlan(plan)
    flag("--geojson")?.let { path ->
        File(path).writeText(toGeoJson(plan))
        println("\nWrote $path - drag it onto https://geojson.io to see the route.")
    }
}

private fun printPlan(plan: RoutePlan) {
    println("\nOptimized route (house always on the passenger side):")
    println(String.format(Locale.US, "  %-3s %-6s %-40s %8s  %s", "#", "Sheet", "Address", "Leg", "Note"))
    plan.stops.forEachIndexed { i, p ->
        val note = if (p.leadIn != null) "forced approach (Maps would arrive wrong-side)" else ""
        println(String.format(Locale.US, "  %-3d %-6s %-40s %6.2fkm  %s", i + 1, p.sheet.stopNumber ?: "?", p.sheet.singleLine, p.legMeters / 1000, note))
    }
    plan.unresolved.forEach { println("  ! Could not find: ${it.singleLine}") }
    plan.warnings.forEach { println("  ! $it") }

    val saved = plan.sheetOrderMeters - plan.totalMeters
    println()
    println(String.format(Locale.US, "  Optimized : %6.2f km  %5.1f min driving", plan.totalMeters / 1000, plan.totalSeconds / 60))
    println(String.format(Locale.US, "  Sheet order (same curb-side rule): %6.2f km  %5.1f min", plan.sheetOrderMeters / 1000, plan.sheetOrderSeconds / 60))
    if (plan.sheetOrderMeters > 0) {
        println(String.format(Locale.US, "  Saved     : %6.2f km (%.0f%%)", saved / 1000, 100 * saved / plan.sheetOrderMeters))
    }
    println("\nGoogle Maps (open in order; each continues from wherever you are):")
    plan.mapsLinks.forEachIndexed { i, url -> println("  Part ${i + 1}/${plan.mapsLinks.size}: $url") }
}

private fun toGeoJson(plan: RoutePlan): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        if (plan.path.size >= 2) addJsonObject {
            put("type", "Feature")
            putJsonObject("properties") { put("stroke", "#1a73e8"); put("stroke-width", 4) }
            putJsonObject("geometry") {
                put("type", "LineString")
                put("coordinates", JsonArray(plan.path.map { buildJsonArray { add(JsonPrimitive(it.lng)); add(JsonPrimitive(it.lat)) } }))
            }
        }
        plan.stops.forEachIndexed { i, p ->
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {
                    put("order", i + 1)
                    put("title", "${i + 1}. ${p.sheet.label}")
                    put("marker-color", if (p.leadIn != null) "#e8710a" else "#188038")
                }
                putJsonObject("geometry") {
                    put("type", "Point")
                    put("coordinates", buildJsonArray { add(JsonPrimitive(p.location.lng)); add(JsonPrimitive(p.location.lat)) })
                }
            }
            p.leadIn?.let { lead ->
                addJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") { put("title", "lead-in for ${p.sheet.label}"); put("marker-size", "small"); put("marker-color", "#e8710a") }
                    putJsonObject("geometry") {
                        put("type", "Point")
                        put("coordinates", buildJsonArray { add(JsonPrimitive(lead.lng)); add(JsonPrimitive(lead.lat)) })
                    }
                }
            }
        }
    }
}.toString()

private fun fail(msg: String): Nothing {
    System.err.println(msg)
    exitProcess(2)
}
