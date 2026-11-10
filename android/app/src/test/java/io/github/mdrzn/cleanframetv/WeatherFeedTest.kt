package io.github.mdrzn.cleanframetv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherFeedTest {
    private fun body(current: String) = JSONObject("""{"current":$current}""")

    @Test fun readsTemperatureConditionAndDaylight() {
        val reading = JSONObject(WeatherFeed.readingJson("Bahrain", "Manama", body("""{"temperature_2m":31.6,"weather_code":1,"is_day":0}"""))!!)
        assertEquals("what the owner typed wins over the geocoder's spelling", "Bahrain", reading.getString("place"))
        assertEquals(32, reading.getInt("temp"))
        assertEquals(1, reading.getInt("code"))
        assertEquals(false, reading.getBoolean("day"))
    }

    @Test fun addsTodaysHighAndLowWhenPresent() {
        val body = JSONObject("""{"current":{"temperature_2m":30.2,"weather_code":0,"is_day":1},"daily":{"temperature_2m_max":[33.6],"temperature_2m_min":[24.4]}}""")
        val reading = JSONObject(WeatherFeed.readingJson("Bahrain", "Manama", body)!!)
        assertEquals(34, reading.getInt("hi"))
        assertEquals(24, reading.getInt("lo"))
        assertEquals(false, JSONObject(WeatherFeed.readingJson("x", "x", body("""{"temperature_2m":20,"weather_code":0}"""))!!).has("hi"))
    }

    @Test fun usesTheGeocodersNameOnlyWhenNothingWasTyped() {
        assertEquals("Manama", JSONObject(WeatherFeed.readingJson("", "Manama", body("""{"temperature_2m":20,"weather_code":0}"""))!!).getString("place"))
    }

    @Test fun fallsBackToTheTypedPlaceAndAssumesDaytime() {
        val reading = JSONObject(WeatherFeed.readingJson("Bahrain", "", body("""{"temperature_2m":-0.4,"weather_code":0}"""))!!)
        assertEquals("Bahrain", reading.getString("place"))
        assertEquals(0, reading.getInt("temp"))
        assertEquals(true, reading.getBoolean("day"))
    }

    private val forecast = """{"current":{"temperature_2m":30.2,"weather_code":0,"is_day":1},"daily":{"time":["2026-11-10","2026-11-11","2026-11-12","2026-11-13"],"weather_code":[3,0,61,2],"temperature_2m_max":[33.6,34.4,29.5,31.0],"temperature_2m_min":[24.4,25.0,23.6,24.2]}}"""

    @Test fun addsTheNextThreeDaysAfterToday() {
        val reading = JSONObject(WeatherFeed.readingJson("Bahrain", "Manama", JSONObject(forecast))!!)
        assertEquals(34, reading.getInt("hi"))
        val days = reading.getJSONArray("days")
        assertEquals(3, days.length())
        assertEquals("2026-11-11", days.getJSONObject(0).getString("d"))
        assertEquals(0, days.getJSONObject(0).getInt("code"))
        assertEquals(34, days.getJSONObject(0).getInt("hi"))
        assertEquals(25, days.getJSONObject(0).getInt("lo"))
        assertEquals(61, days.getJSONObject(1).getInt("code"))
        assertEquals(30, days.getJSONObject(1).getInt("hi"))
        assertEquals("2026-11-13", days.getJSONObject(2).getString("d"))
    }

    @Test fun leavesOutDaysThatAreMissingOrMalformed() {
        val broken = """{"current":{"temperature_2m":30,"weather_code":0},"daily":{"time":["2026-11-10","not a date","2026-11-12"],"weather_code":[3,1,61],"temperature_2m_max":[33,34,29],"temperature_2m_min":[24,25]}}"""
        val reading = JSONObject(WeatherFeed.readingJson("x", "x", JSONObject(broken))!!)
        assertEquals("a bad date and a missing low are both dropped", false, reading.has("days"))
        val oneDay = """{"current":{"temperature_2m":30,"weather_code":0},"daily":{"time":["2026-11-10"],"weather_code":[3],"temperature_2m_max":[33],"temperature_2m_min":[24]}}"""
        assertEquals(false, JSONObject(WeatherFeed.readingJson("x", "x", JSONObject(oneDay))!!).has("days"))
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
            "https://api.open-meteo.com/v1/forecast?latitude=26.2&longitude=50.6&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=4&timezone=auto",
            WeatherFeed.forecastUrl(26.2, 50.6)
        )
    }
}
