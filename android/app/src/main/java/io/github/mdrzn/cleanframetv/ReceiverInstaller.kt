package io.github.mdrzn.cleanframetv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs and updates the open-source AirPlay receiver (github.com/jqssun/android-airplay-server, GPLv3) as its own app; its code is not part of
 * CleanFrame TV. Nothing is contacted until the owner presses OK on the Settings rows. The newest GitHub release is downloaded, and before Android is
 * asked to install it the file must be that package, signed with the project's pinned key ([PINNED_SIGNER]). Android shows its own confirmation, and
 * only accepts an update signed with the same key as the installed app.
 */
class ReceiverInstaller(
    private val context: Context,
    private val catalog: AndroidCatalog,
    private val notify: (String) -> Unit,
    private val changed: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var note = ""
    @Volatile private var busyText = ""

    val installedVersion: String?
        get() = try { context.packageManager.getPackageInfo(PACKAGE, 0).versionName } catch (e: Exception) { null }

    /** {"installed": bool, "version", "busy": bool, "text"} for the Settings rows. */
    fun json(): String {
        val version = installedVersion
        val text = when {
            busyText.isNotEmpty() -> busyText
            note.isNotEmpty() -> note
            version == null -> "Not installed. OK downloads and installs it"
            else -> "Installed · version $version. OK opens it"
        }
        return """{"installed":${version != null},"version":${Catalog.quote(version ?: "")},"busy":${busyText.isNotEmpty()},"text":${Catalog.quote(text)}}"""
    }

    /** OK on "AirPlay receiver": open it when installed, else download and install it. */
    fun primary() {
        if (installedVersion != null) {
            note = ""
            val error = catalog.launch(PACKAGE)
            if (error.isNotEmpty()) notify(error)
            changed()
        } else installLatest(updateOnly = false)
    }

    /** OK on "Update AirPlay receiver": install the newest release only if it is newer than what is installed. */
    fun update() = installLatest(updateOnly = true)

    private fun installLatest(updateOnly: Boolean) {
        if (!running.compareAndSet(false, true)) return
        note = ""
        executor.execute {
            val cache = File(context.cacheDir, "airplay-receiver.apk")
            try {
                busyText = "Checking GitHub…"; changed()
                val release = parseLatest(String(fetch(LATEST_URL, MAX_JSON), Charsets.UTF_8)) ?: throw IOException("No usable release")
                val have = installedVersion
                if (updateOnly && have != null && !isNewer(release.version, have)) {
                    note = "Up to date · version $have"; notify("The AirPlay receiver is up to date."); return@execute
                }
                // Only now, when something will actually be installed, ask for the "Install unknown apps" permission.
                if (!canInstall()) return@execute
                busyText = "Downloading ${release.version}…"; changed()
                download(release, cache)
                verify(release, cache)
                busyText = "Waiting for Android to confirm…"; changed()
                commit(cache)
            } catch (e: Exception) {
                Log.w(AndroidCatalog.TAG, "AirPlay receiver install failed: ${e.message}")
                note = "Couldn't install it. Try again later"
                notify("Couldn't download the AirPlay receiver: ${e.message}")
            } finally {
                cache.delete()
                busyText = ""; running.set(false); changed()
            }
        }
    }

    /** Android asks for "Install unknown apps" per app; send the owner to that screen once. Called only when an install is about to happen, never for a plain check. */
    private fun canInstall(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) return true
        notify("Allow CleanFrame TV to install apps, then press OK again.")
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.w(AndroidCatalog.TAG, "Unknown-sources screen unavailable", e) }
        return false
    }

    private fun fetch(url: String, limit: Int): ByteArray {
        var out = ByteArray(0)
        open(url) { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > limit) throw IOException("Response too large")
            }
            out = buffer.toByteArray()
        }
        return out
    }

    private fun download(release: Release, dest: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        open(release.url) { input ->
            dest.outputStream().use { out ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    count += n
                    if (count > release.size) throw IOException("Download larger than announced")
                    digest.update(chunk, 0, n)
                    out.write(chunk, 0, n)
                }
            }
        }
        if (count != release.size) throw IOException("Download size differs from the release")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && release.sha256 != sha) throw IOException("Download does not match GitHub's checksum")
    }

    /** The file must be the receiver package and signed by the pinned key alone. (Android itself refuses a downgrade or a different signer on update.) */
    private fun verify(release: Release, file: File) {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        if (info == null) throw IOException("The download is not an app")
        if (info.packageName != PACKAGE) throw IOException("The download is a different app")
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: throw IOException("The download is not signed")
            if (si.hasMultipleSigners()) throw IOException("The download has several signers")
            si.apkContentsSigners.map { it.toByteArray() }
        } else @Suppress("DEPRECATION") (info.signatures?.map { it.toByteArray() } ?: emptyList())
        if (!signerAccepted(signers.map { sha256Hex(it) })) throw IOException("The download is not signed by the project's key")
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(PACKAGE) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("airplay-receiver.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** Opens `url` (https, GitHub hosts only); redirects are followed by hand, a few at most, and only to allowed hosts. */
    private fun open(url: String, read: (java.io.InputStream) -> Unit) {
        var next = url
        for (hop in 0..MAX_REDIRECTS) {
            if (!allowedUrl(next)) throw IOException("URL not allowed")
            val connection = URL(next).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "CleanFrameTV (Android TV launcher)")
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            try {
                val code = connection.responseCode
                if (code in 301..308 && code != 304) { next = URL(URL(next), connection.getHeaderField("Location") ?: throw IOException("Redirect without a target")).toString(); continue }
                if (code != 200) throw IOException("HTTP $code")
                connection.inputStream.use(read)
                return
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    fun close() = executor.shutdownNow()

    data class Release(val version: String, val url: String, val size: Long, val sha256: String?)

    companion object {
        const val PACKAGE = "io.github.jqssun.airplay"
        const val LATEST_URL = "https://api.github.com/repos/jqssun/android-airplay-server/releases/latest"
        private const val DOWNLOAD_PREFIX = "https://github.com/jqssun/android-airplay-server/releases/download/"
        /** SHA-256 of the project's APK signing certificate (read from release v0.0.36). A release signed by anything else is refused. */
        const val PINNED_SIGNER = "64ca1fa39ddf9e0e89c57d73fb681d2a5b35ae20f071771e2eb401b5032ef1a9"
        const val MAX_JSON = 512 * 1024
        const val MAX_APK = 80L * 1024 * 1024
        private const val MAX_REDIRECTS = 4
        private val VERSION = Regex("^v?\\d+(\\.\\d+){0,3}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** `{tag_name, assets:[{name, size, browser_download_url, digest?}]}` from the GitHub releases API: the `app-release.apk` asset, or null when anything is off. */
        fun parseLatest(json: String): Release? = try {
            val o = JSONObject(json)
            val tag = o.getString("tag_name")
            if (!VERSION.matches(tag) || o.optBoolean("draft") || o.optBoolean("prerelease")) null else {
                val assets = o.getJSONArray("assets")
                var found: Release? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name") != "app-release.apk") continue
                    val url = a.getString("browser_download_url")
                    val size = a.getLong("size")
                    val digest = a.optString("digest", "").removePrefix("sha256:").lowercase().takeIf { HEX64.matches(it) }
                    if (url.startsWith(DOWNLOAD_PREFIX) && size in 1..MAX_APK) found = Release(tag.removePrefix("v"), url, size, digest)
                }
                found
            }
        } catch (e: Exception) { null }

        /** True when [a] is a later version than [b]; compares dot-separated numbers ("0.0.37" > "0.0.36", "0.1" > "0.0.99"). */
        fun isNewer(a: String, b: String): Boolean {
            val x = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrElse(i) { 0 }
                val q = y.getOrElse(i) { 0 }
                if (p != q) return p > q
            }
            return false
        }

        /** HTTPS on GitHub's own hosts only: the API, github.com (release download) and *.githubusercontent.com (where the redirect lands). */
        fun allowedUrl(url: String): Boolean = try {
            val u = URL(url)
            u.protocol == "https" && u.userInfo == null && (u.port == -1 || u.port == 443) &&
                (u.host == "api.github.com" || u.host == "github.com" || u.host.endsWith(".githubusercontent.com"))
        } catch (e: Exception) { false }

        /** Exactly one signer, and it is the pinned one. */
        fun signerAccepted(signerDigests: List<String>): Boolean = signerDigests.size == 1 && signerDigests[0].lowercase() == PINNED_SIGNER

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Receives the result of the install session: shows Android's confirmation screen, then reports success or failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                try { if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { Log.w(AndroidCatalog.TAG, "Could not show the install confirmation", e) }
            }
            PackageInstaller.STATUS_SUCCESS -> android.widget.Toast.makeText(context, "AirPlay receiver installed.", android.widget.Toast.LENGTH_LONG).show()
            else -> android.widget.Toast.makeText(context, "The AirPlay receiver wasn't installed.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    companion object { const val ACTION = "io.github.mdrzn.cleanframetv.INSTALL_RESULT" }
}
