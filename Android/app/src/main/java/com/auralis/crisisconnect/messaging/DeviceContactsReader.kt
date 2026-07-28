package com.auralis.crisisconnect.messaging

import android.content.Context
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/** A phone-book entry normalized to an E.164 number, ready for hashed directory matching. */
data class DeviceContact(
    val displayName: String,
    val e164: String
)

/**
 * Reads the device address book and normalizes each number to E.164 using the platform's own
 * phone-number utilities (no third-party dependency). Prefers the contacts provider's
 * NORMALIZED_NUMBER column, falling back to formatting the raw number against the SIM/locale
 * region. Requires READ_CONTACTS; call from a background thread.
 */
object DeviceContactsReader {

    fun read(context: Context): List<DeviceContact> {
        val region = deviceRegion(context)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
        )
        val seen = HashSet<String>()
        val out = ArrayList<DeviceContact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val normIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
            while (cursor.moveToNext()) {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                val normalized = if (normIdx >= 0) cursor.getString(normIdx) else null
                val raw = if (numIdx >= 0) cursor.getString(numIdx) else null
                val e164 = normalized?.takeIf { it.startsWith("+") }
                    ?: raw?.let { PhoneNumberUtils.formatNumberToE164(it, region) }
                    ?: continue
                if (seen.add(e164)) {
                    out.add(DeviceContact(displayName = name.ifBlank { e164 }, e164 = e164))
                }
            }
        }
        return out
    }

    private fun deviceRegion(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val region = tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return region.uppercase(Locale.US)
    }
}
