package io.github.mdrzn.cleanframetv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherFeedTest {
    private fun body(current: String) = JSONObject("""{"current":$current}""")

    @Test fun readsTemperatureConditionAndDaylight() {
        val reading = JSONObject(WeatherFeed.readingJson("Bahrain", "Manama", body("""{"temperature_2m":31.6,"weather_code":1,"is_day":0}"""))!!)
        assertEquals("Manama", reading.getString("place"))
        assertEquals(32, reading.getInt("temp"))
        assertEquals(1, reading.getInt("code"))
        assertEquals(false, reading.getBoolean("day"))
    }

    @Test fun fallsBackToTheTypedPlaceAndAssumesDaytime() {
        val reading = JSONObject(WeatherFeed.readingJson("Bahrain", "", body("""{"temperature_2m":-0.4,"weather_code":0}"""))!!)
        assertEquals("Bahrain", reading.getString("place"))
        assertEquals(0, reading.getInt("temp"))
        assertEquals(true, reading.getBoolean("day"))
    }

    @Test fun rejectsResponsesWithoutACurrentReading() {
        assertNull(WeatherFeed.readingJson("x", "x", JSONObject("""{}""")))
        assertNull(WeatherFeed.readingJson("x", "x", body("""{"temperature_2m":20}""")))
    }

    @Test fun urlsEncodeThePlaceAndOnlyUseOpenMeteo() {
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=Abu+Dhabi%26x&count=1&language=en&format=json",
            WeatherFeed.geocodeUrl("Abu Dhabi&x")
        )
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=26.2&longitude=50.6&current=temperature_2m,weather_code,is_day&timezone=auto",
            WeatherFeed.forecastUrl(26.2, 50.6)
        )
    }
}
