package io.github.mdrzn.cleanframetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PexelsFeedTest {
    private fun json(original: String) =
        """{"photos":[{"id":42,"photographer":"Ana","alt":"Quiet lake","src":{"original":"$original","large2x":"https://images.pexels.com/x?w=940"}}]}"""

    @Test fun usesTheOriginalFileAndCreditsThePhotographer() {
        val item = PexelsFeed.parse(json("https://images.pexels.com/photos/42/p.jpeg"), "lake")!!
        assertEquals("https://images.pexels.com/photos/42/p.jpeg", item.url)
        assertEquals("42", item.photoId)
        assertEquals("Quiet lake", item.title)
        assertEquals("Photo by Ana on Pexels", item.caption)
    }

    @Test fun rejectsOtherHostsAndPlainHttp() {
        assertNull(PexelsFeed.parse(json("https://example.com/p.jpeg"), "lake"))
        assertNull(PexelsFeed.parse(json("http://images.pexels.com/p.jpeg"), "lake"))
        assertNull(PexelsFeed.parse("""{"photos":[]}""", "lake"))
    }

    @Test fun keywordsAreTrimmedDedupedAndCapped() {
        assertEquals(listOf("ocean", "city at night"), PexelsFeed.parseKeywords(" ocean ,, city   at night, ocean "))
        assertEquals(10, PexelsFeed.parseKeywords((1..15).joinToString(",") { "k$it" }).size)
    }

    @Test fun searchUrlEncodesTheKeyword() {
        assertEquals("https://img.fn.xyz.dev/?query=city+at+night&orientation=landscape&per_page=1&page=3", PexelsFeed.searchUrl("city at night", 3))
    }

    @Test fun longDescriptionsAreShortenedAtAWord() {
        assertEquals("A serene view of the Hungarian…", PexelsFeed.shorten("A serene view of the Hungarian countryside with lush greenery"))
        assertEquals("Quiet lake", PexelsFeed.shorten("Quiet lake"))
    }

    @Test fun keepsAtLeastTwelveAndTwoPerKeyword() {
        assertEquals(12, PexelsFeed.keepFor(1))
        assertEquals(12, PexelsFeed.keepFor(6))
        assertEquals(20, PexelsFeed.keepFor(10))
    }

    @Test fun entryRecordsTheKeywordOnlyWhenGiven() {
        val meta = PhotoMeta("dark", "light", "light", 0.12, false)
        assert(meta.entryJson("pexels-1", "Lake", "c", "pexels", "city at night").contains("\"keyword\":\"city at night\""))
        assert(!meta.entryJson("pexels-1", "Lake", "c", "pexels").contains("keyword"))
    }
}
