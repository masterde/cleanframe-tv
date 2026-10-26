package io.github.mdrzn.cleanframetv

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Bing photo of the day: checks at most every 12 hours, keeps the newest [KEEP] photos ready for the interface. */
class BingFeed(private val context: Context, private val photos: Photos, private val onUpdated: () -> Unit) {
    data class Item(val id: String, val url: String, val title: String, val caption: String)

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("cleanframe", Context.MODE_PRIVATE)

    /** The interface's photo source. Bing is only contacted while it is "bing" (or on an explicit refresh). */
    fun setSource(source: String) {
        prefs.edit().putString(SOURCE, source).apply()
        if (source == BING) refresh(force = false)
    }

    fun refresh(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && prefs.getString(SOURCE, null) != BING) return
        if (!force && now - prefs.getLong(CHECKED, 0L) < INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val changed = update()
                prefs.edit().putLong(CHECKED, System.currentTimeMillis()).apply()
                if (changed) onUpdated()
            } catch (e: Exception) {
                Log.w(AndroidCatalog.TAG, "Bing photos not updated: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    fun close() = executor.shutdownNow()

    private fun update(): Boolean {
        val locale = Locale.getDefault()
        val items = parse(String(get(feedUrl(locale.language, locale.country)), Charsets.UTF_8)).take(KEEP)
        if (items.isEmpty()) return false
        return photos.update("bing-", KEEP) { entries ->
            var changed = false
            for (item in items) {
                if (entries.containsKey(item.id) && photos.has(item.id)) continue
                val download = File(context.cacheDir, "${item.id}.download")
                try {
                    download.writeBytes(get(item.url))
                    val meta = PhotoMaker.make(download, photos.root, item.id)
                    entries[item.id] = meta.entryJson(item.id, item.title, item.caption)
                    changed = true
                    Log.i(AndroidCatalog.TAG, "Bing photo ready: ${item.id} (${item.title})")
                } finally {
                    download.delete()
                }
            }
            changed
        }
    }

    private fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "CleanFrameTV/0.1 (Android TV launcher)")
        try {
            if (connection.responseCode != 200) throw IllegalStateException("HTTP ${connection.responseCode} for $url")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val KEEP = 8
        private const val INTERVAL_MS = 12L * 60 * 60 * 1000
        private const val CHECKED = "bingChecked"
        private const val SOURCE = "photoSource"
        private const val BING = "bing"

        fun feedUrl(language: String, country: String): String {
            val market = if (language.isNotBlank() && country.isNotBlank()) "$language-$country" else "en-US"
            return "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=$KEEP&mkt=$market"
        }

        /** Newest first; entries without a valid date or image path are skipped. */
        fun parse(json: String): List<Item> {
            val images = JSONObject(json).optJSONArray("images") ?: return emptyList()
            val out = ArrayList<Item>()
            for (i in 0 until images.length()) {
                val image = images.optJSONObject(i) ?: continue
                val date = image.optString("startdate")
                val urlbase = image.optString("urlbase")
                if (!Regex("\\d{8}").matches(date) || !urlbase.startsWith("/")) continue
                val caption = image.optString("copyright").trim()
                val place = caption.substringBefore(" (©").trim()
                val title = image.optString("title").trim().ifEmpty { place.ifEmpty { "Bing photo of the day" } }
                out += Item("bing-$date", "https://www.bing.com${urlbase}_1920x1080.jpg", title, caption)
            }
            return out.sortedByDescending { it.id }
        }
    }
}
