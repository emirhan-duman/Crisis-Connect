package com.auralis.crisisconnect.ai

import org.json.JSONObject

/**
 * Typed views of the web chat API's `_card` payloads (the 12 field-team kinds). Parsing is
 * deliberately lenient — every field is optional — so a partially populated card renders
 * partially instead of failing. Manager-only kinds (ops/license/aiUsage) and unknown kinds
 * parse to null and are silently skipped, matching the web's render switch.
 */
sealed class CrisisSentinelCard {

    data class Weather(
        val label: String?,
        val tempC: Double?,
        val feelsLikeC: Double?,
        val humidity: Double?,
        val windKmh: Double?,
        val gustKmh: Double?,
        val rainMm: Double?,
        val precipMm: Double?
    ) : CrisisSentinelCard()

    data class Quake(
        val region: String?,
        val label: String?,
        val events: List<QuakeEvent>
    ) : CrisisSentinelCard() {
        data class QuakeEvent(
            val magnitude: Double?,
            val depth: Double?,
            val place: String?,
            val occurredAt: String?,
            val lat: Double?,
            val lon: Double?
        )
    }

    data class AirQuality(
        val label: String?,
        val usAqi: Double?,
        val level: String?,
        val pm25: Double?,
        val pm10: Double?,
        val ozone: Double?,
        val no2: Double?,
        val so2: Double?,
        val co: Double?
    ) : CrisisSentinelCard()

    data class HazardEvents(
        val category: String?,
        val label: String?,
        val place: String?,
        val events: List<HazardEvent>
    ) : CrisisSentinelCard() {
        data class HazardEvent(
            val title: String?,
            val category: String?,
            val lat: Double?,
            val lon: Double?,
            val date: String?
        )
    }

    data class Flood(
        val label: String?,
        val unit: String?,
        val todayDischarge: Double?,
        val peakDischarge: Double?,
        val peakDate: String?,
        val rising: Boolean?
    ) : CrisisSentinelCard()

    data class Alerts(
        val title: String?,
        val source: String?,
        val items: List<AlertItem>
    ) : CrisisSentinelCard() {
        data class AlertItem(
            val severity: String?,
            val label: String?,
            val detail: String?,
            val whenText: String?
        )
    }

    data class QuakeImpact(val events: List<ImpactEvent>) : CrisisSentinelCard() {
        data class ImpactEvent(
            val mag: Double?,
            val place: String?,
            val alert: String?,
            val mmi: Double?,
            val felt: Long?,
            val tsunami: Boolean?,
            val whenText: String?
        )
    }

    data class Route(
        val from: String?,
        val to: String?,
        val mode: String?,
        val distanceKm: Double?,
        val durationMin: Double?,
        val steps: List<RouteStep>
    ) : CrisisSentinelCard() {
        data class RouteStep(val text: String?, val distanceKm: Double?)
    }

    data class Marine(
        val label: String?,
        val waveHeight: Double?,
        val severity: String?,
        val waveDirection: Double?,
        val wavePeriod: Double?,
        val windWaveHeight: Double?,
        val swellWaveHeight: Double?,
        val seaSurfaceTemp: Double?
    ) : CrisisSentinelCard()

    data class Satellite(
        val label: String?,
        val date: String?,
        val imageKind: String?,
        val widthKm: Double?,
        val url: String?
    ) : CrisisSentinelCard()

    data class Facility(
        val label: String?,
        val facilityType: String?,
        val facilities: List<FacilityEntry>
    ) : CrisisSentinelCard() {
        data class FacilityEntry(
            val name: String?,
            val lat: Double?,
            val lon: Double?,
            val distanceKm: Double?
        )
    }

    data class Damage(
        val rating: String?,
        val summary: String?,
        val hazards: List<String>
    ) : CrisisSentinelCard()
}

