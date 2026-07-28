package com.auralis.crisisconnect.screens.authority

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.Intent
import android.os.IBinder
import android.location.Location
import android.net.Uri
import android.widget.Toast
import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.screens.Chat.ChatTextureBackground
import com.auralis.crisisconnect.ui.components.ContactAvatar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.data.AuthorityChannelReadEntity
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.observeMessages
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.saveLocalAudioMessage
import com.auralis.crisisconnect.data.saveLocalImageMessage
import com.auralis.crisisconnect.data.saveLocalMessage
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.data.toAuthorityEntity
import com.auralis.crisisconnect.data.toHierarchyMessage
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.screens.Chat.AudioMessageContent
import com.auralis.crisisconnect.screens.Chat.CallEventRow
import com.auralis.crisisconnect.screens.Chat.FILE_COMPRESSION_NONE
import com.auralis.crisisconnect.screens.Chat.FileMessageContent
import com.auralis.crisisconnect.screens.Chat.ImageMessageContent
import com.auralis.crisisconnect.screens.Chat.PreparedDocumentAttachment
import com.auralis.crisisconnect.screens.Chat.buildSharedFileMessage
import com.auralis.crisisconnect.screens.Chat.parseSharedFilePayload
import com.auralis.crisisconnect.screens.Chat.ChatEncryptionNoticeCard
import com.auralis.crisisconnect.screens.Chat.DateHeader
import com.auralis.crisisconnect.screens.Chat.incomingChatBubbleColors
import com.auralis.crisisconnect.screens.Chat.isSameLocalDay
import com.auralis.crisisconnect.screens.Chat.outgoingChatBubbleColors
import com.auralis.crisisconnect.screens.Chat.ScrollToBottomButton
import com.auralis.crisisconnect.screens.Chat.RelativeLocationFallbackMap
import com.auralis.crisisconnect.screens.Chat.buildLocationPayload
import com.auralis.crisisconnect.screens.Chat.fetchCurrentLocationEstimateForChatSend
import com.auralis.crisisconnect.screens.Chat.hasLocationPermission
import com.auralis.crisisconnect.screens.Chat.parseSharedLocationPayload
import com.auralis.crisisconnect.screens.Chat.rememberOwnLocationSnapshot
import com.auralis.crisisconnect.screens.Chat.LOCATION_SOURCE_GPS
import com.auralis.crisisconnect.nearby.NearbyAutoLink
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.RfcommForegroundService.CallDirection
import com.auralis.crisisconnect.service.RfcommForegroundService.CallEvent
import com.auralis.crisisconnect.service.RfcommForegroundService.CallResult
import com.auralis.crisisconnect.ui.components.rememberConnectedSessions
import com.auralis.crisisconnect.messaging.ChannelAttachment
import com.auralis.crisisconnect.messaging.ChannelAttachments
import com.auralis.crisisconnect.messaging.InternetChatTransport
import com.auralis.crisisconnect.messaging.InternetConversation
import com.auralis.crisisconnect.messaging.HierarchyChannelKey
import com.auralis.crisisconnect.messaging.HierarchyMessage
import com.auralis.crisisconnect.messaging.HierarchyMessagingClient
import com.auralis.crisisconnect.messaging.PendingChannelAttachment
import com.auralis.crisisconnect.messaging.call.AuthorityCallSignaling
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** UI state for a single cross-panel (hierarchy) 1:1 thread. */
data class ChannelThreadState(
    val loading: Boolean = true,
    val error: String? = null,
    val messages: List<HierarchyMessage> = emptyList(),
    val sending: Boolean = false,
)

/**
 * Drives one hierarchy channel thread with a specific peer. Fetches the shared channel key, subscribes
 * to the (E2E) message log and keeps only the 1:1 messages between me and the peer, and sends addressed
 * replies. The transport ([HierarchyMessagingClient]) is byte-compatible with the web dashboard, so this
 * mirrors what the panel sees. Nav args are supplied via [start] from the screen (no custom factory).
 */
class AuthorityChannelThreadViewModel(app: Application) : AndroidViewModel(app) {
    private val client = HierarchyMessagingClient()
    private val auth = FirebaseAuth.getInstance()
    private val dao by lazy { AppDatabase.getInstance(getApplication()).authorityMessageDao() }
    private var listener: ListenerRegistration? = null
    private var readCursorListener: ListenerRegistration? = null
    private var typingListener: ListenerRegistration? = null
    private var typingExpiryJob: Job? = null
    private var lastWrittenCursor = 0L
    private var roomJob: Job? = null
    private var keyFetchJob: Job? = null
    // Nullable until the shared key arrives; exposed as a flow so attachment loads can await it. Bubbles
    // now render from Room before the key is fetched, so media must wait for the key rather than give up.
    private val channelKeyState = MutableStateFlow<HierarchyChannelKey?>(null)
    private val channelKey: HierarchyChannelKey? get() = channelKeyState.value
    private var channelId: String = ""
    private var peerUid: String = ""

    private val _state = MutableStateFlow(ChannelThreadState())
    val state: StateFlow<ChannelThreadState> = _state.asStateFlow()

    /** How far the peer has read MY messages (millis) — drives ✓✓ ticks. Web-compatible readReceipts. */
    private val _partnerReadAt = MutableStateFlow(0L)
    val partnerReadAt: StateFlow<Long> = _partnerReadAt.asStateFlow()

    /** Whether the peer is currently typing to me. */
    private val _partnerTyping = MutableStateFlow(false)
    val partnerTyping: StateFlow<Boolean> = _partnerTyping.asStateFlow()

    val myUid: String get() = auth.currentUser?.uid.orEmpty()

    // ---- Offline Bluetooth bridge ----
    // The hidden bridge contact ([Contact.isAuthorityBridge], created by AuthorityBridgeContacts)
    // lives at the deterministic 1:1 sessionCode for this peer. Opening the thread mirrors what
    // ChatScreen does for internet contacts: try once to bootstrap the number-keyed SPAKE2 link if
    // it's missing, and nudge an RFCOMM connect when a link exists so the badge can go green.
    private var bridgeAutoLinkJob: Job? = null
    private var bridgeAutoLinkAttemptedFor: String? = null
    private val _bridgeSessionCode = MutableStateFlow<String?>(null)
    /** Session code of the peer's Bluetooth bridge contact — the key to match in connected sessions. */
    val bridgeSessionCode: StateFlow<String?> = _bridgeSessionCode.asStateFlow()

