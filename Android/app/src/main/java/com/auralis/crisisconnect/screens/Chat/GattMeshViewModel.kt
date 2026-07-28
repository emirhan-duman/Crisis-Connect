package com.auralis.crisisconnect.screens.Chat

import android.app.Application
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.core.chat.previewTextForReplyTarget
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.GattMeshChatStore
import com.auralis.crisisconnect.data.MeshChatMessage
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.MeshMessageStatus
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.loadRecentMessages
import com.auralis.crisisconnect.data.markAllRemoteMessagesRead
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.data.updateRemoteMessageMetadata
import com.auralis.crisisconnect.data.updateLocalMessageDeliveryState
import com.auralis.crisisconnect.data.upsertLocalTextMessage
import com.auralis.crisisconnect.service.gattmesh.GattMeshConnectedPeer
import com.auralis.crisisconnect.service.gattmesh.GattMeshSendDisposition
import com.auralis.crisisconnect.service.gattmesh.GattMeshServiceBinding
import com.auralis.crisisconnect.settingsDataStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import com.auralis.crisisconnect.security.SecurityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.firebase.crashlytics.FirebaseCrashlytics

class GattMeshViewModel(application: Application) : AndroidViewModel(application) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    data class GattMeshUiState(
        val publicMeshEnabled: Boolean = false,
        val isServiceEnabled: Boolean = false,
        val isScanning: Boolean = false,
        val connectedPeerCount: Int = 0,
        val discoveredPeerCount: Int = 0,
        val sendReadyPeerCount: Int = 0,
        val connectedPeers: List<GattMeshConnectedPeer> = emptyList(),
        val errorMessage: Int? = null,
        val canChat: Boolean = false,
        /** This device's own verified institution (kurum), shown on the self row in the peers sheet. */
        val localAgency: String? = null,
    )

    private val appContext = application.applicationContext
    private val meshBinding = GattMeshServiceBinding(appContext)

    private val _messageDraft = MutableStateFlow("")
    val messageDraft: StateFlow<String> = _messageDraft.asStateFlow()

    val messages: StateFlow<List<MeshChatMessage>> = GattMeshChatStore.messages

    private val _uiState = MutableStateFlow(GattMeshUiState())
    val uiState: StateFlow<GattMeshUiState> = _uiState.asStateFlow()

    private val _sendFailureEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val sendFailureEvents: SharedFlow<Int> = _sendFailureEvents

    private val readReceiptLock = Any()
    private val pendingReadReceiptIds = LinkedHashSet<String>()
    private val sentReadReceiptIds = LinkedHashSet<String>()
    private val pendingDispatchLock = Any()
    private val pendingDispatchIds = LinkedHashSet<String>()

    private var isScreenActive = false
    private var isMeshBoundForScreen = false
    private var restorePersistedMessagesJob: Job? = null
    private var hasRestoredPersistedMessages = false
    private var flushPendingDispatchJob: Job? = null
    private var retryPendingDispatchJob: Job? = null
    private var retryPendingDispatchAtMillis: Long? = null
    private var flushReadReceiptsJob: Job? = null

    private val localAgencyFlow = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch(exceptionHandler) {
            localAgencyFlow.value = runCatching {
                SecurityRepository(appContext).getUsableStoredCertificateAgency()
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        val publicMeshEnabledFlow = appContext.settingsDataStore.data
            .map { prefs -> prefs[PUBLIC_MESH_ENABLED] ?: false }
            .distinctUntilChanged()

        viewModelScope.launch(exceptionHandler) {
            combine(meshBinding.state, publicMeshEnabledFlow, localAgencyFlow) { serviceState, publicMeshEnabled, localAgency ->
                Triple(serviceState, publicMeshEnabled, localAgency)
            }.collect { (serviceState, publicMeshEnabled, localAgency) ->
                val canChat = publicMeshEnabled &&
                    serviceState.isEnabled &&
                    serviceState.sendReadyPeerCount > 0
                _uiState.value = GattMeshUiState(
                    publicMeshEnabled = publicMeshEnabled,
                    isServiceEnabled = serviceState.isEnabled,
                    isScanning = serviceState.isScanning,
                    connectedPeerCount = serviceState.connectedPeerCount,
                    discoveredPeerCount = serviceState.discoveredPeerCount,
                    sendReadyPeerCount = serviceState.sendReadyPeerCount,
                    connectedPeers = serviceState.connectedPeers,
                    errorMessage = serviceState.errorMessage,
                    canChat = canChat,
                    localAgency = localAgency,
                )
                syncMeshBindingForScreen(publicMeshEnabled)
                flushPendingDispatchIfPossible()
                flushReadReceiptsIfPossible()
            }
        }

        viewModelScope.launch(exceptionHandler) {
            messages.collect { chatMessages ->
                enqueueReadReceiptsFor(chatMessages)
                syncPersistedUnreadState(chatMessages)
                prunePendingDispatchIds(chatMessages)
                flushPendingDispatchIfPossible()
                flushReadReceiptsIfPossible()
            }
        }
    }

    fun onScreenStarted() {
        isScreenActive = true
        GattMeshChatStore.setChatOpen(true)
        viewModelScope.launch(exceptionHandler) {
            markAllRemoteMessagesRead(
                context = appContext,
                sessionCode = GattMeshChatStore.SESSION_CODE
            )
        }
        restorePersistedMessagesIfNeeded()
        syncMeshBindingForScreen(uiState.value.publicMeshEnabled)
        flushPendingDispatchIfPossible()
        flushReadReceiptsIfPossible()
    }

    fun onScreenStopped() {
        isScreenActive = false
        GattMeshChatStore.setChatOpen(false)
        if (isMeshBoundForScreen) {
            meshBinding.unbind()
            isMeshBoundForScreen = false
        }
    }

    fun updateDraft(text: String) {
        _messageDraft.value = text
    }

    fun enablePublicMeshMode() {
        if (uiState.value.publicMeshEnabled) {
            return
        }
        viewModelScope.launch(exceptionHandler) {
            appContext.settingsDataStore.edit { prefs ->
                prefs[PUBLIC_MESH_ENABLED] = true
            }
            meshBinding.setEnabled(true)
        }
    }

    fun sendMessage(replyTo: MeshChatMessage? = null) {
        val content = _messageDraft.value.trim()
        if (content.isBlank()) return

        if (!uiState.value.publicMeshEnabled) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_mode_disabled_notice)
            return
        }

        val formattedContent = buildReplyFormattedMessage(
            body = content,
            replyTo = replyTo
        )
        // Store-and-forward baseline:
        // 1) allocate stable packet ID in UI layer,
        // 2) show message immediately as QUEUED,
        // 3) let dispatcher/service lift status to SENDING/SENT/DELIVERED.
        val packetId = UUID.randomUUID().toString()
        GattMeshChatStore.appendLocalMessage(
            text = formattedContent,
            messageId = packetId,
            status = MeshMessageStatus.QUEUED
        )
        _messageDraft.value = ""
        enqueuePendingDispatch(packetId)
        Analytics.messageSent(kind = "text", transport = "ble_mesh", chat = "mesh")

        viewModelScope.launch(exceptionHandler) {
            persistLocalMessageState(
                messageId = packetId,
                text = formattedContent,
                status = MeshMessageStatus.QUEUED
            )
            flushPendingDispatchIfPossible()
        }
    }

    override fun onCleared() {
        super.onCleared()
        isScreenActive = false
        GattMeshChatStore.setChatOpen(false)
        if (isMeshBoundForScreen) {
            meshBinding.unbind()
            isMeshBoundForScreen = false
        }
        restorePersistedMessagesJob?.cancel()
        flushPendingDispatchJob?.cancel()
        retryPendingDispatchJob?.cancel()
        flushReadReceiptsJob?.cancel()
    }

    private fun restorePersistedMessagesIfNeeded() {
        if (hasRestoredPersistedMessages) {
            return
        }
        if (restorePersistedMessagesJob?.isActive == true) {
            return
        }
        restorePersistedMessagesJob = viewModelScope.launch(exceptionHandler) {
            ensureMeshGeneralContact()
            val restoreStartedAtMillis = System.currentTimeMillis()
            val hasPersistedPendingOutboundQueue = hasPersistedPendingOutboundQueueSnapshot()
            val persistedMessages = withContext(Dispatchers.IO) {
                loadRecentMessages(
                    context = appContext,
                    sessionCode = GattMeshChatStore.SESSION_CODE,
                    limit = RESTORE_MESSAGE_LIMIT
                )
            }.map { persisted ->
                val displayTimestampMillis =
                    resolveGattMeshDisplayTimestampMillis(
                        timestampMillis = persisted.timestampMillis,
                        originalTimestampMillis = persisted.originalTimestampMillis
                    )
                MeshChatMessage(
                    id = persisted.messageUuid,
                    text = persisted.text,
                    senderLabel = persisted.senderDisplayName
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: persisted.senderAddress
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                    sourceAddress = persisted.senderAddress
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                    originVerifiedRole = persisted.originVerifiedRole
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                    originVerifiedAtMillis = persisted.originVerifiedAtMillis
                        ?.takeIf { it > 0L },
                    isLocal = persisted.isLocal,
                    timestampMillis = displayTimestampMillis,
                    receivedTimestampMillis = resolveGattMeshReceivedTimestampMillis(
                        timestampMillis = persisted.timestampMillis,
                        originalTimestampMillis = persisted.originalTimestampMillis,
                        isLocal = persisted.isLocal
                    ),
                    status = mapPersistedStatus(
                        deliveryStatus = persisted.deliveryStatus,
                        isRead = persisted.isRead,
                        isLocal = persisted.isLocal
                    ),
                    sentTo = persisted.sentTo,
                    deliveredTo = persisted.deliveredTo,
                    readBy = persisted.readBy
                )
            }
            val stalePendingMessageIds = persistedMessages
                .asSequence()
                .filter { message ->
                    shouldFailPendingMessageOnRestore(
                        message = message,
                        restoreStartedAtMillis = restoreStartedAtMillis,
                        hasPersistedPendingOutboundQueue = hasPersistedPendingOutboundQueue
                    )
                }
                .map { message -> message.id }
                .toSet()
            val runtimeMessages = GattMeshChatStore.currentMessages()
            val persistedMessagesById = persistedMessages.associateBy(MeshChatMessage::id)
            val mergedById = LinkedHashMap<String, MeshChatMessage>(
                persistedMessages.size + runtimeMessages.size
            )
            persistedMessages.forEach { message ->
                mergedById[message.id] = message
            }
            runtimeMessages.forEach { runtimeMessage ->
                val persistedMessage = mergedById[runtimeMessage.id]
                mergedById[runtimeMessage.id] = if (persistedMessage == null) {
                    runtimeMessage
                } else {
                    mergeRestoredMessage(
                        persisted = persistedMessage,
                        runtime = runtimeMessage
                    )
                }
            }
            val mergedMessages = mergedById.values
                .asSequence()
                .map { message ->
                    if (
                        message.id in stalePendingMessageIds &&
                        message.isLocal &&
                        isPendingDispatchStatus(message.status)
                    ) {
                        message.copy(status = MeshMessageStatus.FAILED)
                    } else {
                        message
                    }
                }
                .sortedWith(compareBy<MeshChatMessage>({ it.timestampMillis }, { it.id }))
                .toList()
            GattMeshChatStore.replaceMessages(mergedMessages)
            backfillPersistedOriginVerificationSnapshots(
                mergedMessages = mergedMessages,
                persistedMessagesById = persistedMessagesById
            )
            val failedRestoredPendingMessages = mergedMessages.filter { message ->
                message.id in stalePendingMessageIds &&
                    message.isLocal &&
                    message.status == MeshMessageStatus.FAILED
            }
            failedRestoredPendingMessages.forEach { message ->
                persistExistingLocalMessageStatus(
                    messageId = message.id,
                    status = MeshMessageStatus.FAILED
                )
            }
            if (failedRestoredPendingMessages.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Marked stale restored gatt mesh messages as failed count=${failedRestoredPendingMessages.size}"
                )
            }
            hasRestoredPersistedMessages = true
            flushPendingDispatchIfPossible()
        }
    }

    private suspend fun persistLocalMessageState(
        messageId: String,
        text: String,
        status: MeshMessageStatus
    ) {
        ensureMeshGeneralContact()
        upsertLocalTextMessage(
            context = appContext,
            sessionCode = GattMeshChatStore.SESSION_CODE,
            uuid = messageId,
            text = text,
            deliveryStatus = mapDeliveryStatus(status)
        )
    }

    private suspend fun persistExistingLocalMessageStatus(
        messageId: String,
        status: MeshMessageStatus
    ) {
        updateLocalMessageDeliveryState(
            context = appContext,
            uuid = messageId,
            deliveryStatus = mapDeliveryStatus(status),
            retryCount = 0,
            nextRetryAtMillis = null,
            lastAttemptAtMillis = null,
            lastError = null,
            outboundRoute = null
        )
    }

    private fun backfillPersistedOriginVerificationSnapshots(
        mergedMessages: List<MeshChatMessage>,
        persistedMessagesById: Map<String, MeshChatMessage>
    ) {
        val messagesNeedingBackfill = mergedMessages.filter { message ->
            if (message.isLocal || message.originVerifiedAtMillis == null) {
                return@filter false
            }
            val persisted = persistedMessagesById[message.id]
            persisted?.originVerifiedAtMillis != message.originVerifiedAtMillis ||
                (
                    !message.originVerifiedRole.isNullOrBlank() &&
                        persisted?.originVerifiedRole != message.originVerifiedRole
                    )
        }
        if (messagesNeedingBackfill.isEmpty()) {
            return
        }
        viewModelScope.launch(exceptionHandler) {
            messagesNeedingBackfill.forEach { message ->
                runCatching {
                    updateRemoteMessageMetadata(
                        context = appContext,
                        uuid = message.id,
                        originVerifiedRole = message.originVerifiedRole,
                        originVerifiedAtMillis = message.originVerifiedAtMillis
                    )
                }.onFailure { throwable ->
                    Log.w(
                        TAG,
                        "Unable to backfill persisted gatt mesh origin verification id=${message.id}",
                        throwable
                    )
                }
            }
        }
    }

    private suspend fun ensureMeshGeneralContact() {
        withContext(Dispatchers.IO) {
            if (getContact(appContext, GattMeshChatStore.SESSION_CODE) != null) {
                return@withContext
            }
            saveContact(
                appContext,
                Contact(
                    name = appContext.getString(R.string.mesh_chat_general_title),
                    aesKey = "",
                    sessionCode = GattMeshChatStore.SESSION_CODE,
                    address = ""
                )
            )
        }
    }

    private fun mapPersistedStatus(
        deliveryStatus: MessageDeliveryStatus?,
        isRead: Boolean,
        isLocal: Boolean
    ): MeshMessageStatus {
        if (!isLocal) {
            return if (isRead) {
                MeshMessageStatus.READ
            } else {
                MeshMessageStatus.DELIVERED
            }
        }
        if (isRead || deliveryStatus == MessageDeliveryStatus.READ) {
            return MeshMessageStatus.READ
        }
        return when (deliveryStatus) {
            MessageDeliveryStatus.QUEUED -> MeshMessageStatus.QUEUED
            MessageDeliveryStatus.SENDING -> MeshMessageStatus.SENDING
            MessageDeliveryStatus.SENT -> MeshMessageStatus.SENT
            MessageDeliveryStatus.DELIVERED -> MeshMessageStatus.DELIVERED
            MessageDeliveryStatus.READ -> MeshMessageStatus.READ
            MessageDeliveryStatus.FAILED -> MeshMessageStatus.FAILED
            null -> MeshMessageStatus.SENT
        }
    }

    private fun mapDeliveryStatus(status: MeshMessageStatus): MessageDeliveryStatus {
        return when (status) {
            MeshMessageStatus.QUEUED -> MessageDeliveryStatus.QUEUED
            MeshMessageStatus.SENDING -> MessageDeliveryStatus.SENDING
            MeshMessageStatus.SENT -> MessageDeliveryStatus.SENT
            MeshMessageStatus.DELIVERED -> MessageDeliveryStatus.DELIVERED
            MeshMessageStatus.READ -> MessageDeliveryStatus.READ
            MeshMessageStatus.FAILED -> MessageDeliveryStatus.FAILED
        }
    }

    private fun mergeRestoredMessage(
        persisted: MeshChatMessage,
        runtime: MeshChatMessage
    ): MeshChatMessage {
        if (persisted.isLocal != runtime.isLocal) {
            return runtime
        }
        val senderLabel = runtime.senderLabel
            ?.takeIf { it.isNotBlank() }
            ?: persisted.senderLabel
        val sourceAddress = runtime.sourceAddress
            ?.takeIf { it.isNotBlank() }
            ?: persisted.sourceAddress
        val originVerifiedRole = runtime.originVerifiedRole
            ?.takeIf { it.isNotBlank() }
            ?: persisted.originVerifiedRole
        val originVerifiedAtMillis = runtime.originVerifiedAtMillis
            ?.takeIf { it > 0L }
            ?: persisted.originVerifiedAtMillis
        return runtime.copy(
            text = runtime.text.ifBlank { persisted.text },
            senderLabel = senderLabel,
            sourceAddress = sourceAddress,
            originVerifiedRole = originVerifiedRole,
            originVerifiedAtMillis = originVerifiedAtMillis,
            timestampMillis = runtime.timestampMillis.takeIf { it > 0L } ?: persisted.timestampMillis,
            receivedTimestampMillis = runtime.receivedTimestampMillis
                ?: persisted.receivedTimestampMillis,
            status = mergePreferredStatus(
                persisted.status,
                runtime.status
            ),
            sentTo = (persisted.sentTo + runtime.sentTo).distinct(),
            deliveredTo = (persisted.deliveredTo + runtime.deliveredTo).distinct(),
            readBy = (persisted.readBy + runtime.readBy).distinct()
        )
    }

    private fun mergePreferredStatus(
        persistedStatus: MeshMessageStatus,
        runtimeStatus: MeshMessageStatus
    ): MeshMessageStatus {
        return if (statusRank(runtimeStatus) >= statusRank(persistedStatus)) {
            runtimeStatus
        } else {
            persistedStatus
        }
    }

    private fun statusRank(status: MeshMessageStatus): Int {
        return when (status) {
            MeshMessageStatus.QUEUED -> 1
            MeshMessageStatus.SENDING -> 2
            MeshMessageStatus.SENT -> 3
            MeshMessageStatus.DELIVERED -> 4
            MeshMessageStatus.READ -> 5
            MeshMessageStatus.FAILED -> 0
        }
    }

    private fun isPendingDispatchStatus(status: MeshMessageStatus): Boolean {
        return status == MeshMessageStatus.QUEUED || status == MeshMessageStatus.SENDING
    }

    private fun resolveGattMeshDisplayTimestampMillis(
        timestampMillis: Long,
        originalTimestampMillis: Long?
    ): Long {
        val original = originalTimestampMillis?.takeIf { it > 0L && it < timestampMillis }
        return original ?: timestampMillis
    }

    private fun resolveGattMeshReceivedTimestampMillis(
        timestampMillis: Long,
        originalTimestampMillis: Long?,
        isLocal: Boolean
    ): Long? {
        if (isLocal) {
            return null
        }
        val original = originalTimestampMillis?.takeIf { it > 0L && it < timestampMillis }
        return if (original != null) timestampMillis else null
    }

    private fun hasPersistedPendingOutboundQueueSnapshot(): Boolean {
        val queueFile = File(appContext.filesDir, PERSISTED_PENDING_OUTBOUND_QUEUE_FILE_NAME)
        return queueFile.exists() && queueFile.length() > 0L
    }

    private fun shouldFailPendingMessageOnRestore(
        message: MeshChatMessage,
        restoreStartedAtMillis: Long,
        hasPersistedPendingOutboundQueue: Boolean
    ): Boolean {
        if (!message.isLocal || !isPendingDispatchStatus(message.status)) {
            return false
        }
        if (!hasPersistedPendingOutboundQueue) {
            return true
        }
        val ageMillis = restoreStartedAtMillis - message.timestampMillis
        return ageMillis > RESTORE_PENDING_MESSAGE_MAX_AGE_MS
    }

    private fun syncMeshBindingForScreen(publicMeshEnabled: Boolean) {
        if (!isScreenActive) {
            return
        }
        if (publicMeshEnabled) {
            if (!isMeshBoundForScreen || !uiState.value.isServiceEnabled) {
                meshBinding.setEnabled(true)
                isMeshBoundForScreen = true
            }
        } else if (isMeshBoundForScreen) {
            meshBinding.unbind()
            isMeshBoundForScreen = false
        }
    }

    private fun enqueuePendingDispatch(packetId: String) {
        val normalizedId = packetId.trim()
        if (!normalizedId.matches(MESSAGE_ID_REGEX)) {
            return
        }
        synchronized(pendingDispatchLock) {
            pendingDispatchIds += normalizedId
            trimTrackingSet(pendingDispatchIds, MAX_TRACKED_PENDING_DISPATCH_IDS)
        }
    }

    private fun prunePendingDispatchIds(chatMessages: List<MeshChatMessage>) {
        val existingIds = chatMessages
            .asSequence()
            .map { it.id.trim() }
            .filter { it.matches(MESSAGE_ID_REGEX) }
            .toSet()
        synchronized(pendingDispatchLock) {
            pendingDispatchIds.removeAll { messageId -> messageId !in existingIds }
        }
    }

    private fun flushPendingDispatchIfPossible() {
        if (!isScreenActive || !uiState.value.publicMeshEnabled) {
            return
        }
        if (flushPendingDispatchJob?.isActive == true) {
            return
        }
        flushPendingDispatchJob = viewModelScope.launch(exceptionHandler) {
            val pendingIds = synchronized(pendingDispatchLock) {
                pendingDispatchIds.toList()
            }
            if (pendingIds.isEmpty()) {
                return@launch
            }
            val messageById = GattMeshChatStore.currentMessages()
                .associateBy { message -> message.id }
            var shouldRetry = false
            pendingIds.forEach { messageId ->
                val message = messageById[messageId]
                if (message == null || !message.isLocal) {
                    synchronized(pendingDispatchLock) {
                        pendingDispatchIds.remove(messageId)
                    }
                    return@forEach
                }
                GattMeshChatStore.updateLocalMessageStatus(messageId, MeshMessageStatus.SENDING)
                val result = withContext(Dispatchers.IO) {
                    meshBinding.sendGroupMessage(
                        message = message.text,
                        messageId = messageId
                    )
                }
                // `null` means dispatch path is unavailable for now (binding/service race),
                // so keep message queued and retry. Non-null result is authoritative.
                if (result == null) {
                    shouldRetry = true
                    GattMeshChatStore.updateLocalMessageStatus(messageId, MeshMessageStatus.QUEUED)
                    return@forEach
                }
                synchronized(pendingDispatchLock) {
                    pendingDispatchIds.remove(messageId)
                }
                when (result.disposition) {
                    GattMeshSendDisposition.SENT -> {
                        GattMeshChatStore.updateLocalMessageStatus(messageId, MeshMessageStatus.SENT)
                    }

                    GattMeshSendDisposition.QUEUED -> {
                        GattMeshChatStore.updateLocalMessageStatus(messageId, MeshMessageStatus.QUEUED)
                    }
                }
            }
            schedulePendingDispatchRetry(shouldRetry)
        }
    }

    private fun schedulePendingDispatchRetry(forceRetry: Boolean) {
        val hasPending = synchronized(pendingDispatchLock) {
            pendingDispatchIds.isNotEmpty()
        }
        if (!forceRetry && !hasPending) {
            retryPendingDispatchJob?.cancel()
            retryPendingDispatchJob = null
            retryPendingDispatchAtMillis = null
            return
        }
        val now = System.currentTimeMillis()
        val scheduledAt = now + PENDING_DISPATCH_RETRY_DELAY_MS
        val existingAt = retryPendingDispatchAtMillis
        if (
            retryPendingDispatchJob?.isActive == true &&
            existingAt != null &&
            kotlin.math.abs(existingAt - scheduledAt) <= 250L
        ) {
            return
        }
        retryPendingDispatchJob?.cancel()
        retryPendingDispatchAtMillis = scheduledAt
        retryPendingDispatchJob = viewModelScope.launch(exceptionHandler) {
            // Single timer gate: prevents multiple concurrent retry loops for the same queue.
            delay(PENDING_DISPATCH_RETRY_DELAY_MS)
            retryPendingDispatchAtMillis = null
            flushPendingDispatchIfPossible()
        }
    }

    private suspend fun syncPersistedUnreadState(chatMessages: List<MeshChatMessage>) {
        if (!isScreenActive) {
            return
        }
        if (chatMessages.none { message -> !message.isLocal }) {
            return
        }
        markAllRemoteMessagesRead(
            context = appContext,
            sessionCode = GattMeshChatStore.SESSION_CODE
        )
    }

    private fun enqueueReadReceiptsFor(chatMessages: List<MeshChatMessage>) {
        if (!isScreenActive) {
            return
        }
        synchronized(readReceiptLock) {
            chatMessages
                .asSequence()
                .filter { !it.isLocal }
                .map { it.id.trim() }
                .filter { it.matches(MESSAGE_ID_REGEX) }
                .forEach { messageId ->
                    if (messageId !in sentReadReceiptIds) {
                        pendingReadReceiptIds += messageId
                    }
                }
            trimTrackingSet(pendingReadReceiptIds, MAX_TRACKED_RECEIPT_IDS)
            trimTrackingSet(sentReadReceiptIds, MAX_TRACKED_RECEIPT_IDS)
        }
    }

    private fun flushReadReceiptsIfPossible() {
        if (!isScreenActive || !uiState.value.canChat) {
            return
        }
        val hasPendingDispatch = synchronized(pendingDispatchLock) {
            pendingDispatchIds.isNotEmpty()
        }
        if (hasPendingDispatch) {
            return
        }
        if (flushReadReceiptsJob?.isActive == true) {
            return
        }
        val batch = synchronized(readReceiptLock) {
            // Prioritize the most recently observed remote messages so fresh "seen" updates are
            // not starved behind historical backlog after process restarts or long chat history.
            pendingReadReceiptIds.toList().takeLast(MAX_READ_RECEIPT_BATCH)
        }
        if (batch.isEmpty()) {
            return
        }
        flushReadReceiptsJob = viewModelScope.launch(exceptionHandler) {
            val sent = withContext(Dispatchers.IO) {
                meshBinding.sendReadReceipt(batch)
            }
            if (!sent) {
                return@launch
            }
            synchronized(readReceiptLock) {
                batch.forEach { messageId ->
                    pendingReadReceiptIds.remove(messageId)
                    sentReadReceiptIds += messageId
                }
                trimTrackingSet(sentReadReceiptIds, MAX_TRACKED_RECEIPT_IDS)
            }
        }
    }

    private fun trimTrackingSet(set: LinkedHashSet<String>, maxSize: Int) {
        while (set.size > maxSize) {
            val oldest = set.firstOrNull() ?: return
            set.remove(oldest)
        }
    }

    private fun buildReplyFormattedMessage(
        body: String,
        replyTo: MeshChatMessage?
    ): String {
        val trimmedBody = body.trim()
        val target = replyTo ?: return trimmedBody
        val targetId = target.id.trim().takeIf { it.matches(MESSAGE_ID_REGEX) } ?: return trimmedBody

        val sourcePreview = previewTextForReplyTarget(target.text)
            ?: target.text.trim()
        val preview = sourcePreview
            .replace(MULTI_WHITESPACE_REGEX, " ")
            .trim()
            .take(REPLY_PREVIEW_MAX_LENGTH)
            .ifBlank { appContext.getString(R.string.chat_reply_unknown_placeholder) }

        val author = if (target.isLocal) {
            appContext.getString(R.string.chat_reply_sender_you)
        } else {
            target.senderLabel?.trim().orEmpty()
        }
        val heading = if (author.isBlank()) preview else "${author}|$preview"
        return "↪[$targetId] $heading\n$trimmedBody"
    }

    companion object {
        private const val TAG = "GattMeshVM"
        val PUBLIC_MESH_ENABLED = booleanPreferencesKey("advanced_public_mesh_enabled")
        private const val PENDING_DISPATCH_RETRY_DELAY_MS = 1_500L
        private const val MAX_TRACKED_PENDING_DISPATCH_IDS = 4096
        private const val MAX_TRACKED_RECEIPT_IDS = 2048
        private const val MAX_READ_RECEIPT_BATCH = 2
        private const val RESTORE_MESSAGE_LIMIT = 500
        private const val PERSISTED_PENDING_OUTBOUND_QUEUE_FILE_NAME =
            "gatt_mesh_pending_outbound_queue.json"
        private const val RESTORE_PENDING_MESSAGE_MAX_AGE_MS = 2 * 60 * 1000L
        private const val REPLY_PREVIEW_MAX_LENGTH = 72
        private val MESSAGE_ID_REGEX = Regex("^[a-zA-Z0-9-]{8,128}$")
        private val MULTI_WHITESPACE_REGEX = Regex("\\s+")
    }
}
