package io.github.mdrzn.cleanframetv

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Pexels photos for the owner's keywords, through the owner's search function (which holds the API key).
 * Adds one photo per check (at most every 30 minutes), cycling through the keywords, and keeps the newest [keepFor].
 * Each photo remembers its keyword so Home can rotate through the keywords, one background per rotation step.
 * Only the function and images.pexels.com are contacted, and only while the source is "pexels".
 */
class PexelsFeed(private val context: Context, private val photos: Photos, private val onUpdated: () -> Unit) {
    data class Item(val photoId: String, val url: String, val title: String, val caption: String)

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("cleanframe", Context.MODE_PRIVATE)

    val keywords: String
        get() = prefs.getString(KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS

    fun setSource(source: String) { if (source == PEXELS) refresh(force = false) }

    /** New keywords replace the current Pexels photos with one fresh photo per keyword. */
    fun setKeywords(text: String) {
        val clean = parseKeywords(text).joinToString(", ")
        if (clean.isEmpty() || clean == keywords) return
        prefs.edit().putString(KEYWORDS, clean).putInt(NEXT, 0).putLong(CHECKED, 0L).apply()
        photos.update(PREFIX, keepFor(parseKeywords(clean).size)) { it.clear(); true }
        onUpdated()
        executor.execute { running.set(true); try { repeat(parseKeywords(clean).size) { add() } } catch (e: Exception) { Log.w(AndroidCatalog.TAG, "Pexels photos not updated: ${e.message}") } finally { running.set(false) } }
    }

    fun refresh(force: Boolean) {
        if (!force && prefs.getString(SOURCE, null) != PEXELS) return
        if (!force && System.currentTimeMillis() - prefs.getLong(CHECKED, 0L) < INTERVAL_MS) return
        run { add() }
    }

    fun close() = executor.shutdownNow()

    private fun run(work: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try { work() } catch (e: Exception) { Log.w(AndroidCatalog.TAG, "Pexels photos not updated: ${e.message}") } finally { running.set(false) }
        }
    }

    /** Fetches the next keyword's photo; a photo already on the TV is skipped. */
    private fun add() {
        val words = parseKeywords(keywords)
        if (words.isEmpty()) return
        val index = prefs.getInt(NEXT, 0) % words.size
        prefs.edit().putInt(NEXT, index + 1).putLong(CHECKED, System.currentTimeMillis()).apply()
        val item = parse(String(get(searchUrl(words[index], Random.nextInt(1, PAGES + 1)), MAX_JSON), Charsets.UTF_8), words[index]) ?: return
        val id = "$PREFIX${System.currentTimeMillis()}-${item.photoId}"
        val download = File(context.cacheDir, "$id.download")
        if (photos.json().contains("-${item.photoId}\"")) return
        try {
            download.writeBytes(get(item.url, MAX_IMAGE))
            val entry = PhotoMaker.make(download, photos.root, id).entryJson(id, item.title, item.caption, PEXELS, words[index])
            photos.update(PREFIX, keepFor(words.size)) { it[id] = entry; true }
            Log.i(AndroidCatalog.TAG, "Pexels photo ready: $id (${item.title})")
            onUpdated()
        } finally {
            download.delete()
        }
    }

    private fun get(url: String, limit: Int): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "CleanFrameTV/0.2 (Android TV launcher)")
        try {
            if (connection.responseCode != 200) throw IllegalStateException("HTTP ${connection.responseCode}")
            if (connection.contentLengthLong > limit) throw IOException("Response too large")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > limit) throw IOException("Response too large")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PEXELS = "pexels"
        const val PREFIX = "pexels-"
        private const val MIN_KEEP = 12

        /** Enough photos for two per keyword, never fewer than [MIN_KEEP]. */
        fun keepFor(keywordCount: Int): Int = maxOf(MIN_KEEP, keywordCount * 2)
        const val ENDPOINT = "https://img.fn.xyz.dev/"
        const val DEFAULT_KEYWORDS = "landscape, nature"
        private const val PAGES = 20
        private const val INTERVAL_MS = 30L * 60 * 1000
        private const val MAX_JSON = 1 shl 20
        private const val MAX_IMAGE = 50 shl 20
        private const val KEYWORDS = "pexelsKeywords"
        private const val NEXT = "pexelsNext"
        private const val CHECKED = "pexelsChecked"
        private const val SOURCE = "photoSource"

        /** Comma-separated, trimmed, at most 10 keywords of 60 characters. */
        fun parseKeywords(text: String): List<String> =
            text.split(',', '\n').map { it.trim().replace(Regex("\\s+"), " ").take(60) }.filter { it.isNotEmpty() }.distinct().take(10)

        /** Pexels descriptions are sentences; Settings rows need a short name. Cuts at a word boundary. */
        fun shorten(text: String, max: Int = 40): String {
            if (text.length <= max) return text
            val cut = text.take(max + 1).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ')
            return (if (cut.length >= max / 2) cut else text.take(max)) + "…"
        }

        /** `page` and `orientation` are passed on in case the function forwards them to Pexels. */
        fun searchUrl(keyword: String, page: Int): String =
            ENDPOINT + "?query=" + URLEncoder.encode(keyword, "UTF-8") + "&orientation=landscape&per_page=1&page=$page"

        /** The first photo, using its largest ("original") file; only https images.pexels.com URLs are accepted. */
        fun parse(json: String, keyword: String): Item? {
            val photo = JSONObject(json).optJSONArray("photos")?.optJSONObject(0) ?: return null
            val url = photo.optJSONObject("src")?.optString("original").orEmpty()
            val parsed = try { URL(url) } catch (e: Exception) { return null }
            if (parsed.protocol != "https" || parsed.host != "images.pexels.com") return null
            val id = photo.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: return null
            val by = photo.optString("photographer").trim()
            val title = shorten(photo.optString("alt").trim().trimEnd('.')).ifEmpty { keyword.replaceFirstChar { it.uppercase() } }
            return Item(id, url, title, if (by.isEmpty()) "Photo from Pexels" else "Photo by $by on Pexels")
        }
    }
}
