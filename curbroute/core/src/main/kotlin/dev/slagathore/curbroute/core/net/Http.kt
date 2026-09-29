package dev.slagathore.curbroute.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * "GET this URL, give me the body." It's an interface (not a concrete class) so unit tests can plug in
 * a fake network and run offline - see OsrmRouterTest.
 */
fun interface HttpGet {
    @Throws(IOException::class)
    fun get(url: String): String
}

class HttpException(val code: Int, body: String, url: String) :
    IOException("HTTP $code from ${url.substringBefore('?')}: ${body.take(300)}")

/** Blocking HTTP client built on HttpURLConnection, which exists on both desktop Java and Android. */
class UrlConnectionHttp(
    private val userAgent: String = "CurbRoute/0.1 (lawn route planner)",
    private val timeoutMs: Int = 20_000,
    private val retries: Int = 2,
) : HttpGet {
    override fun get(url: String): String {
        var attempt = 0
        while (true) {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                // Free services (Nominatim, OSRM demo, Census) require an honest User-Agent.
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code == 200) return conn.inputStream.bufferedReader().use { it.readText() }
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                // 429 = "slow down", 5xx = server hiccup: worth a retry with backoff (1s, 2s, ...).
                if ((code == 429 || code >= 500) && attempt < retries) {
                    Thread.sleep(1000L shl attempt)
                    attempt++
                    continue
                }
                throw HttpException(code, body, url)
            } finally {
                conn.disconnect()
            }
        }
    }
}

// --- tiny JSON helpers so the parsing code reads like the JSON it's parsing ------------------------------

internal fun String.toJson(): JsonElement = Json.parseToJsonElement(this)
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
internal val JsonElement?.array: JsonArray get() = this?.jsonArray ?: JsonArray(emptyList())
internal val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.doubleOrNull
internal val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.contentOrNull

internal fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
