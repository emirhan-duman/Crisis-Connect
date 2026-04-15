package com.auralis.crisisconnect.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferIdsTest {

    @Test
    fun `accepts standard uuid transfer id`() {
        val rawId = "123e4567-e89b-12d3-a456-426614174000"

        assertEquals(rawId, normalizeSafeTransferId(rawId))
    }

    @Test
    fun `accepts underscore and hyphen safe transfer id`() {
        val rawId = "chat_file_20260317_abcdef123456"

        assertEquals(rawId, normalizeSafeTransferId(rawId))
    }

    @Test
    fun `rejects traversal style transfer id`() {
        assertNull(normalizeSafeTransferId("../cache/escape"))
        assertNull(normalizeSafeTransferId("..\\cache\\escape"))
        assertNull(normalizeSafeTransferId("unsafe.with.dot.segment"))
    }

    @Test
    fun `rejects blank or too short transfer id`() {
        assertNull(normalizeSafeTransferId(""))
        assertNull(normalizeSafeTransferId("short-id"))
    }
}
