package io.github.mdrzn.cleanframetv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrayerFeedTest {
    private fun body(fajr: String = "04:12 (+03)") =
        """{"data":{"timings":{"Fajr":"$fajr","Sunrise":"05:30","Dhuhr":"11:25","Asr":"14:48","Maghrib":"17:20","Isha":"18:50"}}}"""

    @Test fun readsTheFivePrayersAndDropsZoneSuffixes() {
        val times = PrayerFeed.parse(body())!!
        assertEquals(listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"), times.keys.toList())
        assertEquals("04:12", times["Fajr"])
        assertEquals("14:48", times["Asr"])
    }

    @Test fun padsSingleDigitHours() {
        assertEquals("04:05", PrayerFeed.parse(body("4:05"))!!["Fajr"])
    }

    @Test fun rejectsIncompleteOrInvalidResponses() {
        assertNull(PrayerFeed.parse("""{"data":{"timings":{"Fajr":"04:12"}}}"""))
        assertNull(PrayerFeed.parse("not json"))
        assertNull(PrayerFeed.parse("""{}"""))
    }

    @Test fun resultCarriesTodayAndTomorrowsFajr() {
        val today = PrayerFeed.parse(body())!!
        val tomorrow = PrayerFeed.parse(body("04:13"))!!
        val json = JSONObject(PrayerFeed.resultJson("26-10-2026", today, tomorrow))
        assertEquals("26-10-2026", json.getString("date"))
        assertEquals("11:25", json.getJSONObject("t").getString("Dhuhr"))
        assertEquals("04:13", json.getString("next"))
    }

    @Test fun urlUsesCoordinatesAndTheGulfMethod() {
        assertEquals("https://api.aladhan.com/v1/timings/26-10-2026?latitude=26.2&longitude=50.6&method=8", PrayerFeed.url("26-10-2026", 26.2, 50.6))
    }
}
