package com.auralis.crisisconnect.service.sos

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SosContactDeliveryStateTest {
    @Test fun `offline start restores all recipients with the original IDs`() {
        val started = state(selected = listOf("a", "b"))
        val reopened = state(resuming = true, selected = listOf("changed"), ids = started.messageIds)
        assertEquals(started.messageIds, reopened.messageIds)
        assertTrue(reopened.isPending("a"))
        assertTrue(reopened.isPending("b"))
        assertNull(reopened.messageIds["changed"])
    }

    @Test fun `partial delivery restores only unsent alerts as pending`() {
        val started = state(selected = listOf("a", "b"))
        started.markAlertSent("a", null)
        val reopened = state(resuming = true, ids = started.messageIds, sent = started.sentMessageIds)
        assertFalse(reopened.isPending("a"))
        assertTrue(reopened.isPending("b"))
        assertEquals(started.messageIds["b"], reopened.messageIds["b"])
    }

    @Test fun `legacy delivered alerts are kept without alerting new contacts`() {
        val reopened = state(resuming = true, selected = listOf("new"), sent = mapOf("a" to "original"))
        assertEquals(mapOf("a" to "original"), reopened.messageIds)
        assertFalse(reopened.isPending("a"))
    }

    @Test fun `resume before selection and fresh session can select contacts`() {
        assertTrue(state(resuming = true, selected = listOf("a")).isPending("a"))
        val fresh = state(selected = listOf("b"), ids = mapOf("a" to "old"), sent = mapOf("a" to "old"))
        assertEquals(setOf("b"), fresh.messageIds.keys)
        assertTrue(fresh.isPending("b"))
    }

    @Test fun `one location success does not suppress another recipients retry at the same position`() {
        val delivery = state(selected = listOf("a", "b"))
        val old = SosContactDeliveryState.Coordinates(41.0, 29.0)
        val moved = SosContactDeliveryState.Coordinates(41.001, 29.0)
        delivery.markAlertSent("a", old)
        delivery.markAlertSent("b", old)
        assertTrue(delivery.needsLocationUpdate("a", moved))
        delivery.markLocationSent("a", moved)
        assertFalse(delivery.needsLocationUpdate("a", moved))
        assertTrue(delivery.needsLocationUpdate("b", moved))
        delivery.markLocationSent("b", moved)
        assertFalse(delivery.needsLocationUpdate("b", moved))
    }

    @Test fun `first fix after locationless alert and resume needs no movement`() {
        val delivery = state(selected = listOf("a"))
        delivery.markAlertSent("a", null)
        val fix = SosContactDeliveryState.Coordinates(41.0, 29.0)
        assertTrue(delivery.needsLocationUpdate("a", fix))
        assertFalse(delivery.needsLocationUpdate("not-alerted", fix))
        delivery.markLocationSent("a", fix)
        assertFalse(delivery.needsLocationUpdate("a", fix))
        val reopened = state(resuming = true, ids = delivery.messageIds, sent = delivery.sentMessageIds)
        assertTrue(reopened.needsLocationUpdate("a", fix))
    }

    private fun state(resuming: Boolean = false, selected: List<String> = emptyList(), ids: Map<String, String> = emptyMap(), sent: Map<String, String> = emptyMap()) =
        SosContactDeliveryState(resuming, selected, ids, sent)
}
