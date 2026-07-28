package com.auralis.crisisconnect.service.sos

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.util.Log
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.getSessionFirstMessageTimes
import com.auralis.crisisconnect.data.getSessionMessageCounts
import com.auralis.crisisconnect.data.saveLocalMessage
import com.auralis.crisisconnect.data.saveLocalSosMessage
import com.auralis.crisisconnect.data.updateSosAlertText
import com.auralis.crisisconnect.messaging.InternetChatTransport
import com.auralis.crisisconnect.security.ChildProfileManager
import com.auralis.crisisconnect.service.BlePeerIdentityUtils
import com.auralis.crisisconnect.service.GattSOSServerService
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Stage 3 of the SOS escalation: automatically message the victim's emergency contacts.
 *
 * Recipient selection: the user's hand-picked emergency contacts ([SosEmergencyContactsStore])
 * when set; otherwise an automatic blend of "most messaged" and "longest known" — each contact
 * is ranked on both signals and the combined rank picks the top [MAX_RECIPIENTS]. Only
 * internet-capable contacts qualify either way.
 *
 * Alerts go out as a dedicated SOS message type (template 202 on the wire, SOS_ALERT locally) so
 * chats render them as an unmistakable emergency bubble. While the SOS runs, the victim's
 * movement is streamed as template-203 live updates that rewrite the SAME bubble in place on
 * both ends — contacts always see the latest position without the thread filling up. Stopping
 * the SOS sends a plain-text all-clear; one that can't leave the phone (offline stop) is
 * persisted and delivered by [SosReportWorker] once a network appears.
 */
object SosContactNotifier {

    enum class RecipientStatus { Waiting, Sending, Sent, Failed }

    enum class SelectionSource { Custom, Automatic }

    data class Recipient(
        val sessionCode: String,
        val name: String,
        val status: RecipientStatus
    )

    private data class AlertedRecipient(
        val contact: Contact,
        val alertMessageId: String
    )

    private const val TAG = "SosContactNotifier"
    private const val MAX_RECIPIENTS = SosEmergencyContactsStore.MAX_CONTACTS
    private const val PREFS_NAME = "sos_contact_notifier"
    private const val KEY_PENDING_ALL_CLEAR_SESSIONS = "pending_all_clear_sessions"

    private const val INITIAL_LOCATION_WAIT_MS = 12_000L
    private const val LOOP_TICK_MS = 15_000L
    private const val INITIAL_RETRY_INTERVAL_MS = 30_000L
    private const val LOCATION_REFRESH_INTERVAL_MS = 60_000L
    private const val LIVE_UPDATE_MIN_INTERVAL_MS = 60_000L
    private const val LIVE_UPDATE_MIN_MOVE_METERS = 25f

    private val notifierScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var alerted: MutableMap<String, AlertedRecipient> = linkedMapOf()

    private val _recipients = MutableStateFlow<List<Recipient>>(emptyList())
    val recipients: StateFlow<List<Recipient>> = _recipients.asStateFlow()

    /** True once recipient selection finished (distinguishes "loading" from "no one to notify"). */
    private val _selectionReady = MutableStateFlow(false)
    val selectionReady: StateFlow<Boolean> = _selectionReady.asStateFlow()

    private val _selectionSource = MutableStateFlow(SelectionSource.Automatic)
    val selectionSource: StateFlow<SelectionSource> = _selectionSource.asStateFlow()

