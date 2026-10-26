package io.github.mdrzn.cleanframetv

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * The downloaded photo catalogue as served to the web interface. Each source (Bing, Pexels, uploads)
 * owns the entries whose id starts with its prefix; [update] rewrites only those.
 */
class Photos(context: Context) {
    val root = File(context.filesDir, "photos").apply { mkdirs() }
    val catalogue = File(root, "photos.json")

    fun json(): String = try { if (catalogue.isFile) catalogue.readText() else "[]" } catch (e: Exception) { "[]" }

    /** Entry JSON by id, in catalogue order. */
    private fun entries(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val existing = JSONArray(json())
            for (i in 0 until existing.length()) existing.optJSONObject(i)?.let { out[it.optString("id")] = it.toString() }
        } catch (e: Exception) { /* rebuild */ }
        return out
    }

    fun has(id: String) = File(root, "wallpapers/$id.jpg").isFile

    /**
     * Gives [change] this source's entries (id -> JSON, newest first) to edit in place, then keeps the newest [keep]
     * by id, deletes the files of dropped photos and writes the catalogue. Returns whether anything changed.
     */
    @Synchronized fun update(prefix: String, keep: Int, change: (MutableMap<String, String>) -> Boolean): Boolean {
        val all = entries()
        val mine = LinkedHashMap(all.filterKeys { it.startsWith(prefix) })
        var changed = change(mine)
        val kept = mine.keys.sortedDescending().take(keep).toSet()
        for (id in all.keys.filter { it.startsWith(prefix) && it !in kept }) { deleteFiles(id); changed = true }
        for (id in mine.keys.filter { it !in kept }) deleteFiles(id)
        if (!changed) return false
        val others = all.filterKeys { !it.startsWith(prefix) }.values
        val tmp = File(root, "photos.json.tmp")
        tmp.writeText((kept.sortedDescending().map { mine.getValue(it) } + others).joinToString(",", "[", "]"))
        tmp.renameTo(catalogue)
        return true
    }

    fun deleteFiles(id: String) {
        listOf("wallpapers/$id.jpg", "wallpapers/$id-thumb.jpg", "wallpapers/$id-panel.jpg",
            "materials/$id-field-blur.jpg", "materials/$id-field-frost.jpg", "materials/$id-field-clear.jpg", "materials/$id-study-soft.jpg")
            .forEach { File(root, it).delete() }
    }
}
