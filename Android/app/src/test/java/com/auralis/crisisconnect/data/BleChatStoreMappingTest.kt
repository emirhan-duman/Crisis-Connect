package com.auralis.crisisconnect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleChatStoreMappingTest {

    @Test
    fun `toBleChatMessage preserves image metadata and status override`() {
        val source = ChatMessage(
            id = 1L,
            sessionCode = "ABC123",
            messageUuid = "msg-1",
            text = "",
            messageType = MessageType.IMAGE,
            imageFilePath = "/tmp/image.jpg",
            imageThumbnailPath = "/tmp/image-thumb.jpg",
            imageWidth = 1280,
            imageHeight = 720,
            imageMimeType = "image/jpeg",
            isLocal = true,
            isRead = false,
            timestampMillis = 1234L,
            deliveryStatus = MessageDeliveryStatus.SENDING
        )

        val mapped = source.toBleChatMessage(statusOverride = BleMessageStatus.FAILED)

        assertEquals("msg-1", mapped.id)
        assertEquals(MessageType.IMAGE, mapped.messageType)
        assertEquals("/tmp/image.jpg", mapped.imageFilePath)
        assertEquals("/tmp/image-thumb.jpg", mapped.imageThumbnailPath)
        assertEquals(1280, mapped.imageWidth)
        assertEquals(720, mapped.imageHeight)
        assertEquals("image/jpeg", mapped.imageMimeType)
        assertEquals(BleMessageStatus.FAILED, mapped.status)
    }

    @Test
    fun `toBleChatMessage defaults remote messages to delivered`() {
        val source = ChatMessage(
            id = 2L,
            sessionCode = "XYZ789",
            messageUuid = "msg-2",
            text = "hello",
            isLocal = false,
            isRead = false,
            timestampMillis = 5678L
        )

        val mapped = source.toBleChatMessage()

        assertEquals(BleMessageStatus.DELIVERED, mapped.status)
        assertNull(mapped.imageFilePath)
    }
}
