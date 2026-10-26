package io.github.mdrzn.cleanframetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMetaTest {
    private fun grid(w: Int, h: Int, f: (Int, Int) -> Float) = FloatArray(w * h) { f(it % w, it / w) }

    @Test fun darkPhotoUsesLightInkEverywhere() {
        val meta = PhotoMeta.from(grid(48, 27) { _, _ -> 0.18f }, 48, 27)
        assertEquals("dark", meta.tone)
        assertEquals("light", meta.tr); assertEquals("light", meta.br)
        assertFalse(meta.clockMist)
        assertEquals(0.12, meta.frostVeil, 1e-9)
    }

    @Test fun brightSkyNeedsMistAtTheTop() {
        // Bright top third (sky), dark lower part.
        val meta = PhotoMeta.from(grid(48, 27) { _, y -> if (y < 9) 0.9f else 0.25f }, 48, 27)
        assertEquals("mist", meta.tr)
        assertTrue(meta.clockMist)
        assertEquals("light", meta.br)
    }

    @Test fun brightPhotoIsLightToneWithStrongerVeil() {
        val meta = PhotoMeta.from(grid(48, 27) { _, _ -> 0.8f }, 48, 27)
        assertEquals("light", meta.tone)
        assertEquals("mist", meta.br)
        assertEquals(0.20, meta.frostVeil, 1e-9)
    }

    @Test fun jsonEntryMatchesTheWallpaperCatalogueShape() {
        val json = PhotoMeta("dark", "mist", "light", 0.12, true).entryJson("bing-20261020", "Un lago", "Lago (© X)")
        assertEquals(
            """{"id":"bing-20261020","file":"bing-20261020","base":"/photos/","source":"bing","name":"Un lago","caption":"Lago (© X)","tone":"dark","ut":{"tr":"mist","br":"light"},"frostVeil":0.12,"listColumn":0,"dockQuiet":false,"clockMist":true}""",
            json,
        )
    }
}