fun parseCrisisSentinelCard(cardJson: String): CrisisSentinelCard? {
    val root = runCatching { JSONObject(cardJson) }.getOrNull() ?: return null
    return when (root.optString("kind")) {
        "weather" -> root.optJSONObject("weather")?.let { weather ->
            CrisisSentinelCard.Weather(
                label = weather.optStringOrNull("label"),
                tempC = weather.optDoubleOrNull("tempC"),
                feelsLikeC = weather.optDoubleOrNull("feelsLikeC"),
                humidity = weather.optDoubleOrNull("humidity"),
                windKmh = weather.optDoubleOrNull("windKmh"),
                gustKmh = weather.optDoubleOrNull("gustKmh"),
                rainMm = weather.optDoubleOrNull("rainMm"),
                precipMm = weather.optDoubleOrNull("precipMm")
            )
        }

        // Flat payload: fields live on the card object itself.
        "quake" -> CrisisSentinelCard.Quake(
            region = root.optStringOrNull("region"),
            label = root.optStringOrNull("label"),
            events = root.mapArray("events") { event ->
                CrisisSentinelCard.Quake.QuakeEvent(
                    magnitude = event.optDoubleOrNull("magnitude"),
                    depth = event.optDoubleOrNull("depth"),
                    place = event.optStringOrNull("place"),
                    occurredAt = event.optStringOrNull("occurredAt"),
                    lat = event.optDoubleOrNull("lat"),
                    lon = event.optDoubleOrNull("lon")
                )
            }
        )

        "airquality" -> root.optJSONObject("airquality")?.let { aq ->
            CrisisSentinelCard.AirQuality(
                label = aq.optStringOrNull("label"),
                usAqi = aq.optDoubleOrNull("usAqi"),
                level = aq.optStringOrNull("level"),
                pm25 = aq.optDoubleOrNull("pm25"),
                pm10 = aq.optDoubleOrNull("pm10"),
                ozone = aq.optDoubleOrNull("ozone"),
                no2 = aq.optDoubleOrNull("no2"),
                so2 = aq.optDoubleOrNull("so2"),
                co = aq.optDoubleOrNull("co")
            )
        }

        "hazardEvents" -> root.optJSONObject("hazard")?.let { hazard ->
            CrisisSentinelCard.HazardEvents(
                category = hazard.optStringOrNull("category"),
                label = hazard.optStringOrNull("label"),
                place = hazard.optStringOrNull("place"),
                events = hazard.mapArray("events") { event ->
                    CrisisSentinelCard.HazardEvents.HazardEvent(
                        title = event.optStringOrNull("title"),
                        category = event.optStringOrNull("category"),
                        lat = event.optDoubleOrNull("lat"),
                        lon = event.optDoubleOrNull("lon"),
                        date = event.optStringOrNull("date")
                    )
                }
            )
        }

        "flood" -> root.optJSONObject("flood")?.let { flood ->
            CrisisSentinelCard.Flood(
                label = flood.optStringOrNull("label"),
                unit = flood.optStringOrNull("unit"),
                todayDischarge = flood.optDoubleOrNull("todayDischarge"),
                peakDischarge = flood.optDoubleOrNull("peakDischarge"),
                peakDate = flood.optStringOrNull("peakDate"),
                rising = if (flood.has("rising")) flood.optBoolean("rising") else null
            )
        }

        "alerts" -> root.optJSONObject("alerts")?.let { alerts ->
            CrisisSentinelCard.Alerts(
                title = alerts.optStringOrNull("title"),
                source = alerts.optStringOrNull("source"),
                items = alerts.mapArray("items") { item ->
                    CrisisSentinelCard.Alerts.AlertItem(
                        severity = item.optStringOrNull("severity"),
                        label = item.optStringOrNull("label"),
                        detail = item.optStringOrNull("detail"),
                        whenText = item.optStringOrNull("when")
                    )
                }
            )
        }

        "quakeImpact" -> root.optJSONObject("quakeImpact")?.let { impact ->
            CrisisSentinelCard.QuakeImpact(
                events = impact.mapArray("events") { event ->
                    CrisisSentinelCard.QuakeImpact.ImpactEvent(
                        mag = event.optDoubleOrNull("mag"),
                        place = event.optStringOrNull("place"),
                        alert = event.optStringOrNull("alert"),
                        mmi = event.optDoubleOrNull("mmi"),
                        felt = event.optLong("felt", -1L).takeIf { it >= 0L },
                        tsunami = if (event.has("tsunami")) event.optBoolean("tsunami") else null,
                        whenText = event.optStringOrNull("when")
                    )
                }
            )
        }

        "route" -> root.optJSONObject("route")?.let { route ->
            CrisisSentinelCard.Route(
                from = route.optStringOrNull("from"),
                to = route.optStringOrNull("to"),
                mode = route.optStringOrNull("mode"),
                distanceKm = route.optDoubleOrNull("distanceKm"),
                durationMin = route.optDoubleOrNull("durationMin"),
                steps = route.mapArray("steps") { step ->
                    CrisisSentinelCard.Route.RouteStep(
                        text = step.optStringOrNull("text"),
                        distanceKm = step.optDoubleOrNull("distanceKm")
                    )
                }
            )
        }

        "marine" -> root.optJSONObject("marine")?.let { marine ->
            CrisisSentinelCard.Marine(
                label = marine.optStringOrNull("label"),
                waveHeight = marine.optDoubleOrNull("waveHeight"),
                severity = marine.optStringOrNull("severity"),
                waveDirection = marine.optDoubleOrNull("waveDirection"),
                wavePeriod = marine.optDoubleOrNull("wavePeriod"),
                windWaveHeight = marine.optDoubleOrNull("windWaveHeight"),
                swellWaveHeight = marine.optDoubleOrNull("swellWaveHeight"),
                seaSurfaceTemp = marine.optDoubleOrNull("seaSurfaceTemp")
            )
        }

        "satellite" -> root.optJSONObject("satellite")?.let { satellite ->
            CrisisSentinelCard.Satellite(
                label = satellite.optStringOrNull("label"),
                date = satellite.optStringOrNull("date"),
                imageKind = satellite.optStringOrNull("imageKind"),
                widthKm = satellite.optDoubleOrNull("widthKm"),
                url = satellite.optStringOrNull("url")
            )
        }

        // Flat payload.
        "facility" -> CrisisSentinelCard.Facility(
            label = root.optStringOrNull("label"),
            facilityType = root.optStringOrNull("facilityType"),
            facilities = root.mapArray("facilities") { facility ->
                CrisisSentinelCard.Facility.FacilityEntry(
                    name = facility.optStringOrNull("name"),
                    lat = facility.optDoubleOrNull("lat"),
                    lon = facility.optDoubleOrNull("lon"),
                    distanceKm = facility.optDoubleOrNull("distanceKm")
                )
            }
        )

        // Flat payload.
        "damage" -> CrisisSentinelCard.Damage(
            rating = root.optStringOrNull("rating"),
            summary = root.optStringOrNull("summary"),
            hazards = buildList {
                val hazards = root.optJSONArray("hazards")
                for (index in 0 until (hazards?.length() ?: 0)) {
                    hazards?.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        )

        else -> null
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    optDouble(key, Double.NaN).takeIf { !it.isNaN() }

private fun <T> JSONObject.mapArray(key: String, transform: (JSONObject) -> T): List<T> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { add(transform(it)) }
        }
    }
}
