package com.delifar.app.network

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import kotlin.math.abs

data class GeoPoint(
    val lat: Double,
    val lon: Double
) {
    fun isValid(): Boolean = GeoValidation.isValidCoordinate(lat, lon)
}

object GeocodeSource {
    const val ANDROID_GEOCODER = "android_geocoder"
    const val OSM_NOMINATIM = "osm_nominatim"
    const val PASTED_COORDS = "pasted_coords"
    const val GOOGLE_MAPS_LINK = "google_maps_link"
    const val MANUAL_PIN = "manual_pin"
    const val LEGACY = "legacy"
}

data class GeocodeCandidate(
    val id: String,
    val displayName: String,
    val formattedAddress: String,
    val lat: Double,
    val lon: Double,
    val source: String,
    val phone: String = ""
)

data class ComputedRouteResult(
    val polylinePoints: List<GeoPoint>,
    val distanceMeters: Int,
    val durationSeconds: Long,
    val optimizedStopIndices: List<Int>,
    val isStreetRoute: Boolean,
    val sourceLabel: String
)

object GeoValidation {
    const val LEGACY_LA_FALLBACK_LAT = 34.0522
    const val LEGACY_LA_FALLBACK_LON = -118.2437

    /**
     * Validates geographic coordinates and rejects NaN, Infinite, out-of-range values,
     * and Null Island (0.0, 0.0).
     */
    fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        if (!lat.isFinite() || !lon.isFinite()) return false
        if (lat < -90.0 || lat > 90.0) return false
        if (lon < -180.0 || lon > 180.0) return false
        if (abs(lat) < 0.0001 && abs(lon) < 0.0001) return false
        return true
    }

    /**
     * Detects legacy stores that previously fell back silently to downtown Los Angeles
     * coordinates (34.0522, -118.2437) so the app can flag them for user pin review
     * without deleting or relocating existing records.
     */
    fun isSuspectDefaultLosAngelesCoord(lat: Double, lon: Double): Boolean {
        return abs(lat - LEGACY_LA_FALLBACK_LAT) <= 0.0015 &&
            abs(lon - LEGACY_LA_FALLBACK_LON) <= 0.0015
    }

    /**
     * Validates a user-configured geocoding, routing, or map style endpoint URL:
     * must be HTTPS, have a public non-loopback/non-private host, and no embedded credentials.
     */
    fun isSafeHttpsEndpointUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val uri = URI(trimmed)
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (!uri.userInfo.isNullOrBlank()) return false
            val host = uri.host?.lowercase() ?: return false
            if (host.isBlank() || host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) {
                return false
            }
            if (isPrivateOrLoopbackLiteral(host)) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isPrivateOrLoopbackLiteral(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h == "::1" || h == "0:0:0:0:0:0:0:1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) {
            return true
        }
        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(h) ?: return false
        val a = ipv4.groupValues[1].toIntOrNull() ?: return true
        val b = ipv4.groupValues[2].toIntOrNull() ?: return true
        return a == 10 ||
            a == 127 ||
            a == 0 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
    }

    /**
     * Runtime DNS check before connecting to user-configured or redirect URLs.
     */
    fun resolvesToPublicAddress(host: String): Boolean {
        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.none { addr ->
                addr.isAnyLocalAddress ||
                    addr.isLoopbackAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress ||
                    addr.isMulticastAddress
            }
        } catch (_: Exception) {
            false
        }
    }
}

sealed class ParsedShareInput {
    data class ExactLocation(
        val lat: Double,
        val lon: Double,
        val extractedLabel: String?,
        val extractedAddress: String?,
        val source: String
    ) : ParsedShareInput()

    data class AddressQuery(val query: String) : ParsedShareInput()

    data class ShortLinkError(val message: String) : ParsedShareInput()
}

/**
 * Parses pasted coordinates, full Google Maps URLs, or plain street addresses.
 * Shortened Google Maps links (maps.app.goo.gl / goo.gl) are only resolved over HTTPS
 * with strict host allowlisting, no blind redirect following, and max 2 hops.
 */
object ShareLocationParser {
    private val RAW_COORDS_REGEX = Regex(
        "^\\s*(-?\\d{1,2}\\.\\d{3,})\\s*[,\\s]\\s*(-?\\d{1,3}\\.\\d{3,})\\s*$"
    )
    private val PIN_3D_4D_REGEX = Regex("!3d(-?\\d{1,2}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)")
    private val AT_COORDS_REGEX = Regex("@(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)")
    private val QUERY_COORDS_REGEX = Regex("[?&](?:q|query|destination|ll)=(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)")
    private val PLACE_PATH_REGEX = Regex("/maps/place/([^/@?#]+)")
    private val QUERY_TEXT_REGEX = Regex("[?&](?:q|query)=([^&]+)")
    private val ALLOWED_SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl")
    private val ALLOWED_TARGET_SUFFIXES = listOf(".google.com", "google.com", "maps.app.goo.gl", "goo.gl")

