package io.github.mdrzn.cleanframetv

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A tiny string store, so [BundleStore]'s state machine is tested without Android (SharedPreferences backs it on the TV). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * Downloaded web bundles (the files in app/), kept under `files/bundles/<version>` (design: docs/plans/2026-11-10-auto-update-design.md).
 * Version 0 means the interface built into the APK.
 *
 * Life of a bundle: [stage] makes it `pending`; the next page load serves it ([beginLaunch]); when the page reports it rendered
 * ([markReady]) it becomes `active` and the old active one is kept as `previous`. A pending bundle that never reports ready, or that
 * keeps getting relaunched without reporting, is marked bad ([markBad]) and never offered or served again.
 */
class BundleStore(private val root: File, private val kv: KeyValue) {
    val active: Int get() = int(ACTIVE)
    val previous: Int get() = int(PREVIOUS)
    val pending: Int get() = int(PENDING)
    val bad: Set<Int> get() = (kv.get(BAD) ?: "").split(',').mapNotNull { it.toIntOrNull() }.toSet()

    /** The newest version already on the TV (active or waiting), so it is not downloaded again. */
    val newestKnown: Int get() = maxOf(active, pending)

    /**
     * Picks what the next page load serves and counts the attempt. A pending bundle gets [MAX_TRIES] launches to report ready;
     * a missing directory is treated as a failure. Returns 0 (the APK's own interface) when nothing usable is stored.
     */
    @Synchronized fun beginLaunch(): Int {
        val p = pending
        if (p != 0) {
            val tries = int(TRIES) + 1
            if (tries > MAX_TRIES || !dir(p).isDirectory) markBad(p)
            else { kv.put(TRIES, tries.toString()); return p }
        }
        val a = active
        if (a != 0 && !dir(a).isDirectory) { markBad(a); return beginLaunch() }
        return a
    }

    /** The page for [version] rendered. Only a pending bundle changes state; any other call is ignored. */
    @Synchronized fun markReady(version: Int): Boolean {
        if (version == 0 || version != pending) return false
        kv.put(PREVIOUS, active.toString())
        kv.put(ACTIVE, version.toString())
        kv.put(PENDING, "0")
        kv.put(TRIES, "0")
        prune()
        return true
    }

    /** Never serve or offer [version] again. If it was active, fall back to the previous bundle (or the built-in one). */
    @Synchronized fun markBad(version: Int) {
        if (version == 0) return
        kv.put(BAD, (bad + version).joinToString(","))
        if (pending == version) { kv.put(PENDING, "0"); kv.put(TRIES, "0") }
        if (active == version) { kv.put(ACTIVE, previous.toString()); kv.put(PREVIOUS, "0") }
        if (previous == version) kv.put(PREVIOUS, "0")
        prune()
    }

    /**
     * Extracts [zip] to a temporary directory, checks it, renames it into place and makes it pending. On any failure nothing is left
     * behind and nothing changes. Throws [IOException] for a bad bundle.
     */
    @Synchronized fun stage(version: Int, zip: InputStream) {
        require(version > 0) { "version" }
        if (version in bad) throw IOException("Bundle $version was marked bad")
        root.mkdirs()
        val tmp = File(root, "$version.tmp")
        tmp.deleteRecursively()
        try {
            extract(zip, tmp, version)
            val target = dir(version)
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw IOException("Could not move the bundle into place")
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw if (e is IOException) e else IOException(e.message, e)
        }
        kv.put(PENDING, version.toString())
        kv.put(TRIES, "0")
        prune()
    }

    /** The directory to serve for [version], or null for the built-in interface. */
    fun dir(version: Int): File = File(root, version.toString())

    /** Keeps the active, previous and pending bundles; deletes everything else, including half-written `.tmp` directories. */
    private fun prune() {
        val keep = setOf(active, previous, pending).filter { it != 0 }.map { it.toString() }.toSet()
        root.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
    }

    private fun int(key: String) = kv.get(key)?.toIntOrNull() ?: 0

    companion object {
        const val MAX_TRIES = 2
        const val MAX_ENTRIES = 1000
        const val MAX_BYTES = 100L * 1024 * 1024
        private const val ACTIVE = "bundleActive"
        private const val PREVIOUS = "bundlePrevious"
        private const val PENDING = "bundlePending"
        private const val TRIES = "bundleTries"
        private const val BAD = "bundleBad"

        /**
         * Unpacks a bundle zip into [dest] (which must not exist yet). Refuses absolute paths, `..`, backslashes, drive letters and anything
         * that resolves outside [dest]; caps the entry count and the unpacked size; and requires `index.html` and a `bundle.json` whose
         * version is [version]. Throws [IOException] and leaves [dest] for the caller to delete.
         */
        fun extract(input: InputStream, dest: File, version: Int) {
            dest.mkdirs()
            val base = dest.canonicalFile
            var entries = 0
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw IOException("Too many files in the bundle")
                    val name = entry.name
                    if (name.isEmpty() || name.startsWith("/") || name.contains('\\') || name.contains(':') || name.split('/').any { it == ".." }) {
                        throw IOException("Unsafe path in the bundle")
                    }
                    val target = File(base, name).canonicalFile
                    if (target != base && !target.path.startsWith(base.path + File.separator)) throw IOException("Unsafe path in the bundle")
                    if (entry.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("The bundle is too large")
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            if (!File(base, "index.html").isFile) throw IOException("The bundle has no index.html")
            val meta = File(base, "bundle.json")
            if (!meta.isFile) throw IOException("The bundle has no bundle.json")
            val declared = try { JSONObject(meta.readText()).getInt("version") } catch (e: Exception) { throw IOException("bundle.json is not readable") }
            if (declared != version) throw IOException("The bundle is not the version the manifest named")
        }

        /** The file inside [dir] for a request [path] such as `main.js`, or null if there is none or it would leave [dir]. */
        fun resolve(dir: File, path: String): File? {
            if (path.isEmpty() || path.contains('\\') || path.split('/').any { it == ".." }) return null
            val base = dir.canonicalFile
            val file = File(base, path).canonicalFile
            return if (file.path.startsWith(base.path + File.separator) && file.isFile) file else null
        }
    }
}
