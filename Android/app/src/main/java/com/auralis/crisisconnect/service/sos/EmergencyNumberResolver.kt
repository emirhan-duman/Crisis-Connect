package com.auralis.crisisconnect.service.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Resolves the emergency number for the device's current region.
 *
 * Preferred source is the platform's own aggregated list (SIM + network + modem + Android's
 * emergency-number database via [TelephonyManager.getEmergencyNumberList]) — but that API needs
 * READ_PHONE_STATE, which this app does not prompt for, so it only kicks in if the permission was
 * ever granted. The everyday path is permission-free: telephony country ISO (network first — it
 * follows the roamed country — then SIM, then locale) against a curated table, defaulting to 112,
 * which GSM networks accept nearly everywhere.
 */
object EmergencyNumberResolver {

    private val COUNTRY_EMERGENCY_NUMBERS: Map<String, String> = mapOf(
        "TR" to "112",
        "US" to "911", "CA" to "911", "MX" to "911",
        "GB" to "999", "IE" to "112",
        "AU" to "000", "NZ" to "111",
        "IN" to "112", "PK" to "15",
        "JP" to "110", "KR" to "112", "CN" to "110", "TW" to "110",
        "HK" to "999", "SG" to "999", "MY" to "999",
        "PH" to "911", "ID" to "112", "TH" to "191", "VN" to "113",
        "BR" to "190", "AR" to "911", "CL" to "133", "CO" to "123", "PE" to "105",
        "ZA" to "10111", "NG" to "112", "KE" to "999", "EG" to "122",
        "SA" to "911", "AE" to "999", "IL" to "100", "IR" to "110",
        "RU" to "112", "UA" to "112"
        // The EU/EEA and most of the remaining world standardize on 112 — the default below.
    )
    private const val DEFAULT_EMERGENCY_NUMBER = "112"

    /** Emergency number plus the country it was resolved for (null when no region could be detected). */
    data class RegionalEmergencyNumber(
        val number: String,
        val countryIso: String?
    )

    fun resolve(context: Context): String = resolveWithRegion(context).number

    fun resolveWithRegion(context: Context): RegionalEmergencyNumber {
        val iso = detectCountryIso(context)
        fromPlatformList(context)?.let { return RegionalEmergencyNumber(it, iso) }
        return RegionalEmergencyNumber(COUNTRY_EMERGENCY_NUMBERS[iso] ?: DEFAULT_EMERGENCY_NUMBER, iso)
    }

    /** Platform-aggregated emergency numbers; only usable when READ_PHONE_STATE happens to be granted. */
    private fun fromPlatformList(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephony?.emergencyNumberList
                ?.values
                ?.asSequence()
                ?.flatten()
                ?.map { it.number.trim() }
                ?.firstOrNull { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun detectCountryIso(context: Context): String? {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val fromTelephony = runCatching {
            telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
                ?: telephony?.simCountryIso?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val iso = fromTelephony ?: Locale.getDefault().country.takeIf { it.isNotBlank() }
        return iso?.trim()?.uppercase(Locale.US)
    }
}
