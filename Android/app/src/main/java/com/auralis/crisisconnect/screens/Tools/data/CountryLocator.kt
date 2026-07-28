package com.auralis.crisisconnect.screens.Tools.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.network.PinnedOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The country/region the "local" tab represents. Resolved from where the user
 * actually is (GPS → IP → SIM → locale), not from the app language.
 */
data class DisasterRegion(
    val countryCode: String,   // ISO-3166 alpha-2, e.g. "TR"
    val countryName: String,   // localized display name for the tab label
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    fun contains(lat: Double, lon: Double): Boolean =
        (lat != 0.0 || lon != 0.0) && lat in minLat..maxLat && lon in minLon..maxLon
}

/**
 * Detects the user's current country with a layered strategy:
 *  1. GPS last-known location → reverse-geocode to a country code (most precise)
 *  2. IP geolocation (one online lookup)
 *  3. SIM / network country (offline, no permission)
 *  4. Device locale region (last resort)
 *
 * Returns a [DisasterRegion] with a bounding box (from the country table, or a
 * radius around the detected coordinates when the country isn't tabulated), or
 * null if nothing could be resolved.
 */
class CountryLocator(context: Context) {

    private val appContext = context.applicationContext

    private val client by lazy {
        PinnedOkHttpClient.newClient().newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    suspend fun detect(): DisasterRegion? = withContext(Dispatchers.IO) {
        val gps = lastKnownLatLon()
        var iso: String? = null
        var coords: Pair<Double, Double>? = gps

        // 1. GPS → reverse geocode
        if (gps != null) iso = reverseGeocodeCountry(gps.first, gps.second)

        // 2. IP geolocation
        if (iso.isNullOrBlank()) {
            ipLookup()?.let { ip ->
                iso = ip.iso
                if (coords == null && ip.lat != null && ip.lon != null) coords = ip.lat to ip.lon
            }
        }

        // 3. SIM / network country
        if (iso.isNullOrBlank()) iso = simCountry()

        // 4. Device locale region
        if (iso.isNullOrBlank()) iso = Locale.getDefault().country.takeIf { it.isNotBlank() }

        buildRegion(iso, coords)
    }

    private fun buildRegion(iso: String?, coords: Pair<Double, Double>?): DisasterRegion? {
        val code = iso?.trim()?.uppercase()?.takeIf { it.length == 2 }
        val box = code?.let { CountryBounds.bbox(it) }
        val name = code?.let { displayCountry(it) }

        return when {
            box != null && code != null ->
                DisasterRegion(code, name ?: code, box[0], box[1], box[2], box[3])
            coords != null -> {
                val (lat, lon) = coords
                DisasterRegion(
                    countryCode = code ?: "",
                    countryName = name ?: "",
                    minLat = lat - RADIUS_DEG,
                    maxLat = lat + RADIUS_DEG,
                    minLon = lon - RADIUS_DEG,
                    maxLon = lon + RADIUS_DEG
                )
            }
            else -> null
        }
    }

    private fun displayCountry(iso: String): String =
        Locale("", iso).getDisplayCountry(Locale.getDefault()).ifBlank { iso }

    private fun lastKnownLatLon(): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            var best: Location? = null
            for (provider in lm.getProviders(true)) {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            }
            best?.let { it.latitude to it.longitude }
        } catch (e: SecurityException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeCountry(lat: Double, lon: Double): String? = try {
        Geocoder(appContext, Locale.getDefault())
            .getFromLocation(lat, lon, 1)
            ?.firstOrNull()
            ?.countryCode
    } catch (e: Exception) {
        null
    }

    private fun simCountry(): String? {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        return (tm.networkCountryIso.takeIf { !it.isNullOrBlank() } ?: tm.simCountryIso)
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
    }

    private data class IpResult(val iso: String, val lat: Double?, val lon: Double?)

    private fun ipLookup(): IpResult? =
        ipLookupFrom("https://ipapi.co/json/", "country_code", "latitude", "longitude")
            ?: ipLookupFrom("https://ipwho.is/", "country_code", "latitude", "longitude")

    private fun ipLookupFrom(url: String, isoKey: String, latKey: String, lonKey: String): IpResult? = try {
        val obj = JSONObject(httpGet(url))
        if (obj.optBoolean("error", false) || obj.optBoolean("success", true).not()) {
            null
        } else {
            val iso = obj.optString(isoKey, "").trim()
            if (iso.isBlank()) {
                null
            } else {
                val lat = obj.optDouble(latKey, Double.NaN).takeIf { !it.isNaN() }
                val lon = obj.optDouble(lonKey, Double.NaN).takeIf { !it.isNaN() }
                IpResult(iso.uppercase(), lat, lon)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "CrisisConnect").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw java.io.IOException("Empty body")
        }
    }

    companion object {
        // Half-size of the fallback bounding box (degrees) when a country isn't
        // in the table but we know the user's coordinates (~500 km each way).
        private const val RADIUS_DEG = 4.5
    }
}

/**
 * Approximate ISO-3166 alpha-2 → bounding box `[minLat, maxLat, minLon, maxLon]`.
 * Covers the most populous / seismically active countries; anything missing
 * falls back to a radius around the detected coordinates.
 */
object CountryBounds {
    fun bbox(iso: String): DoubleArray? = TABLE[iso.uppercase()]

