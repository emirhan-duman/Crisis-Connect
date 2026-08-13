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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.screens.Chat.ChatTextureBackground
import com.auralis.crisisconnect.ui.components.ContactAvatar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_BLE_GATT
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.normalizePreferredTransport
import com.auralis.crisisconnect.data.observeMessages
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.saveLocalAudioMessage
import com.auralis.crisisconnect.data.saveLocalImageMessage
import com.auralis.crisisconnect.data.saveLocalMessage
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.data.toAuthorityEntity
import com.auralis.crisisconnect.data.toAuthorityConversationEntity
import com.auralis.crisisconnect.data.toHierarchyMessage
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.screens.Chat.AudioMessageContent
import com.auralis.crisisconnect.screens.Chat.CallEventRow
import com.auralis.crisisconnect.screens.Chat.CallOverlay
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
import com.auralis.crisisconnect.service.CallAudioRoute
import com.auralis.crisisconnect.service.CallState
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.RfcommForegroundService.CallDirection
import com.auralis.crisisconnect.service.RfcommForegroundService.CallEvent
import com.auralis.crisisconnect.service.RfcommForegroundService.CallResult
import com.auralis.crisisconnect.ui.components.rememberConnectedSessions
import com.auralis.crisisconnect.messaging.ChannelAttachment
import com.auralis.crisisconnect.messaging.ChannelAttachments
import com.auralis.crisisconnect.messaging.AuthorityMlsChatChannel
import com.auralis.crisisconnect.messaging.AuthorityMlsChatMessage
import com.auralis.crisisconnect.messaging.AuthorityMlsMessagePayload
import com.auralis.crisisconnect.messaging.AuthorityMlsOfflineEnvelopeCodec
import com.auralis.crisisconnect.messaging.AuthorityMlsPreparation
import com.auralis.crisisconnect.messaging.AuthorityMlsScopeType
import com.auralis.crisisconnect.messaging.InternetChatTransport
import com.auralis.crisisconnect.messaging.InternetConversation
import com.auralis.crisisconnect.messaging.HierarchyMessage
import com.auralis.crisisconnect.messaging.HierarchyMessagingClient
import com.auralis.crisisconnect.messaging.PendingChannelAttachment
import com.auralis.crisisconnect.messaging.call.AuthorityCallSignaling
import com.auralis.crisisconnect.service.p2p.P2pGattChatManager
import com.auralis.crisisconnect.service.p2p.call.P2pCallController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** UI state for a single cross-panel (hierarchy) 1:1 thread. */
data class ChannelThreadState(
    val loading: Boolean = true,
    val error: String? = null,
    val messages: List<HierarchyMessage> = emptyList(),
    val sending: Boolean = false,
    val mlsPreparation: AuthorityMlsPreparation? = null,
    val mlsStagingReady: Boolean = false,
    val mlsSendReady: Boolean = false,
    val securityError: String? = null,
    val mlsApprovalUid: String? = null,
    val mlsApprovalError: Boolean = false,
)

/**
 * Drives one hierarchy channel thread with a specific peer. Cloud content uses only the verified MLS-v2
 * session; the retired server-issued shared AES key and its Firestore history are never requested.
 * Nav args are supplied via [start] from the screen (no custom factory).
 */
class AuthorityChannelThreadViewModel(app: Application) : AndroidViewModel(app) {
    private val client = HierarchyMessagingClient()
    private val auth = FirebaseAuth.getInstance()
    private val dao by lazy { AppDatabase.getInstance(getApplication()).authorityMessageDao() }
    private var readCursorListener: ListenerRegistration? = null
    private var deliveryCursorListener: ListenerRegistration? = null
    private var typingListener: ListenerRegistration? = null
    private var typingExpiryJob: Job? = null
    private var lastWrittenCursor = 0L
    @Volatile private var readEligible = false
    private var roomJob: Job? = null
    private var mlsJob: Job? = null
    private var cloudRetryJob: Job? = null
    private val mlsTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mlsChannel: AuthorityMlsChatChannel? = null
    private var lastMlsPreparationDiagnostic: String? = null
    private var channelId: String = ""
    private var peerUid: String = ""
    private var scopeType: AuthorityMlsScopeType = AuthorityMlsScopeType.HIERARCHY
    private var peerDisplayName: String = ""
    private var peerAgencyName: String = ""
    private var peerRole: String = ""

    private val _state = MutableStateFlow(ChannelThreadState())
    val state: StateFlow<ChannelThreadState> = _state.asStateFlow()

    /** How far the peer has read MY messages (millis) — drives ✓✓ ticks. Web-compatible readReceipts. */
    private val _partnerReadAt = MutableStateFlow(0L)
    val partnerReadAt: StateFlow<Long> = _partnerReadAt.asStateFlow()