    fun onSosStarted(
        context: Context,
        locationProvider: suspend () -> BlePeerIdentityUtils.PeerLocationSnapshot?
    ) {
        val appContext = context.applicationContext
        job?.cancel()
        alerted = linkedMapOf()
        _recipients.value = emptyList()
        _selectionReady.value = false
        // A fresh SOS supersedes any all-clear still waiting from the previous session.
        prefs(appContext).edit().remove(KEY_PENDING_ALL_CLEAR_SESSIONS).apply()
        job = notifierScope.launch {
            val targets = runCatching { selectRecipients(appContext) }
                .onFailure { Log.w(TAG, "Failed to select SOS recipients", it) }
                .getOrDefault(emptyList())
            _recipients.value = targets.map {
                Recipient(it.sessionCode, it.name, RecipientStatus.Waiting)
            }
            _selectionReady.value = true
            if (targets.isEmpty()) {
                Log.d(TAG, "No internet-capable contacts to notify")
                return@launch
            }

            val transport = InternetChatTransport(appContext)
            val pending = targets.associateBy { it.sessionCode }.toMutableMap()

            // A fix makes the first alert far more useful; wait briefly, then run with what we have.
            var lastFix = withTimeoutOrNull(INITIAL_LOCATION_WAIT_MS) {
                runCatching { locationProvider() }.getOrNull()
            }
            var lastFixFetchedAt = System.currentTimeMillis()
            var lastSentFix: BlePeerIdentityUtils.PeerLocationSnapshot? = null
            var lastInitialAttemptAt = 0L
            var lastLiveUpdateAt = 0L

            while (isActive && GattSOSServerService.isDeclared.value) {
                val now = System.currentTimeMillis()

                // Refresh the fix on its own cadence so the GPS isn't hammered every tick.
                if (now - lastFixFetchedAt >= LOCATION_REFRESH_INTERVAL_MS) {
                    runCatching { locationProvider() }.getOrNull()?.let { lastFix = it }
                    lastFixFetchedAt = now
                }

                if (!transport.isAvailable()) {
                    delay(LOOP_TICK_MS)
                    continue
                }

                // Initial alerts — rebuilt each attempt so a late send still ships a fresh fix.
                if (pending.isNotEmpty() && now - lastInitialAttemptAt >= INITIAL_RETRY_INTERVAL_MS) {
                    lastInitialAttemptAt = now
                    val text = buildSosMessage(appContext, lastFix)
                    for (contact in pending.values.toList()) {
                        updateStatus(contact.sessionCode, RecipientStatus.Sending)
                        val messageId = UUID.randomUUID().toString()
                        val sent = runCatching {
                            transport.sendText(
                                messageId = messageId,
                                text = text,
                                createdAtMs = System.currentTimeMillis(),
                                contact = contact,
                                priority = InternetChatTransport.PRIORITY_HIGH,
                                templateCode = InternetChatTransport.SOS_ALERT_TEMPLATE
                            )
                        }.getOrDefault(false)
                        if (sent) {
                            runCatching {
                                saveLocalSosMessage(appContext, contact.sessionCode, messageId, text)
                            }.onFailure {
                                Log.w(TAG, "Failed to persist SOS alert into thread", it)
                            }
                            pending.remove(contact.sessionCode)
                            alerted[contact.sessionCode] = AlertedRecipient(contact, messageId)
                            lastSentFix = lastFix
                            updateStatus(contact.sessionCode, RecipientStatus.Sent)
                        } else {
                            updateStatus(contact.sessionCode, RecipientStatus.Failed)
                        }
                    }
                }

                // Live location updates — rewrite the delivered bubbles when the victim has moved.
                val fix = lastFix
                if (
                    alerted.isNotEmpty() &&
                    fix != null &&
                    now - lastLiveUpdateAt >= LIVE_UPDATE_MIN_INTERVAL_MS &&
                    hasMovedEnough(lastSentFix, fix)
                ) {
                    lastLiveUpdateAt = now
                    val newText = buildSosMessage(appContext, fix)
                    var anySent = false
                    alerted.values.forEach { recipient ->
                        val payload = JSONObject()
                            .put("ref", recipient.alertMessageId)
                            .put("text", newText)
                            .toString()
                        val sent = runCatching {
                            transport.sendText(
                                messageId = UUID.randomUUID().toString(),
                                text = payload,
                                createdAtMs = System.currentTimeMillis(),
                                contact = recipient.contact,
                                priority = InternetChatTransport.PRIORITY_NORMAL,
                                templateCode = InternetChatTransport.SOS_LOCATION_UPDATE_TEMPLATE
                            )
                        }.getOrDefault(false)
                        if (sent) {
                            anySent = true
                            runCatching {
                                updateSosAlertText(appContext, recipient.alertMessageId, newText)
                            }
                        }
                    }
                    if (anySent) {
                        lastSentFix = fix
                    }
                }

                delay(LOOP_TICK_MS)
            }
        }
    }

    /**
     * Cancels alerting and sends the plain-text all-clear to everyone who actually received the
     * alert. Undeliverable all-clears (offline stop, transient failure) are persisted and retried
     * by [SosReportWorker] once a network appears — nobody should keep worrying after a false alarm.
     */
    fun onSosStopped(context: Context) {
        val appContext = context.applicationContext
        job?.cancel()
        job = null
        val toNotify = alerted.values.map { it.contact }
        alerted = linkedMapOf()
        _recipients.value = emptyList()
        _selectionReady.value = false
        if (toNotify.isEmpty()) {
            return
        }
        prefs(appContext).edit()
            .putStringSet(
                KEY_PENDING_ALL_CLEAR_SESSIONS,
                toNotify.map { it.sessionCode }.toSet()
            )
            .apply()
        notifierScope.launch {
            if (!deliverPendingAllClear(appContext)) {
                SosReportWorker.enqueue(appContext)
            }
        }
    }

