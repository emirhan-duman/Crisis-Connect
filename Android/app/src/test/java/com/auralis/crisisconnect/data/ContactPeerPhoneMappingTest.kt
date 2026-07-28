package com.auralis.crisisconnect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The peer phone (SPAKE2 password for auto-BT-linking) must survive the Room mapping both ways and
 * normalize consistently (blank -> null in the entity, trimmed on the way out).
 */
class ContactPeerPhoneMappingTest {

    @Test
    fun `peerPhone survives Contact - entity - Contact round trip`() {
        val contact = Contact(
            name = "Peer",
            aesKey = "",
            sessionCode = "pair-1",
            peerUid = "uid-1",
            peerPublicKey = "pk-1",
            peerPhone = "+905551112233"
        )
        assertEquals("+905551112233", contact.toEntity().toContact().peerPhone)
    }

    @Test
    fun `blank peerPhone maps to null in the entity and empty in the Contact`() {
        val entity = Contact(
            name = "Peer",
            aesKey = "",
            sessionCode = "pair-1",
            peerPhone = ""
        ).toEntity()
        assertNull(entity.peerPhone)
        assertEquals("", entity.toContact().peerPhone)
    }

    @Test
    fun `peerPhone is trimmed when mapping from the entity`() {
        val contact = ContactEntity(
            sessionCode = "pair-1",
            name = "Peer",
            aesKey = "",
            peerPhone = "  +905551112233  "
        ).toContact()
        assertEquals("+905551112233", contact.peerPhone)
    }
}
