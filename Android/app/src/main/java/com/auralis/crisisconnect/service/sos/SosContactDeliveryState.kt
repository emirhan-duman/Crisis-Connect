package com.auralis.crisisconnect.service.sos

import android.location.Location
import java.util.UUID

/** Durable alert identities, with independent location acknowledgements for each recipient. */
internal class SosContactDeliveryState(
    resuming: Boolean,
    selectedIds: List<String>,
    messageIds: Map<String, String>,
    sentMessageIds: Map<String, String>
) {
    val messageIds: Map<String, String> = (if (resuming) messageIds + sentMessageIds else emptyMap())
        .ifEmpty { selectedIds.associateWith { UUID.randomUUID().toString() } }
    val sentMessageIds: MutableMap<String, String> =
        (if (resuming) sentMessageIds else emptyMap()).toMutableMap()
    private val lastLocations = mutableMapOf<String, Coordinates>()

    data class Coordinates(val latitude: Double, val longitude: Double)

    fun isPending(recipientId: String): Boolean =
        recipientId in messageIds && recipientId !in sentMessageIds

    fun markAlertSent(recipientId: String, location: Coordinates?) {
        val messageId = messageIds[recipientId] ?: return
        sentMessageIds[recipientId] = messageId
        if (location == null) lastLocations.remove(recipientId) else lastLocations[recipientId] = location
    }

    fun needsLocationUpdate(recipientId: String, location: Coordinates): Boolean {
        if (recipientId !in sentMessageIds) return false
        val previous = lastLocations[recipientId] ?: return true
        val distance = FloatArray(1)
        Location.distanceBetween(previous.latitude, previous.longitude, location.latitude, location.longitude, distance)
        return distance[0] >= 25f
    }

    fun markLocationSent(recipientId: String, location: Coordinates) {
        lastLocations[recipientId] = location
    }
}
