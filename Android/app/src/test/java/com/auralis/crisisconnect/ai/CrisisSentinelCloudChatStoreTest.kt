package com.auralis.crisisconnect.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The panel-key derivation must stay byte-identical to the web dashboard's `activeAgencyKey`
 * (dashboard/page.tsx) or phone-written chats land in a panel the web never reads.
 */
@RunWith(RobolectricTestRunner::class)
class CrisisSentinelCloudChatStoreTest {

    @Test
    fun normalizeAgencyDocumentId_keepsCaseAndOnlyReplacesSlashes() {
        assertEquals(
            "Ankara-Merkez",
            CrisisSentinelCloudChatStore.normalizeAgencyDocumentId(" Ankara/Merkez ")
        )
        assertEquals(
            "AFAD",
            CrisisSentinelCloudChatStore.normalizeAgencyDocumentId("AFAD")
        )
        assertNull(CrisisSentinelCloudChatStore.normalizeAgencyDocumentId("   "))
        assertNull(CrisisSentinelCloudChatStore.normalizeAgencyDocumentId(null))
    }

    @Test
    fun toAgencyKey_slugifiesWithDiacriticStripping() {
        assertEquals("afad-istanbul", CrisisSentinelCloudChatStore.toAgencyKey("AFAD İstanbul"))
        assertEquals("securite-civile", CrisisSentinelCloudChatStore.toAgencyKey("Sécurité Civile"))
        assertEquals("fema", CrisisSentinelCloudChatStore.toAgencyKey("FEMA"))
        assertEquals(
            "protezione-civile",
            CrisisSentinelCloudChatStore.toAgencyKey("  Protezione   Civile  ")
        )
        assertNull(CrisisSentinelCloudChatStore.toAgencyKey("!!!"))
    }

    @Test
    fun jsonMapRoundTrip_preservesNestedStructure() {
        val original = JSONObject(
            """{"kind":"weather","weather":{"label":"İstanbul","tempC":23.4,"codes":[1,2,3],"nested":{"ok":true}}}"""
        )
        val map = CrisisSentinelCloudChatStore.jsonObjectToMap(original)
        val back = CrisisSentinelCloudChatStore.mapToJsonObject(map)

        assertEquals("weather", back.getString("kind"))
        val weather = back.getJSONObject("weather")
        assertEquals("İstanbul", weather.getString("label"))
        assertEquals(23.4, weather.getDouble("tempC"), 0.0001)
        assertEquals(3, weather.getJSONArray("codes").length())
        assertEquals(true, weather.getJSONObject("nested").getBoolean("ok"))
    }
}
