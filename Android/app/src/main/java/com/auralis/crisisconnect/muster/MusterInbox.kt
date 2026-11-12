package com.auralis.crisisconnect.muster

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.crisisconnect.R
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class MusterCardModel(
    val panel: String, val session: String, val kind: String, val title: String,
    val instructions: String, val closed: Boolean, val replyState: String,
    val answer: String?, val note: String, val helpOpen: Boolean,
    val startedAt: Long, val deadlineAt: Long,
    val responseConfig: MusterResponseConfig? = null, val configurationAvailable: Boolean = true,
    val optionId: String? = null, val localVoice: MusterVoiceRef? = null, val remoteVoice: MusterRemoteVoice? = null, val helpVoice: MusterRemoteVoice? = null,
) {
    val key get() = "$panel:$session"
    fun needsResponse(now: Long): Boolean = !closed && replyState.isBlank() &&
        (kind != "drill" || deadlineAt == 0L || deadlineAt > now)
}

internal fun musterCards(assignments: List<JSONObject>, events: List<MusterEvent>, fallbackTitle: String, fallbackInstructions: String? = null): List<MusterCardModel> {
    val latest = events.associateBy { "${it.panel}:${it.session}" }
    val cards = assignments.mapNotNull { row ->
        val session = row.optJSONObject("session") ?: return@mapNotNull null
        val panel = row.optString("panelId"); val id = session.optString("id")
        val kind = session.optString("kind")
        if (panel.isBlank() || id.isBlank() || kind !in setOf("live", "drill")) return@mapNotNull null
        val response = row.optJSONObject("response")
        val serverAnswer = response?.optString("answer")?.takeUnless { it.isBlank() || it == "null" }
        val event = latest["$panel:$id"]?.takeUnless { saved ->
            saved.state == "sent" && serverAnswer != null &&
                (response!!.optLong("answeredAtMillis") > saved.answeredAt ||
                    (response.optLong("answeredAtMillis") == saved.answeredAt && serverAnswer != saved.answer))
        }
        val wakePlaceholder = row.has("receivedAtMillis") && !session.has("startedAtMillis")
        val config = session.optJSONObject("responseConfig")?.let { runCatching { MusterResponseConfig.from(it) }.getOrNull() }
        val configAvailable = (!wakePlaceholder || !session.optBoolean("responseConfigPending")) && (!session.has("responseConfig") || config != null)
        MusterCardModel(panel, id, kind, if (wakePlaceholder) fallbackTitle else session.optString("title").ifBlank { fallbackTitle },
            if (wakePlaceholder) fallbackInstructions ?: session.optString("instructions") else session.optString("instructions"), session.optString("status") == "closed",
            event?.state ?: if (serverAnswer != null) "sent" else "", event?.answer ?: serverAnswer,
            (event?.note ?: response?.optString("note")).orEmpty(), response?.optBoolean("helpOpen") == true,
            session.optLong("startedAtMillis", row.optLong("receivedAtMillis")), session.optLong("deadlineAtMillis"),
            // A local reply is a complete snapshot: null choice/voice explicitly clears the old server fields.
            config, configAvailable, if (event != null) event.optionId else response?.optString("optionId")?.takeUnless { it.isBlank() || it == "null" },
            event?.voice?.takeUnless { event.state == "sent" },
            when {
                event == null -> response?.optJSONObject("voice")?.let { runCatching { MusterRemoteVoice.from(it) }.getOrNull() }
                event.state == "sent" && event.voice != null && event.receiptEventId != null -> MusterRemoteVoice(event.receiptEventId, event.voice.durationMillis, event.voice.bytes, event.voice.sha256)
                else -> null
            },
            response?.optJSONObject("helpVoice")?.let { runCatching { MusterRemoteVoice.from(it) }.getOrNull() })
    }
    val known = cards.mapTo(mutableSetOf()) { it.key }
    val orphaned = latest.values.filter { "${it.panel}:${it.session}" !in known && it.state in setOf("pending", "failed") }
        .map { event -> MusterCardModel(event.panel, event.session, "", fallbackTitle, "", false,
            event.state, event.answer, event.note, false, event.answeredAt, 0, configurationAvailable = false, optionId = event.optionId, localVoice = event.voice) }
    return (cards + orphaned).sortedByDescending { it.startedAt }
}

internal data class MusterInboxState(val uid: String? = null,
    val cards: List<MusterCardModel> = emptyList(), val events: List<MusterEvent> = emptyList(), val unreadable: Boolean = false) {
    val outbox get() = events.filter { it.state == "pending" || it.state == "failed" }
    fun priority(now: Long): MusterCardModel? = cards.firstOrNull { it.needsResponse(now) }
        ?: cards.firstOrNull { it.replyState == "failed" || it.replyState == "pending" }
        ?: cards.firstOrNull { it.helpOpen }
}

/** Local, account-bound observation only: the inbox never polls or opens another server listener. */
@Composable
internal fun rememberMusterInbox(): MusterInboxState {
    val context = LocalContext.current.applicationContext
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val auth = remember { FirebaseAuth.getInstance() }
    var uid by remember { mutableStateOf(auth.currentUser?.takeUnless { it.isAnonymous }?.uid) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { uid = it.currentUser?.takeUnless { user -> user.isAnonymous }?.uid }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    val revision by MusterClient.revision.collectAsStateWithLifecycle()
    var state by remember(uid) { mutableStateOf(MusterInboxState(uid)) }
    LaunchedEffect(uid, revision, language) {
        val expected = uid
        if (expected == null) { state = MusterInboxState(); return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) {
            val events = MusterClient.queue(context).entries(expected)
            val localized = musterLocalizedContext(context)
            MusterInboxState(expected, musterCards(MusterClient.cached(context, expected), events, localized.getString(R.string.muster_title), localized.getString(R.string.muster_connect_instructions)), events)
        } }.onSuccess { if (uid == expected) state = it }
            .onFailure { if (uid == expected) state = state.copy(unreadable = true) }
        state.cards.firstOrNull()?.panel?.let { panel -> MusterClient.synchronizeLanguagePreference(context, panel) }
    }
    return state
}