    fun parseLocal(rawInput: String): ParsedShareInput? {
        val text = rawInput.trim()
        if (text.isEmpty()) return null

        // 1. Direct lat, lon paste (e.g., "34.1425, -118.2551")
        RAW_COORDS_REGEX.matchEntire(text)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && GeoValidation.isValidCoordinate(lat, lon)) {
                return ParsedShareInput.ExactLocation(
                    lat = lat,
                    lon = lon,
                    extractedLabel = null,
                    extractedAddress = null,
                    source = GeocodeSource.PASTED_COORDS
                )
            }
        }

        // 2. Full URL embedded in pasted share text
        val urlMatch = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(text)
        if (urlMatch != null) {
            val urlStr = urlMatch.value.trimEnd('.', ',', ')')
            return parseKnownMapsUrl(urlStr)
        }

        return null
    }

    fun isAllowedShortGoogleMapsLink(rawInput: String): String? {
        val urlMatch = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE).find(rawInput.trim()) ?: return null
        val urlStr = urlMatch.value.trimEnd('.', ',', ')')
        return try {
            val uri = URI(urlStr)
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme.equals("https", ignoreCase = true) && host in ALLOWED_SHORT_HOSTS) {
                urlStr
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseKnownMapsUrl(urlStr: String): ParsedShareInput? {
        val uri = try {
            URI(urlStr)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        val isGoogleHost = ALLOWED_TARGET_SUFFIXES.any { suffix ->
            host == suffix.removePrefix(".") || host.endsWith(suffix)
        }
        if (!isGoogleHost) return null

        val placeLabel = PLACE_PATH_REGEX.find(urlStr)?.groupValues?.get(1)?.let { decodeUrlComponent(it) }

        // Prefer exact pin (!3d...!4d...) over viewport center (@lat,lon)
        PIN_3D_4D_REGEX.find(urlStr)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && GeoValidation.isValidCoordinate(lat, lon)) {
                return ParsedShareInput.ExactLocation(
                    lat = lat,
                    lon = lon,
                    extractedLabel = placeLabel,
                    extractedAddress = placeLabel?.takeIf { it.contains(",") },
                    source = GeocodeSource.GOOGLE_MAPS_LINK
                )
            }
        }

        QUERY_COORDS_REGEX.find(urlStr)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && GeoValidation.isValidCoordinate(lat, lon)) {
                return ParsedShareInput.ExactLocation(
                    lat = lat,
                    lon = lon,
                    extractedLabel = placeLabel,
                    extractedAddress = null,
                    source = GeocodeSource.GOOGLE_MAPS_LINK
                )
            }
        }

        AT_COORDS_REGEX.find(urlStr)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && GeoValidation.isValidCoordinate(lat, lon)) {
                return ParsedShareInput.ExactLocation(
                    lat = lat,
                    lon = lon,
                    extractedLabel = placeLabel,
                    extractedAddress = placeLabel?.takeIf { it.contains(",") },
                    source = GeocodeSource.GOOGLE_MAPS_LINK
                )
            }
        }

        val queryText = QUERY_TEXT_REGEX.find(urlStr)?.groupValues?.get(1)?.let { decodeUrlComponent(it) }
        val fallbackQuery = queryText ?: placeLabel
        if (!fallbackQuery.isNullOrBlank()) {
            return ParsedShareInput.AddressQuery(fallbackQuery)
        }
        return null
    }

    /**
     * Safely resolves an allowlisted `https://maps.app.goo.gl/...` link by inspecting only
     * the HTTP Location redirect header (max 2 hops, HTTPS only, Google hosts only).
     */
    fun resolveShortMapsLinkSafely(shortUrl: String): ParsedShareInput {
        var currentUrl = shortUrl
        repeat(2) {
            val uri = try {
                URI(currentUrl)
            } catch (_: Exception) {
                return ParsedShareInput.ShortLinkError("Invalid link format. Paste the full street address or coordinates instead.")
            }
            val host = uri.host?.lowercase() ?: return ParsedShareInput.ShortLinkError("Invalid link host.")
            val hostAllowed = ALLOWED_TARGET_SUFFIXES.any { s -> host == s.removePrefix(".") || host.endsWith(s) }
            if (!uri.scheme.equals("https", ignoreCase = true) || !hostAllowed) {
                return ParsedShareInput.ShortLinkError("Unsupported link host. Paste a full street address or coordinates.")
            }
            if (!GeoValidation.resolvesToPublicAddress(host)) {
                return ParsedShareInput.ShortLinkError("Could not safely resolve that link.")
            }

            // First check if currentUrl already contains coordinates/address
            parseKnownMapsUrl(currentUrl)?.let { parsed ->
                if (parsed is ParsedShareInput.ExactLocation || parsed is ParsedShareInput.AddressQuery) {
                    return parsed
                }
            }

            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("User-Agent", "DeliFarAndroid/2.1")
            }
            try {
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    val resolved = uri.resolve(location).toString()
                    parseKnownMapsUrl(resolved)?.let { return it }
                    currentUrl = resolved
                } else {
                    return ParsedShareInput.ShortLinkError(
                        "Short link could not be expanded automatically. Copy the full street address or coordinates from Google Maps and paste them here."
                    )
                }
            } catch (_: Exception) {
                return ParsedShareInput.ShortLinkError(
                    "Could not reach shortened link while offline or restricted. Paste the full street address or coordinates instead."
                )
            } finally {
                conn.disconnect()
            }
        }
        return ParsedShareInput.ShortLinkError(
            "Could not extract location from shortened link. Paste the full street address or coordinates."
        )
    }

    private fun decodeUrlComponent(raw: String): String {
        return runCatching {
            URLDecoder.decode(raw.replace('+', ' '), "UTF-8").trim()
        }.getOrDefault(raw.replace('+', ' ').trim())
    }
}
