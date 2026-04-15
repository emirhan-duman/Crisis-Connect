package com.auralis.crisisconnect.service.client

/**
 * Represents a decrypted chat message received over the secure BLE channel.
 */
data class IncomingBleMessage(
    val address: String,
    val userId: String?,
    val message: String,
    val messageId: String? = null,
    val createdAtMillis: Long? = null,
    val ttlMillis: Long? = null,
    val route: String? = null,
    val timestampMs: Long,
)
