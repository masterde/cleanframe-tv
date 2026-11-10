package io.github.mdrzn.cleanframetv

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Looks for a newer CleanFrame TV (design: docs/plans/2026-11-10-auto-update-design.md). It fetches the signed manifest and its signature
 * from the one configured HTTPS host, verifies them, and reports what is available. It downloads and installs nothing yet.
 * No request is made unless this build was given an update-signing public key.
 */
class UpdateChecker(
    private val context: Context,
    private val publicKey: String,
    private val baseUrl: String,
    private val installedApk: Int,
    private val installedName: String,
    private val notify: (String) -> Unit,
) {
    data class Outcome(val state: String, val text: String, val sequence: Long = 0)
    private class NotPublished : IOException("HTTP 404")

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("cleanframe", Context.MODE_PRIVATE)

    val enabled: Boolean get() = publicKey.isNotBlank() && baseUrl.startsWith("https://") && baseUrl.endsWith("/")

    /** {"state": off|never|current|available|error, "version", "text"} for Settings → Android TV → Updates. */
    fun json(): String {
        val state = if (!enabled) "off" else prefs.getString(STATE, "never") ?: "never"
        val last = prefs.getLong(LAST, 0L)
        val text = when {
            !enabled -> "Not set up in this build"
            state == "never" -> "Version $installedName. Not checked yet"
            else -> (prefs.getString(TEXT, "") ?: "") + if (last > 0) " · checked " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(last)) else ""
        }
        return """{"state":${Catalog.quote(state)},"version":${Catalog.quote(installedName)},"text":${Catalog.quote(text)}}"""
    }

    /** At launcher start: checks when the last successful check is a day old. */
    fun maybeCheck() {
        if (enabled && System.currentTimeMillis() - prefs.getLong(LAST, 0L) >= DAY_MS) check(manual = false)
    }

    fun check(manual: Boolean) {
        if (!enabled) { if (manual) notify("Updates aren't set up in this build."); return }
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val host = URL(baseUrl).host
                val manifest = get(baseUrl + "manifest.json", host, UpdateManifest.MAX_BYTES)
                val signature = String(get(baseUrl + "manifest.json.sig", host, 1024), Charsets.UTF_8)
                val outcome = evaluate(manifest, signature, publicKey, host, installedApk, installedName, 0, prefs.getLong(SEQ, 0L))
                val edit = prefs.edit().putString(STATE, outcome.state).putString(TEXT, outcome.text)
                if (outcome.state != "error") edit.putLong(LAST, System.currentTimeMillis()).putLong(SEQ, maxOf(outcome.sequence, prefs.getLong(SEQ, 0L)))
                edit.apply()
                if (manual || outcome.state == "available") notify(outcome.text)
            } catch (e: NotPublished) {
                // The updates branch or its files do not exist yet: not an error for the owner.
                prefs.edit().putString(STATE, "current").putString(TEXT, "Up to date · version $installedName (no updates published)").putLong(LAST, System.currentTimeMillis()).apply()
                if (manual) notify("No updates have been published yet.")
            } catch (e: Exception) {
                Log.w(AndroidCatalog.TAG, "Update check failed: ${e.message}")
                prefs.edit().putString(STATE, "error").putString(TEXT, "Couldn't check. Try again later").apply()
                if (manual) notify("Couldn't check for updates. Try again later.")
            } finally {
                running.set(false)
            }
        }
    }

    fun close() = executor.shutdownNow()

    /** Only https on [host]; redirects are not followed, so a moved file fails instead of leaving the host. */
    private fun get(url: String, host: String, limit: Int): ByteArray {
        if (!UpdateVerifier.allowedUrl(url, host)) throw IOException("URL not allowed")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "CleanFrameTV/$installedName (Android TV launcher)")
        try {
            if (connection.responseCode == 404) throw NotPublished()
            if (connection.responseCode != 200) throw IllegalStateException("HTTP ${connection.responseCode}")
            if (connection.contentLengthLong > limit) throw IOException("Response too large")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
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
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val STATE = "updState"
        private const val TEXT = "updText"
        private const val LAST = "updLast"
        private const val SEQ = "updSeq"

        /** Verifies and interprets the downloaded files. Pure, so it is tested without a network. [seenSequence] is the highest manifest accepted before. */
        fun evaluate(manifest: ByteArray, signature: String, publicKey: String, host: String, installedApk: Int, installedName: String, installedBundle: Int, seenSequence: Long): Outcome {
            if (!UpdateVerifier.verify(manifest, signature, publicKey)) return Outcome("error", "The update file could not be verified")
            val m = UpdateManifest.parse(manifest) ?: return Outcome("error", "The update file is not readable")
            val urls = listOfNotNull(m.bundle?.url, m.apk?.url)
            if (urls.any { !UpdateVerifier.allowedUrl(it, host) }) return Outcome("error", "The update file points somewhere it should not")
            if (m.sequence < seenSequence) return Outcome("error", "Ignored an older update file")
            val d = UpdateDecision.decide(m, installedApk, installedBundle, seenSequence - 1)
            val parts = listOfNotNull(d.apk?.let { "app ${it.versionName}" }, d.bundle?.let { "interface ${it.version}" })
            return if (parts.isEmpty()) Outcome("current", "Up to date · version $installedName", m.sequence)
            else Outcome("available", "Update available: " + parts.joinToString(" and "), m.sequence)
        }
    }
}
