package com.auralis.crisisconnect.service.sos

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Resolves the regional number without requesting phone-state access: network country first
 * (including roaming), then SIM country, then locale, using the existing curated country table.
 * The platform emergency-number list needs READ_PHONE_STATE, which this app does not declare.
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
        return RegionalEmergencyNumber(COUNTRY_EMERGENCY_NUMBERS[iso] ?: DEFAULT_EMERGENCY_NUMBER, iso)
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
