package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AuthorityMlsTransportTest {
    @Test
    fun identifiersMatchTheCrossPlatformVectors() {
        val first = AuthorityMlsIdentifiers.conversationId(
            AuthorityMlsBinding(AuthorityMlsScopeType.AGENCY, "ankara", listOf("u2", "u1")),
        )
        val reordered = AuthorityMlsIdentifiers.conversationId(
            AuthorityMlsBinding(AuthorityMlsScopeType.AGENCY, "ankara", listOf("u1", "u2")),
        )
        assertEquals("am2_vvDRM4CAUnWzulIh43GmvwOHV2so1SHHUbGgYEQA1Rs", first)
        assertEquals(first, reordered)
        assertNotEquals(
            first,
            AuthorityMlsIdentifiers.conversationId(
                AuthorityMlsBinding(AuthorityMlsScopeType.HIERARCHY, "ankara", listOf("u1", "u2")),
            ),
        )
        assertEquals(
            "c_P1CEdhUZK8L-JW5uRkiOo4hi4sGj70AOMkOu73VV4ck",
            AuthorityMlsIdentifiers.controlEventId(
                "am2_test",
                7,
                "cc-mls:v1:dTE:ZDE",
                "payload-a",
            ),
        )
    }
}