    /**
     * Attempts delivery of the persisted all-clear messages; used both right after the stop and by
     * [SosReportWorker] retries. Returns true when nothing is left pending. Never sends while a
     * NEW SOS is already running — the fresh session owns those conversations again.
     */
    internal suspend fun deliverPendingAllClear(context: Context): Boolean {
        val appContext = context.applicationContext
        val pendingSessions = prefs(appContext)
            .getStringSet(KEY_PENDING_ALL_CLEAR_SESSIONS, null)
            ?.toMutableSet()
            ?: return true
        if (pendingSessions.isEmpty()) {
            clearPendingAllClear(appContext)
            return true
        }
        if (GattSOSServerService.isDeclared.value) {
            return true
        }
        val transport = InternetChatTransport(appContext)
        if (!transport.isAvailable()) {
            return false
        }
        val text = appContext.getString(R.string.sos_contact_message_ended)
        for (sessionCode in pendingSessions.toList()) {
            val contact = runCatching { getContact(appContext, sessionCode) }.getOrNull()
            if (contact == null || !contact.supportsInternet) {
                pendingSessions.remove(sessionCode)
                continue
            }
            val messageId = UUID.randomUUID().toString()
            val sent = runCatching {
                transport.sendText(
                    messageId = messageId,
                    text = text,
                    createdAtMs = System.currentTimeMillis(),
                    contact = contact,
                    priority = InternetChatTransport.PRIORITY_HIGH
                )
            }.getOrDefault(false)
            if (sent) {
                runCatching { saveLocalMessage(appContext, sessionCode, messageId, text) }
                pendingSessions.remove(sessionCode)
            }
        }
        prefs(appContext).edit()
            .putStringSet(KEY_PENDING_ALL_CLEAR_SESSIONS, pendingSessions)
            .apply()
        return if (pendingSessions.isEmpty()) {
            clearPendingAllClear(appContext)
            true
        } else {
            false
        }
    }

    internal fun clearPendingAllClear(context: Context) {
        prefs(context.applicationContext).edit().remove(KEY_PENDING_ALL_CLEAR_SESSIONS).apply()
    }

    /**
     * Hand-picked emergency contacts when configured; otherwise the automatic blend: contacts are
     * ranked separately by message volume (more = closer) and by conversation age (older = closer),
     * and the sum of both ranks decides — so a brand-new chat with many messages and a quiet
     * long-standing one both surface.
     */
    private suspend fun selectRecipients(context: Context): List<Contact> {
        // Child-profile contacts are never SOS recipients — an alert must reach an adult.
        val contacts = getContacts(context).filter { it.supportsInternet && !it.peerIsChild }
        if (contacts.isEmpty()) {
            _selectionSource.value = SelectionSource.Automatic
            return emptyList()
        }

        // On a child device the confirmed parents ARE the emergency contacts. Only when none
        // are usable (no parents approved yet) fall through to the normal selection — in an
        // emergency reaching someone beats reaching no one.
        if (ChildProfileManager.isEnabled(context)) {
            val parentCodes = ChildProfileManager.parentsFlow(context).value
            val parentRecipients = contacts
                .filter { it.sessionCode in parentCodes }
                .take(MAX_RECIPIENTS)
            if (parentRecipients.isNotEmpty()) {
                _selectionSource.value = SelectionSource.Custom
                return parentRecipients
            }
        }

        val chosen = runCatching { SosEmergencyContactsStore.load(context) }.getOrDefault(emptySet())
        if (chosen.isNotEmpty()) {
            val custom = contacts
                .filter { it.sessionCode in chosen }
                .take(MAX_RECIPIENTS)
            if (custom.isNotEmpty()) {
                _selectionSource.value = SelectionSource.Custom
                return custom
            }
        }

        _selectionSource.value = SelectionSource.Automatic
        val counts = getSessionMessageCounts(context)
        val firstTimes = getSessionFirstMessageTimes(context)
        val byVolume = contacts.sortedByDescending { counts[it.sessionCode] ?: 0 }
        val byAge = contacts.sortedBy { firstTimes[it.sessionCode] ?: Long.MAX_VALUE }
        val volumeRank = byVolume.withIndex().associate { (i, c) -> c.sessionCode to i }
        val ageRank = byAge.withIndex().associate { (i, c) -> c.sessionCode to i }
        return contacts
            .sortedBy { (volumeRank.getValue(it.sessionCode)) + (ageRank.getValue(it.sessionCode)) }
            .take(MAX_RECIPIENTS)
    }

    private fun buildSosMessage(
        context: Context,
        location: BlePeerIdentityUtils.PeerLocationSnapshot?
    ): String {
        return if (location != null) {
            val mapsLink = String.format(
                Locale.US,
                "https://maps.google.com/?q=%.6f,%.6f",
                location.latitude,
                location.longitude
            )
            context.getString(R.string.sos_contact_message, mapsLink)
        } else {
            context.getString(R.string.sos_contact_message_no_location)
        }
    }

    private fun hasMovedEnough(
        from: BlePeerIdentityUtils.PeerLocationSnapshot?,
        to: BlePeerIdentityUtils.PeerLocationSnapshot
    ): Boolean {
        // The very first update after an alert that shipped WITHOUT a fix should always go out.
        if (from == null) return true
        val results = FloatArray(1)
        return runCatching {
            Location.distanceBetween(
                from.latitude,
                from.longitude,
                to.latitude,
                to.longitude,
                results
            )
            results[0] >= LIVE_UPDATE_MIN_MOVE_METERS
        }.getOrDefault(false)
    }

    private fun updateStatus(sessionCode: String, status: RecipientStatus) {
        _recipients.value = _recipients.value.map {
            if (it.sessionCode == sessionCode) it.copy(status = status) else it
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
