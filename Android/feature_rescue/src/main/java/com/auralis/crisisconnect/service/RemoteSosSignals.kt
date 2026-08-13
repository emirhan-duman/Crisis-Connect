package com.auralis.crisisconnect.service

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Live internet-side SOS signals for the rescuer's own agency panel
 * (`agencyPanels/{slug}/signals` — the same collection the web dashboard maps).
 *
 * Until now the phone rescuer only saw victims inside BLE range; signals self-reported over the
 * internet (reportSosSignal) or sighted by OTHER rescuers were dashboard-only. This is read-only:
 * signal WRITES stay with CrisisLink / the victim uplink.
 */
object RemoteSosSignals {

    private const val TAG = "RemoteSosSignals"
    private const val PANEL_COLLECTION = "agencyPanels"
    private const val SIGNALS_COLLECTION = "signals"
    private const val QUERY_LIMIT = 50L
    const val FRESHNESS_WINDOW_MS = 24L * 60 * 60 * 1000

    data class RemoteSignal(
        val signalId: String,
        val victimName: String?,
        val victimBatteryPercent: Int?,
        val latitude: Double?,
        val longitude: Double?,
        val source: String,
        val status: String,
        val lastSeenMillis: Long,
        val reporterCount: Int
    )

    /**
     * Emits the freshest active signals for the signed-in rescuer's panel AND its ancestor
     * panels, merged; empty when signed out.
     *
     * Ancestors matter because victim self-reports route to the NATIONAL panel (country → agency,
     * e.g. `afad`) while a sub-agency rescuer's own slug is the child (e.g. `afad-istanbul`) —
     * listening to the own panel alone showed an empty list next to a live signal. The rules
     * already grant child→ancestor reads (canReadAgencyByHierarchy), mirroring the web dashboard.
     */
    fun observe(context: Context): Flow<List<RemoteSignal>> = callbackFlow {
        val appContext = context.applicationContext
        val registrations = mutableListOf<ListenerRegistration>()
        val panelIds = runCatching { resolvePanelIds(appContext) }.getOrDefault(emptyList())
        if (panelIds.isEmpty()) {
            trySend(emptyList())
        } else {
            val lock = Any()
            val signalsByPanel = mutableMapOf<String, List<RemoteSignal>>()
            fun publishMerged() {
                val merged = synchronized(lock) {
                    signalsByPanel.values.flatten()
                        .groupBy { it.signalId.lowercase(Locale.US) }
                        .map { (_, copies) -> copies.maxBy { it.lastSeenMillis } }
                        .sortedByDescending { it.lastSeenMillis }
                }
                trySend(merged)
            }
            panelIds.forEach { panelId ->
                val query = FirebaseFirestore.getInstance()
                    .collection(PANEL_COLLECTION)
                    .document(panelId)
                    .collection(SIGNALS_COLLECTION)
                    .orderBy("lastSeenAt", Query.Direction.DESCENDING)
                    .limit(QUERY_LIMIT)
                registrations += query.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Remote signal listener failed for panel=$panelId", error)
                        synchronized(lock) { signalsByPanel[panelId] = emptyList() }
                        publishMerged()
                        return@addSnapshotListener
                    }
                    val now = System.currentTimeMillis()
                    val signals = snapshot?.documents.orEmpty().mapNotNull { doc ->
                        val lastSeenMillis = doc.getLong("lastSeenMillis")
                            ?: doc.getTimestamp("lastSeenAt")?.toDate()?.time
                            ?: return@mapNotNull null
                        if (now - lastSeenMillis > FRESHNESS_WINDOW_MS) return@mapNotNull null
                        val status = doc.getString("status")?.lowercase(Locale.US) ?: "active"
                        if (status != "active") return@mapNotNull null
                        // Resolved wins, mirroring the web's readEffectiveResolveMillis precedence
                        // (lib/dashboard/signal-resolution.ts). Resolve stamps come from TWO
                        // writers: the victim's own stop (top-level resolvedAt/resolvedMillis via
                        // reportSosSignal) and an operator resolve (ops.resolvedAt*). An operator
                        // REOPEN stamps ops.reopenedAt* WITHOUT clearing either resolve, and the
                        // victim resolve leaves lastSeenMillis == resolvedMillis — so a reopen must
                        // cancel the resolve here too, or the reopened SOS stays hidden on every
                        // phone until the beacon is next heard. Only a sighting NEWER than a
                        // still-effective resolve reactivates.
                        val ops = doc.get("ops") as? Map<*, *>
                        val resolveMillis = maxOf(
                            epochMillisOrZero(doc.get("resolvedMillis")),
                            epochMillisOrZero(doc.get("resolvedAt")),
                            epochMillisOrZero(ops?.get("resolvedAtMillis")),
                            epochMillisOrZero(ops?.get("resolvedAt"))
                        )
                        val reopenMillis = maxOf(
                            epochMillisOrZero(ops?.get("reopenedAtMillis")),
                            epochMillisOrZero(ops?.get("reopenedAt"))
                        )
                        val resolveInEffect = resolveMillis > 0L && reopenMillis < resolveMillis
                        if (resolveInEffect && lastSeenMillis <= resolveMillis) {
                            return@mapNotNull null
                        }
                        val gps = (doc.get("gps") as? Map<*, *>)
                            ?: (doc.get("victimGps") as? Map<*, *>)
                            ?: (doc.get("estimatedVictimGps") as? Map<*, *>)
                        RemoteSignal(
                            signalId = doc.getString("signalId") ?: doc.id,
                            victimName = doc.getString("victimName")
                                ?.trim()?.takeIf { it.isNotEmpty() },
                            victimBatteryPercent = doc.getLong("victimBatteryPercent")
                                ?.toInt()?.takeIf { it in 0..100 },
                            latitude = (gps?.get("lat") as? Number)?.toDouble(),
                            longitude = (gps?.get("lon") as? Number)?.toDouble(),
                            source = doc.getString("source") ?: "unknown",
                            status = status,
                            lastSeenMillis = lastSeenMillis,
                            reporterCount = (doc.get("reporterIds") as? List<*>)?.size ?: 0
                        )
                    }
                    synchronized(lock) { signalsByPanel[panelId] = signals }
                    publishMerged()
                }
            }
        }
        awaitClose { registrations.forEach { it.remove() } }
    }

    /**
     * Epoch millis from the shapes these stamps actually land in on-device: epoch numbers
     * (resolvedMillis and the ops `*Millis` twins the SOS console always writes alongside its ISO
     * strings) and Firestore Timestamps (reportSosSignal's resolvedAt). Zero when absent/invalid so
     * callers can fold with maxOf, matching the web helper.
     */
    private fun epochMillisOrZero(value: Any?): Long {
        val millis = when (value) {
            is Number -> value.toLong()
            is Timestamp -> value.toDate().time
            is Date -> value.time
            else -> 0L
        }
        return if (millis > 0L) millis else 0L
    }

    /** Own panel first, then its ancestors (parent/national), distinct, order-preserving. */
    private suspend fun resolvePanelIds(context: Context): List<String> {
        val slug = resolveAgencySlug(context) ?: return emptyList()
        val panelIds = linkedSetOf(slug)
        runCatching {
            val panelDoc = FirebaseFirestore.getInstance()
                .collection(PANEL_COLLECTION)
                .document(slug)
                .get()
                .await()
            (panelDoc.get("ancestorPanelIds") as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf { id -> id.isNotEmpty() } }
                ?.forEach { panelIds += it }
            panelDoc.getString("parentPanelId")
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { panelIds += it }
        }.onFailure { throwable ->
            Log.w(TAG, "Panel hierarchy lookup failed for $slug", throwable)
        }
        return panelIds.toList()
    }

    /**
     * The rescuer's panel id, mirroring the CrisisLink/web resolution chain:
     * users/{uid} slug-ish fields first, else a slugified agency-ish field.
     */
    private suspend fun resolveAgencySlug(context: Context): String? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: LocalKeyStorage.getSavedUid(context)?.trim()
        if (uid.isNullOrBlank()) return null
        val snapshot = FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .get()
            .await()
        if (!snapshot.exists()) return null
        val slugFields = listOf("agencySlug", "panelSlug", "panelId", "agencyPanelId", "agencyKey", "panelKey")
        slugFields.firstNotNullOfOrNull { field ->
            snapshot.getString(field)?.trim()?.takeIf { it.isNotEmpty() }
        }?.let { return it }
        val agencyFields = listOf("agency", "agencyName", "authority", "institution", "organization")
        val agency = agencyFields.firstNotNullOfOrNull { field ->
            snapshot.getString(field)?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return null
        return slugify(agency).takeIf { it.isNotEmpty() }
    }

    private fun slugify(value: String): String {
        val lowered = value.trim().lowercase(Locale.US)
        val builder = StringBuilder(lowered.length)
        var pendingDash = false
        lowered.forEach { ch ->
            when {
                ch.isLetterOrDigit() -> {
                    if (pendingDash && builder.isNotEmpty()) {
                        builder.append('-')
                    }
                    builder.append(ch)
                    pendingDash = false
                }

                builder.isNotEmpty() -> pendingDash = true
            }
        }
        return builder.toString().trim('-')
    }
}