    /** How far the peer client has decrypted MY messages — drives the gray ✓✓ state. */
    private val _partnerDeliveredAt = MutableStateFlow(0L)
    val partnerDeliveredAt: StateFlow<Long> = _partnerDeliveredAt.asStateFlow()

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
    private val pendingOfflineEnvelopes = ConcurrentHashMap<String, String>()
    private val pendingBluetoothSends = ConcurrentHashMap<String, String>()
    private val pendingBluetoothAttachments = ConcurrentHashMap<String, List<ChannelAttachment>>()
    private val offlineDrainMutex = Mutex()
    private val bluetoothDrainMutex = Mutex()
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

    private val p2pCallController by lazy { P2pCallController.shared(getApplication()) }
    private var gattCallsSnapshot: Map<String, CallUiState> = emptyMap()
    private var rfcommCallsSnapshot: Map<String, CallUiState> = emptyMap()
    private var btCallStateJob: Job? = null
    private val _nearbyCall = MutableStateFlow<CallUiState?>(null)
    val nearbyCall: StateFlow<CallUiState?> = _nearbyCall.asStateFlow()

    init {
        // This is the same process-wide GATT call engine observed by the normal ChatScreen.
        // Android<->iOS authority calls therefore use the already-tested cross-platform wire path.
        viewModelScope.launch {
            p2pCallController.calls.collect { calls ->
                gattCallsSnapshot = calls
                publishNearbyCall()
            }
        }
    }

    private fun publishNearbyCall() {
        val session = _bridgeSessionCode.value
        _nearbyCall.value = session?.let { code ->
            gattCallsSnapshot.values.firstOrNull {
                it.sessionCode.equals(code, ignoreCase = true)
            } ?: rfcommCallsSnapshot[code]
        }
    }

    /** Places the same authenticated nearby voice call used by the normal ChatScreen. */
    fun startBluetoothCall(onResult: (Boolean) -> Unit) {
        val session = _bridgeSessionCode.value
        if (session == null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val contact = withContext(Dispatchers.IO) { getContact(context, session) }
            if (contact == null || contact.aesKey.isBlank()) {
                requestBluetoothConnect(session)
                onResult(false)
                return@launch
            }

            if (normalizePreferredTransport(contact.preferredTransport) == PREFERRED_TRANSPORT_BLE_GATT) {
                val gatt = P2pGattChatManager.shared(context)
                gatt.updateContact(contact)
                gatt.start()
                val started = if (gatt.isReady() || p2pCallController.isCallReachable(session)) {
                    withContext(Dispatchers.IO) { p2pCallController.startCall(session) }
                } else {
                    false
                }
                onResult(started)
                return@launch
            }

            val service = btService
            if (service == null) {
                requestBluetoothConnect(session)
                onResult(false)
                return@launch
            }
            service.startVoipCall(session) { started -> onResult(started) }
        }
    }

    fun acceptBluetoothCall(callId: String) {
        val session = _bridgeSessionCode.value ?: return
        if (gattCallsSnapshot.values.any { it.callId == callId }) {
            viewModelScope.launch(Dispatchers.IO) {
                p2pCallController.acceptCall(session, callId)
            }
        } else {
            btService?.acceptIncomingCall(session, callId) { }
        }
    }

    fun rejectBluetoothCall(callId: String) {
        val session = _bridgeSessionCode.value ?: return
        if (gattCallsSnapshot.values.any { it.callId == callId }) {
            p2pCallController.rejectCall(session, callId)
        } else {
            btService?.rejectIncomingCall(session, callId)
        }
    }

    fun endBluetoothCall(callId: String) {
        val session = _bridgeSessionCode.value ?: return
        if (gattCallsSnapshot.values.any { it.callId == callId }) {
            p2pCallController.endCall(session)
        } else {
            btService?.endVoipCall(session, callId)
        }
    }

    fun setBluetoothCallMuted(muted: Boolean) {
        val session = _bridgeSessionCode.value ?: return
        val call = _nearbyCall.value ?: return
        if (gattCallsSnapshot.values.any { it.callId == call.callId }) {
            p2pCallController.setMuted(session, muted)
        } else {
            btService?.setCallMicMuted(session, muted)
        }
    }

    fun setBluetoothCallAudioRoute(route: CallAudioRoute) {
        val session = _bridgeSessionCode.value ?: return
        val call = _nearbyCall.value ?: return
        if (route !in call.availableRoutes) return
        if (gattCallsSnapshot.values.any { it.callId == call.callId }) {
            p2pCallController.setSpeakerEnabled(session, route == CallAudioRoute.Speaker)
        } else {
            btService?.setCallAudioRoute(session, route)
        }
    }

