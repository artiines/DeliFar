package com.delifar.app.network

import com.delifar.app.BuildConfig
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.SearchArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class PlaceSearchResult(
    val placeId: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val phone: String = ""
)

class QuotaExceededException(message: String) : Exception(message)

/**
 * Google Maps Platform Places API (New) client (`places:searchText`).
 * Uses strict X-Goog-FieldMask, area location biasing, 7-day cache (well within the 30-day ToS limit),
 * and detects HTTP 429 / RESOURCE_EXHAUSTED demo-key quota conditions.
 */
class GooglePlacesClient(
    private val repository: DeliFarRepository,
    private val onQuotaExceeded: () -> Unit = {}
) {
    companion object {
        private const val ENDPOINT = "https://places.googleapis.com/v1/places:searchText"
        private const val FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.location,places.nationalPhoneNumber"
        private const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_RESULTS = 12
    }

    suspend fun search(
        query: String,
        area: String,
        languageTag: String,
        centerOverride: Pair<Double, Double>? = null
    ): Result<List<PlaceSearchResult>> = withContext(Dispatchers.IO) {
        val cleaned = query.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length < 2) return@withContext Result.success(emptyList())

        val apiKey = BuildConfig.GOOGLE_MAPS_API_KEY.trim()
        if (apiKey.isEmpty() || apiKey == "your_google_maps_api_key_here") {
            return@withContext Result.failure(
                IllegalStateException("Google Maps API key is not configured.")
            )
        }

        val (biasLat, biasLon, radiusMeters) = when {
            centerOverride != null -> Triple(centerOverride.first, centerOverride.second, 45000.0)
            area == SearchArea.SAC -> Triple(38.5816, -121.4944, 50000.0)
            else -> Triple(34.0522, -118.2437, 50000.0)
        }

        val lang = when (languageTag.lowercase(Locale.US)) {
            "ru" -> "ru"
            "hy" -> "hy"
            else -> "en"
        }

        val cacheKey = "gplaces|$area|$lang|${cleaned.lowercase(Locale.US)}"
        val cached = repository.getCachedSearch(cacheKey, CACHE_MAX_AGE_MS)
        if (cached != null) {
            runCatching { parsePlacesResponse(cached) }
                .onSuccess { return@withContext Result.success(it) }
        }

        val payload = JSONObject().apply {
            put("textQuery", cleaned)
            put("languageCode", lang)
            put("maxResultCount", MAX_RESULTS)
            put("locationBias", JSONObject().apply {
                put("circle", JSONObject().apply {
                    put("center", JSONObject().apply {
                        put("latitude", biasLat)
                        put("longitude", biasLon)
                    })
                    put("radius", radiusMeters)
                })
            })
        }

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Goog-Api-Key", apiKey)
            setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            }.orEmpty()

            if (code == 429 || body.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                body.contains("OVER_QUERY_LIMIT", ignoreCase = true)
            ) {
                onQuotaExceeded()
                return@withContext Result.failure(
                    QuotaExceededException("Google Maps Platform quota reached (HTTP $code).")
                )
            }

            if (code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("Places search failed (HTTP $code).")
                )
            }

            val parsed = parsePlacesResponse(body)
            repository.putCachedSearch(cacheKey, body)
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun parsePlacesResponse(raw: String): List<PlaceSearchResult> {
        val root = JSONObject(raw)
        val arr: JSONArray = root.optJSONArray("places") ?: return emptyList()
        val results = mutableListOf<PlaceSearchResult>()
        val seen = mutableSetOf<String>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val placeId = item.optString("id").trim()
            if (placeId.isEmpty() || !seen.add(placeId)) continue

            val loc = item.optJSONObject("location") ?: continue
            val lat = loc.optDouble("latitude", Double.NaN)
            val lon = loc.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue

            val name = item.optJSONObject("displayName")?.optString("text")?.trim().orEmpty()
            val address = item.optString("formattedAddress").trim()
            val phone = item.optString("nationalPhoneNumber").trim()
            if (name.isEmpty() && address.isEmpty()) continue

            results += PlaceSearchResult(
                placeId = placeId,
                name = name.ifEmpty { address.substringBefore(",") },
                address = address.ifEmpty { name },
                lat = lat,
                lon = lon,
                phone = phone
            )
        }
        return results
    }
}
