package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceAlertWakeClientTest {
    @Test
    fun parsesOnlyBoundedContentFreeWake() {
        val payload = parseResourceAlertWake(
            mapOf(
                "type" to "resource_alert_wake",
                "panelId" to "afad-istanbul",
                "attemptId" to "attempt:device-1",
                "receiptNonce" to "11111111-1111-4111-8111-111111111111",
                "unexpected" to "ignored",
            )
        )
        assertEquals("afad-istanbul", payload?.panelId)
        assertNull(
            parseResourceAlertWake(
                mapOf(
                    "type" to "resource_alert_wake",
                    "panelId" to "../foreign",
                    "attemptId" to "attempt-1",
                    "receiptNonce" to "11111111-1111-4111-8111-111111111111",
                )
            )
        )
        assertNull(
            parseResourceAlertWake(
                mapOf(
                    "type" to "chat",
                    "panelId" to "afad",
                    "attemptId" to "attempt-1",
                    "receiptNonce" to "11111111-1111-4111-8111-111111111111",
                )
            )
        )
    }

    @Test
    fun buildsNativeAckWithoutAlertContent() {
        val payload = ResourceAlertWakePayload(
            panelId = "afad",
            attemptId = "attempt-device-1",
            receiptNonce = "11111111-1111-4111-8111-111111111111",
        )
        val json = buildResourceAlertWakeAck(payload)
        assertEquals("afad", json.getString("panelId"))
        assertEquals("ackWake", json.getString("action"))
        assertEquals("attempt-device-1", json.getString("attemptId"))
        assertEquals("11111111-1111-4111-8111-111111111111", json.getString("receiptNonce"))
        assertEquals("native", json.getString("source"))
        assertTrue(!json.has("alertId") && !json.has("message"))
    }
}
