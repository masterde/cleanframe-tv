package io.github.mdrzn.cleanframetv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Builds the image set the web interface expects for a photo (see docs/assets.md), once per download:
 * wallpapers/<id>.jpg, <id>-thumb.jpg, <id>-panel.jpg and materials/<id>-field-blur|field-frost|field-clear|study-soft.jpg.
 * Blur is computed at quarter resolution and scaled up, which is visually identical for wide blurs and fast on TV chips.
 */
object PhotoMaker {
    private const val W = 1920
    private const val H = 1080
    private const val QW = W / 4
    private const val QH = H / 4

    fun make(source: File, root: File, id: String): PhotoMeta {
        val walls = File(root, "wallpapers").apply { mkdirs() }
        val mats = File(root, "materials").apply { mkdirs() }
        // Large originals (Pexels, uploads) are subsampled while decoding so they never need more than ~2x the output in memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unreadable image")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= W && bounds.outHeight / (sample * 2) >= H) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample })
            ?: throw IOException("Unreadable image")
        val full = cover(decoded, W, H).also { if (it !== decoded) decoded.recycle() }
        save(full, File(walls, "$id.jpg"), 90)
        scaled(full, 512, 288).let { save(it, File(walls, "$id-thumb.jpg"), 85); it.recycle() }

        val quarter = scaled(full, QW, QH)
        val base = IntArray(QW * QH).also { quarter.getPixels(it, 0, QW, 0, 0, QW, QH) }
        quarter.recycle()
        val meta = PhotoMeta.from(luminanceGrid(base, QW, QH, 48, 27), 48, 27)

        material(base, sigma = 7.0, saturation = 0.92f, scrim = false, File(mats, "$id-field-blur.jpg"))
        val frost = material(base, sigma = 10.5, saturation = 1.0f, scrim = false, File(mats, "$id-field-frost.jpg"))
        material(base, sigma = 3.5, saturation = 0.95f, scrim = true, File(mats, "$id-study-soft.jpg"))
        scaled(frost, 320, 180).let { save(it, File(walls, "$id-panel.jpg"), 85); it.recycle() }
        frost.recycle()

        // Clear: a light 2px softening at half resolution with a touch more saturation.
        val half = scaled(full, W / 2, H / 2)
        val hp = IntArray((W / 2) * (H / 2)).also { half.getPixels(it, 0, W / 2, 0, 0, W / 2, H / 2) }
        saturate(hp, 1.06f)
        half.setPixels(hp, 0, W / 2, 0, 0, W / 2, H / 2)
        scaled(half, W, H).let { save(it, File(mats, "$id-field-clear.jpg"), 88); it.recycle() }
        half.recycle()
        full.recycle()
        return meta
    }

    private fun material(base: IntArray, sigma: Double, saturation: Float, scrim: Boolean, out: File): Bitmap {
        val px = base.copyOf()
        gaussian(px, QW, QH, sigma)
        if (saturation != 1f) saturate(px, saturation)
        if (scrim) lowerScrim(px, QW, QH)
        val small = Bitmap.createBitmap(px, QW, QH, Bitmap.Config.ARGB_8888)
        scaled(small, W, H).let { save(it, out, 88); it.recycle() }
        return small
    }

    /** Scale to cover w x h and centre-crop. */
    private fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).roundToInt().coerceAtMost(src.width)
        val ch = (h / scale).roundToInt().coerceAtMost(src.height)
        val left = (src.width - cw) / 2
        val top = (src.height - ch) / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(left, top, left + cw, top + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun scaled(src: Bitmap, w: Int, h: Int): Bitmap = Bitmap.createScaledBitmap(src, w, h, true)

    private fun save(bitmap: Bitmap, file: File, quality: Int) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) throw IOException("Could not write $file")
    }

    fun luminanceGrid(px: IntArray, w: Int, h: Int, gw: Int, gh: Int): FloatArray {
        val sums = DoubleArray(gw * gh); val counts = IntArray(gw * gh)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val l = (0.2126 * (c shr 16 and 255) + 0.7152 * (c shr 8 and 255) + 0.0722 * (c and 255)) / 255.0
            val cell = (y * gh / h) * gw + (x * gw / w)
            sums[cell] += l; counts[cell]++
        }
        return FloatArray(gw * gh) { if (counts[it] == 0) 0f else (sums[it] / counts[it]).toFloat() }
    }

    private fun saturate(px: IntArray, s: Float) {
        for (i in px.indices) {
            val c = px[i]
            val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            px[i] = (c and -0x1000000) or (clamp(l + (r - l) * s) shl 16) or (clamp(l + (g - l) * s) shl 8) or clamp(l + (b - l) * s)
        }
    }

    /** The Dock "Soft" recipe darkens the lower half towards the bottom edge. */
    private fun lowerScrim(px: IntArray, w: Int, h: Int) {
        for (y in h / 2 until h) {
            val k = 1f - 0.42f * ((y - h / 2f) / (h / 2f)).pow(1.5f)
            for (x in 0 until w) {
                val i = y * w + x; val c = px[i]
                px[i] = (c and -0x1000000) or (clamp((c shr 16 and 255) * k) shl 16) or (clamp((c shr 8 and 255) * k) shl 8) or clamp((c and 255) * k)
            }
        }
    }

    private fun clamp(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else v.toInt()

    /** Gaussian approximation: three box blurs per axis (Kutskir), edges clamped. */
    private fun gaussian(px: IntArray, w: Int, h: Int, sigma: Double) {
        val n = 3
        val ideal = sqrt(12 * sigma * sigma / n + 1)
        var wl = floor(ideal).toInt(); if (wl % 2 == 0) wl--
        val wu = wl + 2
        val m = ((12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4 * wl - 4)).roundToInt()
        val tmp = IntArray(px.size)
        for (i in 0 until n) {
            val r = ((if (i < m) wl else wu) - 1) / 2
            boxH(px, tmp, w, h, r); boxV(tmp, px, w, h, r)
        }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val c = src[row + k.coerceIn(0, w - 1)]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255 }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]; val sub = src[row + (x - r).coerceAtLeast(0)]
                sr += (add shr 16 and 255) - (sub shr 16 and 255); sg += (add shr 8 and 255) - (sub shr 8 and 255); sb += (add and 255) - (sub and 255)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val d = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val c = src[k.coerceIn(0, h - 1) * w + x]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255 }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
                val add = src[(y + r + 1).coerceAtMost(h - 1) * w + x]; val sub = src[(y - r).coerceAtLeast(0) * w + x]
                sr += (add shr 16 and 255) - (sub shr 16 and 255); sg += (add shr 8 and 255) - (sub shr 8 and 255); sb += (add and 255) - (sub and 255)
            }
        }
    }
}
