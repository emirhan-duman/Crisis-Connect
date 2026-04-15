package com.auralis.crisisconnect.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64

class AesGcmTest {

    @Test
    fun `encrypt and decrypt round trip`() {
        val key = Base64.getDecoder().decode("mFeZb0Qp0+R0J0WfPZl7Y0bY2ypu7D5p4R2QJ8x3sDU=")
        val plaintext = "hello secure world".toByteArray(Charsets.UTF_8)
        val aad = canonicalAad(
            uuid = "123",
            mime = "audio/mp4",
            durationMs = 1500L,
            chunkSize = 512,
            totalBytes = plaintext.size,
            chunkCount = 1,
            encrypted = true
        )

        val encrypted = AesGcm.encryptAesGcm(key, plaintext, aad)
        val decrypted = AesGcm.decryptAesGcm(key, encrypted.iv, encrypted.cipher, aad)

        assertNotEquals(plaintext.toList(), encrypted.cipher.toList())
        assertArrayEquals(plaintext, decrypted)
    }
}
