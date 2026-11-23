package com.delifar.app.network

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.delifar.app.data.DeliFarRepository
import com.delifar.app.data.SearchArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

data class GeocodeOutcome(
    val candidates: List<GeocodeCandidate> = emptyList(),
    val warningMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * Free, zero-API-key geocoding and address/business search service:
 * 1. Parses pasted coordinates or Google Maps share links immediately.
 * 2. Uses Android's built-in Geocoder (free on Android devices) for street addresses.
 * 3. Falls back to a policy-compliant, rate-limited, configurable OSM/Nominatim endpoint
 *    with 7-day Room caching when Android Geocoder returns no matches or for business search.
 *
 * Never fabricates or silently substitutes default city coordinates on failure.
 */
class AddressGeocoderService(
    private val context: Context,
    private val repository: DeliFarRepository
) {
    companion object {
        const val DEFAULT_NOMINATIM_URL = "https://nominatim.openstreetmap.org"
        private const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MIN_NOMINATIM_INTERVAL_MS = 1_150L
        private const val MAX_RESULTS = 8
        private const val USER_AGENT = "DeliFarAndroid/2.1 (delivery-pickup-organizer; contact: support@delifar.app)"
    }

    private val rateLimitMutex = Mutex()
    private var lastOsmRequestAtMs = 0L

    /**
     * Locates a manually entered street address (or pasted coordinates / Google Maps share link).
     * Tries pasted coordinates/link -> Android Geocoder -> configurable OSM Nominatim fallback.
     */
    suspend fun locateAddress(
        rawAddress: String,
        area: String,
        languageTag: String,
        osmBaseUrl: String,
        allowOsmFallback: Boolean = true
    ): GeocodeOutcome = withContext(Dispatchers.IO) {
        val cleaned = rawAddress.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length < 3) {
            return@withContext GeocodeOutcome(
                errorMessage = "Enter a full street address (at least 3 characters) or paste coordinates."
            )
        }

        // 1. Check for direct coordinates or full Google Maps link
        when (val parsed = ShareLocationParser.parseLocal(cleaned)) {
            is ParsedShareInput.ExactLocation -> {
                return@withContext GeocodeOutcome(
                    candidates = listOf(
                        GeocodeCandidate(
                            id = "coord:${"%.5f".format(Locale.US, parsed.lat)},${"%.5f".format(Locale.US, parsed.lon)}",
                            displayName = parsed.extractedLabel ?: "Pinned Coordinates",
                            formattedAddress = parsed.extractedAddress
                                ?: "${"%.5f".format(Locale.US, parsed.lat)}, ${"%.5f".format(Locale.US, parsed.lon)}",
                            lat = parsed.lat,
                            lon = parsed.lon,
                            source = parsed.source
                        )
                    )
                )
            }
            is ParsedShareInput.AddressQuery -> {
                return@withContext locateQueryString(
                    query = parsed.query,
                    area = area,
                    languageTag = languageTag,
                    osmBaseUrl = osmBaseUrl,
                    preferAndroidFirst = true,
                    allowOsmFallback = allowOsmFallback
                )
            }
            is ParsedShareInput.ShortLinkError -> {
                return@withContext GeocodeOutcome(errorMessage = parsed.message)
            }
            null -> {
                // Check allowlisted shortened Google Maps link (maps.app.goo.gl)
                val shortLink = ShareLocationParser.isAllowedShortGoogleMapsLink(cleaned)
                if (shortLink != null) {
                    return@withContext when (val resolved = ShareLocationParser.resolveShortMapsLinkSafely(shortLink)) {
                        is ParsedShareInput.ExactLocation -> GeocodeOutcome(
                            candidates = listOf(
                                GeocodeCandidate(
                                    id = "glink:${"%.5f".format(Locale.US, resolved.lat)},${"%.5f".format(Locale.US, resolved.lon)}",
                                    displayName = resolved.extractedLabel ?: "Google Maps Location",
                                    formattedAddress = resolved.extractedAddress
                                        ?: "${"%.5f".format(Locale.US, resolved.lat)}, ${"%.5f".format(Locale.US, resolved.lon)}",
                                    lat = resolved.lat,
                                    lon = resolved.lon,
                                    source = resolved.source
                                )
                            )
                        )
                        is ParsedShareInput.AddressQuery -> locateQueryString(
                            query = resolved.query,
                            area = area,
                            languageTag = languageTag,
                            osmBaseUrl = osmBaseUrl,
                            preferAndroidFirst = true,
                            allowOsmFallback = allowOsmFallback
                        )
                        is ParsedShareInput.ShortLinkError -> GeocodeOutcome(errorMessage = resolved.message)
                    }
                }
            }
        }

        locateQueryString(
            query = cleaned,
            area = area,
            languageTag = languageTag,
            osmBaseUrl = osmBaseUrl,
            preferAndroidFirst = true,
            allowOsmFallback = allowOsmFallback
        )
    }

    /**
     * Searches by business name or address using configured OSM/Nominatim + Android Geocoder.
     */
    suspend fun searchDirectory(
        query: String,
        area: String,
        languageTag: String,
        osmBaseUrl: String,
        allowOsmFallback: Boolean = true
    ): GeocodeOutcome = withContext(Dispatchers.IO) {
        val cleaned = query.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length < 2) return@withContext GeocodeOutcome()

        // If user pasted coordinates or a Google Maps link into the search box, handle it directly
        val direct = locateAddress(cleaned, area, languageTag, osmBaseUrl, allowOsmFallback)
        if (ShareLocationParser.parseLocal(cleaned) != null ||
            ShareLocationParser.isAllowedShortGoogleMapsLink(cleaned) != null
        ) {
            return@withContext direct
        }

        // For business-name + address directory search, try OSM Nominatim + Android Geocoder
        locateQueryString(
            query = cleaned,
            area = area,
            languageTag = languageTag,
            osmBaseUrl = osmBaseUrl,
            preferAndroidFirst = false,
            allowOsmFallback = allowOsmFallback
        )
    }

    private suspend fun locateQueryString(
        query: String,
        area: String,
        languageTag: String,
        osmBaseUrl: String,
        preferAndroidFirst: Boolean,
        allowOsmFallback: Boolean
    ): GeocodeOutcome {
        val errors = mutableListOf<String>()

        if (preferAndroidFirst) {
            val androidRes = queryAndroidGeocoder(query, area)
            androidRes.onSuccess { list ->
                if (list.isNotEmpty()) return GeocodeOutcome(candidates = list)
            }.onFailure { err ->
                errors += err.message ?: "Android Geocoder unavailable"
            }

            if (allowOsmFallback) {
                val osmRes = queryOsmNominatim(query, area, languageTag, osmBaseUrl)
                osmRes.onSuccess { list ->
                    if (list.isNotEmpty()) return GeocodeOutcome(candidates = list)
                }.onFailure { err ->
                    errors += err.message ?: "OSM geocoding failed"
                }
            }
        } else {
            val combined = mutableListOf<GeocodeCandidate>()
            if (allowOsmFallback) {
                val osmRes = queryOsmNominatim(query, area, languageTag, osmBaseUrl)
                osmRes.onSuccess { combined += it }
                    .onFailure { errors += it.message ?: "OSM geocoding failed" }
            }
            if (combined.size < 3) {
                val androidRes = queryAndroidGeocoder(query, area)
                androidRes.onSuccess { list ->
                    list.forEach { cand ->
                        val nearDuplicate = combined.any { existing ->
                            kotlin.math.abs(existing.lat - cand.lat) < 0.0004 &&
                                kotlin.math.abs(existing.lon - cand.lon) < 0.0004
                        }
                        if (!nearDuplicate) combined += cand
                    }
                }.onFailure { errors += it.message ?: "Android Geocoder unavailable" }
            }
            if (combined.isNotEmpty()) {
                return GeocodeOutcome(candidates = combined.take(MAX_RESULTS))
            }
        }

        val reason = if (errors.isNotEmpty()) {
            "Could not locate address (${errors.joinToString("; ")}). You can still tap the map below to place the pin manually."
        } else {
            "No matching location was found for \"$query\". Double-check the street, city, and state, or tap the map below to place the pin manually."
        }
        return GeocodeOutcome(errorMessage = reason)
    }

    private suspend fun queryAndroidGeocoder(query: String, area: String): Result<List<GeocodeCandidate>> {
        if (!Geocoder.isPresent()) {
            return Result.failure(IllegalStateException("Device Geocoder service unavailable"))
        }
        val (minLat, minLon, maxLat, maxLon) = areaBounds(area)
        return runCatching {
            val geocoder = Geocoder(context, Locale.US)
            // First try biased to selected metro area; if empty, retry globally
            val biased = fetchAndroidAddresses(geocoder, query, minLat, minLon, maxLat, maxLon)
            val raw = biased.ifEmpty { fetchAndroidAddresses(geocoder, query, null, null, null, null) }
            raw.mapIndexedNotNull { idx, addr -> addressToCandidate(addr, idx) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun fetchAndroidAddresses(
        geocoder: Geocoder,
        query: String,
        lowerLeftLat: Double?,
        lowerLeftLon: Double?,
        upperRightLat: Double?,
        upperRightLon: Double?
    ): List<Address> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(4_000L) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    }
                    try {
                        if (lowerLeftLat != null && lowerLeftLon != null && upperRightLat != null && upperRightLon != null) {
                            geocoder.getFromLocationName(
                                query, MAX_RESULTS,
                                lowerLeftLat, lowerLeftLon, upperRightLat, upperRightLon,
                                listener
                            )
                        } else {
                            geocoder.getFromLocationName(query, MAX_RESULTS, listener)
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
            }.orEmpty()
        } else {
            if (lowerLeftLat != null && lowerLeftLon != null && upperRightLat != null && upperRightLon != null) {
                geocoder.getFromLocationName(
                    query, MAX_RESULTS,
                    lowerLeftLat, lowerLeftLon, upperRightLat, upperRightLon
                ).orEmpty()
            } else {
                geocoder.getFromLocationName(query, MAX_RESULTS).orEmpty()
            }
        }
    }

    private fun addressToCandidate(addr: Address, index: Int): GeocodeCandidate? {
        if (!addr.hasLatitude() || !addr.hasLongitude()) return null
        val lat = addr.latitude
        val lon = addr.longitude
        if (!GeoValidation.isValidCoordinate(lat, lon)) return null
        val fullLine = (0..addr.maxAddressLineIndex)
            .mapNotNull { i -> addr.getAddressLine(i)?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString(", ")
        val formatted = fullLine.ifBlank {
            listOfNotNull(addr.subThoroughfare, addr.thoroughfare, addr.locality, addr.adminArea, addr.postalCode)
                .joinToString(" ")
                .trim()
        }
        if (formatted.isBlank()) return null
        val displayName = addr.featureName?.takeIf { it.isNotBlank() && it != addr.subThoroughfare }
            ?: formatted.substringBefore(",")
        return GeocodeCandidate(
            id = "android:${"%.5f".format(Locale.US, lat)},${"%.5f".format(Locale.US, lon)}:$index",
            displayName = displayName,
            formattedAddress = formatted,
            lat = lat,
            lon = lon,
            source = GeocodeSource.ANDROID_GEOCODER,
            phone = addr.phone.orEmpty()
        )
    }

    private suspend fun queryOsmNominatim(
        query: String,
        area: String,
        languageTag: String,
        osmBaseUrl: String
    ): Result<List<GeocodeCandidate>> {
        val base = osmBaseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_NOMINATIM_URL }
        if (!GeoValidation.isSafeHttpsEndpointUrl(base)) {
            return Result.failure(IllegalArgumentException("Geocoding URL must be a valid public HTTPS endpoint."))
        }
        val uri = URI(base)
        val host = uri.host ?: return Result.failure(IllegalArgumentException("Invalid geocoding host."))
        if (!GeoValidation.resolvesToPublicAddress(host)) {
            return Result.failure(SecurityException("Geocoding host did not resolve to a public address."))
        }

        val lang = when (languageTag.lowercase(Locale.US)) {
            "ru" -> "ru,en"
            "hy" -> "hy,en"
            else -> "en"
        }
        val cacheKey = "osm|$base|$area|$lang|${query.lowercase(Locale.US)}"
        repository.getCachedSearch(cacheKey, CACHE_MAX_AGE_MS)?.let { cached ->
            runCatching { parseNominatimJson(cached) }
                .onSuccess { return Result.success(it) }
        }

        // Respect Nominatim usage policy (max 1 request/sec)
        rateLimitMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastOsmRequestAtMs
            if (elapsed < MIN_NOMINATIM_INTERVAL_MS) {
                delay(MIN_NOMINATIM_INTERVAL_MS - elapsed)
            }
            lastOsmRequestAtMs = System.currentTimeMillis()
        }

        val (minLat, minLon, maxLat, maxLon) = areaBounds(area)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // viewbox = left,top,right,bottom (minLon, maxLat, maxLon, minLat) without bounded=1 so valid addresses outside metro still resolve
        val urlStr = "$base/search?q=$encodedQuery&format=jsonv2&addressdetails=1&limit=$MAX_RESULTS" +
            "&viewbox=$minLon,$maxLat,$maxLon,$minLat"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", lang)
        }

        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            }.orEmpty()
            if (code == 429) {
                return Result.failure(IllegalStateException("Geocoding service rate limit reached; try again shortly or place pin on map"))
            }
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("Geocoding server returned HTTP $code"))
            }
            val parsed = parseNominatimJson(body)
            repository.putCachedSearch(cacheKey, body)
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.message ?: "Network error reaching geocoding service"))
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseNominatimJson(raw: String): List<GeocodeCandidate> {
        val arr = JSONArray(raw)
        val out = mutableListOf<GeocodeCandidate>()
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val lat = obj.optString("lat").toDoubleOrNull() ?: continue
            val lon = obj.optString("lon").toDoubleOrNull() ?: continue
            if (!GeoValidation.isValidCoordinate(lat, lon)) continue
            val osmType = obj.optString("osm_type", "n")
            val osmId = obj.optString("osm_id", i.toString())
            val id = "osm:$osmType:$osmId"
            if (!seen.add(id)) continue

            val displayFull = obj.optString("display_name").trim()
            val explicitName = obj.optString("name").trim()
            val addrObj = obj.optJSONObject("address")
            val shortAddress = if (addrObj != null) {
                val street = listOfNotNull(
                    addrObj.optString("house_number").takeIf { it.isNotBlank() },
                    addrObj.optString("road").takeIf { it.isNotBlank() }
                ).joinToString(" ")
                val city = addrObj.optString("city").ifBlank {
                    addrObj.optString("town").ifBlank { addrObj.optString("village") }
                }
                val state = addrObj.optString("state")
                val postcode = addrObj.optString("postcode")
                listOf(street, city, state, postcode).filter { it.isNotBlank() }.joinToString(", ")
                    .ifBlank { displayFull }
            } else {
                displayFull
            }
            if (shortAddress.isBlank()) continue
            out += GeocodeCandidate(
                id = id,
                displayName = explicitName.ifBlank { shortAddress.substringBefore(",") },
                formattedAddress = shortAddress,
                lat = lat,
                lon = lon,
                source = GeocodeSource.OSM_NOMINATIM
            )
        }
        return out
    }

    private fun areaBounds(area: String): QuadDouble = when (area) {
        SearchArea.SAC -> QuadDouble(38.20, -121.95, 39.00, -121.00)
        else -> QuadDouble(33.65, -118.85, 34.45, -117.65)
    }

    private data class QuadDouble(val a: Double, val b: Double, val c: Double, val d: Double)
}