    // Screen-fed link state (same connected-sessions source as the badge) + the citizen internet
    // transport's availability check — together they decide whether a send rides Bluetooth.
    @Volatile
    private var bluetoothLinked = false
    private val internetTransport by lazy { InternetChatTransport(getApplication()) }
    private var bridgeMessagesJob: Job? = null
    // The thread renders one merged, time-sorted timeline: cloud channel messages (Room cache of
    // Firestore) + plain-text Bluetooth messages exchanged with the peer's bridge contact.
    private val cloudMessages = MutableStateFlow<List<HierarchyMessage>>(emptyList())
    private val bridgeMessages = MutableStateFlow<List<HierarchyMessage>>(emptyList())

    // Bluetooth-carried media (voice notes + images) by merged-row id ("bt:<uuid>") — the bubble
    // renders them from the local file with the same widgets citizen chat uses.
    private val _bridgeMediaMessages = MutableStateFlow<Map<String, ChatMessage>>(emptyMap())
    val bridgeMediaMessages: StateFlow<Map<String, ChatMessage>> = _bridgeMediaMessages.asStateFlow()

    /** Whether the E2E internet relay can currently carry traffic (drives call/send routing). */
    fun isInternetAvailable(): Boolean = internetTransport.isAvailable()

    /** Places a classic Bluetooth (RFCOMM) voice call to the peer's bridge contact. */
    fun startBluetoothCall(onResult: (Boolean) -> Unit) {
        val session = _bridgeSessionCode.value
        val service = btService
        if (session == null || service == null) {
            session?.let { requestBluetoothConnect(it) }
            onResult(false)
            return
        }
        service.startVoipCall(session) { started -> onResult(started) }
    }

    fun setBluetoothLinked(linked: Boolean) {
        bluetoothLinked = linked
    }

    private fun publishMergedMessages() {
        val cloud = cloudMessages.value
        // A backfilled channel doc carries the Bluetooth copy's uuid — drop that bt: row so the
        // message renders once (the cloud copy is the one the web also sees).
        val backfilledUuids = cloud.mapNotNullTo(HashSet()) { msg ->
            msg.clientUuid.takeIf { it.isNotBlank() }
        }
        val bridge = bridgeMessages.value.filterNot {
            it.id.removePrefix("bt:") in backfilledUuids
        }
        val merged = (cloud + bridge).sortedBy { it.atMillis }
        _state.value = _state.value.copy(loading = false, messages = merged)
    }

    private var backfillJob: Job? = null

    /**
     * Once the channel key is available and the internet is back, posts every Bluetooth-carried
     * text/location of this thread that never reached the channel (keyed by clientUuid) — so the
     * web dashboard catches up on the offline conversation. Media backfill is deliberately out
     * for now (blobs would need re-encryption + upload).
     */
    private suspend fun backfillBluetoothMessages(key: HierarchyChannelKey) {
        if (!internetTransport.isAvailable()) return
        val session = _bridgeSessionCode.value ?: return
        val context = getApplication<Application>()
        val cloudClientUuids = withContext(Dispatchers.IO) {
            runCatching {
                dao.observeThread(channelId, peerUid).first()
                    .mapNotNullTo(HashSet()) { it.clientUuid.takeIf { u -> u.isNotBlank() } }
            }.getOrDefault(hashSetOf())
        }
        val rows = withContext(Dispatchers.IO) {
            runCatching { observeMessages(context, session).first() }.getOrDefault(emptyList())
        }
        val senderName = runCatching { getSavedUserName(context).first() }.getOrDefault("")
            .ifBlank { auth.currentUser?.displayName.orEmpty() }
        val peerName = cloudMessages.value.firstOrNull { it.senderUid == peerUid }?.senderName.orEmpty()
        for (msg in rows) {
            if (!msg.isLocal || msg.messageUuid in cloudClientUuids) continue
            when (msg.messageType) {
                MessageType.TEXT -> {
                    val trimmed = msg.text.trim()
                    if (trimmed.isEmpty()) continue
                    // CC_FILE payloads stay Bluetooth-only: the raw blob isn't kept on the row
                    // and the web doesn't speak that wire format anyway.
                    if (trimmed.startsWith("CC_") && parseSharedLocationPayload(trimmed) == null) {
                        continue
                    }
                    runCatching {
                        client.send(
                            key, senderName, peerUid, peerName, trimmed,
                            clientUuid = msg.messageUuid
                        )
                    }
                }
                MessageType.AUDIO -> {
                    val attachment = withContext(Dispatchers.IO) {
                        val file = msg.audioFilePath?.let(::File)?.takeIf { it.exists() }
                        val bytes = file?.let { runCatching { it.readBytes() }.getOrNull() }
                        if (file == null || bytes == null || bytes.isEmpty()) {
                            null
                        } else {
                            PendingChannelAttachment(
                                bytes = bytes,
                                name = file.name,
                                mime = audioMimeForFileName(file.name),
                                durationSec = msg.audioDurationMillis
                                    ?.let { (it / 1000L).toInt() }
                                    ?.takeIf { it > 0 }
                            )
                        }
                    } ?: continue
                    runCatching {
                        client.send(
                            key, senderName, peerUid, peerName, "",
                            attachments = listOf(attachment),
                            clientUuid = msg.messageUuid
                        )
                    }
                }
                MessageType.IMAGE -> {
                    val attachment = withContext(Dispatchers.IO) {
                        val file = msg.imageFilePath?.let(::File)?.takeIf { it.exists() }
                        val bytes = file?.let { runCatching { it.readBytes() }.getOrNull() }
                        if (file == null || bytes == null || bytes.isEmpty()) {
                            null
                        } else {
                            PendingChannelAttachment(
                                bytes = bytes,
                                name = file.name,
                                mime = msg.imageMimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg",
                                width = msg.imageWidth,
                                height = msg.imageHeight
                            )
                        }
                    } ?: continue
                    runCatching {
                        client.send(
                            key, senderName, peerUid, peerName, "",
                            attachments = listOf(attachment),
                            clientUuid = msg.messageUuid
                        )
                    }
                }
                else -> continue
            }
        }
    }

    private fun audioMimeForFileName(fileName: String): String = when {
        fileName.endsWith(".ogg", ignoreCase = true) ||
            fileName.endsWith(".opus", ignoreCase = true) -> "audio/ogg"
        fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        fileName.endsWith(".aac", ignoreCase = true) -> "audio/aac"
        fileName.endsWith(".3gp", ignoreCase = true) -> "audio/3gpp"
        else -> "audio/mp4"
    }

