package com.auralis.crisisconnect.screens.Tools.data

import android.content.Context
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.network.PinnedOkHttpClient
import com.auralis.crisisconnect.service.NotificationLocalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ─── Domain model ───────────────────────────────────────────────────────────

/** Which feed the UI is currently showing. */
enum class DisasterFeed { GLOBAL, LOCAL }

// DisasterRegion now lives in CountryLocator.kt — resolved from the user's
// actual location (GPS → IP → SIM → locale), not from the app language.

/** A single normalized disaster event, regardless of which source it came from. */
data class DisasterEvent(
    val id: Int,
    val title: String,
    val description: String,
    val htmlDescription: String,
    val eventType: String,      // EQ, FL, TC, VO, WF, DR
    val alertLevel: String,     // Red, Orange, Green
    val country: String,
    val fromDate: String,
    val toDate: String,
    val severityText: String,
    val magnitude: Double?,     // populated for earthquakes; null otherwise
    val source: String,         // "GDACS", "AFAD", "USGS", "EMSC"
    val eventTimeMillis: Long,  // parsed epoch (0L if unknown) — used for sorting + relative time
    val latitude: Double,
    val longitude: Double
)

/** Why a load failed. Only set when every network source actually failed. */
enum class DisasterErrorType { OFFLINE, TIMEOUT, NO_DATA }

/**
 * Outcome of a load. [isStale] is true only when we are showing cached data
 * *because all live sources failed* — NOT when the cache was still within its
 * TTL. That distinction keeps the "offline" banner from appearing while online.
 */
data class DisasterFetchResult(
    val events: List<DisasterEvent>,
    val lastUpdatedMillis: Long,
    val isStale: Boolean,
    val error: DisasterErrorType?,
    val primarySource: String
)

// ─── Repository ───────────────────────────────────────────────────────────────

/** One feed source: a URL and the parser that normalizes its response. */
private data class SourceSpec(val url: String, val parser: (String) -> List<DisasterEvent>)

class DisasterRepository(context: Context) {

    private val appContext = context.applicationContext

    private val client by lazy {
        PinnedOkHttpClient.newClient().newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // v2 cache holds our *normalized* event list (independent of source formats),
    // keyed per feed/region so switching language uses a separate cache.
    private fun cacheFile(feed: DisasterFeed, region: DisasterRegion?): File {
        val key = if (feed == DisasterFeed.GLOBAL) "global"
        else region?.countryCode?.lowercase()?.ifBlank { null } ?: "local"
        return File(appContext.cacheDir, "recent_disasters_${key}_v2.json")
    }

    private fun isCacheFresh(file: File): Boolean {
        if (!file.exists()) return false
        return file.lastModified() >= System.currentTimeMillis() - CACHE_TTL_MS
    }

    /**
     * Loads the requested feed by querying every source in parallel and merging
     * the results (de-duplicating earthquakes reported by more than one agency).
     * Serves a fresh cache without hitting the network; falls back to a stale
     * cache only when every source fails.
     */
    suspend fun load(
        feed: DisasterFeed,
        region: DisasterRegion?,
        forceRefresh: Boolean
    ): DisasterFetchResult =
        withContext(Dispatchers.IO) {
            val cache = cacheFile(feed, region)

            // 1. Fresh cache → serve directly; we are NOT stale/offline.
            if (!forceRefresh && isCacheFresh(cache)) {
                val cached = runCatching { deserialize(cache.readText()) }.getOrNull()
                if (!cached.isNullOrEmpty()) {
                    return@withContext DisasterFetchResult(
                        events = cached,
                        lastUpdatedMillis = cache.lastModified(),
                        isStale = false,
                        error = null,
                        primarySource = sourcesLabel(cached, feed)
                    )
                }
            }

            // 2. Query every source in parallel; null = that source failed.
            val specs = sourcesFor(feed, region)
            val results = coroutineScope {
                specs.map { spec ->
                    async { runCatching { spec.parser(httpGet(spec.url)) }.getOrNull() }
                }.awaitAll()
            }
            val anySuccess = results.any { it != null }
            val all = results.filterNotNull().flatten()

            if (anySuccess) {
                val merged = dedupEarthquakes(all).recentSorted()
                runCatching { cache.writeText(serialize(merged)) }
                return@withContext DisasterFetchResult(
                    events = merged,
                    lastUpdatedMillis = System.currentTimeMillis(),
                    isStale = false,
                    error = null,
                    primarySource = sourcesLabel(merged, feed)
                )
            }

            // 3. Every source failed → offline. Serve the stale cache if we have one.
            return@withContext fallbackToCache(cache, feed)
        }

    private fun fallbackToCache(cache: File, feed: DisasterFeed): DisasterFetchResult {
        if (cache.exists()) {
            val cached = runCatching { deserialize(cache.readText()) }.getOrNull()
            if (!cached.isNullOrEmpty()) {
                return DisasterFetchResult(
                    events = cached,
                    lastUpdatedMillis = cache.lastModified(),
                    isStale = true,
                    error = DisasterErrorType.OFFLINE,
                    primarySource = sourcesLabel(cached, feed)
                )
            }
        }
        return DisasterFetchResult(
            events = emptyList(),
            lastUpdatedMillis = 0L,
            isStale = false,
            error = DisasterErrorType.NO_DATA,
            primarySource = defaultSource(feed)
        )
    }

    /**
     * The cached feed as-is, never touching the network — for surfaces that must
     * render instantly (home-screen widget). Returns null when no usable cache
     * exists. [DisasterFetchResult.isStale] only reflects the TTL here, so
     * callers can show a "last updated" time instead of an offline banner.
     */
    fun peekCache(feed: DisasterFeed, region: DisasterRegion?): DisasterFetchResult? {
        val cache = cacheFile(feed, region)
        if (!cache.exists()) return null
        val cached = runCatching { deserialize(cache.readText()) }.getOrNull()
        if (cached.isNullOrEmpty()) return null
        return DisasterFetchResult(
            events = cached,
            lastUpdatedMillis = cache.lastModified(),
            isStale = !isCacheFresh(cache),
            error = null,
            primarySource = sourcesLabel(cached, feed)
        )
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Empty body")
        }
    }

