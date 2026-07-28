package com.auralis.crisisconnect.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrisisSentinelCardModelsTest {

    @Test
    fun parsesWeatherCard() {
        val card = parseCrisisSentinelCard(
            """{"kind":"weather","weather":{"label":"İstanbul","tempC":23.4,"humidity":62,"windKmh":15.2}}"""
        ) as CrisisSentinelCard.Weather
        assertEquals("İstanbul", card.label)
        assertEquals(23.4, card.tempC!!, 0.0001)
        assertEquals(62.0, card.humidity!!, 0.0001)
        assertNull(card.gustKmh)
    }

    @Test
    fun parsesFlatQuakeCard() {
        val card = parseCrisisSentinelCard(
            """{"kind":"quake","region":"tr","label":"Türkiye","events":[
                {"magnitude":4.5,"depth":7.2,"place":"Ege Denizi","occurredAt":"2026-07-09","lat":38.1,"lon":26.4}
            ]}"""
        ) as CrisisSentinelCard.Quake
        assertEquals("Türkiye", card.label)
        assertEquals(1, card.events.size)
        assertEquals(4.5, card.events[0].magnitude!!, 0.0001)
        assertEquals(38.1, card.events[0].lat!!, 0.0001)
    }

    @Test
    fun parsesAllNestedKinds() {
        val payloads = mapOf(
            "airquality" to """{"kind":"airquality","airquality":{"label":"Ankara","usAqi":42,"level":"Good"}}""",
            "hazardEvents" to """{"kind":"hazardEvents","hazard":{"category":"wildfires","label":"Fires","events":[{"title":"F1","lat":1.0,"lon":2.0}]}}""",
            "flood" to """{"kind":"flood","flood":{"label":"Meriç","unit":"m3/s","todayDischarge":120.5,"rising":true}}""",
            "alerts" to """{"kind":"alerts","alerts":{"title":"Uyarılar","source":"GDACS","items":[{"severity":"red","label":"A1","when":"today"}]}}""",
            "quakeImpact" to """{"kind":"quakeImpact","quakeImpact":{"events":[{"mag":6.1,"place":"X","alert":"orange","felt":1200,"tsunami":false}]}}""",
            "route" to """{"kind":"route","route":{"from":"A","to":"B","mode":"driving","distanceKm":12.3,"durationMin":18.0,"steps":[{"text":"Turn left","distanceKm":0.4}]}}""",
            "marine" to """{"kind":"marine","marine":{"label":"Ege","waveHeight":1.4,"severity":"moderate"}}""",
            "satellite" to """{"kind":"satellite","satellite":{"label":"İzmir","date":"2026-07-01","url":"https://example.com/img.png"}}"""
        )
        payloads.forEach { (kind, json) ->
            val card = parseCrisisSentinelCard(json)
            assertTrue("kind=$kind should parse", card != null)
        }
    }

    @Test
    fun parsesFlatFacilityAndDamage() {
        val facility = parseCrisisSentinelCard(
            """{"kind":"facility","label":"En yakın hastane","facilityType":"hospital",
                "facilities":[{"name":"Şehir Hastanesi","lat":41.0,"lon":29.0,"distanceKm":2.4}]}"""
        ) as CrisisSentinelCard.Facility
        assertEquals(1, facility.facilities.size)
        assertEquals("Şehir Hastanesi", facility.facilities[0].name)

        val damage = parseCrisisSentinelCard(
            """{"kind":"damage","rating":"moderate","summary":"Cracks visible","hazards":["collapse risk"]}"""
        ) as CrisisSentinelCard.Damage
        assertEquals("moderate", damage.rating)
        assertEquals(listOf("collapse risk"), damage.hazards)
    }

    @Test
    fun unknownKindAndMalformedJsonReturnNull() {
        assertNull(parseCrisisSentinelCard("""{"kind":"ops","ops":{}}"""))
        assertNull(parseCrisisSentinelCard("""{"kind":"unknown-thing"}"""))
        assertNull(parseCrisisSentinelCard("not json at all"))
        // Missing nested wrapper → null instead of crash.
        assertNull(parseCrisisSentinelCard("""{"kind":"weather"}"""))
    }

    @Test
    fun partialPayloadParsesPartially() {
        val card = parseCrisisSentinelCard(
            """{"kind":"weather","weather":{"tempC":10}}"""
        ) as CrisisSentinelCard.Weather
        assertNull(card.label)
        assertEquals(10.0, card.tempC!!, 0.0001)
    }
}
