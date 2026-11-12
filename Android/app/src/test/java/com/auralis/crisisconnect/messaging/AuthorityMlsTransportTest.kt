package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AuthorityMlsTransportTest {
    @Test
    fun independentEpochCannotReplayTheLegacyOutbox() {
        val binding = AuthorityMlsBinding(AuthorityMlsScopeType.AGENCY, "ankara", listOf("u2", "u1"), resilient = true)
        assertEquals("am2_Q34xV18_kMERdYo6alt4AfiSGWizq-ISGFglVhtEjHQ", AuthorityMlsIdentifiers.conversationId(binding))
        assertEquals(emptyList<String>(), AuthorityMlsIdentifiers.legacyConversationIds(binding))
    }

    @Test
    fun identifiersMatchTheCrossPlatformVectors() {
        val first = AuthorityMlsIdentifiers.conversationId(
            AuthorityMlsBinding(AuthorityMlsScopeType.AGENCY, "ankara", listOf("u2", "u1")),
        )
        val reordered = AuthorityMlsIdentifiers.conversationId(
            AuthorityMlsBinding(AuthorityMlsScopeType.AGENCY, "ankara", listOf("u1", "u2")),
        )
        assertEquals("am2_36om86YSvFJ90u_qaV8unF2vgbwU75BrB87jRIT7wPw", first)
        assertEquals(first, reordered)
        assertEquals(
            listOf(
                "am2_lstm_ouUI1rNUblPFA_yc2mCJCVvUVN3vwSdORLDlMk",
                "am2_XQTDvUgIzCL0dW3dmJdXah8A_Wm9Bb2UDhUxYQa0eKI",
                "am2_vvDRM4CAUnWzulIh43GmvwOHV2so1SHHUbGgYEQA1Rs",
                "am2_57M0l0d9Te7ypH32OmZ89TFyAV9zUndBflhQQGk4QgI",
                "am2_fF4HTidtEcLtqh80WthF4MEYGxOZGW0_SzBYC7NS328",
                "am2_-0xwogGrbE5ImXGISHIDBuZYKFGAWpJxqA7j3Sa8vf8",
                "am2_tQZALic0fIKnNzoxfkuOa1cZQYP66Ju48Ybfs_j4N_g",
            ),
            AuthorityMlsIdentifiers.legacyConversationIds(
                AuthorityMlsBinding(AuthorityMlsScopeType.AGENCY, "ankara", listOf("u2", "u1")),
            ),
        )
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