    // ─── Source wiring ────────────────────────────────────────────────────────

    private fun sourcesFor(feed: DisasterFeed, region: DisasterRegion?): List<SourceSpec> = when (feed) {
        DisasterFeed.GLOBAL -> listOf(
            SourceSpec(gdacsUrl()) { parseGdacs(it) },       // multi-hazard (EQ, FL, TC, VO, WF, DR)
            SourceSpec(usgsUrl(null)) { parseUsgs(it) },     // global earthquakes, near-real-time
            SourceSpec(emscUrl(null)) { parseEmsc(it) }      // global earthquakes, near-real-time
        )
        DisasterFeed.LOCAL -> buildList {
            if (region == null) {
                // Shouldn't happen for LOCAL, but stay safe → behave like global.
                add(SourceSpec(usgsUrl(null)) { parseUsgs(it) })
                add(SourceSpec(emscUrl(null)) { parseEmsc(it) })
            } else {
                if (region.countryCode.equals("TR", ignoreCase = true)) {
                    add(SourceSpec(afadUrl()) { parseAfad(it) })         // official government agency
                    add(SourceSpec(kandilliUrl()) { parseKandilli(it) }) // Kandilli Observatory (KOERI)
                }
                add(SourceSpec(emscUrl(region)) { parseEmsc(it) })       // regional bbox, often fastest
                add(SourceSpec(usgsUrl(region)) { parseUsgs(it) })       // regional bbox
                // GDACS, filtered to the country bbox → floods/storms/volcanoes/etc.
                add(SourceSpec(gdacsUrl()) { body ->
                    parseGdacs(body).filter { region.contains(it.latitude, it.longitude) }
                })
            }
        }
    }

    private fun gdacsUrl() =
        "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=EQ;TC;FL;VO;WF;DR&pagesize=50"

    private fun afadUrl(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val end = sdf.format(Date())
        val start = sdf.format(Date(System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L))
        return "https://deprem.afad.gov.tr/apiv2/event/filter?start=$start&end=$end&limit=100"
    }

    // Community JSON mirror of Kandilli Observatory (KOERI) data — KOERI itself
    // only publishes an HTTP HTML list, which the network policy blocks. Failures
    // here are tolerated (other Türkiye sources still fill the feed).
    private fun kandilliUrl() = "https://api.orhanaydogdu.com.tr/deprem/kandilli/live?limit=100"

