package com.auralis.crisisconnect.messaging

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the agency-channel AES-256-GCM to a golden vector produced by the WEB `lib/messaging/agency.ts`
 * (same key/nonce/AAD=agencySlug). If this fails, phone field teams can't decrypt what authorities
 * send from the dashboard (or vice versa).
 */
@RunWith(RobolectricTestRunner::class)
class AgencyMessageCryptoTest {

    private val keyBase64 = "AwoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dw="
    private val nonceBase64 = "BRIfLDlGU2BteoeU"
    private val ciphertextBase64 =
        "ddAjrPq5cGG/7e/3SBGMHMX5C4d19wZgTQDF4RPh7YMhJvnOMDvLG7ehAGDUjkt6HA3pI2FnfAE="
    private val agencySlug = "beylikduzu-afet"
    private val expectedText = "Ekip 3 bölgeye ulaştı, 2 yaralı var."

    private fun key() = AgencyKey(
        keyId = "agency-v1",
        key = Base64.decode(keyBase64, Base64.NO_WRAP),
        agencySlug = agencySlug
    )

    @Test
    fun decrypts_web_golden_vector() {
        assertEquals(expectedText, AgencyMessageCrypto.decrypt(key(), nonceBase64, ciphertextBase64))
    }

    @Test
    fun encrypt_then_decrypt_roundTrips() {
        val agencyKey = key()
        val text = "Ekip 5 sahaya intikal etti."
        val (nonce, ciphertext) = AgencyMessageCrypto.encrypt(agencyKey, text)
        assertEquals(text, AgencyMessageCrypto.decrypt(agencyKey, nonce, ciphertext))
    }
}
