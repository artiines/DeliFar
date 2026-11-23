package com.delifar.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenStreetMap-based routing client supporting any configurable OSRM-compatible server
 * (e.g., self-hosted OSRM instance). Never forces a paid Google Routes API key.
 *
 * If no external routing endpoint is configured (or if an external server is unreachable),
 * falls back transparently to offline nearest-neighbor stop ordering with an explicit
 * `isStreetRoute = false` flag so the UI clearly distinguishes street routes from direct previews.
 */
class OsmRoutesClient {

    suspend fun computeRoute(
        stops: List<GeoPoint>,
        routingBaseUrl: String,
        optimizeIntermediates: Boolean = true
    ): Result<ComputedRouteResult> = withContext(Dispatchers.IO) {
        val validStops = stops.filter { it.isValid() }.take(25)
        if (validStops.size < 2) {
            return@withContext Result.failure(
                IllegalArgumentException("At least 2 stores with confirmed coordinates are required for routing.")
            )
        }

        val trimmedBase = routingBaseUrl.trim().trimEnd('/')
        if (trimmedBase.isNotEmpty()) {
            val remote = fetchOsrmRoute(validStops, trimmedBase, optimizeIntermediates)
            if (remote.isSuccess) return@withContext remote
        }

        // Transparent local offline nearest-neighbor ordering fallback
        Result.success(buildLocalNearestNeighborFallback(validStops, optimizeIntermediates))
    }

    private fun fetchOsrmRoute(
        stops: List<GeoPoint>,
        baseUrl: String,
        optimizeIntermediates: Boolean
    ): Result<ComputedRouteResult> {
        if (!GeoValidation.isSafeHttpsEndpointUrl(baseUrl)) {
            return Result.failure(IllegalArgumentException("Routing server must use a public HTTPS URL."))
        }
        val host = URI(baseUrl).host ?: return Result.failure(IllegalArgumentException("Invalid routing host."))
        if (!GeoValidation.resolvesToPublicAddress(host)) {
            return Result.failure(SecurityException("Routing host did not resolve to a public address."))
        }

        val coordList = stops.joinToString(";") {
            "${"%.6f".format(Locale.US, it.lon)},${"%.6f".format(Locale.US, it.lat)}"
        }
        val useTrip = optimizeIntermediates && stops.size > 2
        val endpoint = if (useTrip) {
            "$baseUrl/trip/v1/driving/$coordList?source=first&destination=last&roundtrip=false&overview=full&geometries=polyline"
        } else {
            "$baseUrl/route/v1/driving/$coordList?overview=full&geometries=polyline"
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "DeliFarAndroid/2.1")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            }.orEmpty()
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("OSRM routing returned HTTP $code"))
            }
            val root = JSONObject(body)
            val arrKey = if (useTrip) "trips" else "routes"
            val routes = root.optJSONArray(arrKey)
            if (routes == null || routes.length() == 0) {
                return Result.failure(IllegalStateException("No street route found."))
            }
            val first = routes.getJSONObject(0)
            val encoded = first.optString("geometry")
            val distance = first.optDouble("distance", 0.0).toInt()
            val duration = first.optDouble("duration", 0.0).toLong()
            val points = decodePolyline(encoded)
            if (points.size < 2) {
                return Result.failure(IllegalStateException("Route geometry was empty."))
            }

            val order = if (useTrip) {
                val wps = root.optJSONArray("waypoints")
                if (wps != null && wps.length() == stops.size) {
                    (0 until wps.length())
                        .map { idx -> idx to wps.getJSONObject(idx).optInt("waypoint_index", idx) }
                        .sortedBy { it.second }
                        .map { it.first }
                } else {
                    stops.indices.toList()
                }
            } else {
                stops.indices.toList()
            }

            Result.success(
                ComputedRouteResult(
                    polylinePoints = points,
                    distanceMeters = distance,
                    durationSeconds = duration,
                    optimizedStopIndices = order,
                    isStreetRoute = true,
                    sourceLabel = "OSRM"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    internal fun buildLocalNearestNeighborFallback(
        stops: List<GeoPoint>,
        optimizeIntermediates: Boolean
    ): ComputedRouteResult {
        val order = if (optimizeIntermediates && stops.size > 2) {
            val remaining = (1 until stops.size - 1).toMutableList()
            val result = mutableListOf(0)
            var current = stops[0]
            while (remaining.isNotEmpty()) {
                val bestIdx = remaining.minBy { idx -> haversineMeters(current, stops[idx]) }
                result += bestIdx
                current = stops[bestIdx]
                remaining.remove(bestIdx)
            }
            result += (stops.size - 1)
            result
        } else {
            stops.indices.toList()
        }

        val orderedPoints = order.map { stops[it] }
        var totalMeters = 0.0
        for (i in 0 until orderedPoints.size - 1) {
            totalMeters += haversineMeters(orderedPoints[i], orderedPoints[i + 1])
        }
        // Conservative urban average (~22 mph / ~9.8 m/s) estimate for offline preview
        val estRoadMeters = (totalMeters * 1.25).toInt()
        val estSeconds = (estRoadMeters / 9.8).toLong().coerceAtLeast(60L)
        return ComputedRouteResult(
            polylinePoints = orderedPoints,
            distanceMeters = estRoadMeters,
            durationSeconds = estSeconds,
            optimizedStopIndices = order,
            isStreetRoute = false,
            sourceLabel = "Offline Stop Order"
        )
    }

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Standard Polyline5 decoder used by OSRM & OpenStreetMap routers. */
    fun decodePolyline(encoded: String): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()
        val poly = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dLat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dLat

            shift = 0
            result = 0
            while (index < len) {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
                if (b < 0x20) break
            }
            val dLng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dLng

            poly.add(GeoPoint(lat / 1E5, lng / 1E5))
        }
        return poly
    }
}
