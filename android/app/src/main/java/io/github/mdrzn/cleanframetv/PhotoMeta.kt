package io.github.mdrzn.cleanframetv

/**
 * Foreground decisions for a downloaded photo, in the same terms as app/assets/wallpapers/catalog.js:
 * overall tone, ink treatment behind the top-right/bottom-right controls ("light" or "mist" = stronger halo),
 * frost veil strength and whether the clock needs mist.
 */
data class PhotoMeta(val tone: String, val tr: String, val br: String, val frostVeil: Double, val clockMist: Boolean) {

    fun entryJson(id: String, name: String, caption: String, source: String = "bing", keyword: String = ""): String =
        """{"id":${Catalog.quote(id)},"file":${Catalog.quote(id)},"base":"/photos/","source":${Catalog.quote(source)},${if (keyword.isEmpty()) "" else """"keyword":${Catalog.quote(keyword)},"""}"name":${Catalog.quote(name)},"caption":${Catalog.quote(caption)},""" +
            """"tone":"$tone","ut":{"tr":"$tr","br":"$br"},"frostVeil":$frostVeil,"listColumn":0,"dockQuiet":false,"clockMist":$clockMist}"""

    companion object {
        /** [lum] is a w x h grid of relative luminance (0..1), row-major. */
        fun from(lum: FloatArray, w: Int, h: Int): PhotoMeta {
            fun mean(x0: Double, x1: Double, y0: Double, y1: Double): Double {
                var sum = 0.0; var n = 0
                for (y in 0 until h) for (x in 0 until w) {
                    val fx = (x + 0.5) / w; val fy = (y + 0.5) / h
                    if (fx >= x0 && fx < x1 && fy >= y0 && fy < y1) { sum += lum[y * w + x]; n++ }
                }
                return if (n == 0) 0.0 else sum / n
            }
            val all = mean(0.0, 1.0, 0.0, 1.0)
            val topRight = mean(0.6, 1.0, 0.0, 0.25)
            val bottomRight = mean(0.6, 1.0, 0.7, 1.0)
            val topLeft = mean(0.0, 0.35, 0.0, 0.25)
            return PhotoMeta(
                tone = if (all > 0.55) "light" else "dark",
                tr = if (topRight > 0.62) "mist" else "light",
                br = if (bottomRight > 0.62) "mist" else "light",
                frostVeil = if (all > 0.6) 0.20 else 0.12,
                clockMist = topLeft > 0.6,
            )
        }
    }
}
