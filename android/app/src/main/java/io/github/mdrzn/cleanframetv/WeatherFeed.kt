package io.github.mdrzn.cleanframetv

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Current weather for a place the owner types, from Open-Meteo (free, no API key).
 * Off until a place is set; then api.open-meteo.com and geocoding-api.open-meteo.com are contacted at most every [INTERVAL_MS].
 * Only the place name is sent (to find coordinates); the TV's own location is never used.
 */
class WeatherFeed(context: Context, private val onUpdated: () -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("cleanframe", Context.MODE_PRIVATE)

    val place: String
        get() = prefs.getString(PLACE, "") ?: ""

    /** Cached reading as JSON for the interface, or "" when weather is off or not downloaded yet. Starts a refresh when it is stale. */
    fun json(): String {
        if (place.isEmpty()) return ""
        refresh(force = false)
        return prefs.getString(READING, "") ?: ""
    }

    /** `{"lat":…,"lon":…,"name":…}` for the place once it has been found, else null. */
    fun coordinates(): JSONObject? = prefs.getString(COORDS, null)?.let { JSONObject(it) }

    /** An empty [text] turns weather off. */
    fun setPlace(text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ").take(80)
        if (clean == place) return
        prefs.edit().putString(PLACE, clean).remove(READING).remove(COORDS).putLong(CHECKED, 0L).apply()
        onUpdated()
        refresh(force = true)
    }

    fun refresh(force: Boolean) {
        if (place.isEmpty()) return
        if (!force && System.currentTimeMillis() - prefs.getLong(CHECKED, 0L) < INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        prefs.edit().putLong(CHECKED, System.currentTimeMillis()).apply()
        executor.execute {
            try {
                val wanted = place
                val reading = fetch(wanted)
                if (wanted == place) prefs.edit().putString(READING, reading).apply().also { onUpdated() }
            } catch (e: Exception) {
                Log.w(AndroidCatalog.TAG, "Weather not updated: ${e.message}")
                prefs.edit().putLong(CHECKED, System.currentTimeMillis() - INTERVAL_MS + RETRY_MS).apply()
            } finally { running.set(false) }
        }
    }

    fun close() = executor.shutdownNow()

    private fun fetch(wanted: String): String {
        val coords = prefs.getString(COORDS, null)?.let { JSONObject(it) } ?: locate(wanted).also { prefs.edit().putString(COORDS, it.toString()).apply() }
        val body = JSONObject(String(get(forecastUrl(coords.getDouble("lat"), coords.getDouble("lon"))), Charsets.UTF_8))
        return readingJson(wanted, coords.optString("name", wanted), body) ?: throw IOException("No current weather")
    }

    private fun locate(wanted: String): JSONObject {
        val body = JSONObject(String(get(geocodeUrl(wanted)), Charsets.UTF_8))
        val hit = body.optJSONArray("results")?.optJSONObject(0) ?: throw IOException("Place not found: $wanted")
        return JSONObject().put("lat", hit.getDouble("latitude")).put("lon", hit.getDouble("longitude")).put("name", hit.optString("name", wanted))
    }

    private fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "CleanFrameTV/0.2 (Android TV launcher)")
        try {
            if (connection.responseCode != 200) throw IllegalStateException("HTTP ${connection.responseCode}")
            if (connection.contentLengthLong > MAX_JSON) throw IOException("Response too large")
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_JSON) throw IOException("Response too large")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PLACE = "weather_place"
        private const val READING = "weather_reading"
        private const val COORDS = "weather_coords"
        private const val CHECKED = "weather_checked"
        private const val INTERVAL_MS = 30 * 60 * 1000L
        private const val RETRY_MS = 5 * 60 * 1000L
        private const val MAX_JSON = 64 * 1024

        fun geocodeUrl(place: String) =
            "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(place, "UTF-8")}&count=1&language=en&format=json"

        fun forecastUrl(lat: Double, lon: Double) =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"

        /** `{place, temp, code, day, hi, lo}` (hi and lo only when the response has them) from an Open-Meteo forecast response, or null when it has no current reading. */
        fun readingJson(wanted: String, name: String, body: JSONObject): String? {
            val current = body.optJSONObject("current") ?: return null
            if (!current.has("temperature_2m") || !current.has("weather_code")) return null
            val reading = JSONObject()
                .put("place", name.ifEmpty { wanted })
                .put("temp", Math.round(current.getDouble("temperature_2m")).toInt())
                .put("code", current.getInt("weather_code"))
                .put("day", current.optInt("is_day", 1) == 1)
            val daily = body.optJSONObject("daily")
            val hi = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
            val lo = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
            if (!hi.isNaN() && !lo.isNaN()) reading.put("hi", Math.round(hi).toInt()).put("lo", Math.round(lo).toInt())
            return reading.toString()
        }
    }
}
