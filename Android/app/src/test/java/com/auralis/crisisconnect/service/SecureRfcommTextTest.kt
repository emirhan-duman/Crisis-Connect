package com.auralis.crisisconnect.service

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class SecureRfcommTextTest {
    @Test fun unavailableOrMalformedKeyCannotProduceAnyFrame() {
        for (key in listOf(null, ByteArray(0), ByteArray(17))) assertNull(SecureRfcommText.seal(key, "message-1", "help"))
    }
    @Test fun legitimateEncryptedWireFormatStillDecryptsWithExistingReceiver() {
        val key = ByteArray(32) { 4 }; val text = "yardım\nhelp"
        val frame = requireNotNull(SecureRfcommText.seal(key, "message-1", text))
        assertTrue(frame.startsWith("SEC_MSG|message-1|")); assertFalse(frame.contains(text))
        val storeForwardId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d~1762627000000"
        val sfFrame = requireNotNull(SecureRfcommText.seal(key, storeForwardId, text))
        assertTrue(sfFrame.startsWith("SEC_MSG|$storeForwardId|"))
        val parts = frame.trim().split('|')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(parts[2])))
        assertEquals(text, cipher.doFinal(Base64.getDecoder().decode(parts[3])).toString(Charsets.UTF_8))
    }
    @Test fun malformedMessageIdentifierCannotInjectAnotherFrame() {
        assertNull(SecureRfcommText.seal(ByteArray(32), "id\nMSG|forged", "help"))
    }
}