    fun setBluetoothLinked(linked: Boolean) {
        bluetoothLinked = linked
        if (linked) viewModelScope.launch {
            queuePendingMlsForBluetooth()
            drainBluetoothSends()
        }
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

    private var btService: RfcommForegroundService? = null
    private var btServiceBound = false
    private var pendingBtConnectSession: String? = null
    private val btServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            btService = (binder as? RfcommForegroundService.LocalBinder)?.getService()
            btCallStateJob?.cancel()
            btCallStateJob = viewModelScope.launch {
                btService?.calls?.collect { calls ->
                    rfcommCallsSnapshot = calls
                    publishNearbyCall()
                }
            }
            pendingBtConnectSession?.let { code -> btService?.connectToContact(code) { } }
            pendingBtConnectSession = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            btCallStateJob?.cancel()
            btCallStateJob = null
            rfcommCallsSnapshot = emptyMap()
            btService = null
            publishNearbyCall()
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
                        msg.messageType == MessageType.TEXT &&
                            trimmed.startsWith(AuthorityMlsOfflineEnvelopeCodec.PREFIX) -> {
                            val envelope = AuthorityMlsOfflineEnvelopeCodec.decode(trimmed)
                            if (envelope != null) {
                                pendingOfflineEnvelopes[msg.messageUuid] = trimmed
                                viewModelScope.launch { drainOfflineEnvelopes() }
                            }
                            null
                        }
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
            if (refreshed.aesKey.isNotBlank()) {
                if (normalizePreferredTransport(refreshed.preferredTransport) == PREFERRED_TRANSPORT_BLE_GATT) {
                    P2pGattChatManager.shared(context).apply {
                        updateContact(refreshed)
                        start()
                    }
                } else if (refreshed.address.isNotBlank()) {
                    requestBluetoothConnect(session)
                }
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

    private suspend fun drainOfflineEnvelopes() = offlineDrainMutex.withLock {
        val channel = mlsChannel ?: return@withLock
        for ((storeId, encoded) in pendingOfflineEnvelopes.entries.toList()) {
            val accepted = runCatching { channel.acceptOfflineEnvelope(encoded) }
                .onFailure { android.util.Log.w("AuthorityChat", "Nearby MLS delivery rejected", it) }
                .isSuccess
            if (accepted) {
                pendingOfflineEnvelopes.remove(storeId, encoded)
                _bridgeSessionCode.value?.let { session ->
                    withContext(Dispatchers.IO) {
                        AppDatabase.getInstance(getApplication())
                            .messageDao()
                            .deleteInboundAuthorityMlsTransportRow(session, storeId)
                    }
                }
            }
        }
    }

    private suspend fun sendBluetoothEnvelope(
        messageId: String,
        encoded: String,
        attachments: List<ChannelAttachment>,
    ): Boolean {
        val session = _bridgeSessionCode.value ?: return false
        val context = getApplication<Application>()
        val contact = withContext(Dispatchers.IO) { getContact(context, session) } ?: return false

        // Prefer the cross-platform BLE GATT lane; it is the only nearby lane available on iOS.
        val gatt = P2pGattChatManager.shared(context)
        gatt.updateContact(contact)
        gatt.start()
        if (gatt.isReady()) {
            val blobsSent = attachments.withIndex().all { (index, attachment) ->
                val cipher = ChannelAttachments.readCachedAuthorityMlsCiphertext(context, attachment.path)
                    ?: return@all false
                runCatching {
                    gatt.sendFileMessage(
                        contact = contact,
                        messageId = "amlsa_${messageId.takeLast(80)}_$index",
                        displayName = attachment.path,
                        mimeType = ChannelAttachments.AUTHORITY_MLS_BLOB_MIME,
                        originalSizeBytes = cipher.size.toLong(),
                        bytes = cipher,
                    )
                }.getOrDefault(false)
            }
            val envelopeBytes = encoded.toByteArray(Charsets.UTF_8)
            if (blobsSent && runCatching {
                    gatt.sendFileMessage(
                        contact = contact,
                        messageId = "amls_$messageId",
                        displayName = "authority-mls-envelope",
                        mimeType = ChannelAttachments.AUTHORITY_MLS_ENVELOPE_MIME,
                        originalSizeBytes = envelopeBytes.size.toLong(),
                        bytes = envelopeBytes,
                    )
                }.getOrDefault(false)) return true
        }

        // Android ↔ Android may already own a faster RFCOMM stream.
        if (attachments.isNotEmpty()) {
            requestBluetoothConnect(session)
            return false
        }
        val service = btService
        if (service != null) {
            val result = CompletableDeferred<Boolean>()
            service.sendMessage(session, "amls_$messageId", encoded) { result.complete(it) }
            if (result.await()) return true
        }
        requestBluetoothConnect(session)
        return false
    }

    private suspend fun drainBluetoothSends() = bluetoothDrainMutex.withLock {
        for ((messageId, encoded) in pendingBluetoothSends.entries.toList()) {
            val attachments = pendingBluetoothAttachments[messageId].orEmpty()
            if (sendBluetoothEnvelope(messageId, encoded, attachments)) {
                pendingBluetoothSends.remove(messageId, encoded)
                pendingBluetoothAttachments.remove(messageId)
            }
        }
    }

    private fun queueBluetoothSend(
        messageId: String,
        encoded: String,
        attachments: List<ChannelAttachment> = emptyList(),
    ) {
        pendingBluetoothSends[messageId] = encoded
        if (attachments.isNotEmpty()) pendingBluetoothAttachments[messageId] = attachments
        viewModelScope.launch { drainBluetoothSends() }
        schedulePendingCloudFlush()
    }

    private fun schedulePendingCloudFlush() {
        if (cloudRetryJob?.isActive == true) return
        val expectedConversation = mlsChannel?.conversationId ?: return
        cloudRetryJob = viewModelScope.launch {
            while (mlsChannel?.conversationId == expectedConversation) {
                val channel = mlsChannel ?: return@launch
                if (isInternetAvailable() && _state.value.mlsSendReady) {
                    val flushed = runCatching {
                        channel.flushPending().forEach { delivered ->
                            persistMlsMessage(delivered)
                            pendingBluetoothSends.remove(delivered.id)
                            pendingBluetoothAttachments.remove(delivered.id)
                        }
                    }.isSuccess
                    if (flushed) return@launch
                }
                delay(1_500L)
            }
        }
    }

    private suspend fun queuePendingMlsForBluetooth() {
        val channel = mlsChannel ?: return
        runCatching {
            channel.pendingOfflineEnvelopes() to channel.pendingAttachmentDescriptors()
        }.onSuccess { (envelopes, attachments) ->
            envelopes.forEach { (id, value) ->
                pendingBluetoothSends[id] = value
                attachments[id]?.takeIf { it.isNotEmpty() }?.let {
                    pendingBluetoothAttachments[id] = it
                }
            }
        }
            .onFailure { android.util.Log.w("AuthorityChat", "Unable to prepare nearby MLS outbox", it) }
    }

    fun start(
        channelId: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType,
        peerDisplayName: String,
        peerAgencyName: String,
        peerRole: String,
    ) {
        if (this.channelId == channelId && this.peerUid == peerUid && this.scopeType == scopeType &&
            (mlsChannel != null || mlsJob?.isActive == true)
        ) return
        this.channelId = channelId
        this.peerUid = peerUid
        this.scopeType = scopeType
        this.peerDisplayName = peerDisplayName
        this.peerAgencyName = peerAgencyName
        this.peerRole = peerRole
        lastWrittenCursor = 0L
        cloudMessages.value = emptyList()
        bridgeMessages.value = emptyList()
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            mlsPreparation = null,
            mlsStagingReady = false,
            mlsSendReady = false,
            securityError = null,
        )
        mlsJob?.cancel()
        cloudRetryJob?.cancel()
        cloudRetryJob = null
        mlsChannel?.let { previous -> viewModelScope.launch { previous.close() } }
        mlsChannel = null
        // The nearby link is transport-only: it carries the exact MLS application ciphertext and
        // never receives AuthorityChat plaintext or attachment keys outside MLS.
        startBluetoothBridge(peerUid)

        // Read-receipt (✓✓) + typing subscriptions — metadata only, so they work without the channel key.
        val me = myUid
        _partnerReadAt.value = 0L
        _partnerDeliveredAt.value = 0L
        _partnerTyping.value = false
        readCursorListener?.remove()
        readCursorListener = client.listenReadCursor(
            channelId, me, peerUid,
            onAt = { at -> _partnerReadAt.value = at },
            scopeType = scopeType,
        )
        deliveryCursorListener?.remove()
        deliveryCursorListener = client.listenDeliveredCursor(
            channelId, me, peerUid,
            onAt = { at -> _partnerDeliveredAt.value = at },
            scopeType = scopeType,
        )
        typingListener?.remove()
        typingExpiryJob?.cancel()
        typingListener = client.listenTyping(
            channelId, me, peerUid,
            onTyping = { typing ->
                typingExpiryJob?.cancel()
                if (typing) {
                    _partnerTyping.value = true
                    // Local safety expiry in case the peer's "stopped typing" write never lands (tab closed).
                    typingExpiryJob = viewModelScope.launch { delay(6000); _partnerTyping.value = false }
                } else {
                    _partnerTyping.value = false
                }
            },
            scopeType = scopeType,
        )

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
                if (readEligible) advanceReadCursor(msgs)
            }
        }

