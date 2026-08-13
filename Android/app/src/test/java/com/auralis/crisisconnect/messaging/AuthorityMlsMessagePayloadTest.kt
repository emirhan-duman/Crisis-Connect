package com.auralis.crisisconnect.messaging

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthorityMlsMessagePayloadTest {
    @Test
    fun roundTripsCanonicalCrossPlatformEnvelope() {
        val payload = AuthorityMlsMessagePayload(
            recipientUid = "peer-uid",
            recipientName = "Peer",
            senderName = "Sender",
            text = "hello",
            sentAtMillis = 1_700_000_000_000,
            attachments = listOf(ChannelAttachment(
                path = "authorityMessageAttachments/am2_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/sender-uid/00000000-0000-4000-8000-000000000051",
                nonce = "AAAAAAAAAAAAAAAA",
                key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                name = "incident.pdf",
                mime = "application/pdf",
                size = 42,
            )),
            replyToId = "m_parent",
        )
        assertEquals(payload, AuthorityMlsMessagePayloadCodec.decode(AuthorityMlsMessagePayloadCodec.encode(payload)))
    }

    @Test
    fun rejectsTypeConfusionAndMissingFileKey() {
        val typeConfused = JSONObject()
            .put("version", 2).put("kind", "message")
            .put("recipientUid", 123).put("recipientName", "Peer").put("senderName", "Sender")
            .put("text", "hello").put("sentAtMillis", 1).put("attachments", org.json.JSONArray())
        assertThrows(Throwable::class.java) {
            AuthorityMlsMessagePayloadCodec.decode(typeConfused.toString().toByteArray())
        }
        val missingKey = JSONObject()
            .put("version", 2).put("kind", "message")
            .put("recipientUid", "peer").put("recipientName", "Peer").put("senderName", "Sender")
            .put("text", "file").put("sentAtMillis", 1)
            .put("attachments", org.json.JSONArray().put(JSONObject()
                .put("path", "authorityMessageAttachments/am2_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/sender/00000000-0000-4000-8000-000000000051")
                .put("nonce", "AAAAAAAAAAAAAAAA").put("name", "x").put("mime", "x").put("size", 1)))
        assertThrows(Throwable::class.java) {
            AuthorityMlsMessagePayloadCodec.decode(missingKey.toString().toByteArray())
        }
    }
}
