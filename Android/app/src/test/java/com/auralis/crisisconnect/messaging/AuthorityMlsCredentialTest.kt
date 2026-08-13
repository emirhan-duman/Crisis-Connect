package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthorityMlsCredentialTest {
    @Test
    fun `credential round trips account and device without delimiter ambiguity`() {
        val encoded = AuthorityMlsCredential.encode("uid:with/slash", "cc-00112233445566778899aabb")
        assertEquals(
            AuthorityMlsCredential.Parsed("uid:with/slash", "cc-00112233445566778899aabb"),
            AuthorityMlsCredential.decode(encoded),
        )
    }

    @Test
    fun `credential rejects non canonical and empty identities`() {
        assertNull(AuthorityMlsCredential.decode("cc-mls:v1:not base64:x"))
        assertThrows(IllegalArgumentException::class.java) {
            AuthorityMlsCredential.encode("", "device")
        }
    }
}