        startMls(channelId, peerUid)
    }

    /** The UI enables this only while resumed and genuinely showing the newest row. */
    fun setReadEligible(eligible: Boolean) {
        readEligible = eligible
        if (eligible) {
            viewModelScope.launch(Dispatchers.IO) { advanceReadCursor(cloudMessages.value) }
        }
    }

    private suspend fun advanceReadCursor(messages: List<HierarchyMessage>) {
        if (!readEligible) return
        val newest = messages.asSequence()
            .filter { it.senderUid == peerUid }
            .maxOfOrNull { it.atMillis } ?: return
        runCatching {
            dao.upsertRead(
                AuthorityChannelReadEntity(
                    channelId = channelId,
                    peerUid = peerUid,
                    lastReadAtMillis = newest,
                ),
            )
        }
        if (readEligible && newest > lastWrittenCursor) {
            lastWrittenCursor = newest
            client.writeReadCursor(channelId, myUid, peerUid, newest, scopeType)
        }
    }

    private fun startMls(channelId: String, peerUid: String) {
        val me = myUid
        if (me.isBlank() || peerUid.isBlank()) {
            android.util.Log.w("AuthorityChat", "MLS setup skipped because the authenticated binding is incomplete")
            return
        }
        mlsJob = viewModelScope.launch {
            var attempt = 0
            while (true) {
                try {
                    val channel = mlsChannel ?: AuthorityMlsChatChannel.prepare(
                        context = getApplication(),
                        selfUid = me,
                        peerUid = peerUid,
                        scopeType = scopeType,
                        channelId = channelId,
                        deviceLabel = "Android ${android.os.Build.MODEL}".take(64),
                    ).also { mlsChannel = it }
                    if (!_state.value.mlsStagingReady) {
                        _state.value = _state.value.copy(mlsStagingReady = true)
                    }
                    val preparation = channel.refreshPreparation()
                    val diagnostic = buildString {
                        append("ready=").append(preparation.ready)
                        append(" rejected=").append(preparation.rejectedDirectoryRecords)
                        append(" approved=").append(preparation.trust.count { it.approved })
                            .append('/').append(preparation.trust.size)
                        append(" deviceSets=")
                        append(preparation.trust.joinToString(",") { it.deviceCommitments.size.toString() })
                    }
                    if (diagnostic != lastMlsPreparationDiagnostic) {
                        lastMlsPreparationDiagnostic = diagnostic
                        android.util.Log.i("AuthorityChat", "MLS preparation $diagnostic")
                    }
                    _state.value = _state.value.copy(
                        loading = false,
                        mlsPreparation = preparation,
                        mlsSendReady = false,
                        securityError = "automatic-retry",
                    )
                    if (preparation.ready) {
                        activateMls(channel)
                        if (channel.isReadyToSend()) {
                            channel.flushPending().forEach { delivered ->
                                persistMlsMessage(delivered)
                            }
                            _state.value = _state.value.copy(mlsSendReady = true, securityError = null)
                            return@launch
                        }
                    }
                    // A complete device directory is not sufficient: this fresh Android leaf must
                    // receive a Welcome and both accounts must be represented in the live MLS group.
                    runCatching { channel.requestPeerPreparation() }.onFailure {
                        android.util.Log.w("AuthorityChat", "MLS preparation wake failed", it)
                    }
                } catch (error: Throwable) {
                    android.util.Log.w("AuthorityChat", "MLS setup will retry automatically", error)
                    _state.value = _state.value.copy(
                        loading = false,
                        mlsStagingReady = mlsChannel != null,
                        mlsSendReady = false,
                        securityError = "automatic-retry",
                    )
                }
                // Directory convergence is normally sub-second; a 30-second exponential backoff
                // made a healthy protected thread feel frozen after one transient miss.
                delay(if (attempt < 10) 400L else 1_500L)
                attempt += 1
            }
        }
    }

    fun approveDeviceSet(uid: String, expectedFingerprint: String) {
        val channel = mlsChannel ?: return
        if (uid.isBlank() || expectedFingerprint.isBlank() || _state.value.mlsApprovalUid != null) return
        _state.value = _state.value.copy(mlsApprovalUid = uid, mlsApprovalError = false)
        viewModelScope.launch {
            runCatching { channel.approveDeviceSet(uid, expectedFingerprint) }
                .onSuccess { preparation ->
                    _state.value = _state.value.copy(
                        mlsPreparation = preparation,
                        mlsApprovalUid = null,
                        mlsApprovalError = false,
                    )
                }
                .onFailure { error ->
                    android.util.Log.w("AuthorityChat", "Device-set approval failed closed", error)
                    _state.value = _state.value.copy(mlsApprovalUid = null, mlsApprovalError = true)
                }
        }
    }

    private suspend fun activateMls(channel: AuthorityMlsChatChannel) {
        channel.activate(
            onMessage = { message -> persistMlsMessage(message) },
            onSecurityError = { error ->
                viewModelScope.launch {
                    android.util.Log.w("AuthorityChat", "MLS transport will reconnect automatically", error)
                    _state.value = _state.value.copy(
                        mlsPreparation = null,
                        mlsStagingReady = false,
                        mlsSendReady = false,
                        securityError = "automatic-retry",
                    )
                    if (mlsChannel === channel) mlsChannel = null
                    runCatching { channel.close() }
                    delay(1_000L)
                    startMls(channelId, peerUid)
                }
            },
        )
        drainOfflineEnvelopes()
        if (bluetoothLinked) {
            queuePendingMlsForBluetooth()
            drainBluetoothSends()
        }
    }

    private suspend fun persistMlsMessage(message: AuthorityMlsChatMessage) {
        val payload = message.payload
        val row = HierarchyMessage(
            id = message.id,
            senderUid = message.senderUid,
            senderName = payload.senderName,
            recipientUid = payload.recipientUid,
            recipientName = payload.recipientName,
            text = payload.text,
            atMillis = payload.sentAtMillis,
            attachments = payload.attachments,
            clientUuid = if (message.pending) "authority-mls-pending-v2" else "",
        )
        withContext(Dispatchers.IO) {
            dao.upsertAll(listOf(row.toAuthorityEntity(channelId, myUid)))
            val firstAttachment = payload.attachments.firstOrNull()
            dao.upsertConversations(
                listOf(
                    ChannelConversation(
                        channelId = channelId,
                        peerUid = peerUid,
                        peerName = peerDisplayName.ifBlank {
                            if (message.senderUid == peerUid) payload.senderName else payload.recipientName
                        }.ifBlank { peerUid },
                        peerPanelName = peerAgencyName.ifBlank { channelId },
                        group = if (scopeType == AuthorityMlsScopeType.AGENCY) "agency" else "hierarchy",
                        lastText = payload.text,
                        lastAtMillis = payload.sentAtMillis,
                        lastSenderUid = message.senderUid,
                        lastAttachmentKind = when {
                            firstAttachment?.isAudio == true -> "audio"
                            firstAttachment?.isImage == true -> "image"
                            firstAttachment != null -> "file"
                            else -> ""
                        },
                        peerRole = peerRole,
                    ).toAuthorityConversationEntity(),
                ),
            )
        }
        if (message.senderUid == peerUid) {
            client.writeDeliveredCursor(channelId, myUid, peerUid, payload.sentAtMillis, scopeType)
        }
    }

    /** Publishes my typing state toward the peer (best-effort; web-compatible). */
    fun setTyping(typing: Boolean) {
        val cid = channelId.takeIf { it.isNotBlank() } ?: return
        client.setTyping(cid, myUid, peerUid, typing, scopeType)
    }

    fun send(
        peerName: String,
        text: String,
        attachments: List<PendingChannelAttachment> = emptyList(),
        onAccepted: () -> Unit = {},
    ) {
        val body = text.trim()
        if ((body.isEmpty() && attachments.isEmpty()) || _state.value.sending) return
        val analyticsKind = when {
            attachments.isEmpty() -> "text"
            attachments.any { it.mime.startsWith("audio/") } -> "voice"
            attachments.any { it.mime.startsWith("image/") } -> "image"
            else -> "file"
        }
        val mls = mlsChannel ?: run {
            _state.value = _state.value.copy(securityError = "MLS conversation is not ready.")
            return
        }
        if (!_state.value.mlsStagingReady) {
            _state.value = _state.value.copy(securityError = "MLS conversation is not ready for local staging.")
            return
        }
        _state.value = _state.value.copy(sending = true)
        viewModelScope.launch {
            val senderName = runCatching { getSavedUserName(getApplication()).first() }.getOrDefault("")
                .ifBlank { auth.currentUser?.displayName.orEmpty() }
                .ifBlank { getApplication<Application>().getString(R.string.internet_message_notification_title) }
            var preparedAttachments = emptyList<ChannelAttachment>()
            runCatching {
                preparedAttachments = ChannelAttachments.prepareAuthorityMlsAttachments(
                    context = getApplication(),
                    conversationId = mls.conversationId,
                    pendings = attachments,
                )
                val message = mls.stage(AuthorityMlsMessagePayload(
                    recipientUid = peerUid,
                    recipientName = peerName,
                    senderName = senderName,
                    text = body,
                    sentAtMillis = System.currentTimeMillis(),
                    attachments = preparedAttachments,
                ))
                persistMlsMessage(message)
                onAccepted()
                val cloudDelivered = if (_state.value.mlsSendReady && isInternetAvailable()) {
                    runCatching {
                        mls.flushPending().forEach { delivered -> persistMlsMessage(delivered) }
                    }.isSuccess
                } else false
                if (!cloudDelivered) {
                    val encoded = mls.offlineEnvelope(message.id)
                    queueBluetoothSend(message.id, encoded, preparedAttachments)
                    Analytics.messageSent(analyticsKind, "bluetooth_mls", "authority_channel")
                } else if (cloudDelivered) {
                    Analytics.messageSent(analyticsKind, "internet", "authority_channel")
                } else if (isInternetAvailable()) {
                    runCatching { mls.requestPeerPreparation() }
                }
            }.onFailure { error ->
                _state.value = _state.value.copy(securityError = error.message ?: "MLS send failed")
            }
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

    /** Downloads an MLS-v2 attachment. Its independent per-file key is inside the MLS plaintext. */
    suspend fun loadAttachmentBytes(att: ChannelAttachment): ByteArray? {
        return runCatching {
            ChannelAttachments.fetchAttachmentBytes(getApplication(), null, null, att)
        }.getOrNull()
    }

    override fun onCleared() {
        readCursorListener?.remove()
        readCursorListener = null
        deliveryCursorListener?.remove()
        deliveryCursorListener = null
        typingListener?.remove()
        typingListener = null
        typingExpiryJob?.cancel()
        // Leaving the thread: clear my typing flag so the peer's "…yazıyor" indicator disappears.
        if (channelId.isNotBlank()) client.setTyping(channelId, myUid, peerUid, false, scopeType)
        roomJob?.cancel()
        roomJob = null
        mlsJob?.cancel()
        mlsJob = null
        cloudRetryJob?.cancel()
        cloudRetryJob = null
        val channelToClose = mlsChannel
        mlsChannel = null
        if (channelToClose != null) {
            // viewModelScope is already being cancelled when onCleared runs. Closing there can be
            // skipped entirely, leaking the native MLS context and keeping every action disabled on
            // the next visit. This one-shot independent scope releases the ratchet lease reliably.
            mlsTeardownScope.launch {
                try {
                    channelToClose.close()
                } finally {
                    mlsTeardownScope.cancel()
                }
            }
        } else {
            mlsTeardownScope.cancel()
        }
        bridgeAutoLinkJob?.cancel()
        bridgeAutoLinkJob = null
        bridgeMessagesJob?.cancel()
        bridgeMessagesJob = null
        btCallStateJob?.cancel()
        btCallStateJob = null
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
    scopeType: AuthorityMlsScopeType = AuthorityMlsScopeType.HIERARCHY,
) {
    val viewModel: AuthorityChannelThreadViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val partnerReadAt by viewModel.partnerReadAt.collectAsStateWithLifecycle()
    val partnerDeliveredAt by viewModel.partnerDeliveredAt.collectAsStateWithLifecycle()
    val partnerTyping by viewModel.partnerTyping.collectAsStateWithLifecycle()
    val securityReady = state.mlsStagingReady
    val callReady = state.mlsPreparation?.ready == true
    LaunchedEffect(channelId, peerUid, scopeType, title, agency, role) {
        viewModel.start(channelId, peerUid, scopeType, title, agency, role)
    }

    // Offline Bluetooth bridge status: green subtitle when this peer's hidden bridge contact has a
    // live BT link (same connected-sessions source the home list pills use).
    val connectedSessions = rememberConnectedSessions()
    val bridgeSessionCode by viewModel.bridgeSessionCode.collectAsStateWithLifecycle()
    val isBluetoothLinked = bridgeSessionCode != null && bridgeSessionCode in connectedSessions
    LaunchedEffect(isBluetoothLinked) { viewModel.setBluetoothLinked(isBluetoothLinked) }
    val bridgeMediaMessages by viewModel.bridgeMediaMessages.collectAsStateWithLifecycle()
    val nearbyCall by viewModel.nearbyCall.collectAsStateWithLifecycle()
    var isNearbyCallScreenVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(nearbyCall?.callId) {
        val call = nearbyCall
        if (call != null && call.state != CallState.Idle && call.state != CallState.Ended) {
            isNearbyCallScreenVisible = true
        }
    }

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
    val lifecycleOwner = LocalLifecycleOwner.current
    var screenResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            screenResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setReadEligible(false)
        }
    }
    LaunchedEffect(screenResumed, hasInitialized, isAtBottom, state.messages.lastOrNull()?.id) {
        viewModel.setReadEligible(screenResumed && hasInitialized && isAtBottom)
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
    val focusManager = LocalFocusManager.current
    val callFailedText = stringResource(R.string.chat_call_failed)
    val placeSfuCall = call@{ video: Boolean ->
        if (!com.auralis.crisisconnect.messaging.call.sfu.SfuCallConfig.ENABLED) {
            Toast.makeText(context, callFailedText, Toast.LENGTH_SHORT).show()
            return@call
        }
        android.util.Log.i("SfuAuthorityCall", "placeCall path=SFU-v2 channel=$channelId peer=$peerUid video=$video")
        com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager.init(context)
        com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager.startOutgoing(
            channelId = channelId,
            kind = if (scopeType == AuthorityMlsScopeType.AGENCY) {
                AuthorityCallSignaling.ChannelKind.AGENCY
            } else {
                AuthorityCallSignaling.ChannelKind.HIERARCHY
            },
            myUid = viewModel.myUid,
            peerUid = peerUid,
            peerName = heading,
            video = video,
        )
    }
    val placeCall = { video: Boolean ->
        // Video remains on the MLS-protected SFU. Audio prefers the already-authenticated nearby
        // link, exactly like ChatScreen; if that link disappears between tap and dial, retry SFU.
        if (!video && isBluetoothLinked) {
            viewModel.startBluetoothCall { started ->
                if (!started) {
                    if (viewModel.isInternetAvailable()) placeSfuCall(false)
                    else Toast.makeText(context, callFailedText, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            placeSfuCall(video)
        }
    }
    var pendingVideoCall by remember { mutableStateOf(false) }
    var pendingNearbyAcceptCallId by remember { mutableStateOf<String?>(null) }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true
        val acceptCallId = pendingNearbyAcceptCallId
        pendingNearbyAcceptCallId = null
        if (micOk && acceptCallId != null) {
            viewModel.acceptBluetoothCall(acceptCallId)
        } else if (micOk) {
            placeCall(pendingVideoCall && grants[Manifest.permission.CAMERA] == true)
        }
    }
    val startCallWithPermissions = { video: Boolean ->
        // The call overlay shares this activity. Leaving the composer focused keeps the IME above
        // the ringing screen and can cover its controls, so release focus before permission/call UI.
        focusManager.clearFocus(force = true)
        pendingVideoCall = video
        pendingNearbyAcceptCallId = null
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
                    IconButton(
                        onClick = onVideoCallClick,
                        enabled = callReady &&
                            com.auralis.crisisconnect.messaging.call.sfu.SfuCallConfig.ENABLED,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = stringResource(R.string.authority_channel_call_video),
                        )
                    }
                    IconButton(onClick = onCallClick, enabled = callReady || isBluetoothLinked) {
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
                Column {
                    ChannelComposer(
                        value = input,
                        onValueChange = onInputChange,
                        sending = state.sending,
                        securityReady = securityReady,
                        isRecording = isRecording,
                        recordSeconds = recordSeconds,
                        onSend = {
                            val body = input.trim()
                            if (body.isNotEmpty() && securityReady) {
                                viewModel.send(heading, body) {
                                    if (input.trim() == body) input = ""
                                    lastTypingWrite = 0L
                                    viewModel.setTyping(false)
                                }
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
                                deliveredToPeer = message.atMillis <= partnerDeliveredAt,
                                readByPeer = message.atMillis <= partnerReadAt,
                                ownLocation = currentOwnLocation,
                                bridgeMedia = bridgeMediaMessages[message.id],
                            )
                        }
                    }
                }
            }

            val pendingDeviceSets = state.mlsPreparation?.trust.orEmpty()
                .filter { !it.approved && it.fingerprint.isNotBlank() }
            if (pendingDeviceSets.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.97f),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                        .widthIn(max = 640.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.authority_device_verification_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.authority_device_verification_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        pendingDeviceSets.forEach { assessment ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = assessment.safetyNumber,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        enabled = state.mlsApprovalUid == null,
                                        onClick = { viewModel.approveDeviceSet(assessment.uid, assessment.fingerprint) },
                                    ) {
                                        Text(
                                            if (state.mlsApprovalUid == assessment.uid) {
                                                stringResource(R.string.authority_device_verification_busy)
                                            } else {
                                                stringResource(R.string.authority_device_verification_approve)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        if (state.mlsApprovalError) {
                            Text(
                                text = stringResource(R.string.authority_device_verification_changed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
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

    nearbyCall
        ?.takeIf { it.state != CallState.Idle && it.state != CallState.Ended && isNearbyCallScreenVisible }
        ?.let { call ->
            CallOverlay(
                modifier = Modifier.fillMaxSize(),
                call = call,
                contactName = heading,
                avatarStableKey = bridgeSessionCode ?: peerUid,
                avatarBitmap = null,
                onAccept = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.acceptBluetoothCall(call.callId)
                    } else {
                        pendingNearbyAcceptCallId = call.callId
                        callPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                },
                onReject = { viewModel.rejectBluetoothCall(call.callId) },
                onHangup = { viewModel.endBluetoothCall(call.callId) },
                onToggleMute = viewModel::setBluetoothCallMuted,
                onSelectAudioRoute = viewModel::setBluetoothCallAudioRoute,
                onMinimize = { isNearbyCallScreenVisible = false },
            )
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
    deliveredToPeer: Boolean,
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
                        // ✓ sent, gray ✓✓ decrypted by peer, blue ✓✓ read.
                        if (mine) {
                            Icon(
                                imageVector = if (readByPeer || deliveredToPeer) {
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
    securityReady: Boolean,
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
                FilledIconButton(onClick = onStopRecord, enabled = securityReady, modifier = Modifier.size(52.dp)) {
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
                IconButton(onClick = onAttach, enabled = securityReady, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.chat_add_attachment),
                    )
                }
                IconButton(
                    onClick = onShareLocation,
                    enabled = securityReady && !sharingLocation,
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
                        enabled = securityReady && !sending,
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
                        enabled = securityReady && !sending,
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
