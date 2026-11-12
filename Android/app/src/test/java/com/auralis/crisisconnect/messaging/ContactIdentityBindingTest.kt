package com.auralis.crisisconnect.messaging

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ContactIdentityBindingTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    @Test fun sharedVectorAndIdentitySubstitution() {
        val proof = ContactIdentityBinding.sign(key, "uid-ä", "ABC123", "public-key")
        assertEquals("FbhJmHAiLEAITG2CBkZTI3SW8B+fyk7diKAaJipi9pw=", proof)
        assertTrue(ContactIdentityBinding.verify(proof, key, "uid-ä", "ABC123", "public-key"))
        assertFalse(ContactIdentityBinding.verify(proof, key, "attacker", "ABC123", "public-key"))
        assertFalse(ContactIdentityBinding.verify(proof, key, "uid-ä", "OTHER1", "public-key"))
        assertFalse(ContactIdentityBinding.verify(proof, key, "uid-ä", "ABC123", "attacker-key"))
        assertFalse(ContactIdentityBinding.verify("", key, "uid-ä", "ABC123", "public-key"))
        assertFalse(ContactIdentityBinding.verify(proof, Base64.getEncoder().encodeToString(ByteArray(32)), "uid-ä", "ABC123", "public-key"))
    }
}