    private val TABLE: Map<String, DoubleArray> = mapOf(
        "TR" to doubleArrayOf(35.8, 42.2, 25.6, 44.9),
        "JP" to doubleArrayOf(24.0, 46.0, 122.0, 146.0),
        "IN" to doubleArrayOf(6.0, 37.1, 68.1, 97.4),
        "US" to doubleArrayOf(24.4, 49.4, -125.0, -66.9),
        "GB" to doubleArrayOf(49.9, 60.9, -8.6, 1.8),
        "DE" to doubleArrayOf(47.2, 55.1, 5.8, 15.0),
        "FR" to doubleArrayOf(41.3, 51.1, -5.1, 9.6),
        "ES" to doubleArrayOf(36.0, 43.8, -9.3, 3.3),
        "IT" to doubleArrayOf(36.6, 47.1, 6.6, 18.5),
        "GR" to doubleArrayOf(34.8, 41.8, 19.3, 28.2),
        "PT" to doubleArrayOf(36.9, 42.2, -9.5, -6.2),
        "NL" to doubleArrayOf(50.7, 53.6, 3.3, 7.2),
        "CH" to doubleArrayOf(45.8, 47.8, 5.9, 10.5),
        "AT" to doubleArrayOf(46.3, 49.0, 9.5, 17.2),
        "PL" to doubleArrayOf(49.0, 54.9, 14.1, 24.2),
        "RO" to doubleArrayOf(43.6, 48.3, 20.2, 29.7),
        "RU" to doubleArrayOf(41.2, 77.0, 19.6, 180.0),
        "UA" to doubleArrayOf(44.4, 52.4, 22.1, 40.2),
        "IR" to doubleArrayOf(25.0, 39.8, 44.0, 63.3),
        "IQ" to doubleArrayOf(29.1, 37.4, 38.8, 48.6),
        "SY" to doubleArrayOf(32.3, 37.3, 35.7, 42.4),
        "IL" to doubleArrayOf(29.5, 33.3, 34.3, 35.9),
        "SA" to doubleArrayOf(16.3, 32.2, 34.5, 55.7),
        "AE" to doubleArrayOf(22.6, 26.1, 51.6, 56.4),
        "EG" to doubleArrayOf(22.0, 31.7, 25.0, 35.0),
        "PK" to doubleArrayOf(23.7, 37.1, 60.9, 77.8),
        "AF" to doubleArrayOf(29.4, 38.5, 60.5, 74.9),
        "CN" to doubleArrayOf(18.2, 53.6, 73.5, 134.8),
        "ID" to doubleArrayOf(-11.0, 6.1, 95.0, 141.0),
        "PH" to doubleArrayOf(5.0, 19.6, 117.2, 126.6),
        "NP" to doubleArrayOf(26.3, 30.4, 80.1, 88.2),
        "TH" to doubleArrayOf(5.6, 20.5, 97.3, 105.6),
        "VN" to doubleArrayOf(8.6, 23.4, 102.1, 109.5),
        "KR" to doubleArrayOf(33.2, 38.6, 126.1, 129.6),
        "TW" to doubleArrayOf(21.9, 25.3, 120.0, 122.0),
        "MX" to doubleArrayOf(14.5, 32.7, -118.4, -86.7),
        "BR" to doubleArrayOf(-33.8, 5.3, -73.9, -34.8),
        "AR" to doubleArrayOf(-55.1, -21.8, -73.6, -53.6),
        "CL" to doubleArrayOf(-55.9, -17.5, -75.6, -66.4),
        "PE" to doubleArrayOf(-18.4, 0.0, -81.4, -68.7),
        "CO" to doubleArrayOf(-4.2, 12.5, -79.0, -66.9),
        "EC" to doubleArrayOf(-5.0, 1.4, -81.0, -75.2),
        "CA" to doubleArrayOf(41.7, 70.0, -141.0, -52.6),
        "AU" to doubleArrayOf(-43.6, -10.7, 113.3, 153.6),
        "NZ" to doubleArrayOf(-47.3, -34.4, 166.4, 178.6),
        "ZA" to doubleArrayOf(-34.8, -22.1, 16.5, 32.9),
        "NG" to doubleArrayOf(4.3, 13.9, 2.7, 14.7),
        "KE" to doubleArrayOf(-4.7, 5.0, 33.9, 41.9),
        "ET" to doubleArrayOf(3.4, 14.9, 33.0, 48.0),
        "MA" to doubleArrayOf(27.7, 35.9, -13.2, -1.0),
        "DZ" to doubleArrayOf(19.0, 37.1, -8.7, 12.0),
        "GE" to doubleArrayOf(41.0, 43.6, 40.0, 46.7),
        "AZ" to doubleArrayOf(38.4, 41.9, 44.8, 50.4),
        "AM" to doubleArrayOf(38.8, 41.3, 43.4, 46.6)
    )
}
