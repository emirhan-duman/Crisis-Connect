package com.auralis.crisisconnect.messaging

import android.app.Application
import com.auralis.crisisconnect.data.Contact
import org.json.JSONObject
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class QrContactIntroductionTest {
    private val qrKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val payload get() = JSONObject().put("rsc", "SCAN01").put("name", "Scanner")
        .put("binding", ContactIdentityBinding.sign(qrKey, "scanner", "SCAN01", "scanner-key"))
    private fun accept(json: JSONObject = payload, existing: Contact? = null, activeKey: String? = qrKey,
                       sender: String = "scanner", publicKey: String = "scanner-key") =
        QrContactIntroduction.resolve(sender, "display-owner", publicKey, json, existing, activeKey, "Unknown")

    @Test fun `QR displayer gets an internet contact without any chat message or prior row`() {
        val contact = accept()!!
        assertEquals("Scanner", contact.name)
        assertEquals("scanner", contact.peerUid)
        assertEquals("SCAN01", contact.remoteSessionCode)
        assertTrue(contact.supportsInternet)
        assertEquals(InternetConversation.pairId("scanner", "display-owner"), contact.sessionCode)
        assertFalse(contact.verified)
        assertEquals(1, contact.offlineKeyVersion)
        assertEquals(qrKey, contact.aesKey)
    }

    @Test fun `an unknown sender without possession of the shared QR cannot add itself`() {
        assertNull(accept(payload.put("binding", "")))
        assertNull(accept(activeKey = null))
        assertNull(accept(activeKey = Base64.getEncoder().encodeToString(ByteArray(32))))
    }

    @Test fun `expired or closed QR cannot create a new reciprocal contact`() {
        assertNull(accept(activeKey = null))
    }

    @Test fun `proof binds the scanner account session and public key`() {
        assertNull(accept(sender = "another-account"))
        assertNull(accept(publicKey = "another-key"))
        assertNull(accept(payload.put("rsc", "OTHER1")))
    }

    @Test fun `existing BLE pairing is healed without replacing its name or offline identity`() {
        val existing = Contact(name = "My custom name", aesKey = qrKey, sessionCode = "ble:peer",
            verified = true, verifiedIdentityKey = "peer-device", remoteSessionCode = "SCAN01")
        val contact = accept(existing = existing, activeKey = null)!!
        assertEquals("ble:peer", contact.sessionCode)
        assertEquals("My custom name", contact.name)
        assertEquals(existing.aesKey, contact.aesKey)
        assertEquals(existing.verifiedIdentityKey, contact.verifiedIdentityKey)
        assertTrue(contact.verified)
        assertEquals("scanner", contact.peerUid)
    }

    @Test fun `repeat delivery after QR closes reuses the same pinned contact`() {
        val first = accept()!!
        assertEquals(first, accept(existing = first, activeKey = null))
    }

    @Test fun `a matching QR proof cannot overwrite a different pinned identity`() {
        val existing = accept()!!.copy(peerUid = "different-person")
        assertNull(accept(existing = existing))
        assertNull(accept(existing = accept()!!.copy(peerPublicKey = "different-key")))
    }

    @Test fun `a current QR cannot rebind an unrelated older BLE contact by guessing its session code`() {
        val previous = Contact(name = "Previously paired", sessionCode = "ble:old", remoteSessionCode = "SCAN01",
            aesKey = Base64.getEncoder().encodeToString(ByteArray(32)), verified = true,
            verifiedIdentityKey = "old-device")
        assertNull(accept(existing = previous))
    }

    @Test fun `self introduction is rejected`() {
        assertNull(accept(sender = "display-owner"))
    }

    @Test fun `directory child status is preserved on a new reciprocal contact`() {
        val contact = QrContactIntroduction.resolve("scanner", "display-owner", "scanner-key", payload,
            null, qrKey, "Unknown", knownPeerIsChild = true)
        assertTrue(contact!!.peerIsChild)
    }

    @Test fun `a nameless scanner is still immediately visible with a fallback name`() {
        assertEquals("Unknown", accept(payload.put("name", " "))?.name)
    }
}
