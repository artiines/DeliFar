package com.delifar.app.network

import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.SearchArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PhotonPlace(
    val providerId: String?,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val category: String
)

class PhotonSearchClient(
    private val repository: DeliFarRepository
) {
    private val requestMutex = Mutex()
    private var lastRequestAtMs = 0L

    companion object {
        private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MIN_INTERVAL_MS = 1100L
        private const val USER_AGENT = "DeliFar/1.0 (Android; personal delivery organizer)"

        // Bounding boxes (minLon, minLat, maxLon, maxLat) + bias centers
        private const val LA_BBOX = "-118.95,33.65,-117.65,34.45"
        private const val LA_LAT = 34.0522
        private const val LA_LON = -118.2437

        private const val SAC_BBOX = "-121.75,38.30,-121.00,38.85"
        private const val SAC_LAT = 38.5816
        private const val SAC_LON = -121.4944
    }

    suspend fun search(
        query: String,
        area: String,
        baseUrl: String
    ): Result<List<PhotonPlace>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) {
            return@withContext Result.success(emptyList())
        }
        val cleanBase = baseUrl.trim()
        if (!cleanBase.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("Search endpoint must use HTTPS."))
        }

        val cacheKey = "${cleanBase.lowercase()}|$area|${cleanQuery.lowercase()}"
        val cached = repository.getCachedSearch(cacheKey, CACHE_TTL_MS)
        if (cached != null) {
            return@withContext runCatching { parseGeoJson(cached) }
        }

        requestMutex.withLock {
            // Re-check cache inside lock in case another call just populated it
            repository.getCachedSearch(cacheKey, CACHE_TTL_MS)?.let {
                return@withLock runCatching { parseGeoJson(it) }
            }

            val elapsed = System.currentTimeMillis() - lastRequestAtMs
            if (elapsed < MIN_INTERVAL_MS) {
                delay(MIN_INTERVAL_MS - elapsed)
            }
            lastRequestAtMs = System.currentTimeMillis()

            val (bbox, lat, lon) = if (area == SearchArea.SAC) {
                Triple(SAC_BBOX, SAC_LAT, SAC_LON)
            } else {
                Triple(LA_BBOX, LA_LAT, LA_LON)
            }

            val separator = if (cleanBase.contains("?")) "&" else "?"
            val encodedQ = URLEncoder.encode(cleanQuery, "UTF-8")
            val fullUrl = "${cleanBase}${separator}q=$encodedQ&limit=12&bbox=$bbox&lat=$lat&lon=$lon"

            runCatching {
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("Search service returned HTTP $code")
                    }
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val places = parseGeoJson(body)
                    repository.putCachedSearch(cacheKey, body)
                    places
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    private fun parseGeoJson(raw: String): List<PhotonPlace> {
        val root = JSONObject(raw)
        val features = root.optJSONArray("features") ?: return emptyList()
        val results = mutableListOf<PhotonPlace>()
        val seenIds = mutableSetOf<String>()

        for (i in 0 until features.length().coerceAtMost(12)) {
            val feat = features.optJSONObject(i) ?: continue
            val geom = feat.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue

            val props = feat.optJSONObject("properties") ?: JSONObject()
            val osmType = props.optString("osm_type", "").trim()
            val osmId = if (props.has("osm_id") && !props.isNull("osm_id")) {
                props.optLong("osm_id", -1L).takeIf { it > 0L }
            } else {
                null
            }
            val providerId = if (osmType.isNotEmpty() && osmId != null) {
                "osm:${osmType.uppercase()}:$osmId"
            } else {
                null
            }
            if (providerId != null && !seenIds.add(providerId)) continue

            val house = props.optString("housenumber", "").trim()
            val street = props.optString("street", "").trim()
            val streetLine = listOf(house, street).filter { it.isNotEmpty() }.joinToString(" ")
            val city = props.optString("city", "").ifBlank {
                props.optString("district", "").ifBlank { props.optString("county", "") }
            }.trim()
            val state = props.optString("state", "").trim()
            val postcode = props.optString("postcode", "").trim()

            val rawName = props.optString("name", "").trim()
            val displayName = rawName.ifEmpty { streetLine.ifEmpty { city.ifEmpty { "Unnamed Place" } } }
            val addressParts = listOf(
                streetLine,
                city,
                listOf(state, postcode).filter { it.isNotEmpty() }.joinToString(" ")
            ).filter { it.isNotEmpty() }
            val fullAddress = addressParts.joinToString(", ").ifEmpty { displayName }
            val category = props.optString("osm_value", "").ifBlank {
                props.optString("osm_key", "")
            }

            results += PhotonPlace(
                providerId = providerId,
                name = displayName,
                address = fullAddress,
                lat = lat,
                lon = lon,
                category = category
            )
        }
        return results
    }
}
