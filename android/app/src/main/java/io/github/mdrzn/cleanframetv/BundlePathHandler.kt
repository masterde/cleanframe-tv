package io.github.mdrzn.cleanframetv

import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream

/**
 * Serves `/assets/...` from a downloaded bundle directory. A file the bundle does not contain, a path that would leave the directory,
 * or any error goes to [fallback] (the interface built into the APK), so a broken bundle cannot leave a black screen.
 */
class BundlePathHandler(private val dir: File, private val fallback: WebViewAssetLoader.PathHandler) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? = try {
        val file = BundleStore.resolve(dir, path)
        if (file == null) fallback.handle(path) else {
            val type = mime(file.name)
            WebResourceResponse(type, if (type.startsWith("text/") || type == "application/json") "utf-8" else null, FileInputStream(file))
        }
    } catch (e: Exception) {
        Log.w(AndroidCatalog.TAG, "Bundle file $path failed: ${e.message}")
        fallback.handle(path)
    }

    private fun mime(name: String): String {
        return when (val ext = name.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "txt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
