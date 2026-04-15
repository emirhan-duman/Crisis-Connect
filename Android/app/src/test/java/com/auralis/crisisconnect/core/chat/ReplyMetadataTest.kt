package com.auralis.crisisconnect.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyMetadataTest {

    @Test
    fun parseReplyMetadata_acceptsHeaderOnlyReply() {
        val parsed = parseReplyMetadata("↪[abc-123] Nuri|Merhaba")

        requireNotNull(parsed)
        assertEquals("abc-123", parsed.targetUuid)
        assertEquals("Nuri", parsed.authorLabel)
        assertEquals("Merhaba", parsed.preview)
        assertEquals("", parsed.body)
    }

    @Test
    fun previewTextForReplyTarget_prefersVisibleBody() {
        val preview = previewTextForReplyTarget("↪[abc-123] Nuri|Eski mesaj\nYeni cevap")

        assertEquals("Yeni cevap", preview)
    }

    @Test
    fun stripReplyMetadata_returnsNullForHeaderOnlyReply() {
        val stripped = stripReplyMetadata("↪[abc-123] Nuri|Merhaba")

        assertNull(stripped)
    }
}
