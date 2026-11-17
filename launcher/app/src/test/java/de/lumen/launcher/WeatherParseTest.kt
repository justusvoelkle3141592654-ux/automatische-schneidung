package de.lumen.launcher

import de.lumen.launcher.widgets.WeatherCodes
import de.lumen.launcher.widgets.WeatherRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WeatherParseTest {
    private val forecast = """{"latitude":48.12743,"longitude":11.576928,"current":{"time":"2026-11-17T18:15","interval":900,"temperature_2m":12.7,"weather_code":0,"is_day":0},"hourly":{"time":["2026-11-17T18:00","2026-11-17T19:00","2026-11-17T20:00","2026-11-17T21:00","2026-11-17T22:00","2026-11-17T23:00"],"temperature_2m":[12.8,12.5,11.9,11.0,10.3,10.5],"weather_code":[0,0,1,1,0,3]},"daily":{"time":["2026-11-17","2026-11-18","2026-11-19","2026-11-20","2026-11-21"],"weather_code":[61,80,80,80,80],"temperature_2m_max":[13.9,12.0,9.1,9.2,7.6],"temperature_2m_min":[6.5,7.8,6.2,6.6,1.9]}}"""
    private val places = """{"results":[{"id":2867714,"name":"München","latitude":48.13743,"longitude":11.57549,"country":"Deutschland","admin1":"Bayern"}],"generationtime_ms":1.0}"""

    @Test fun parsesForecast() {
        val d = WeatherRepository.parseForecast(forecast, 1L)
        assertEquals(12.7, d.temp, 1e-9)
        assertEquals(0, d.code)
        assertFalse(d.isDay)
        assertEquals(5, d.daily.size)
        assertEquals(13.9, d.daily[0].max, 1e-9)
        assertEquals(6, d.hourly.size)
        assertEquals(3, d.hourly.last().code)
    }

    @Test fun parsesPlaces() {
        val p = WeatherRepository.parsePlaces(places)
        assertEquals("München", p.single().name)
        assertEquals("Bayern, Deutschland", p.single().region)
        assertEquals(emptyList<Any>(), WeatherRepository.parsePlaces("{\"generationtime_ms\":0.2}"))
    }

    @Test fun codes() {
        assertEquals("Regen", WeatherCodes.describe(63))
        assertEquals(WeatherCodes.Kind.SNOW, WeatherCodes.kind(75))
    }
}
