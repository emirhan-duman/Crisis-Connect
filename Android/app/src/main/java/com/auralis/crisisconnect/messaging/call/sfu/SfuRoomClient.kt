package com.auralis.crisisconnect.messaging.call.sfu

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import java.util.Date
import kotlinx.coroutines.tasks.await

/**
 * SFU room roster over Firestore — the coordination plane the SFU itself doesn't provide. Mirrors the
 * web `sfu-room.ts`: `sfuRooms/{roomId}/participants/{uid}` holds each member's SFU sessionId + published
 * track names so everyone can pull everyone else. Carries NO media and NO plaintext keys — MLS handshake
 * bytes (Faz C) ride the sibling `mlsMessages` subcollection as opaque blobs. Metadata-only, so the same
 * membership-gated rules the web uses already allow mobile accounts.
 */
class SfuRoomClient(
    private val roomId: String,
    private val selfUid: String,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val participants = firestore.collection("sfuRooms").document(roomId).collection("participants")
    private val mls = firestore.collection("sfuRooms").document(roomId).collection("mlsMessages")

    // sfuRooms are ephemeral (one per call) but their docs — the room meta, participant roster, and the
    // MLS handshake blobs — are never cleaned except a participant's own graceful-leave delete. An
    // `expireAt` field on every write lets a Firestore TTL policy sweep the orphans (a crashed peer's
    // roster doc, all mlsMessages, the room doc). Live calls keep extending it via heartbeat, so an
    // in-progress call is never swept.
    private fun expireAt(): Timestamp = Timestamp(Date(System.currentTimeMillis() + ROOM_TTL_MS))

    /** Announce (or update) my SFU identity so other members can pull my tracks and show my profile. */
    fun publishSelf(
        name: String,
        photoUrl: String?,
        cameraOn: Boolean,
        muted: Boolean,
        sessionId: String,
        tracks: List<SfuPublishedTrack>,
    ) {
        val doc = hashMapOf(
            "uid" to selfUid,
            "name" to name,
            "photoUrl" to photoUrl,
            "cameraOn" to cameraOn,
            "muted" to muted,
            "sessionId" to sessionId,
            "tracks" to tracks.map { mapOf("trackName" to it.trackName, "kind" to it.kind, "source" to it.source) },
            "updatedAt" to FieldValue.serverTimestamp(),
            "expireAt" to expireAt(),
        )
        runCatching { participants.document(selfUid).set(doc) }
    }

    /** Remove myself from the room roster on hang-up (best-effort). */
    fun leave() {
        runCatching { participants.document(selfUid).delete() }
    }

    /**
     * Liveness heartbeat: refresh my roster entry's `updatedAt` so peers can tell a live member from a
     * crashed/killed one (an ungraceful exit never deletes the doc — only a stale timestamp reveals it).
     */
    fun heartbeat() {
        runCatching {
            participants.document(selfUid)
                .update(mapOf("updatedAt" to FieldValue.serverTimestamp(), "expireAt" to expireAt()))
        }
    }

    /**
     * Atomically claim the MLS group-creator role on the room doc (mirrors web `claimMlsCreator`). Exactly
     * one member gets `true` (race-free); everyone else joins the group the creator makes. Each call uses
     * a fresh ephemeral room id, so a stale creator is never an issue.
     */
    suspend fun claimMlsCreator(): Boolean {
        val ref = firestore.collection("sfuRooms").document(roomId)
        return firestore.runTransaction { tx ->
            val creator = tx.get(ref).getString("mlsCreator")
            if (creator == null) {
                tx.set(ref, mapOf("mlsCreator" to selfUid, "createdAt" to FieldValue.serverTimestamp(), "expireAt" to expireAt()), SetOptions.merge())
                true
            } else {
                creator == selfUid
            }
        }.await()
    }

    /** Subscribe to the room roster; delivers everyone except me. Returns the registration to remove. */
    fun listenRoster(onRoster: (List<SfuRemoteParticipant>) -> Unit): ListenerRegistration =
        participants.addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val remotes = ArrayList<SfuRemoteParticipant>()
            for (d in snap.documents) {
                if (d.id == selfUid) continue
                val sessionId = d.getString("sessionId") ?: continue
                @Suppress("UNCHECKED_CAST")
                val rawTracks = d.get("tracks") as? List<Map<String, Any?>> ?: continue
                val tracks = rawTracks.mapNotNull { t ->
                    val trackName = t["trackName"] as? String ?: return@mapNotNull null
                    SfuPublishedTrack(
                        trackName = trackName,
                        kind = t["kind"] as? String ?: "audio",
                        source = t["source"] as? String ?: "mic",
                    )
                }
                remotes.add(
                    SfuRemoteParticipant(
                        uid = d.id,
                        sessionId = sessionId,
                        tracks = tracks,
                        updatedAtMillis = d.getTimestamp("updatedAt")?.toDate()?.time,
                    )
                )
            }
            onRoster(remotes)
        }

    // ---- MLS handshake relay (Faz C). Opaque blobs; the SFU + Firestore never see keys/plaintext. ----

    /** Broadcast one opaque MLS handshake message (Base64) to the room. */
    fun publishMlsMessage(payloadBase64: String) {
        runCatching {
            mls.add(hashMapOf("from" to selfUid, "payload" to payloadBase64, "createdAt" to FieldValue.serverTimestamp(), "expireAt" to expireAt()))
        }
    }

    /**
     * Subscribe to peers' MLS messages, INCLUDING any backlog present at attach. Room ids are ephemeral
     * (fresh UUID per call), so everything in this collection belongs to this call's handshake — and the
     * peer can publish its KeyPackage before our listener attaches (our creator-claim transaction takes a
     * network round-trip first), which a skip-the-backlog listener would drop, deadlocking the handshake.
     * Batches are processed in createdAt order (handshake messages are order-sensitive); server timestamps
     * can be momentarily null on latency-compensated local echoes, but we never receive our own messages
     * here, so every doc we process has a resolved timestamp.
     */
    fun listenMlsMessages(onMessage: (payloadBase64: String) -> Unit): ListenerRegistration {
        val seen = HashSet<String>()
        return mls.addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            snap.documentChanges
                .filter { it.type == DocumentChange.Type.ADDED && seen.add(it.document.id) }
                .map { it.document }
                .filter { it.getString("from") != selfUid }
                .sortedBy { it.getTimestamp("createdAt")?.toDate()?.time ?: Long.MAX_VALUE }
                .forEach { d -> d.getString("payload")?.let(onMessage) }
        }
    }

    private companion object {
        // 12h: comfortably longer than any real authority call, refreshed by heartbeat while live.
        private const val ROOM_TTL_MS = 12L * 60 * 60 * 1000
    }
}