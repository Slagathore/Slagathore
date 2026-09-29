package dev.slagathore.curbroute.core.geo

import dev.slagathore.curbroute.core.LatLng
import dev.slagathore.curbroute.core.net.HttpGet
import dev.slagathore.curbroute.core.net.array
import dev.slagathore.curbroute.core.net.double
import dev.slagathore.curbroute.core.net.get
import dev.slagathore.curbroute.core.net.string
import dev.slagathore.curbroute.core.net.toJson
import dev.slagathore.curbroute.core.net.urlEncode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.Locale

/** A geocoded address. */
data class GeoHit(
    val location: LatLng,
    /** Which service answered ("census", "osm", "android", "manual"...). */
    val source: String,
    /** Census only: 'L'/'R' = side of the street relative to how the street line was drawn. Handy for debugging. */
    val side: Char? = null,
)

/** Address text in, coordinates out (or null = not found). */
fun interface Geocoder {
    @Throws(IOException::class)
    fun geocode(address: String): GeoHit?
}

/**
 * US Census Bureau geocoder. Free, no API key, public domain, fine for commercial use.
 * Its points are offset a few meters from the street centerline toward the correct side, and that offset
 * is exactly what the router needs to know which side of the road a house is on.
 * US only. Brand-new subdivisions can be missing until the Census updates its address ranges.
 */
class CensusGeocoder(private val http: HttpGet) : Geocoder {
    override fun geocode(address: String): GeoHit? {
        val url = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress" +
            "?address=${address.urlEncode()}&benchmark=Public_AR_Current&format=json"
        val match = http.get(url).toJson()["result"]["addressMatches"][0] ?: return null
        val lat = match["coordinates"]["y"].double ?: return null
        val lng = match["coordinates"]["x"].double ?: return null
        return GeoHit(LatLng(lat, lng), "census", match["tigerLine"]["side"].string?.firstOrNull())
    }
}

/**
 * OpenStreetMap Nominatim. Free, but the usage policy says: max 1 request/second and an honest User-Agent.
 * Used as a fallback. When OSM has the actual house point it's excellent; when it only knows the street,
 * the point sits on the centerline and the side of the street is ambiguous.
 */
class NominatimGeocoder(private val http: HttpGet, private val minIntervalMs: Long = 1_100) : Geocoder {
    private var lastCallAt = 0L

    @Synchronized
    override fun geocode(address: String): GeoHit? {
        val wait = lastCallAt + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastCallAt = System.currentTimeMillis()
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=us&q=${address.urlEncode()}"
        val hit = http.get(url).toJson()[0] ?: return null
        val lat = hit["lat"].string?.toDoubleOrNull() ?: return null
        val lng = hit["lon"].string?.toDoubleOrNull() ?: return null
        return GeoHit(LatLng(lat, lng), "osm")
    }
}

/** Tries each geocoder in order until one answers. A network error in one doesn't stop the rest. */
class FallbackGeocoder(private val chain: List<Geocoder>) : Geocoder {
    override fun geocode(address: String): GeoHit? {
        var lastError: IOException? = null
        for (geocoder in chain) {
            try {
                geocoder.geocode(address)?.let { return it }
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (lastError != null) throw lastError
        return null
    }
}

/**
 * Remembers every answer in a small JSON file on disk.
 * Lawn customers come back every 6-8 weeks, so after the first lap nearly every lookup is instant and free.
 * [pin] stores a hand-corrected location, and it always wins over the automatic geocoders.
 */
class CachingGeocoder(private val delegate: Geocoder, private val file: File) : Geocoder {
    private val cache: MutableMap<String, GeoHit> = load()

    @Synchronized
    override fun geocode(address: String): GeoHit? {
        val key = keyOf(address)
        cache[key]?.let { return it }
        val hit = delegate.geocode(address) ?: return null
        cache[key] = hit
        save()
        return hit
    }

    /** Save a manual correction (e.g. the driver long-pressed the real driveway on a map). */
    @Synchronized
    fun pin(address: String, location: LatLng) {
        cache[keyOf(address)] = GeoHit(location, "manual")
        save()
    }

    private fun keyOf(address: String) = address.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    private fun load(): MutableMap<String, GeoHit> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val root = file.readText().toJson() as? kotlinx.serialization.json.JsonObject ?: return mutableMapOf()
            root.mapNotNull { (key, v) ->
                val lat = v["lat"].double
                val lng = v["lng"].double
                if (lat == null || lng == null) null
                else key to GeoHit(LatLng(lat, lng), v["source"].string ?: "cache", v["side"].string?.firstOrNull())
            }.toMap().toMutableMap()
        } catch (e: Exception) {
            mutableMapOf() // corrupt cache: start over rather than crash the app
        }
    }

    private fun save() {
        val json = buildJsonObject {
            for ((key, hit) in cache) {
                put(key, buildJsonObject {
                    put("lat", hit.location.lat)
                    put("lng", hit.location.lng)
                    put("source", hit.source)
                    hit.side?.let { put("side", it.toString()) }
                })
            }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) { // rename is atomic on the same disk - no half-written cache files
            file.delete()
            tmp.renameTo(file)
        }
    }
}