    private var btService: RfcommForegroundService? = null
    private var btServiceBound = false
    private var pendingBtConnectSession: String? = null
    private val btServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            btService = (binder as? RfcommForegroundService.LocalBinder)?.getService()
            pendingBtConnectSession?.let { code -> btService?.connectToContact(code) { } }
            pendingBtConnectSession = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            btService = null
        }
    }

    private fun startBluetoothBridge(peerUid: String) {
        val me = myUid
        if (me.isBlank() || peerUid.isBlank()) return
        val session = InternetConversation.pairId(me, peerUid)
        _bridgeSessionCode.value = session
        // Bluetooth-carried plain-text messages for this peer, mapped into the thread's message
        // shape ("bt:" ids keep them distinct from Firestore doc ids). Machine payloads (CC_*)
        // stay out until the offline attachment phase renders them properly. Incoming rows are
        // marked read here so the hidden bridge contact never inflates the unread tab badge.
        bridgeMessagesJob?.cancel()
        bridgeMessagesJob = viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            observeMessages(context, session).collect { rows ->
                val mediaById = HashMap<String, ChatMessage>()
                fun rowOf(msg: ChatMessage, text: String) = HierarchyMessage(
                    id = "bt:${msg.messageUuid}",
                    senderUid = if (msg.isLocal) me else peerUid,
                    senderName = msg.senderDisplayName.orEmpty(),
                    recipientUid = if (msg.isLocal) peerUid else me,
                    recipientName = "",
                    text = text,
                    atMillis = msg.timestampMillis,
                )
                bridgeMessages.value = rows.mapNotNull { msg ->
                    val trimmed = msg.text.trim()
                    when {
                        msg.messageType == MessageType.AUDIO &&
                            !msg.audioFilePath.isNullOrBlank() -> {
                            mediaById["bt:${msg.messageUuid}"] = msg
                            rowOf(msg, "")
                        }
                        msg.messageType == MessageType.IMAGE &&
                            !msg.imageFilePath.isNullOrBlank() -> {
                            mediaById["bt:${msg.messageUuid}"] = msg
                            rowOf(msg, "")
                        }
                        // Machine payloads stay out — except shared locations and shared files,
                        // which the thread's bubbles render properly on both transports.
                        msg.messageType == MessageType.TEXT && trimmed.isNotEmpty() &&
                            (!trimmed.startsWith("CC_") ||
                                parseSharedLocationPayload(trimmed) != null ||
                                parseSharedFilePayload(trimmed) != null) -> {
                            rowOf(msg, msg.text)
                        }
                        else -> null
                    }
                }
                _bridgeMediaMessages.value = mediaById
                runCatching {
                    AppDatabase.getInstance(context).messageDao().markAllRemoteMessagesRead(session)
                }
                publishMergedMessages()
            }
        }
        if (bridgeAutoLinkAttemptedFor == session) return
        bridgeAutoLinkAttemptedFor = session
        bridgeAutoLinkJob?.cancel()
        bridgeAutoLinkJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val contact = withContext(Dispatchers.IO) { getContact(context, session) } ?: return@launch
            if (NearbyAutoLink.isEligible(contact)) {
                withContext(Dispatchers.IO) {
                    runCatching { NearbyAutoLink.tryEstablish(context, contact) }
                }
            }
            val refreshed = withContext(Dispatchers.IO) { getContact(context, session) } ?: return@launch
            if (refreshed.aesKey.isNotBlank() && refreshed.address.isNotBlank()) {
                requestBluetoothConnect(session)
            }
        }
    }

    /** Asks the RFCOMM service (bound lazily, main thread) to bring up the link for [sessionCode]. */
    private fun requestBluetoothConnect(sessionCode: String) {
        btService?.let { service ->
            service.connectToContact(sessionCode) { }
            return
        }
        pendingBtConnectSession = sessionCode
        if (btServiceBound) return
        val context = getApplication<Application>()
        btServiceBound = runCatching {
            context.bindService(
                Intent(context, RfcommForegroundService::class.java),
                btServiceConnection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)
    }

    fun start(channelId: String, peerUid: String) {
        startBluetoothBridge(peerUid)
        if (this.channelId == channelId && this.peerUid == peerUid && channelKey != null) return
        this.channelId = channelId
        this.peerUid = peerUid
        lastWrittenCursor = 0L
        channelKeyState.value = null
        cloudMessages.value = emptyList()
        bridgeMessages.value = emptyList()
        _state.value = _state.value.copy(loading = true, error = null)
        // Backfill Bluetooth-era messages into the channel as soon as the shared key is here.
        backfillJob?.cancel()
        backfillJob = viewModelScope.launch {
            val key = channelKeyState.filterNotNull().first()
            backfillBluetoothMessages(key)
        }
        listener?.remove()
        listener = null

        // Read-receipt (✓✓) + typing subscriptions — metadata only, so they work without the channel key.
        val me = myUid
        _partnerReadAt.value = 0L
        _partnerTyping.value = false
        readCursorListener?.remove()
        readCursorListener = client.listenReadCursor(channelId, me, peerUid) { at -> _partnerReadAt.value = at }
        typingListener?.remove()
        typingExpiryJob?.cancel()
        typingListener = client.listenTyping(channelId, me, peerUid) { typing ->
            typingExpiryJob?.cancel()
            if (typing) {
                _partnerTyping.value = true
                // Local safety expiry in case the peer's "stopped typing" write never lands (tab closed).
                typingExpiryJob = viewModelScope.launch { delay(6000); _partnerTyping.value = false }
            } else {
                _partnerTyping.value = false
            }
        }

        // Offline-first: the local (SQLCipher) cache is the single source of truth for what we render, so
        // the thread shows instantly and keeps working with no connectivity.
        roomJob?.cancel()
        roomJob = viewModelScope.launch(Dispatchers.IO) {
            dao.observeThread(channelId, peerUid).collect { rows ->
                val msgs = rows.map { it.toHierarchyMessage() }
                cloudMessages.value = msgs
                _state.value = _state.value.copy(
                    error = if (msgs.isNotEmpty()) null else _state.value.error,
                )
                publishMergedMessages()
                // Having the thread open (and re-emitting as new messages arrive) means the user has seen
                // up to the newest message, so advance the read cursor — this clears the home-list badge
                // and (via Firestore) shows the peer ✓✓ on their messages.
                if (msgs.isNotEmpty()) {
                    val newest = msgs.maxOf { it.atMillis }
                    runCatching {
                        dao.upsertRead(
                            AuthorityChannelReadEntity(
                                channelId = channelId,
                                peerUid = peerUid,
                                lastReadAtMillis = newest,
                            ),
                        )
                    }
                    if (newest > lastWrittenCursor) {
                        lastWrittenCursor = newest
                        client.writeReadCursor(channelId, me, peerUid, newest)
                    }
                }
            }
        }

        ensureChannelKey()
    }

    /** Publishes my typing state toward the peer (best-effort; web-compatible). */
    fun setTyping(typing: Boolean) {
        val cid = channelId.takeIf { it.isNotBlank() } ?: return
        client.setTyping(cid, myUid, peerUid, typing)
    }

    /**
     * Fetches the shared key + subscribes to Firestore (write-through into Room). Idempotent and
     * retryable: if the first attempt failed offline, calling this again (e.g. when media is retried or
     * a send is attempted after reconnecting) picks the key back up — otherwise a thread opened offline
     * would never recover once the network returned.
     */
    private fun ensureChannelKey() {
        if (channelKeyState.value != null || keyFetchJob?.isActive == true) return
        val cid = channelId
        val peer = peerUid
        keyFetchJob = viewModelScope.launch {
            try {
                val key = client.fetchChannelKey(cid)
                channelKeyState.value = key
                listener?.remove()
                listener = client.listen(key) { all ->
                    val me = myUid
                    val mine = all.filter { m ->
                        (m.senderUid == peer && m.recipientUid == me) ||
                            (m.senderUid == me && m.recipientUid == peer)
                    }
                    if (mine.isEmpty()) return@listen
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { dao.upsertAll(mine.map { it.toAuthorityEntity(cid, me) }) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AuthorityChannels", "thread sync failed channelId=$cid", e)
                // Keep showing whatever is cached; only surface the error when there's nothing to show.
                _state.value = _state.value.copy(
                    loading = false,
                    error = if (_state.value.messages.isEmpty()) e.message else null,
                )
            }
        }
    }

    fun send(
        peerName: String,
        text: String,
        attachments: List<PendingChannelAttachment> = emptyList(),
    ) {
        val body = text.trim()
        if ((body.isEmpty() && attachments.isEmpty()) || _state.value.sending) return
        Analytics.messageSent(
            kind = when {
                attachments.isEmpty() -> "text"
                attachments.any { it.mime.startsWith("audio/") } -> "voice"
                attachments.any { it.mime.startsWith("image/") } -> "image"
                else -> "file"
            },
            transport = if (bluetoothLinked && !internetTransport.isAvailable()) "bluetooth" else "internet",
            chat = "authority_channel"
        )
        // Offline Bluetooth path: plain text and shared locations (CC_LOC renders as the same map
        // bubble on both ends). Cloud stays the transport of record whenever the internet can
        // carry the message (web parity); Bluetooth takes over exactly when it can't and the
        // bridge link is up.
        val isLocationPayload = parseSharedLocationPayload(body) != null
        if (attachments.isEmpty() && (!body.startsWith("CC_") || isLocationPayload) &&
            bluetoothLinked && !internetTransport.isAvailable()
        ) {
            sendViaBluetoothBridge(body)
            return
        }
        // Attachments ride Bluetooth too when the cloud can't carry them — the citizen streams
        // chunk + encrypt over the link, and the receiver persists them as normal media rows.
        val single = attachments.singleOrNull()
        if (single != null && body.isEmpty() &&
            bluetoothLinked && !internetTransport.isAvailable()
        ) {
            when {
                single.mime.startsWith("audio/") -> sendVoiceViaBluetoothBridge(single)
                single.mime.startsWith("image/") -> sendImageViaBluetoothBridge(single)
                else -> sendFileViaBluetoothBridge(single)
            }
            return
        }
        val key = channelKey ?: return
        _state.value = _state.value.copy(sending = true)
        viewModelScope.launch {
            val senderName = runCatching { getSavedUserName(getApplication()).first() }.getOrDefault("")
                .ifBlank { auth.currentUser?.displayName.orEmpty() }
                .ifBlank { getApplication<Application>().getString(R.string.internet_message_notification_title) }
            runCatching { client.send(key, senderName, peerUid, peerName, body, attachments) }
            _state.value = _state.value.copy(sending = false)
        }
    }

    /**
     * Sends [body] to the peer's bridge contact over the live Bluetooth link. The row is persisted
     * to the citizen message store first (it is what the merged timeline renders), so even if the
     * link drops mid-send the text isn't lost — it shows in the thread and the peer gets it on the
     * next exchange.
     */
    private fun sendViaBluetoothBridge(body: String) {
        val session = _bridgeSessionCode.value ?: return
        _state.value = _state.value.copy(sending = true)
        viewModelScope.launch {
            val context = getApplication<Application>()
            val uuid = UUID.randomUUID().toString()
            withContext(Dispatchers.IO) {
                runCatching { saveLocalMessage(context, session, uuid, body) }
            }
            val service = btService
            if (service != null) {
                service.sendMessage(session, uuid, body) { }
            } else {
                // Binder not up (rare): the message is stored; nudge the link so it can drain.
                requestBluetoothConnect(session)
            }
            _state.value = _state.value.copy(sending = false)
        }
    }

    /** Persists the recording locally (that's what the merged timeline plays) then streams it. */
    private fun sendVoiceViaBluetoothBridge(att: PendingChannelAttachment) {
        val session = _bridgeSessionCode.value ?: return
        _state.value = _state.value.copy(sending = true)
        viewModelScope.launch {
            val context = getApplication<Application>()
            val uuid = UUID.randomUUID().toString()
            val durationMs = att.durationSec?.let { it * 1000L }
            withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = voiceMessageFileName(uuid, att.mime)
                    voiceMessageFile(context, fileName).writeBytes(att.bytes)
                    saveLocalAudioMessage(context, session, uuid, fileName, durationMs)
                }
            }
            val service = btService
            if (service != null) {
                service.sendVoiceStream(session, uuid, att.mime, durationMs, att.bytes) { }
            } else {
                requestBluetoothConnect(session)
            }
            _state.value = _state.value.copy(sending = false)
        }
    }

    private fun sendImageViaBluetoothBridge(att: PendingChannelAttachment) {
        val session = _bridgeSessionCode.value ?: return
        _state.value = _state.value.copy(sending = true)
        viewModelScope.launch {
            val context = getApplication<Application>()
            val uuid = UUID.randomUUID().toString()
            val ext = when {
                att.mime.contains("png") -> "png"
                att.mime.contains("webp") -> "webp"
                else -> "jpg"
            }
            val fileName = "$uuid.$ext"
            withContext(Dispatchers.IO) {
                runCatching {
                    imageMessageFile(context, fileName).writeBytes(att.bytes)
                    saveLocalImageMessage(
                        context = context,
                        sessionCode = session,
                        uuid = uuid,
                        fileName = fileName,
                        thumbnailName = null,
                        width = att.width,
                        height = att.height,
                        mimeType = att.mime
                    )
                }
            }
            val service = btService
            if (service != null) {
                service.sendImageStream(session, uuid, att.mime, att.width, att.height, att.bytes) { }
            } else {
                requestBluetoothConnect(session)
            }
            _state.value = _state.value.copy(sending = false)
        }
    }

    /** Shared files travel as the citizen CC_FILE payload row + a chunked blob stream. */
    private fun sendFileViaBluetoothBridge(att: PendingChannelAttachment) {
        val session = _bridgeSessionCode.value ?: return
        _state.value = _state.value.copy(sending = true)
        viewModelScope.launch {
            val context = getApplication<Application>()
            val uuid = UUID.randomUUID().toString()
            val payloadText = buildSharedFileMessage(
                PreparedDocumentAttachment(
                    displayName = att.name,
                    mimeType = att.mime.takeIf { it.isNotBlank() },
                    originalSizeBytes = att.bytes.size.toLong(),
                    transferSizeBytes = att.bytes.size,
                    compression = FILE_COMPRESSION_NONE,
                    payloadBytes = att.bytes
                )
            )
            withContext(Dispatchers.IO) {
                runCatching { saveLocalMessage(context, session, uuid, payloadText) }
            }
            val service = btService
            if (service != null) {
                service.sendFileStream(
                    sessionCode = session,
                    uuid = uuid,
                    displayName = att.name,
                    mimeType = att.mime.takeIf { it.isNotBlank() },
                    originalSizeBytes = att.bytes.size.toLong(),
                    compression = FILE_COMPRESSION_NONE,
                    payload = att.bytes
                ) { }
            } else {
                requestBluetoothConnect(session)
            }
            _state.value = _state.value.copy(sending = false)
        }
    }

    /**
     * Downloads + decrypts an attachment blob (for rendering an image / playing a voice note). Bubbles
     * render from Room before the channel key is fetched, so wait (briefly) for the key to arrive rather
     * than failing immediately — otherwise media would never load on a cold thread open.
     */
    suspend fun loadAttachmentBytes(att: ChannelAttachment): ByteArray? {
        // Cached blobs live on-device, so try the fetch even before the key arrives; the disk cache is
        // served key-free. Only if it's not cached yet (a cold, still-syncing open) do we wait for the key.
        val current = channelKeyState.value
        runCatching {
            ChannelAttachments.fetchAttachmentBytes(getApplication(), current?.key, current?.channelId, att)
        }.getOrNull()?.let { return it }
        // Not cached and no key yet — kick the (idempotent) key fetch in case it failed offline, then wait.
        if (channelKeyState.value == null) ensureChannelKey()
        val key = withTimeoutOrNull(20_000) { channelKeyState.filterNotNull().first() } ?: return null
        return runCatching {
            ChannelAttachments.fetchAttachmentBytes(getApplication(), key.key, key.channelId, att)
        }.getOrNull()
    }

    override fun onCleared() {
        listener?.remove()
        listener = null
        readCursorListener?.remove()
        readCursorListener = null
        typingListener?.remove()
        typingListener = null
        typingExpiryJob?.cancel()
        // Leaving the thread: clear my typing flag so the peer's "…yazıyor" indicator disappears.
        if (channelId.isNotBlank()) client.setTyping(channelId, myUid, peerUid, false)
        roomJob?.cancel()
        roomJob = null
        keyFetchJob?.cancel()
        keyFetchJob = null
        bridgeAutoLinkJob?.cancel()
        bridgeAutoLinkJob = null
        bridgeMessagesJob?.cancel()
        bridgeMessagesJob = null
        backfillJob?.cancel()
        backfillJob = null
        if (btServiceBound) {
            runCatching { getApplication<Application>().unbindService(btServiceConnection) }
            btServiceBound = false
        }
        btService = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorityChannelThreadScreen(
    navController: NavHostController,
    channelId: String,
    peerUid: String,
    title: String,
    agency: String = "",
    role: String = "",
) {
    val viewModel: AuthorityChannelThreadViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val partnerReadAt by viewModel.partnerReadAt.collectAsStateWithLifecycle()
    val partnerTyping by viewModel.partnerTyping.collectAsStateWithLifecycle()
    LaunchedEffect(channelId, peerUid) { viewModel.start(channelId, peerUid) }

    // Offline Bluetooth bridge status: green subtitle when this peer's hidden bridge contact has a
    // live BT link (same connected-sessions source the home list pills use).
    val connectedSessions = rememberConnectedSessions()
    val bridgeSessionCode by viewModel.bridgeSessionCode.collectAsStateWithLifecycle()
    val isBluetoothLinked = bridgeSessionCode != null && bridgeSessionCode in connectedSessions
    LaunchedEffect(isBluetoothLinked) { viewModel.setBluetoothLinked(isBluetoothLinked) }
    val bridgeMediaMessages by viewModel.bridgeMediaMessages.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Typing indicator: publish "true" while editing (throttled to once/3s), and auto-"false" after a
    // 4s pause — mirrors the web's typing writes so the peer sees "…yazıyor" and it clears cleanly.
    var lastTypingWrite by remember { mutableStateOf(0L) }
    val onInputChange: (String) -> Unit = { newValue ->
        input = newValue
        if (newValue.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastTypingWrite > 3000L) {
                lastTypingWrite = now
                viewModel.setTyping(true)
            }
        } else {
            lastTypingWrite = 0L
            viewModel.setTyping(false)
        }
    }
    LaunchedEffect(input) {
        if (input.isNotBlank()) {
            delay(4000)
            lastTypingWrite = 0L
            viewModel.setTyping(false)
        }
    }

    // Scroll-to-bottom affordance, exactly like ChatScreen: stick to the newest message only while already
    // at the bottom; if the user has scrolled up, leave them there and count arriving messages as unread.
    // List index 0 is the E2EE notice, so the last message lives at index == messages.size.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            last >= info.totalItemsCount - 1
        }
    }
    val showScrollToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last < info.totalItemsCount - 1
        }
    }
    // Unread-on-scroll badge + auto-scroll, exactly like ChatScreen. The existing thread history must NEVER
    // count as unread: only messages that ARRIVE after the first load, while the user is scrolled up, do.
    // On first load we jump straight to the newest message (instant, so no scroll button flashes) and treat
    // all history as already seen. `hasInitialized` guards against the old bug where seenMessageCount==0 on
    // entry made every message look unread (and re-entering reset it, so the badge came back every time).
    var seenMessageCount by remember { mutableIntStateOf(0) }
    var hasInitialized by remember { mutableStateOf(false) }
    val newMessageCount = if (hasInitialized) (state.messages.size - seenMessageCount).coerceAtLeast(0) else 0
    LaunchedEffect(state.messages.size) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!hasInitialized) {
            listState.scrollToItem(state.messages.size)
            seenMessageCount = state.messages.size
            hasInitialized = true
        } else if (isAtBottom) {
            listState.animateScrollToItem(state.messages.size)
            seenMessageCount = state.messages.size
        }
    }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) seenMessageCount = state.messages.size
    }

    // Defensive: a title routed from a stale Room row or a deep-link may still be the peer's login
    // email — prettify it here too so the thread never shows a raw email (home rows resolve it fully,
    // with the peer's message name, at build time).
    val heading = com.auralis.crisisconnect.messaging.AuthorityNameResolver
        .resolve(title.trim())
        .ifBlank { stringResource(R.string.authority_channels_title) }
    // Which agency the peer belongs to (e.g. "AFAD İstanbul"), shown under the name like the web — more
    // informative than a generic "Authority" label. Falls back to that label only when unknown.
    val agencySubtitle = agency.trim().ifBlank { stringResource(R.string.authority_channel_row_label) }

    // Day-grouped date separators, exactly like ChatScreen: locale-preferred month/day ordering and the
    // same local-day comparison so a new header appears whenever the calendar day changes.
    val listTimeZone = remember { TimeZone.getDefault() }
    val dateHeaderFormatter = remember {
        val locale = Locale.getDefault()
        SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, "MMMMd"), locale)
    }

    // Outgoing calls go through the SAME shared InternetCallManager ChatScreen uses, so the global
    // call overlay (MainActivity) renders the identical call screen. AuthorityCallReceiver already
    // handles the receive side app-wide; here we only place the outgoing offer over this channel.
    val context = LocalContext.current
    val callFailedText = stringResource(R.string.chat_call_failed)
    val placeCall = call@{ video: Boolean ->
        // Offline + bridge link up → classic Bluetooth voice call to the hidden bridge contact
        // (same call UI as citizen BT calls). Video needs the internet-side stacks.
        if (!video && isBluetoothLinked && !viewModel.isInternetAvailable()) {
            val micGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!micGranted) {
                Toast.makeText(context, callFailedText, Toast.LENGTH_SHORT).show()
                return@call
            }
            viewModel.startBluetoothCall { started ->
                if (!started) {
                    Toast.makeText(context, callFailedText, Toast.LENGTH_SHORT).show()
                }
            }
            return@call
        }
        android.util.Log.i("SfuAuthorityCall", "placeCall path=${if (com.auralis.crisisconnect.messaging.call.sfu.SfuCallConfig.ENABLED) "SFU" else "P2P"} channel=$channelId peer=$peerUid video=$video")
        if (com.auralis.crisisconnect.messaging.call.sfu.SfuCallConfig.ENABLED) {
            // SFU (Cloudflare Realtime) authority call — interoperable with the web dashboard.
            com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager.init(context)
            com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager.startOutgoing(
                channelId = channelId,
                kind = AuthorityCallSignaling.ChannelKind.HIERARCHY,
                myUid = viewModel.myUid,
                peerUid = peerUid,
                peerName = heading,
                video = video,
            )
        } else {
            AuthorityCallSignaling(
                channelId = channelId,
                myUid = viewModel.myUid,
                kind = AuthorityCallSignaling.ChannelKind.HIERARCHY,
                peerNameResolver = { heading },
            ).startCall(peerUid)
        }
    }
    var pendingVideoCall by remember { mutableStateOf(false) }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true
        if (micOk) placeCall(pendingVideoCall && grants[Manifest.permission.CAMERA] == true)
    }
    val startCallWithPermissions = { video: Boolean ->
        pendingVideoCall = video
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (video && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) placeCall(video) else callPermissionLauncher.launch(needed.toTypedArray())
    }
    val onCallClick = { startCallWithPermissions(false) }
    val onVideoCallClick = { startCallWithPermissions(true) }

    // --- Voice notes + attachments (web-compatible, encrypted with the channel key) ---
    val scope = rememberCoroutineScope()

    // Location sharing — same CC_LOC wire format as the citizen chat, sent as a normal text message so
    // it renders on the web dashboard too. The viewer's own location (below) drives the comparison map
    // on received pins so the reader sees "them vs me" at a glance.
    val currentOwnLocation = rememberOwnLocationSnapshot(enabled = hasLocationPermission(context))
    var sharingLocation by remember { mutableStateOf(false) }
    val doShareLocation: () -> Unit = {
        if (!sharingLocation) {
            sharingLocation = true
            scope.launch {
                val estimate = fetchCurrentLocationEstimateForChatSend(
                    context = context, bleRssi = null, preferFreshFix = true,
                )
                if (estimate == null) {
                    sharingLocation = false
                    Toast.makeText(context, context.getString(R.string.chat_location_unavailable), Toast.LENGTH_SHORT).show()
                } else {
                    if (estimate.source != LOCATION_SOURCE_GPS) {
                        Toast.makeText(context, context.getString(R.string.chat_location_sent_approximate), Toast.LENGTH_SHORT).show()
                    }
                    viewModel.send(heading, buildLocationPayload(estimate))
                    sharingLocation = false
                }
            }
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) doShareLocation()
    }
    val onLocationClick = {
        if (hasLocationPermission(context)) doShareLocation()
        else locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }
    val voiceRecorder = remember { AuthorityVoiceRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordSeconds = 0
            while (true) {
                delay(1000)
                recordSeconds += 1
            }
        }
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted && voiceRecorder.start()) isRecording = true }
    val onStartRecord: () -> Unit = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (voiceRecorder.start()) isRecording = true
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val onStopRecord: () -> Unit = {
        val result = voiceRecorder.stop()
        isRecording = false
        if (result != null) {
            viewModel.send(heading, "", listOf(makeVoiceAttachment(result.first, result.second)))
        }
    }
    val onCancelRecord: () -> Unit = {
        voiceRecorder.cancel()
        isRecording = false
    }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val att = prepareChannelAttachmentFromUri(context, uri)
                if (att != null) viewModel.send(heading, "", listOf(att))
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(
                            displayName = heading,
                            stableKey = "authch:$channelId:$peerUid",
                            modifier = Modifier.size(40.dp),
                            textStyle = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = heading,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                RoleBadge(role = role)
                            }
                            // subtitle priority: "typing…" > Bluetooth link status > agency
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                if (isBluetoothLinked && !partnerTyping) {
                                    Icon(
                                        imageVector = Icons.Filled.Bluetooth,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    text = when {
                                        partnerTyping -> stringResource(R.string.authority_channel_typing)
                                        isBluetoothLinked ->
                                            stringResource(R.string.chat_status_connected_bluetooth)
                                        else -> agencySubtitle
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (partnerTyping || isBluetoothLinked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onVideoCallClick) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = stringResource(R.string.authority_channel_call_video),
                        )
                    }
                    IconButton(onClick = onCallClick) {
                        Icon(
                            Icons.Filled.Phone,
                            contentDescription = stringResource(R.string.chat_call_contact),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                ),
            )
        },
        bottomBar = {
            Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                ChannelComposer(
                    value = input,
                    onValueChange = onInputChange,
                    sending = state.sending,
                    isRecording = isRecording,
                    recordSeconds = recordSeconds,
                    onSend = {
                        val body = input.trim()
                        if (body.isNotEmpty()) {
                            viewModel.send(heading, body)
                            input = ""
                            lastTypingWrite = 0L
                            viewModel.setTyping(false)
                        }
                    },
                    onAttach = { attachmentPicker.launch("*/*") },
                    onShareLocation = onLocationClick,
                    sharingLocation = sharingLocation,
                    onStartRecord = onStartRecord,
                    onStopRecord = onStopRecord,
                    onCancelRecord = onCancelRecord,
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ChatTextureBackground(modifier = Modifier.matchParentSize())
            when {
                state.loading && state.messages.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.error != null && state.messages.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.authority_channels_error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                state.messages.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.authority_channel_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                    ) {
                        item(key = "authority_e2ee_notice", contentType = "system_notice") {
                            ChatEncryptionNoticeCard(
                                text = stringResource(R.string.authority_channel_e2ee_notice),
                            )
                        }
                        itemsIndexed(state.messages, key = { _, m -> m.id }) { index, message ->
                            val showDateHeader = index == 0 || !isSameLocalDay(
                                previousTimestamp = state.messages[index - 1].atMillis,
                                currentTimestamp = message.atMillis,
                                timeZone = listTimeZone,
                            )
                            if (showDateHeader) {
                                DateHeader(date = dateHeaderFormatter.format(Date(message.atMillis)))
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            ChannelBubble(
                                message = message,
                                mine = message.senderUid == viewModel.myUid,
                                loadBytes = viewModel::loadAttachmentBytes,
                                readByPeer = message.atMillis <= partnerReadAt,
                                ownLocation = currentOwnLocation,
                                bridgeMedia = bridgeMediaMessages[message.id],
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showScrollToBottom,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            ) {
                ScrollToBottomButton(
                    count = newMessageCount,
                    onClick = {
                        scope.launch { listState.animateScrollToItem(state.messages.size) }
                    },
                )
            }
        }
    }
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

// Call summaries are carried inside the encrypted body with the same control-char sentinel the web uses
// (secure-channel.ts encodeCallLog): U+0001 "call" U+0001 + JSON. Without parsing they show as raw code.
private const val CALL_LOG_PREFIX = "call"

private data class ChannelCallLog(val kind: String, val status: String, val durationSec: Int)

private fun parseChannelCallLog(text: String): ChannelCallLog? {
    if (!text.startsWith(CALL_LOG_PREFIX)) return null
    return try {
        val json = org.json.JSONObject(text.substring(CALL_LOG_PREFIX.length))
        val kind = json.optString("kind")
        if (kind != "audio" && kind != "video") return null
        ChannelCallLog(kind = kind, status = json.optString("status"), durationSec = json.optInt("durationSec"))
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun ChannelBubble(
    message: HierarchyMessage,
    mine: Boolean,
    loadBytes: suspend (ChannelAttachment) -> ByteArray?,
    readByPeer: Boolean,
    ownLocation: Location? = null,
    bridgeMedia: ChatMessage? = null,
) {
    // Bluetooth-carried media (bridge rows): voice notes play and images render from the local
    // file with the same widgets the citizen chat uses.
    if (bridgeMedia != null) {
        val (bubbleColor, contentColor) = if (mine) {
            outgoingChatBubbleColors()
        } else {
            incomingChatBubbleColors()
        }
        val bubbleShape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomEnd = if (mine) 4.dp else 16.dp,
            bottomStart = if (mine) 16.dp else 4.dp,
        )
        val context = LocalContext.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 280.dp).clip(bubbleShape),
                shape = bubbleShape,
                color = bubbleColor,
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (bridgeMedia.messageType == MessageType.IMAGE) {
                        ImageMessageContent(
                            message = bridgeMedia,
                            contentColor = contentColor,
                            progress = null,
                            onImageClick = { uri ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, bridgeMedia.imageMimeType ?: "image/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    )
                                }
                            },
                        )
                    } else {
                        AudioMessageContent(
                            message = bridgeMedia,
                            contentColor = contentColor,
                            progress = null,
                        )
                    }
                    Text(
                        text = clockFormat.format(Date(message.atMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
        return
    }

    // A Bluetooth-carried shared file (CC_FILE payload row): the citizen file chip shows the
    // name/size; the blob itself lands via the file stream on the receiver.
    val sharedFile = remember(message.text) { parseSharedFilePayload(message.text) }
    if (sharedFile != null) {
        val (bubbleColor, contentColor) = if (mine) {
            outgoingChatBubbleColors()
        } else {
            incomingChatBubbleColors()
        }
        val bubbleShape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomEnd = if (mine) 4.dp else 16.dp,
            bottomStart = if (mine) 16.dp else 4.dp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 280.dp).clip(bubbleShape),
                shape = bubbleShape,
                color = bubbleColor,
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    FileMessageContent(payload = sharedFile, contentColor = contentColor)
                    Text(
                        text = clockFormat.format(Date(message.atMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
        return
    }
    // A call-summary message renders as ChatScreen's dedicated call bubble (CallEventRow) so authority
    // call logs look identical to the main chat. `mine` gives the direction; the web call-log status
    // (ended/missed/declined) maps onto the same CallResult set ChatScreen already renders.
    val callLog = parseChannelCallLog(message.text)
    if (callLog != null) {
        CallEventRow(
            event = CallEvent(
                id = message.id,
                sessionCode = "",
                timestampMillis = message.atMillis,
                direction = if (mine) CallDirection.OUTGOING else CallDirection.INCOMING,
                result = when (callLog.status) {
                    "ended" -> CallResult.ANSWERED
                    "declined" -> CallResult.REJECTED
                    else -> CallResult.MISSED
                },
                durationMillis = callLog.durationSec.takeIf { it > 0 }?.let { it * 1000L },
            ),
            messageFormatter = clockFormat,
        )
        return
    }

    // A shared-location message (CC_LOC…) renders as a comparison map — the same fallback diagram the
    // citizen chat uses, showing the sender's pin AND (for received pins) the viewer's own location so
    // "them vs me" is legible at a glance. Web-compatible: the wire format is identical.
    val sharedLocation = remember(message.text) { parseSharedLocationPayload(message.text) }
    if (sharedLocation != null) {
        val context = LocalContext.current
        val bubbleShapeLoc = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomEnd = if (mine) 4.dp else 16.dp,
            bottomStart = if (mine) 16.dp else 4.dp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 280.dp).clip(bubbleShapeLoc),
                shape = bubbleShapeLoc,
                color = if (mine) outgoingChatBubbleColors().first else incomingChatBubbleColors().first,
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    RelativeLocationFallbackMap(
                        ownLocation = if (mine) null else ownLocation,
                        payload = sharedLocation,
                        sharedDisplayName = if (mine) null else message.senderName,
                        sharedStableKey = message.senderUid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                runCatching {
                                    val uri = Uri.parse(
                                        "geo:${sharedLocation.latitude},${sharedLocation.longitude}?q=" +
                                            "${sharedLocation.latitude},${sharedLocation.longitude}"
                                    )
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.chat_location_shared_point),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = clockFormat.format(Date(message.atMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        return
    }

    // Match ChatScreen's ChatBubble exactly: shared ChatBubbleColors, tail shape, outgoing border.
    val (bubbleColor, contentColor) = if (mine) outgoingChatBubbleColors() else incomingChatBubbleColors()
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomEnd = if (mine) 4.dp else 16.dp,
        bottomStart = if (mine) 16.dp else 4.dp,
    )
    val displayText = message.text
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            contentColor = contentColor,
            shape = bubbleShape,
            border = if (mine) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            } else {
                null
            },
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (displayText.isNotBlank()) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )
                }
                message.attachments.forEachIndexed { index, att ->
                    if (index > 0 || displayText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    ChannelAttachmentContent(
                        attachment = att,
                        loadBytes = loadBytes,
                        contentColor = contentColor,
                        mine = mine,
                    )
                }
                val stamp = if (message.atMillis > 0L) clockFormat.format(Date(message.atMillis)) else ""
                if (stamp.isNotEmpty()) {
                    // Same timestamp treatment as ChatScreen's ChatBubble: an explicit 8dp gap, then an
                    // aligned Row (spacedBy 4dp, centered) holding the labelSmall stamp at 0.7 alpha.
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.align(if (mine) Alignment.End else Alignment.Start),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                            textAlign = TextAlign.Start,
                        )
                        // ✓ sent / ✓✓ read on my own messages (WhatsApp-style, web-compatible receipts).
                        if (mine) {
                            Icon(
                                imageVector = if (readByPeer) {
                                    Icons.Filled.DoneAll
                                } else {
                                    Icons.Filled.Done
                                },
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = if (readByPeer) {
                                    Color(0xFF34B7F1)
                                } else {
                                    contentColor.copy(alpha = 0.7f)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Small chip showing the peer's authority role (admin / authority / field team), like the web header. */
@Composable
private fun RoleBadge(role: String) {
    val label = roleLabel(role) ?: return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun roleLabel(role: String): String? {
    val key = role.trim().lowercase(Locale.getDefault()).replace(Regex("[-_ ]"), "")
    return when (key) {
        "" -> null
        "admin", "superadmin" -> stringResource(R.string.authority_role_admin)
        "authority" -> stringResource(R.string.authority_role_authority)
        "fieldteam", "field", "rescue", "rescuer" -> stringResource(R.string.authority_role_fieldteam)
        else -> role.trim().replaceFirstChar { it.uppercase() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelComposer(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    isRecording: Boolean,
    recordSeconds: Int,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onShareLocation: () -> Unit,
    sharingLocation: Boolean,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onCancelRecord: () -> Unit,
) {
    // Same shell as ChatScreen's MessageComposer: elevated surface + rounded surfaceVariant field, an
    // attachment button, and a mic button that flips to Send when there's text. Insets are handled by
    // the Scaffold's bottomBar wrapper, like ChatScreen.
    Surface(tonalElevation = 3.dp) {
        if (isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935)),
                )
                Text(
                    text = "%d:%02d".format(recordSeconds / 60, recordSeconds % 60),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancelRecord) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.authority_call_cancel))
                }
                FilledIconButton(onClick = onStopRecord, modifier = Modifier.size(52.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.authority_channel_send),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onAttach, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.chat_add_attachment),
                    )
                }
                IconButton(
                    onClick = onShareLocation,
                    enabled = !sharingLocation,
                    modifier = Modifier.size(44.dp),
                ) {
                    if (sharingLocation) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = stringResource(R.string.chat_share_location),
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                    placeholder = { Text(stringResource(R.string.authority_channel_composer_hint)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        errorContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                if (value.isNotBlank()) {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = !sending,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.authority_channel_send),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onStartRecord,
                        enabled = !sending,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.authority_channel_record),
                        )
                    }
                }
            }
        }
    }
}
