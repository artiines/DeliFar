package com.delifar.app.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

object OsmProjection {
    fun latLonToTileDouble(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
        val clampedLat = lat.coerceIn(-85.0511, 85.0511)
        val n = 2.0.pow(zoom)
        val x = (lon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(clampedLat)
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return x to y
    }

    fun tileDoubleToLatLon(x: Double, y: Double, zoom: Int): Pair<Double, Double> {
        val n = 2.0.pow(zoom)
        val lon = x / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1.0 - 2.0 * y / n)))
        val lat = Math.toDegrees(latRad).coerceIn(-85.0511, 85.0511)
        return lat to lon
    }
}

class OsmTileLoader(context: Context) {
    private val cacheDir = File(context.cacheDir, "osm_tiles").apply { mkdirs() }
    private val memoryCache = object : LruCache<String, Bitmap>(64) {}

    companion object {
        private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val USER_AGENT = "DeliFar/1.0 (Android; personal delivery organizer)"
    }

    fun getCachedMemory(z: Int, x: Int, y: Int, tileUrlTemplate: String): Bitmap? {
        return memoryCache.get(cacheKey(z, x, y, tileUrlTemplate))
    }

    suspend fun loadTile(z: Int, x: Int, y: Int, tileUrlTemplate: String): Bitmap? = withContext(Dispatchers.IO) {
        val maxTile = 1 shl z
        if (y < 0 || y >= maxTile) return@withContext null
        val wrappedX = ((x % maxTile) + maxTile) % maxTile
        val key = cacheKey(z, wrappedX, y, tileUrlTemplate)
        memoryCache.get(key)?.let { return@withContext it }

        val file = File(cacheDir, "${key.hashCode()}_${z}_${wrappedX}_${y}.png")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() <= CACHE_TTL_MS) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                memoryCache.put(key, bmp)
                return@withContext bmp
            }
        }

        val urlStr = tileUrlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", wrappedX.toString())
            .replace("{y}", y.toString())
        if (!urlStr.startsWith("https://")) return@withContext null

        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                val bytes = conn.inputStream.use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                runCatching { file.writeBytes(bytes) }
                memoryCache.put(key, bmp)
                bmp
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheKey(z: Int, x: Int, y: Int, template: String): String =
        "${template.hashCode()}/$z/$x/$y"
}