    private fun usgsUrl(region: DisasterRegion?): String {
        val start = startDateIso()
        val base = "https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson" +
            "&orderby=time&limit=100&starttime=$start"
        return if (region == null) {
            "$base&minmagnitude=4.5"
        } else {
            "$base&minmagnitude=2.5" + bboxQuery(region)
        }
    }

    private fun emscUrl(region: DisasterRegion?): String {
        val start = startDateIso()
        val base = "https://www.seismicportal.eu/fdsnws/event/1/query?format=json" +
            "&orderby=time&limit=100&starttime=$start"
        return if (region == null) {
            "$base&minmagnitude=4.5"
        } else {
            "$base&minmagnitude=2.5" + bboxQuery(region)
        }
    }

    private fun bboxQuery(region: DisasterRegion) =
        "&minlatitude=${region.minLat}&maxlatitude=${region.maxLat}" +
            "&minlongitude=${region.minLon}&maxlongitude=${region.maxLon}"

    private fun startDateIso(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() - RECENT_WINDOW_MS))

    // ─── Merge / de-duplication ─────────────────────────────────────────────────

    /**
     * The same earthquake is often reported by several agencies. Keep one copy
     * per cluster (events within [DUP_TIME_MS] and [DUP_DISTANCE_KM]), preferring
     * the most authoritative source. Non-earthquake events are never merged.
     */
    private fun dedupEarthquakes(events: List<DisasterEvent>): List<DisasterEvent> {
        val (quakes, others) = events.partition { it.eventType.equals("EQ", ignoreCase = true) }
        val kept = mutableListOf<DisasterEvent>()
        // Visit higher-priority sources first so they win each cluster.
        for (e in quakes.sortedByDescending { sourcePriority(it.source) }) {
            val duplicate = kept.any { k ->
                abs(k.eventTimeMillis - e.eventTimeMillis) <= DUP_TIME_MS &&
                    e.eventTimeMillis != 0L && k.eventTimeMillis != 0L &&
                    haversineDistanceKm(k.latitude, k.longitude, e.latitude, e.longitude) <= DUP_DISTANCE_KM
            }
            if (!duplicate) kept.add(e)
        }
        return others + kept
    }

    private fun sourcePriority(source: String): Int = when (source) {
        SOURCE_AFAD -> 5
        SOURCE_KANDILLI -> 4
        SOURCE_USGS -> 3
        SOURCE_EMSC -> 2
        SOURCE_GDACS -> 1
        else -> 0
    }

    private fun sourcesLabel(events: List<DisasterEvent>, feed: DisasterFeed): String {
        val labels = events.map { it.source }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { sourcePriority(it) }
        return if (labels.isEmpty()) defaultSource(feed) else labels.joinToString(" · ")
    }

    private fun defaultSource(feed: DisasterFeed) =
        if (feed == DisasterFeed.GLOBAL) SOURCE_GDACS else SOURCE_AFAD

    /**
     * Drops events older than [RECENT_WINDOW_MS] (so months-old, still-listed
     * incidents don't clutter the feed) and sorts newest-first. Events whose
     * date couldn't be parsed (epoch 0) are kept — we can't judge their age.
     */
    private fun List<DisasterEvent>.recentSorted(): List<DisasterEvent> {
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        return this
            .filter { it.eventTimeMillis == 0L || it.eventTimeMillis >= cutoff }
            .sortedByDescending { it.eventTimeMillis }
    }

    // ─── Parsers ──────────────────────────────────────────────────────────────

    // GDACS (GeoJSON, multi-hazard)
    private fun parseGdacs(jsonStr: String): List<DisasterEvent> {
        val list = mutableListOf<DisasterEvent>()
        val root = JSONObject(jsonStr)
        val features = root.optJSONArray("features") ?: return emptyList()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coordinates = geometry.optJSONArray("coordinates")

            val longitude = coordinates?.optDouble(0, 0.0) ?: 0.0
            val latitude = coordinates?.optDouble(1, 0.0) ?: 0.0

            val eventType = properties.optString("eventtype", "")
            val fromDate = properties.optString("fromdate", "")
            val severityObj = properties.optJSONObject("severitydata")
            val severityText = severityObj?.optString("severitytext", "") ?: ""
            val magnitude = if (eventType.equals("EQ", true)) {
                severityObj?.optDouble("severity", Double.NaN)?.takeIf { !it.isNaN() }
            } else null

            list.add(
                DisasterEvent(
                    id = properties.optInt("eventid", 0),
                    title = properties.optString("name", "Unknown Incident"),
                    description = properties.optString("description", ""),
                    htmlDescription = properties.optString("htmldescription", ""),
                    eventType = eventType,
                    alertLevel = properties.optString("alertlevel", "Green"),
                    country = properties.optString("country", "Global"),
                    fromDate = fromDate,
                    toDate = properties.optString("todate", ""),
                    severityText = severityText,
                    magnitude = magnitude,
                    source = SOURCE_GDACS,
                    eventTimeMillis = parseEpochMillis(fromDate, utc = true),
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
        return list
    }

    // AFAD (JSON array) — labels resolved in the app's selected language.
    private fun parseAfad(jsonStr: String): List<DisasterEvent> {
        val list = mutableListOf<DisasterEvent>()
        val l10n = NotificationLocalization.localizedContext(appContext)
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("eventID", "0").toIntOrNull() ?: 0
            val location = obj.optString("location", "Türkiye")
            val latitude = obj.optString("latitude", "0.0").toDoubleOrNull() ?: 0.0
            val longitude = obj.optString("longitude", "0.0").toDoubleOrNull() ?: 0.0
            val depth = obj.optString("depth", "0.0")
            val magnitude = obj.optString("magnitude", "0.0")
            val magDouble = magnitude.toDoubleOrNull() ?: 0.0
            val country = obj.optString("country", "Türkiye")
            val dateStr = obj.optString("date", "")

            val alertLevel = when {
                magDouble >= 4.5 -> "Red"
                magDouble >= 3.0 -> "Orange"
                else -> "Green"
            }

            val province = obj.optString("province", "")
            val district = obj.optString("district", "")
            val districtInfo = if (province.isNotBlank()) "$district, $province" else location

            list.add(
                DisasterEvent(
                    id = id,
                    title = l10n.getString(R.string.recent_disasters_afad_title, districtInfo),
                    description = l10n.getString(
                        R.string.recent_disasters_afad_description,
                        location, magnitude, depth, dateStr
                    ),
                    htmlDescription = "",
                    eventType = "EQ",
                    alertLevel = alertLevel,
                    country = country,
                    fromDate = dateStr,
                    toDate = dateStr,
                    severityText = l10n.getString(R.string.recent_disasters_afad_severity, magnitude, depth),
                    magnitude = magDouble.takeIf { it > 0.0 },
                    source = SOURCE_AFAD,
                    eventTimeMillis = parseEpochMillis(dateStr, utc = false),
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
        return list
    }

    // USGS (GeoJSON, earthquakes only)
    private fun parseUsgs(jsonStr: String): List<DisasterEvent> {
        val l10n = NotificationLocalization.localizedContext(appContext)
        val list = mutableListOf<DisasterEvent>()
        val root = JSONObject(jsonStr)
        val features = root.optJSONArray("features") ?: return emptyList()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coordinates = geometry.optJSONArray("coordinates")

            val longitude = coordinates?.optDouble(0, 0.0) ?: 0.0
            val latitude = coordinates?.optDouble(1, 0.0) ?: 0.0
            val depth = coordinates?.optDouble(2, 0.0) ?: 0.0

            val mag = properties.optDouble("mag", Double.NaN).takeIf { !it.isNaN() }
            val place = properties.optString("place")
                .ifBlank { l10n.getString(R.string.recent_disasters_unknown_location) }
            val title = properties.optString("title", place)
            val timeMs = properties.optLong("time", 0L)
            val usgsAlert = properties.optString("alert", "")

            val alertLevel = when {
                usgsAlert.equals("red", true) -> "Red"
                usgsAlert.equals("orange", true) || usgsAlert.equals("yellow", true) -> "Orange"
                usgsAlert.equals("green", true) -> "Green"
                else -> alertFromMag(mag)
            }

            list.add(
                DisasterEvent(
                    id = properties.optString("code", "0").hashCode(),
                    title = title,
                    description = place,
                    htmlDescription = "",
                    eventType = "EQ",
                    alertLevel = alertLevel,
                    country = place.substringAfterLast(", ").ifBlank { "—" },
                    fromDate = isoUtc(timeMs),
                    toDate = isoUtc(timeMs),
                    severityText = mag?.let { "M %.1f • %.0f km".format(it, abs(depth)) } ?: "",
                    magnitude = mag,
                    source = SOURCE_USGS,
                    eventTimeMillis = timeMs,
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
        return list
    }

    // EMSC / seismicportal.eu (GeoJSON, earthquakes only)
    private fun parseEmsc(jsonStr: String): List<DisasterEvent> {
        val list = mutableListOf<DisasterEvent>()
        val root = JSONObject(jsonStr)
        val features = root.optJSONArray("features") ?: return emptyList()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val p = feature.optJSONObject("properties") ?: continue
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates")

            val longitude = coordinates?.optDouble(0, p.optDouble("lon", 0.0)) ?: p.optDouble("lon", 0.0)
            val latitude = coordinates?.optDouble(1, p.optDouble("lat", 0.0)) ?: p.optDouble("lat", 0.0)
            val depth = coordinates?.optDouble(2, p.optDouble("depth", 0.0)) ?: p.optDouble("depth", 0.0)

            val mag = p.optDouble("mag", Double.NaN).takeIf { !it.isNaN() }
            val region = p.optString("flynn_region", p.optString("region", "")).trim()
            val timeMs = parseEpochMillis(p.optString("time", ""), utc = true)

            list.add(
                DisasterEvent(
                    id = p.optString("unid", p.optString("source_id", "0")).hashCode(),
                    title = buildEqTitle(mag, region),
                    description = region,
                    htmlDescription = "",
                    eventType = "EQ",
                    alertLevel = alertFromMag(mag),
                    country = region.ifBlank { "—" },
                    fromDate = isoUtc(timeMs),
                    toDate = isoUtc(timeMs),
                    severityText = mag?.let { "M %.1f • %.0f km".format(it, abs(depth)) } ?: "",
                    magnitude = mag,
                    source = SOURCE_EMSC,
                    eventTimeMillis = timeMs,
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
        return list
    }

    // Kandilli (KOERI) via community JSON mirror — same fields shape as AFAD,
    // so labels reuse the localized AFAD strings.
    private fun parseKandilli(jsonStr: String): List<DisasterEvent> {
        val list = mutableListOf<DisasterEvent>()
        val l10n = NotificationLocalization.localizedContext(appContext)
        val root = JSONObject(jsonStr)
        val result = root.optJSONArray("result") ?: return emptyList()
        for (i in 0 until result.length()) {
            val o = result.optJSONObject(i) ?: continue
            val coords = o.optJSONObject("geojson")?.optJSONArray("coordinates")
            val lon = coords?.optDouble(0, o.optDouble("lng", 0.0)) ?: o.optDouble("lng", 0.0)
            val lat = coords?.optDouble(1, o.optDouble("lat", 0.0)) ?: o.optDouble("lat", 0.0)

            val mag = o.optDouble("mag", Double.NaN).takeIf { !it.isNaN() }
            val magStr = mag?.let { "%.1f".format(it) } ?: "0.0"
            val depthStr = "%.1f".format(o.optDouble("depth", 0.0))
            val place = o.optString("title", "").ifBlank {
                o.optJSONObject("location_properties")?.optJSONObject("epiCenter")?.optString("name", "") ?: ""
            }
            val rawDate = o.optString("date_time", "").ifBlank { o.optString("date", "").replace('.', '-') }
            val magDouble = mag ?: 0.0
            val alertLevel = when {
                magDouble >= 4.5 -> "Red"
                magDouble >= 3.0 -> "Orange"
                else -> "Green"
            }

            list.add(
                DisasterEvent(
                    id = o.optString("earthquake_id", o.optString("_id", "0")).hashCode(),
                    title = l10n.getString(R.string.recent_disasters_afad_title, place.ifBlank { "Türkiye" }),
                    description = place,
                    htmlDescription = "",
                    eventType = "EQ",
                    alertLevel = alertLevel,
                    country = "Türkiye",
                    fromDate = rawDate,
                    toDate = rawDate,
                    severityText = l10n.getString(R.string.recent_disasters_afad_severity, magStr, depthStr),
                    magnitude = mag,
                    source = SOURCE_KANDILLI,
                    eventTimeMillis = parseEpochMillis(rawDate, utc = false),
                    latitude = lat,
                    longitude = lon
                )
            )
        }
        return list
    }

    private fun alertFromMag(mag: Double?): String = when {
        (mag ?: 0.0) >= 6.0 -> "Red"
        (mag ?: 0.0) >= 4.5 -> "Orange"
        else -> "Green"
    }

    private fun buildEqTitle(mag: Double?, region: String): String {
        val m = mag?.let { "M%.1f".format(it) }
        return when {
            m != null && region.isNotBlank() -> "$m — $region"
            m != null -> m
            region.isNotBlank() -> region
            else -> "Earthquake"
        }
    }

    // ─── Normalized cache (de)serialization ───────────────────────────────────

    private fun serialize(events: List<DisasterEvent>): String {
        val arr = JSONArray()
        for (e in events) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("title", e.title)
                    .put("description", e.description)
                    .put("htmlDescription", e.htmlDescription)
                    .put("eventType", e.eventType)
                    .put("alertLevel", e.alertLevel)
                    .put("country", e.country)
                    .put("fromDate", e.fromDate)
                    .put("toDate", e.toDate)
                    .put("severityText", e.severityText)
                    .put("magnitude", e.magnitude ?: JSONObject.NULL)
                    .put("source", e.source)
                    .put("eventTimeMillis", e.eventTimeMillis)
                    .put("latitude", e.latitude)
                    .put("longitude", e.longitude)
            )
        }
        return arr.toString()
    }

    private fun deserialize(json: String): List<DisasterEvent> {
        val arr = JSONArray(json)
        val list = ArrayList<DisasterEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                DisasterEvent(
                    id = o.optInt("id", 0),
                    title = o.optString("title", ""),
                    description = o.optString("description", ""),
                    htmlDescription = o.optString("htmlDescription", ""),
                    eventType = o.optString("eventType", ""),
                    alertLevel = o.optString("alertLevel", "Green"),
                    country = o.optString("country", ""),
                    fromDate = o.optString("fromDate", ""),
                    toDate = o.optString("toDate", ""),
                    severityText = o.optString("severityText", ""),
                    magnitude = if (o.isNull("magnitude")) null
                    else o.optDouble("magnitude").takeIf { !it.isNaN() },
                    source = o.optString("source", ""),
                    eventTimeMillis = o.optLong("eventTimeMillis", 0L),
                    latitude = o.optDouble("latitude", 0.0),
                    longitude = o.optDouble("longitude", 0.0)
                )
            )
        }
        return list
    }

    companion object {
        const val SOURCE_GDACS = "GDACS"
        const val SOURCE_AFAD = "AFAD"
        const val SOURCE_USGS = "USGS"
        const val SOURCE_EMSC = "EMSC"
        const val SOURCE_KANDILLI = "Kandilli"

        // Re-fetch when the cache is older than this on a non-forced load.
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        /** Only events from the last 30 days are shown. Adjust to taste. */
        private const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        // Two earthquakes within this time + distance are treated as the same event.
        private const val DUP_TIME_MS = 120_000L
        private const val DUP_DISTANCE_KM = 150.0
    }
}

// ─── Shared utilities (used by the ViewModel and the UI) ──────────────────────

/** Great-circle distance in kilometres. */
fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun isoUtc(millis: Long): String {
    if (millis <= 0L) return ""
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return sdf.format(Date(millis))
}

/**
 * Best-effort parse of the various date strings the feeds return into an epoch.
 * Tries with and without fractional seconds. Returns 0L when nothing matches.
 */
private val DATE_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSS",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm",
    "yyyy-MM-dd"
)

fun parseEpochMillis(raw: String, utc: Boolean): Long {
    if (raw.isBlank()) return 0L
    val cleaned = raw.trim().removeSuffix("Z")
    val candidates = listOf(cleaned, cleaned.substringBefore('.'))
    for (candidate in candidates) {
        for (pattern in DATE_PATTERNS) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                if (utc) sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(candidate)?.time ?: continue
            } catch (_: Exception) {
                // try next pattern
            }
        }
    }
    return 0L
}
