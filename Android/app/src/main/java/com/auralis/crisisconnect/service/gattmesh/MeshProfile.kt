package com.auralis.crisisconnect.service.gattmesh

import android.content.Context
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.security.AuthorityMeshKeyStore
import com.auralis.crisisconnect.util.UUIDGenerator
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Admission control for a mesh profile.
 *
 * - [OpenToAll]: anyone may join. RoleProof verification still happens but only decorates peers
 *   with a verified-role badge (current public mesh behavior).
 * - [RequireVerifiedRole]: only peers that present a valid RoleProof for one of [allowedRoles] are
 *   admitted; unverified peers are dropped and their chat traffic is rejected until verified.
 */
sealed interface AdmissionPolicy {
    data object OpenToAll : AdmissionPolicy

    data class RequireVerifiedRole(
        val allowedRoles: Set<String>,
        val handshakeTimeoutMs: Long = 12_000L,
        val dropUnverifiedPeers: Boolean = true,
        val gateMessagesUntilVerified: Boolean = true,
    ) : AdmissionPolicy
}

/**
 * Everything that differs between two GATT mesh networks running side by side on the same radio.
 *
 * The mesh transport ([GattMeshForegroundService]) reads every network-specific value from a
 * profile so a second, role-gated "authority" mesh can reuse the entire transport/crypto/relay
 * stack while broadcasting on a separate service UUID and encrypting with a separate key.
 *
 * Captures the three axes the design is built around: `serviceUuid` + `admission` + key material
 * ([payloadKeyId] / [derivePayloadKey]). The remaining fields are only identity/namespacing so the
 * two profiles never collide on the shared scan coordinator, GATT server, prefs, or notifications.
 */
class MeshProfile(
    val id: String,
    val serviceUuid: UUID,
    val messageInUuid: UUID,
    val messageOutUuid: UUID,
    val payloadKeyId: String,
    /**
     * Returns the AES-GCM payload key for this network, or null when the key is unavailable
     * (e.g. an authority device that has not been provisioned yet). When null, the mesh must not
     * start its runtime.
     */
    val derivePayloadKey: (Context) -> ByteArray?,
    val admission: AdmissionPolicy,
    val advertiseManufacturerData: Boolean,
    val enabledPrefKey: String,
    val scanCoordinatorOwner: String,
    val notificationChannelId: String,
    val notificationId: Int,
    /**
     * User-facing notification/channel labels. Profile-specific so the authority mesh never shows
     * the public mesh's "General chat" strings (which made the rescue mesh look like the open mesh).
     */
    val notificationTitleRes: Int,
    val notificationTextConnectedRes: Int,
    val notificationTextWaitingRes: Int,
    val channelNameRes: Int,
    val channelDescriptionRes: Int,
    /**
     * Display name for this mesh's general chat — used for the saved conversation/contact row and the
     * chat screen title. Authority uses its own label so it never shows up as the public "Genel" chat.
     */
    val chatTitleRes: Int,
)

object MeshProfiles {

    /** Public, open mesh — identical behavior/values to the original GattMeshForegroundService. */
    val PUBLIC = MeshProfile(
        id = "public",
        serviceUuid = UUID.fromString("6f4b5d5e-2e0a-4f13-9b89-7d9f3f1d1001"),
        messageInUuid = UUID.fromString("6f4b5d5e-2e0a-4f13-9b89-7d9f3f1d1002"),
        messageOutUuid = UUID.fromString("6f4b5d5e-2e0a-4f13-9b89-7d9f3f1d1003"),
        payloadKeyId = "public-v1",
        derivePayloadKey = { context -> derivePublicPayloadKey(context) },
        admission = AdmissionPolicy.OpenToAll,
        advertiseManufacturerData = true,
        enabledPrefKey = "advanced_public_mesh_enabled",
        scanCoordinatorOwner = "gattmesh-foreground-service",
        notificationChannelId = "gatt_mesh_channel",
        notificationId = 3055,
        notificationTitleRes = R.string.gatt_mesh_notification_title,
        notificationTextConnectedRes = R.string.gatt_mesh_notification_text_connected,
        notificationTextWaitingRes = R.string.gatt_mesh_notification_text_waiting,
        channelNameRes = R.string.gatt_mesh_channel_name,
        channelDescriptionRes = R.string.gatt_mesh_channel_description,
        chatTitleRes = R.string.mesh_chat_general_title,
    )

    /**
     * Authority-only mesh — role-gated (admin/fieldteam) and encrypted with a provisioned shared
     * group key. Broadcasts on a separate UUID in the Crisis Connect 0xCCxx range so it never
     * collides with the public mesh. [derivePayloadKey] returns null until the device has been
     * provisioned with the group key, which keeps the runtime off on unprivileged devices.
     */
    val AUTHORITY = MeshProfile(
        id = "authority",
        serviceUuid = UUIDGenerator.fromAssignedNumber(0xCCA1),
        messageInUuid = UUIDGenerator.fromAssignedNumber(0xCCA2),
        messageOutUuid = UUIDGenerator.fromAssignedNumber(0xCCA3),
        payloadKeyId = "authority-v1",
        derivePayloadKey = { context -> AuthorityMeshKeyStore.loadGroupKey(context) },
        admission = AdmissionPolicy.RequireVerifiedRole(
            allowedRoles = setOf("admin", "fieldteam"),
        ),
        advertiseManufacturerData = true,
        enabledPrefKey = "advanced_authority_mesh_enabled",
        scanCoordinatorOwner = "authoritymesh-foreground-service",
        notificationChannelId = "authority_mesh_channel",
        notificationId = 3065,
        notificationTitleRes = R.string.authority_mesh_notification_title,
        notificationTextConnectedRes = R.string.authority_mesh_notification_text_connected,
        notificationTextWaitingRes = R.string.authority_mesh_notification_text_waiting,
        channelNameRes = R.string.authority_mesh_channel_name,
        channelDescriptionRes = R.string.authority_mesh_channel_description,
        chatTitleRes = R.string.authority_mesh_chat_title,
    )

    private fun derivePublicPayloadKey(context: Context): ByteArray {
        val material = buildString(128) {
            append("dcs-gattmesh-public-payload-key-v1")
            append('|')
            append(context.packageName)
        }.toByteArray(StandardCharsets.UTF_8)
        return runCatching {
            MessageDigest.getInstance("SHA-256").digest(material)
        }.getOrElse {
            ByteArray(32) { index -> (index + 1).toByte() }
        }
    }
}
