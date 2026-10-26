package io.github.mdrzn.cleanframetv

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prayer times for the weather location, from Aladhan (free, no API key).
 * Off until the owner turns on Prayer times and a location is set; then api.aladhan.com is contacted at most once a
 * day (today and tomorrow together), with only the place's coordinates. Times are local to the place.
 */
class PrayerFeed(context: Context, private val weather: WeatherFeed, private val onUpdated: () -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("cleanframe", Context.MODE_PRIVATE)
    @Volatile private var enabled = false

    fun setEnabled(on: Boolean) { enabled = on; if (on) refresh() }

    /** `{"date":"26-10-2026","t":{"Fajr":"04:12",…},"next":"04:13"}` for today, or "" when off or not downloaded yet. */
    fun json(): String {
        if (!enabled) return ""
        refresh()
        val saved = prefs.getString(TIMES, "") ?: ""
        return if (saved.isNotEmpty() && JSONObject(saved).optString("date") == today()) saved else ""
    }

    fun close() = executor.shutdownNow()

    private fun refresh() {
        val saved = prefs.getString(TIMES, "") ?: ""
        if (saved.isNotEmpty() && JSONObject(saved).optString("date") == today()) return
        if (System.currentTimeMillis() - prefs.getLong(CHECKED, 0L) < RETRY_MS) return
        val place = weather.coordinates()
        if (place == null) { weather.json(); return }
        if (!running.compareAndSet(false, true)) return
        prefs.edit().putLong(CHECKED, System.currentTimeMillis()).apply()
        executor.execute {
            try {
                val lat = place.getDouble("lat"); val lon = place.getDouble("lon")
                val cal = Calendar.getInstance()
                val day = format(cal)
                cal.add(Calendar.DAY_OF_MONTH, 1)
                val times = parse(String(get(url(day, lat, lon)), Charsets.UTF_8))
                val next = parse(String(get(url(format(cal), lat, lon)), Charsets.UTF_8))
                if (times != null && next != null) {
                    prefs.edit().putString(TIMES, resultJson(day, times, next)).apply()
                    onUpdated()
                }
            } catch (e: Exception) {
                Log.w(AndroidCatalog.TAG, "Prayer times not updated: ${e.message}")
            } finally { running.set(false) }
        }
    }

    private fun today() = format(Calendar.getInstance())

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
        private const val TIMES = "prayer_times"
        private const val CHECKED = "prayer_checked"
        private const val RETRY_MS = 10 * 60 * 1000L
        private const val MAX_JSON = 64 * 1024
        /** Aladhan method 8: Gulf Region (Bahrain, Kuwait, Qatar, UAE, Oman). */
        private const val METHOD = 8
        val NAMES = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

        private fun format(cal: Calendar) = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(cal.time)

        fun url(date: String, lat: Double, lon: Double) =
            "https://api.aladhan.com/v1/timings/$date?latitude=$lat&longitude=$lon&method=$METHOD"

        /** The five prayer times as HH:mm from an Aladhan response (a zone suffix such as "(+03)" is dropped), or null. */
        fun parse(body: String): Map<String, String>? {
            val timings = try { JSONObject(body).optJSONObject("data")?.optJSONObject("timings") } catch (e: Exception) { null } ?: return null
            val out = LinkedHashMap<String, String>()
            for (name in NAMES) {
                val match = Regex("""(\d{1,2}):(\d{2})""").find(timings.optString(name)) ?: return null
                out[name] = "%02d:%s".format(match.groupValues[1].toInt(), match.groupValues[2])
            }
            return out
        }

        fun resultJson(date: String, today: Map<String, String>, tomorrow: Map<String, String>): String =
            JSONObject().put("date", date).put("t", JSONObject(today as Map<*, *>)).put("next", tomorrow["Fajr"]).toString()
    }
}
