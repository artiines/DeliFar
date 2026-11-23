package com.delifar.app.network

import com.delifar.app.BuildConfig
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ComputedRouteResult(
    val polylinePoints: List<LatLng>,
    val distanceMeters: Int,
    val durationSeconds: Long,
    val optimizedIntermediateOrder: List<Int>
)

/**
 * Google Maps Platform Routes API (`directions/v2:computeRoutes`) client.
 * Computes traffic-aware driving polylines and optimizes intermediate multi-stop delivery sequences.
 */
class GoogleRoutesClient(
    private val onQuotaExceeded: () -> Unit = {}
) {
    companion object {
        private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
        private const val FIELD_MASK =
            "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.optimizedIntermediateWaypointIndex"
    }

    suspend fun computeRoute(
        stops: List<LatLng>,
        optimizeIntermediates: Boolean = true
    ): Result<ComputedRouteResult> = withContext(Dispatchers.IO) {
        if (stops.size < 2) {
            return@withContext Result.failure(IllegalArgumentException("At least 2 stops are required for routing."))
        }
        val apiKey = BuildConfig.GOOGLE_MAPS_API_KEY.trim()
        if (apiKey.isEmpty() || apiKey == "your_google_maps_api_key_here") {
            return@withContext Result.failure(IllegalStateException("Google Maps API key is not configured."))
        }

        val limitedStops = stops.take(25) // Routes API limit: origin + destination + up to 23 intermediates
        val origin = limitedStops.first()
        val destination = limitedStops.last()
        val intermediates = if (limitedStops.size > 2) limitedStops.subList(1, limitedStops.size - 1) else emptyList()

        val payload = JSONObject().apply {
            put("origin", waypointJson(origin))
            put("destination", waypointJson(destination))
            if (intermediates.isNotEmpty()) {
                put("intermediates", JSONArray().apply {
                    intermediates.forEach { put(waypointJson(it)) }
                })
                if (optimizeIntermediates && intermediates.size > 1) {
                    put("optimizeWaypointOrder", true)
                }
            }
            put("travelMode", "DRIVE")
            put("routingPreference", "TRAFFIC_AWARE")
            put("computeAlternativeRoutes", false)
            put("units", "IMPERIAL")
        }

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Goog-Api-Key", apiKey)
            setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
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
                    QuotaExceededException("Google Maps Platform Routes quota reached (HTTP $code).")
                )
            }

            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Routes API failed (HTTP $code)."))
            }

            val root = JSONObject(body)
            val routes = root.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                return@withContext Result.failure(IllegalStateException("No driving route returned."))
            }
            val first = routes.getJSONObject(0)
            val encoded = first.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
            val distance = first.optInt("distanceMeters", 0)
            val durationStr = first.optString("duration", "0s")
            val durationSec = durationStr.removeSuffix("s").toLongOrNull() ?: 0L

            val optArr = first.optJSONArray("optimizedIntermediateWaypointIndex")
            val optimizedOrder = buildList {
                if (optArr != null) {
                    for (i in 0 until optArr.length()) {
                        add(optArr.optInt(i))
                    }
                }
            }

            Result.success(
                ComputedRouteResult(
                    polylinePoints = decodePolyline(encoded),
                    distanceMeters = distance,
                    durationSeconds = durationSec,
                    optimizedIntermediateOrder = optimizedOrder
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun waypointJson(point: LatLng): JSONObject = JSONObject().apply {
        put("location", JSONObject().apply {
            put("latLng", JSONObject().apply {
                put("latitude", point.latitude)
                put("longitude", point.longitude)
            })
        })
    }

    /** Standard Google Encoded Polyline Algorithm Format decoder. */
    fun decodePolyline(encoded: String): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val poly = ArrayList<LatLng>()
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

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }
}
