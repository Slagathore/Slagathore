package dev.slagathore.curbroute

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.slagathore.curbroute.core.CurbRoutePlanner
import dev.slagathore.curbroute.core.Objective
import dev.slagathore.curbroute.core.RoutePlan
import dev.slagathore.curbroute.core.SheetStop
import dev.slagathore.curbroute.core.geo.CachingGeocoder
import dev.slagathore.curbroute.core.geo.CensusGeocoder
import dev.slagathore.curbroute.core.geo.FallbackGeocoder
import dev.slagathore.curbroute.core.geo.NominatimGeocoder
import dev.slagathore.curbroute.core.net.UrlConnectionHttp
import dev.slagathore.curbroute.core.parse.RouteSheetParser
import dev.slagathore.curbroute.core.routing.OsrmRouter
import dev.slagathore.curbroute.ocr.SheetOcr
import dev.slagathore.curbroute.platform.AndroidGeocoder
import dev.slagathore.curbroute.platform.StartLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A parsed stop plus whether the driver wants it in today's route (uncheck cancellations / skips). */
@Serializable
data class ReviewStop(val stop: SheetStop, val included: Boolean = true)

/** Everything worth surviving an app restart. Android often kills apps in the background while Maps runs. */
@Serializable
data class Session(
    val stops: List<ReviewStop> = emptyList(),
    val warnings: List<String> = emptyList(),
    val plan: RoutePlan? = null,
    /** dedupeKeys of stops the driver has checked off: the digital version of the pen check marks. */
    val done: Set<String> = emptySet(),
)

data class Settings(
    val osrmUrl: String = OsrmRouter.PUBLIC_DEMO,
    val objective: Objective = Objective.DISTANCE,
    val startFromGps: Boolean = true,
)

/**
 * The app's brain on the Android side. Screens read [session], [busy], [error], [settings] and call the
 * functions below. Slow work (OCR, network, optimizing) runs off the main thread so the UI never freezes.
 */
class RouteViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val sessionFile = File(app.filesDir, "session.json")
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true } // old saved files keep loading after we add fields
    private val parser = RouteSheetParser()

    var session by mutableStateOf(loadSession())
        private set
    var busy by mutableStateOf<String?>(null) // non-null = show a progress message
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var settings by mutableStateOf(loadSettings())
        private set

    // ---- Sheet photos -> stops ----------------------------------------------------------------------

    fun addPages(photos: List<Uri>) = work("Reading the sheet...") {
        val lines = photos.flatMap { SheetOcr.readLines(context, it) }
        val parsed = withContext(Dispatchers.Default) { parser.parse(lines) }
        // Append new rows, skipping any we already have (overlapping photos / same page added twice).
        val known = session.stops.map { it.stop.dedupeKey }.toSet()
        val fresh = parsed.stops.filter { it.dedupeKey !in known }
        val all = session.stops + fresh.map { ReviewStop(it) }
        val reindexed = all.mapIndexed { i, r -> r.copy(stop = r.stop.copy(sheetIndex = i)) }
        val warnings = session.warnings + parsed.warnings +
            (if (parsed.stops.isNotEmpty() && fresh.isEmpty()) listOf("That page had no new stops.") else emptyList())
        update(session.copy(stops = reindexed, warnings = warnings, plan = null))
    }

    fun toggle(key: String) = update(
        session.copy(stops = session.stops.map { if (it.stop.dedupeKey == key) it.copy(included = !it.included) else it }),
    )

    /** Replace a stop's address with hand-typed text. Returns false if it's not an address, or duplicates another stop. */
    fun edit(key: String, text: String): Boolean {
        val parsed = parser.parse(listOf(text)).stops.firstOrNull() ?: return false
        if (parsed.dedupeKey != key && session.stops.any { it.stop.dedupeKey == parsed.dedupeKey }) return false
        update(
            session.copy(
                stops = session.stops.map { r ->
                    if (r.stop.dedupeKey != key) r
                    else r.copy(
                        stop = r.stop.copy(
                            houseNumber = parsed.houseNumber, street = parsed.street, city = parsed.city,
                            state = parsed.state, zip = parsed.zip,
                        ),
                    )
                },
                plan = null,
            ),
        )
        return true
    }

    fun addManual(text: String): Boolean {
        val parsed = parser.parse(listOf(text)).stops.firstOrNull() ?: return false
        if (session.stops.any { it.stop.dedupeKey == parsed.dedupeKey }) return true
        update(session.copy(stops = session.stops + ReviewStop(parsed.copy(sheetIndex = session.stops.size)), plan = null))
        return true
    }

    fun remove(key: String) = update(session.copy(stops = session.stops.filterNot { it.stop.dedupeKey == key }))

    // ---- Planning ------------------------------------------------------------------------------------

    fun optimize() = work("Planning...") {
        val stops = session.stops.filter { it.included }.map { it.stop }
        if (stops.isEmpty()) {
            error = "No stops selected."
            return@work
        }
        val start = if (settings.startFromGps) {
            busy = "Getting your location..."
            StartLocation.current(context)
        } else {
            null
        }
        val plan = withContext(Dispatchers.IO) {
            planner().plan(stops, start = start) { msg -> viewModelScope.launch { busy = msg } }
        }
        update(session.copy(plan = plan, done = emptySet()))
    }

    fun backToReview() = update(session.copy(plan = null))

    fun markDone(key: String) = update(
        session.copy(done = if (key in session.done) session.done - key else session.done + key),
    )

    fun newDay() = update(Session())

    fun dismissError() {
        error = null
    }

    fun saveSettings(new: Settings) {
        settings = new
        prefs.edit {
            putString("osrm", new.osrmUrl)
            putString("objective", new.objective.name)
            putBoolean("gps", new.startFromGps)
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------------

    private fun planner(): CurbRoutePlanner {
        val http = UrlConnectionHttp(userAgent = "CurbRoute-Android/0.1")
        val geocoder = CachingGeocoder(
            FallbackGeocoder(listOf(CensusGeocoder(http), AndroidGeocoder(context), NominatimGeocoder(http))),
            File(context.filesDir, "geocode-cache.json"),
        )
        return CurbRoutePlanner(
            geocoder = geocoder,
            router = OsrmRouter(http, settings.osrmUrl),
            options = CurbRoutePlanner.Options(objective = settings.objective),
        )
    }

    /** Run [block] in the background with a progress message; any crash becomes a friendly error. */
    private fun work(message: String, block: suspend () -> Unit) {
        if (busy != null) return // one job at a time
        viewModelScope.launch {
            busy = message
            error = null
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = null
            }
        }
    }

    private fun update(new: Session) {
        session = new
        // Save in the background. Two quick taps can start two saves; the lock makes them take turns, and each
        // one writes the NEWEST session, so the file always ends up matching what's on screen.
        viewModelScope.launch(Dispatchers.IO) {
            diskLock.withLock { sessionFile.writeText(json.encodeToString(Session.serializer(), session)) }
        }
    }

    private val diskLock = Mutex()

    private fun loadSession(): Session = try {
        if (sessionFile.exists()) json.decodeFromString(Session.serializer(), sessionFile.readText()) else Session()
    } catch (e: Exception) {
        Session() // unreadable file (e.g. after a big app update): start clean
    }

    private fun loadSettings() = Settings(
        osrmUrl = prefs.getString("osrm", null) ?: OsrmRouter.PUBLIC_DEMO,
        objective = runCatching { Objective.valueOf(prefs.getString("objective", null)!!) }.getOrDefault(Objective.DISTANCE),
        startFromGps = prefs.getBoolean("gps", true),
    )
}
