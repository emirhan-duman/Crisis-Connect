package com.auralis.crisisconnect.screens.Chat

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFormattersTest {

    private val formatter = SimpleDateFormat("HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Test
    fun delayedRemoteMessage_showsSentAndReceivedTimes() {
        val label = formatMessageTimestampLabel(
            formatter = formatter,
            displayTimestampMillis = 10 * 60 * 60 * 1000L + 10 * 60 * 1000L,
            originalTimestampMillis = 10 * 60 * 60 * 1000L,
            isLocal = false
        )

        assertEquals("10:00 ➞ 10:10", label)
    }

    @Test
    fun localMessage_showsSingleTimestamp() {
        val label = formatMessageTimestampLabel(
            formatter = formatter,
            displayTimestampMillis = 10 * 60 * 60 * 1000L,
            originalTimestampMillis = 9 * 60 * 60 * 1000L,
            isLocal = true
        )

        assertEquals("10:00", label)
    }
}
