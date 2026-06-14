package com.auralis.crisisconnect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.os.BatteryManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.core.media.ImageFileUtils
import com.auralis.crisisconnect.core.media.generateImageThumbnail
import com.auralis.crisisconnect.data.BleChatStore
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.saveRemoteAudioMessage
import com.auralis.crisisconnect.data.saveRemoteImageMessage
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.imageThumbnailFile
import com.auralis.crisisconnect.data.markAllLocalMessagesRead
import com.auralis.crisisconnect.data.markAllSentMessagesDelivered
import com.auralis.crisisconnect.data.markLocalMessagesDelivered
import com.auralis.crisisconnect.data.markLocalMessagesRead
import com.auralis.crisisconnect.data.normalizeMacAddress
import com.auralis.crisisconnect.data.persistSharedDocumentLocalCopy
import com.auralis.crisisconnect.data.updateContactAddress
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.service.BleMessageNotifier
import com.auralis.crisisconnect.service.client.BleClientManager
import com.auralis.crisisconnect.service.client.RescueClientManagerProvider
import com.auralis.crisisconnect.service.media.ImageTransferDirection
import com.auralis.crisisconnect.service.media.ImageTransferState
import com.auralis.crisisconnect.service.p2p.P2pBleProtocol
import com.auralis.crisisconnect.service.voice.VoiceTransferDirection
import com.auralis.crisisconnect.service.voice.VoiceTransferState
import com.auralis.crisisconnect.security.SecurityRepository
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the BLE client stack. The service keeps GATT connections alive even
 * when the UI process is backgrounded and exposes the [BleClientManager] via a binder for
 * activities/fragments to observe connection state and send chat messages.
 */
class GattRescueClientService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: BleClientManager
    private var messageCollectorJob: Job? = null
    private var connectionCollectorJob: Job? = null
    private var idleDisconnectJob: Job? = null
    private var isClientEnabled = false
    @Volatile
    private var hasActiveBinding = false
    @Volatile
    private var lastTrafficTimestampMillis: Long = System.currentTimeMillis()
    private val connectionStateByAddress = mutableMapOf<String, BleClientManager.ConnectionStatus>()
    private val incomingVoiceTransfers =
        Collections.synchronizedMap(mutableMapOf<String, BleVoicePayload.IncomingTransfer>())
    private val incomingImageTransfers =
        Collections.synchronizedMap(mutableMapOf<String, BleImagePayload.IncomingTransfer>())
    private val incomingFileTransfers =
        Collections.synchronizedMap(mutableMapOf<String, BleFilePayload.IncomingTransfer>())

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!hasRescueAccess()) {
            android.util.Log.w(TAG, "Missing rescue access; stopping rescue client foreground service")
            stopSelf()
            return
        }
        isClientEnabled = true
        manager = BleClientManager(applicationContext, serviceScope)
        connectionCollectorJob = serviceScope.launch {
            manager.connectionStates.collect { state ->
                val normalizedAddress = state.address.uppercase(Locale.US)
                synchronized(connectionStateByAddress) {
                    connectionStateByAddress[normalizedAddress] = state.status
                }
                if (state.status == BleClientManager.ConnectionStatus.Disconnected ||
                    state.status == BleClientManager.ConnectionStatus.Failed
                ) {
                    clearVoiceTransfersForAddress(normalizedAddress)
                    clearImageTransfersForAddress(normalizedAddress)
                    clearFileTransfersForAddress(normalizedAddress)
                    BleVoiceTransferProgressStore.clearSession(
                        BleSessionResolver.sessionCodeForAddress(normalizedAddress)
                    )
                    BleImageTransferProgressStore.clearSession(
                        BleSessionResolver.sessionCodeForAddress(normalizedAddress)
                    )
                    synchronized(connectionStateByAddress) {
                        connectionStateByAddress.remove(normalizedAddress)
                    }
                }
                if (!hasActiveBinding) {
                    scheduleIdleDisconnect()
                }
            }
        }
        messageCollectorJob = serviceScope.launch {
            manager.incomingMessages.collect { message ->
                val normalizedAddress = message.address.uppercase(Locale.US)
                val sessionCode = withContext(Dispatchers.IO) {
                    resolveIncomingSessionCode(
                        normalizedAddress = normalizedAddress,
                        route = message.route
                    )
                }
                markTraffic()
                BleChatStore.ensureSession(sessionCode)
                val text = message.message.dropLastWhile { it.code == 0 }
                if (text.isEmpty()) {
                    return@collect
                }
                BleDirectChatCompat.parsePayload(text)?.let { payload ->
                    if (
                        handleCompatChatPayload(
                            normalizedAddress = normalizedAddress,
                            sessionCode = sessionCode,
                            payload = payload,
                            timestampMs = message.timestampMs,
                            createdAtMillis = message.createdAtMillis
                        )
                    ) {
                        return@collect
                    }
                }
                BleFilePayload.parsePacket(text)?.let { packet ->
                    when (packet) {
                        is BleFilePayload.Packet.Done -> {
                            BleFileTransferReceiptStore.publishSuccess(packet.transferId)
                        }

                        is BleFilePayload.Packet.Abort -> {
                            BleFileTransferReceiptStore.publishAbort(packet.transferId, packet.reason)
                        }

                        is BleFilePayload.Packet.Init,
                        is BleFilePayload.Packet.Chunk -> {
                            handleIncomingFilePacket(
                                normalizedAddress = normalizedAddress,
                                sessionCode = sessionCode,
                                packet = packet
                            )
                        }
                    }
                    return@collect
                }
                BleImagePayload.parsePacket(text)?.let { packet ->
                    when (packet) {
                        is BleImagePayload.Packet.Done -> {
                            BleImageTransferReceiptStore.publishSuccess(packet.transferId)
                        }

                        is BleImagePayload.Packet.Abort -> {
                            BleImageTransferReceiptStore.publishAbort(packet.transferId, packet.reason)
                        }

                        is BleImagePayload.Packet.Init,
                        is BleImagePayload.Packet.Chunk -> {
                            handleIncomingImagePacket(
                                normalizedAddress = normalizedAddress,
                                sessionCode = sessionCode,
                                packet = packet,
                                timestampMs = message.timestampMs
                            )
                        }
                    }
                    return@collect
                }
                BleVoicePayload.parsePacket(text)?.let { packet ->
                    when (packet) {
                        is BleVoicePayload.Packet.Done -> {
                            BleVoiceTransferReceiptStore.publishSuccess(packet.transferId)
                        }

                        is BleVoicePayload.Packet.Abort -> {
                            BleVoiceTransferReceiptStore.publishAbort(packet.transferId, packet.reason)
                        }

                        is BleVoicePayload.Packet.Init,
                        is BleVoicePayload.Packet.Chunk -> {
                            handleIncomingVoicePacket(
                                normalizedAddress = normalizedAddress,
                                sessionCode = sessionCode,
                                packet = packet,
                                timestampMs = message.timestampMs
                            )
                        }
                    }
                    return@collect
                }
                val messageId = message.messageId ?: UUID.randomUUID().toString()
                BleChatStore.appendRemoteMessage(
                    sessionCode = sessionCode,
                    text = text,
                    messageId = messageId,
                    originalTimestampMillis = message.createdAtMillis,
                    receivedAtMillis = message.timestampMs
                )
                serviceScope.launch(Dispatchers.IO) {
                    val contact = upsertContactForNotification(sessionCode, normalizedAddress)
                    val inserted = runCatching {
                        saveRemoteMessage(
                            context = applicationContext,
                            sessionCode = sessionCode,
                            uuid = messageId,
                            text = text,
                            createdAtMillis = message.createdAtMillis,
                            receivedAtMillis = message.timestampMs
                        )
                    }.getOrElse { throwable ->
                        android.util.Log.w(
                            TAG,
                            "[$normalizedAddress] Failed to persist remote text message",
                            throwable
                        )
                        false
                    }
                    if (inserted) {
                        BleMessageNotifier.notifyIncoming(
                            context = applicationContext,
                            sessionCode = sessionCode,
                            contactName = contact.name,
                            body = text,
                            timestamp = message.timestampMs
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isClientEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasActiveBinding) {
            scheduleIdleDisconnect()
        }
        // Ensure the service stays alive even if the system kills the hosting activity.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (!isClientEnabled) {
            return null
        }
        hasActiveBinding = true
        markTraffic()
        cancelIdleDisconnect()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        hasActiveBinding = true
        markTraffic()
        cancelIdleDisconnect()
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hasActiveBinding = false
        scheduleIdleDisconnect()
        return true
    }

    override fun onDestroy() {
        messageCollectorJob?.cancel()
        connectionCollectorJob?.cancel()
        idleDisconnectJob?.cancel()
        val sessionsToClear = synchronized(incomingVoiceTransfers) {
            incomingVoiceTransfers.keys
                .mapNotNull { key ->
                    val address = key.substringBefore('|', missingDelimiterValue = "").takeIf { it.isNotBlank() }
                    address?.let { resolveSessionCodeForAddress(it) }
                }
                .toSet()
        }
        sessionsToClear.forEach { session ->
            BleVoiceTransferProgressStore.clearSession(session)
            BleImageTransferProgressStore.clearSession(session)
        }
        incomingVoiceTransfers.clear()
        incomingImageTransfers.clear()
        incomingFileTransfers.clear()
        if (::manager.isInitialized) {
            manager.shutdown()
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun getClientManager(): BleClientManager = manager

    private fun buildNotification(): Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        ensureNotificationChannel(channelId)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.rescue_client_notification_title))
            .setContentText(getString(R.string.rescue_client_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            channelId,
            getString(R.string.rescue_client_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.rescue_client_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    inner class LocalBinder : Binder(), RescueClientManagerProvider {
        override fun getManager(): BleClientManager = manager
    }

    private fun scheduleIdleDisconnect() {
        if (!isClientEnabled || !::manager.isInitialized) {
            return
        }
        val delayMs = computeIdleDisconnectDelayMs()
        idleDisconnectJob?.cancel()
        idleDisconnectJob = serviceScope.launch {
            delay(delayMs)
            if (hasActiveBinding) {
                return@launch
            }
            runCatching { manager.shutdown() }
            stopSelf()
        }
    }

    private fun cancelIdleDisconnect() {
        idleDisconnectJob?.cancel()
        idleDisconnectJob = null
    }

    private fun markTraffic() {
        lastTrafficTimestampMillis = System.currentTimeMillis()
    }

    private fun computeIdleDisconnectDelayMs(): Long {
        val now = System.currentTimeMillis()
        val sinceTraffic = (now - lastTrafficTimestampMillis).coerceAtLeast(0L)
        val hasLiveConnection = synchronized(connectionStateByAddress) {
            connectionStateByAddress.values.any { status ->
                status == BleClientManager.ConnectionStatus.Ready ||
                    status == BleClientManager.ConnectionStatus.Connected ||
                    status == BleClientManager.ConnectionStatus.Connecting ||
                    status == BleClientManager.ConnectionStatus.Authenticating ||
                    status == BleClientManager.ConnectionStatus.Discovering ||
                    status == BleClientManager.ConnectionStatus.Reconnecting
            }
        }
        var delayMs = when {
            sinceTraffic <= TRAFFIC_HOT_WINDOW_MS -> IDLE_DELAY_ACTIVE_TRAFFIC_MS
            sinceTraffic <= TRAFFIC_WARM_WINDOW_MS -> IDLE_DELAY_WARM_MS
            else -> IDLE_DELAY_COLD_MS
        }
        if (!hasLiveConnection) {
            delayMs = IDLE_DELAY_NO_LINK_MS
        }
        if (isDeviceCharging()) {
            delayMs += CHARGING_BONUS_MS
        }
        return delayMs.coerceIn(MIN_IDLE_DISCONNECT_MS, MAX_IDLE_DISCONNECT_MS)
    }

    private fun isDeviceCharging(): Boolean {
        val sticky = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun handleIncomingVoicePacket(
        normalizedAddress: String,
        sessionCode: String,
        packet: BleVoicePayload.Packet,
        timestampMs: Long
    ) {
        cleanupStaleIncomingVoiceTransfers()
        when (packet) {
            is BleVoicePayload.Packet.Init -> {
                val key = voiceTransferKey(normalizedAddress, packet.transferId)
                incomingVoiceTransfers[key] = BleVoicePayload.IncomingTransfer(
                    transferId = packet.transferId,
                    mimeType = packet.mimeType,
                    durationMillis = packet.durationMillis,
                    totalChunks = packet.totalChunks
                )
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = packet.transferId,
                    totalChunks = packet.totalChunks,
                    confirmedChunks = 0,
                    state = VoiceTransferState.Initializing
                )
            }

            is BleVoicePayload.Packet.Chunk -> {
                val key = voiceTransferKey(normalizedAddress, packet.transferId)
                val transfer = incomingVoiceTransfers[key] ?: return
                val added = transfer.addChunk(packet.chunkIndex, packet.bytes)
                if (!added) {
                    publishIncomingVoiceProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.chunks.size,
                        state = VoiceTransferState.Failed
                    )
                    clearIncomingVoiceProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "invalid_chunk")
                    incomingVoiceTransfers.remove(key)
                    return
                }
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.chunks.size,
                    state = VoiceTransferState.Transferring
                )
                if (!transfer.isComplete()) {
                    return
                }
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = VoiceTransferState.Verifying
                )
                val bytes = transfer.composeBytes()
                incomingVoiceTransfers.remove(key)
                if (bytes == null) {
                    publishIncomingVoiceProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.chunks.size,
                        state = VoiceTransferState.Failed
                    )
                    clearIncomingVoiceProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "compose_failed")
                    return
                }
                if (bytes.isEmpty()) {
                    publishIncomingVoiceProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.totalChunks,
                        state = VoiceTransferState.Failed
                    )
                    clearIncomingVoiceProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "empty_payload")
                    return
                }
                if (bytes.size > BleVoicePayload.MAX_OUTGOING_TOTAL_BYTES) {
                    publishIncomingVoiceProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.totalChunks,
                        state = VoiceTransferState.Failed
                    )
                    clearIncomingVoiceProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "payload_too_large")
                    return
                }
                persistIncomingVoiceMessage(
                    normalizedAddress = normalizedAddress,
                    sessionCode = sessionCode,
                    transfer = transfer,
                    bytes = bytes,
                    timestampMs = timestampMs
                )
            }

            is BleVoicePayload.Packet.Done,
            is BleVoicePayload.Packet.Abort -> Unit
        }
    }

    private fun handleIncomingFilePacket(
        normalizedAddress: String,
        sessionCode: String,
        packet: BleFilePayload.Packet
    ) {
        cleanupStaleIncomingFileTransfers()
        when (packet) {
            is BleFilePayload.Packet.Init -> {
                val key = fileTransferKey(normalizedAddress, packet.transferId)
                incomingFileTransfers[key] = BleFilePayload.IncomingTransfer(
                    transferId = packet.transferId,
                    messageId = packet.messageId,
                    displayName = packet.displayName,
                    mimeType = packet.mimeType,
                    originalSizeBytes = packet.originalSizeBytes,
                    totalBytes = packet.totalBytes,
                    totalChunks = packet.totalChunks,
                    sha256 = packet.sha256
                )
            }

            is BleFilePayload.Packet.Chunk -> {
                val key = fileTransferKey(normalizedAddress, packet.transferId)
                val transfer = incomingFileTransfers[key] ?: return
                val added = transfer.addChunk(packet.chunkIndex, packet.bytes)
                if (!added) {
                    incomingFileTransfers.remove(key)
                    sendFileAbortPacket(normalizedAddress, transfer.transferId, "invalid_chunk")
                    return
                }
                if (!transfer.isComplete()) {
                    return
                }
                val bytes = transfer.composeBytes()
                incomingFileTransfers.remove(key)
                if (bytes == null || bytes.isEmpty()) {
                    sendFileAbortPacket(normalizedAddress, transfer.transferId, "compose_failed")
                    return
                }
                if (bytes.size > BleFilePayload.MAX_OUTGOING_TOTAL_BYTES) {
                    sendFileAbortPacket(normalizedAddress, transfer.transferId, "payload_too_large")
                    return
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                if (!digest.contentEquals(transfer.sha256)) {
                    sendFileAbortPacket(normalizedAddress, transfer.transferId, "sha_mismatch")
                    return
                }
                persistIncomingFileTransfer(
                    normalizedAddress = normalizedAddress,
                    sessionCode = sessionCode,
                    transfer = transfer,
                    bytes = bytes
                )
            }

            is BleFilePayload.Packet.Done,
            is BleFilePayload.Packet.Abort -> Unit
        }
    }

    private fun persistIncomingFileTransfer(
        normalizedAddress: String,
        sessionCode: String,
        transfer: BleFilePayload.IncomingTransfer,
        bytes: ByteArray
    ) {
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                upsertContactForNotification(sessionCode, normalizedAddress)
                val persisted = persistSharedDocumentLocalCopy(
                    context = applicationContext,
                    uuid = transfer.messageId,
                    displayName = transfer.displayName,
                    bytes = bytes
                )
                check(!persisted.isNullOrBlank()) { "persist_failed" }
                sendFileDonePacket(normalizedAddress, transfer.transferId)
                markTraffic()
            }.onFailure {
                sendFileAbortPacket(normalizedAddress, transfer.transferId, "persist_failed")
            }
        }
    }

    private fun persistIncomingVoiceMessage(
        normalizedAddress: String,
        sessionCode: String,
        transfer: BleVoicePayload.IncomingTransfer,
        bytes: ByteArray,
        timestampMs: Long
    ) {
        val messageId = UUID.randomUUID().toString()
        val duration = transfer.durationMillis.takeIf { it > 0L }
        val fileName = voiceMessageFileName(messageId, transfer.mimeType)
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val contact = upsertContactForNotification(sessionCode, normalizedAddress)
                val destination = voiceMessageFile(applicationContext, fileName)
                destination.parentFile?.mkdirs()
                destination.writeBytes(bytes)
                saveRemoteAudioMessage(
                    context = applicationContext,
                    sessionCode = sessionCode,
                    uuid = messageId,
                    fileName = destination.name,
                    audioDurationMillis = duration
                )
                BleChatStore.appendRemoteAudioMessage(
                    sessionCode = sessionCode,
                    audioFilePath = destination.absolutePath,
                    audioDurationMillis = duration,
                    messageId = messageId
                )
                BleMessageNotifier.notifyIncoming(
                    context = applicationContext,
                    sessionCode = sessionCode,
                    contactName = contact.name,
                    body = getString(R.string.notification_voice_message_body),
                    timestamp = timestampMs
                )
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = VoiceTransferState.Completed
                )
                clearIncomingVoiceProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    delayMs = VOICE_COMPLETED_BADGE_MS
                )
                sendVoiceDonePacket(normalizedAddress, transfer.transferId)
                markTraffic()
            }
            .onFailure {
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = VoiceTransferState.Failed
                )
                clearIncomingVoiceProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId
                )
                sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "persist_failed")
            }
        }
    }

    private fun sendVoiceDonePacket(address: String, transferId: String) {
        val packet = BleVoicePayload.buildDonePacket(transferId)
        if (packet.isBlank()) {
            return
        }
        serviceScope.launch {
            val sent = runCatching { manager.sendMessageAwait(address, packet) }.getOrElse { false }
            if (!sent) {
                android.util.Log.w(TAG, "[$address] Failed to send voice DONE ack for transfer=$transferId")
            }
        }
    }

    private fun sendVoiceAbortPacket(address: String, transferId: String, reason: String) {
        val packet = BleVoicePayload.buildAbortPacket(transferId, reason)
        if (packet.isBlank()) {
            return
        }
        serviceScope.launch {
            val sent = runCatching { manager.sendMessageAwait(address, packet) }.getOrElse { false }
            if (!sent) {
                android.util.Log.w(TAG, "[$address] Failed to send voice ABORT ack for transfer=$transferId")
            }
        }
    }

    private fun sendFileDonePacket(address: String, transferId: String) {
        val packet = BleFilePayload.buildDonePacket(transferId)
        if (packet.isBlank()) {
            return
        }
        serviceScope.launch {
            val sent = runCatching { manager.sendMessageAwait(address, packet) }.getOrElse { false }
            if (!sent) {
                android.util.Log.w(TAG, "[$address] Failed to send file DONE ack for transfer=$transferId")
            }
        }
    }

    private fun sendFileAbortPacket(address: String, transferId: String, reason: String) {
        val packet = BleFilePayload.buildAbortPacket(transferId, reason)
        if (packet.isBlank()) {
            return
        }
        serviceScope.launch {
            val sent = runCatching { manager.sendMessageAwait(address, packet) }.getOrElse { false }
            if (!sent) {
                android.util.Log.w(TAG, "[$address] Failed to send file ABORT ack for transfer=$transferId")
            }
        }
    }

    private suspend fun upsertContactForNotification(
        sessionCode: String,
        normalizedAddress: String,
        preferredName: String? = null
    ): Contact {
        val existing = getContact(applicationContext, sessionCode)
        val resolvedName = resolveContactNameForNotification(existing?.name, sessionCode, preferredName)
        val contact = existing?.copy(
            name = resolvedName,
            address = normalizedAddress
        ) ?: Contact(
            name = resolvedName,
            aesKey = "",
            sessionCode = sessionCode,
            address = normalizedAddress
        )
        if (existing == null || existing != contact) {
            saveContact(applicationContext, contact)
        }
        return contact
    }

    private fun handleCompatChatPayload(
        normalizedAddress: String,
        sessionCode: String,
        payload: BleDirectChatCompat.Payload,
        timestampMs: Long,
        createdAtMillis: Long?
    ): Boolean {
        when (payload.kind) {
            P2pBleProtocol.CHAT_KIND_DELIVERED -> {
                val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() }
                if (messageId == null) {
                    BleChatStore.markDeliveredForAllSent(sessionCode)
                    serviceScope.launch(Dispatchers.IO) {
                        markAllSentMessagesDelivered(applicationContext, sessionCode)
                    }
                } else {
                    BleChatStore.markDelivered(sessionCode, listOf(messageId))
                    serviceScope.launch(Dispatchers.IO) {
                        markLocalMessagesDelivered(applicationContext, sessionCode, listOf(messageId))
                    }
                }
                return true
            }

            P2pBleProtocol.CHAT_KIND_READ -> {
                val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() }
                if (messageId == null) {
                    BleChatStore.markReadAllLocal(sessionCode)
                    serviceScope.launch(Dispatchers.IO) {
                        markAllLocalMessagesRead(applicationContext, sessionCode)
                    }
                } else {
                    BleChatStore.markRead(sessionCode, listOf(messageId))
                    serviceScope.launch(Dispatchers.IO) {
                        markLocalMessagesRead(applicationContext, sessionCode, listOf(messageId))
                    }
                }
                return true
            }

            P2pBleProtocol.CHAT_KIND_TEXT -> {
                val body = payload.text?.trim().takeIf { !it.isNullOrBlank() } ?: return true
                val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() }
                    ?: UUID.randomUUID().toString()
                BleChatStore.appendRemoteMessage(
                    sessionCode = sessionCode,
                    text = body,
                    messageId = messageId,
                    originalTimestampMillis = createdAtMillis,
                    receivedAtMillis = timestampMs
                )
                serviceScope.launch(Dispatchers.IO) {
                    val contact = upsertContactForNotification(
                        sessionCode = sessionCode,
                        normalizedAddress = normalizedAddress,
                        preferredName = payload.senderName
                    )
                    val inserted = runCatching {
                        saveRemoteMessage(
                            context = applicationContext,
                            sessionCode = sessionCode,
                            uuid = messageId,
                            text = body,
                            createdAtMillis = createdAtMillis,
                            receivedAtMillis = timestampMs,
                            senderDisplayName = payload.senderName,
                            senderAddress = normalizedAddress
                        )
                    }.getOrElse { throwable ->
                        android.util.Log.w(
                            TAG,
                            "[$normalizedAddress] Failed to persist compat remote text message",
                            throwable
                        )
                        false
                    }
                    if (inserted) {
                        BleMessageNotifier.notifyIncoming(
                            context = applicationContext,
                            sessionCode = sessionCode,
                            contactName = contact.name,
                            body = body,
                            timestamp = timestampMs
                        )
                    }
                }
                return true
            }

            P2pBleProtocol.CHAT_KIND_VOICE_ABORT -> {
                payload.messageId?.let { transferId ->
                    synchronized(incomingVoiceTransfers) {
                        incomingVoiceTransfers.remove(voiceTransferKey(normalizedAddress, transferId))
                    }
                }
                return true
            }

            BleDirectChatCompat.CHAT_KIND_IMAGE_ABORT -> {
                payload.messageId?.let { transferId ->
                    synchronized(incomingImageTransfers) {
                        incomingImageTransfers.remove(imageTransferKey(normalizedAddress, transferId))
                    }
                }
                return true
            }

            BleDirectChatCompat.CHAT_KIND_FILE_ABORT -> {
                payload.messageId?.let { transferId ->
                    synchronized(incomingFileTransfers) {
                        incomingFileTransfers.remove(fileTransferKey(normalizedAddress, transferId))
                    }
                }
                return true
            }

            P2pBleProtocol.CHAT_KIND_VOICE_INIT,
            P2pBleProtocol.CHAT_KIND_VOICE_CHUNK,
            P2pBleProtocol.CHAT_KIND_VOICE_DONE -> {
                if (!payload.senderName.isNullOrBlank()) {
                    serviceScope.launch(Dispatchers.IO) {
                        upsertContactForNotification(
                            sessionCode = sessionCode,
                            normalizedAddress = normalizedAddress,
                            preferredName = payload.senderName
                        )
                    }
                }
                BleDirectChatCompat.toIncomingVoicePacket(payload)?.let { packet ->
                    handleIncomingVoicePacket(
                        normalizedAddress = normalizedAddress,
                        sessionCode = sessionCode,
                        packet = packet,
                        timestampMs = timestampMs
                    )
                }
                return true
            }

            BleDirectChatCompat.CHAT_KIND_IMAGE_INIT,
            BleDirectChatCompat.CHAT_KIND_IMAGE_CHUNK,
            BleDirectChatCompat.CHAT_KIND_IMAGE_DONE -> {
                if (!payload.senderName.isNullOrBlank()) {
                    serviceScope.launch(Dispatchers.IO) {
                        upsertContactForNotification(
                            sessionCode = sessionCode,
                            normalizedAddress = normalizedAddress,
                            preferredName = payload.senderName
                        )
                    }
                }
                BleDirectChatCompat.toIncomingImagePacket(payload)?.let { packet ->
                    handleIncomingImagePacket(
                        normalizedAddress = normalizedAddress,
                        sessionCode = sessionCode,
                        packet = packet,
                        timestampMs = timestampMs
                    )
                }
                return true
            }

            BleDirectChatCompat.CHAT_KIND_FILE_INIT,
            BleDirectChatCompat.CHAT_KIND_FILE_CHUNK,
            BleDirectChatCompat.CHAT_KIND_FILE_DONE -> {
                if (!payload.senderName.isNullOrBlank()) {
                    serviceScope.launch(Dispatchers.IO) {
                        upsertContactForNotification(
                            sessionCode = sessionCode,
                            normalizedAddress = normalizedAddress,
                            preferredName = payload.senderName
                        )
                    }
                }
                BleDirectChatCompat.toIncomingFilePacket(payload)?.let { packet ->
                    handleIncomingFilePacket(
                        normalizedAddress = normalizedAddress,
                        sessionCode = sessionCode,
                        packet = packet
                    )
                }
                return true
            }
        }
        return false
    }

    private fun resolveIncomingSessionCode(
        normalizedAddress: String,
        route: String?
    ): String {
        val safeAddress = normalizeMacAddress(normalizedAddress).ifBlank {
            normalizedAddress.trim().uppercase(Locale.US)
        }
        if (route.equals(OUTBOUND_ROUTE_BLE_GATT_FALLBACK, ignoreCase = true)) {
            return resolveFallbackSessionCodeForAddress(safeAddress)
        }
        return resolveSessionCodeForAddress(safeAddress)
    }

    private fun resolveSessionCodeForAddress(normalizedAddress: String): String {
        val safeAddress = normalizeMacAddress(normalizedAddress).ifBlank {
            normalizedAddress.trim().uppercase(Locale.US)
        }
        return BleSessionResolver.sessionCodeForAddress(safeAddress)
    }

    private fun resolveFallbackSessionCodeForAddress(normalizedAddress: String): String {
        val safeAddress = normalizeMacAddress(normalizedAddress).ifBlank {
            normalizedAddress.trim().uppercase(Locale.US)
        }
        val contacts = runCatching { getContacts(applicationContext) }
            .getOrNull()
            .orEmpty()
        val matched = contacts
            .asSequence()
            .filter { contact ->
                normalizeMacAddress(contact.address) == safeAddress
            }
            .sortedBy { contact ->
                if (contact.sessionCode.startsWith("ble:", ignoreCase = true)) 1 else 0
            }
            .mapNotNull { contact ->
                contact.sessionCode.trim().takeIf { it.isNotBlank() }
            }
            .firstOrNull()
        if (!matched.isNullOrBlank()) {
            return matched
        }
        val activeSession = ActiveChatTracker.activeSession.value
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("ble:", ignoreCase = true) }
        if (activeSession != null) {
            val activeContact = contacts.firstOrNull { contact ->
                contact.sessionCode.equals(activeSession, ignoreCase = true)
            }
            if (activeContact != null) {
                runCatching {
                    updateContactAddress(applicationContext, activeSession, safeAddress)
                }
                return activeSession
            }
        }
        val nonBleWithoutAddress = contacts
            .asSequence()
            .filter { contact -> !contact.sessionCode.startsWith("ble:", ignoreCase = true) }
            .filter { contact -> normalizeMacAddress(contact.address).isEmpty() }
            .map { contact -> contact.sessionCode.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (nonBleWithoutAddress.size == 1) {
            val resolved = nonBleWithoutAddress.first()
            runCatching {
                updateContactAddress(applicationContext, resolved, safeAddress)
            }
            return resolved
        }
        return BleSessionResolver.sessionCodeForAddress(safeAddress)
    }

    private fun fileTransferKey(normalizedAddress: String, transferId: String): String {
        return "$normalizedAddress|$transferId"
    }

    private fun voiceTransferKey(normalizedAddress: String, transferId: String): String {
        return "$normalizedAddress|$transferId"
    }

    private fun publishIncomingVoiceProgress(
        sessionCode: String,
        transferId: String,
        totalChunks: Int,
        confirmedChunks: Int,
        state: VoiceTransferState
    ) {
        BleVoiceTransferProgressStore.update(
            sessionCode = sessionCode,
            transferId = transferId,
            direction = VoiceTransferDirection.Download,
            totalChunks = totalChunks,
            confirmedChunks = confirmedChunks,
            state = state
        )
    }

    private fun clearIncomingVoiceProgressLater(
        sessionCode: String,
        transferId: String,
        delayMs: Long = VOICE_FAILED_BADGE_MS
    ) {
        serviceScope.launch {
            delay(delayMs)
            BleVoiceTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = VoiceTransferDirection.Download
            )
        }
    }

    private fun clearVoiceTransfersForAddress(normalizedAddress: String) {
        val prefix = "$normalizedAddress|"
        val sessionCode = resolveSessionCodeForAddress(normalizedAddress)
        val removedTransferIds = mutableListOf<String>()
        synchronized(incomingVoiceTransfers) {
            val iterator = incomingVoiceTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    removedTransferIds += entry.value.transferId
                    iterator.remove()
                }
            }
        }
        removedTransferIds.forEach { transferId ->
            BleVoiceTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = VoiceTransferDirection.Download
            )
        }
    }

    private fun cleanupStaleIncomingVoiceTransfers() {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<Pair<String, BleVoicePayload.IncomingTransfer>>()
        synchronized(incomingVoiceTransfers) {
            val iterator = incomingVoiceTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAtMillis > VOICE_TRANSFER_TTL_MS) {
                    stale += entry.key to entry.value
                    iterator.remove()
                }
            }
        }
        stale.forEach { (key, transfer) ->
            val address = key.substringBefore('|', missingDelimiterValue = "")
            if (address.isNotBlank()) {
                val sessionCode = resolveSessionCodeForAddress(address)
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.chunks.size,
                    state = VoiceTransferState.Failed
                )
                clearIncomingVoiceProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId
                )
                sendVoiceAbortPacket(address, transfer.transferId, "transfer_timeout")
            }
        }
    }

    private fun clearFileTransfersForAddress(normalizedAddress: String) {
        val prefix = "$normalizedAddress|"
        synchronized(incomingFileTransfers) {
            val iterator = incomingFileTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    iterator.remove()
                }
            }
        }
    }

    private fun cleanupStaleIncomingFileTransfers() {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<Pair<String, BleFilePayload.IncomingTransfer>>()
        synchronized(incomingFileTransfers) {
            val iterator = incomingFileTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAtMillis > FILE_TRANSFER_TTL_MS) {
                    stale += entry.key to entry.value
                    iterator.remove()
                }
            }
        }
        stale.forEach { (key, transfer) ->
            val address = key.substringBefore('|', missingDelimiterValue = "")
            if (address.isNotBlank()) {
                sendFileAbortPacket(address, transfer.transferId, "transfer_timeout")
            }
        }
    }

    private fun handleIncomingImagePacket(
        normalizedAddress: String,
        sessionCode: String,
        packet: BleImagePayload.Packet,
        timestampMs: Long
    ) {
        cleanupStaleIncomingImageTransfers()
        when (packet) {
            is BleImagePayload.Packet.Init -> {
                val key = imageTransferKey(normalizedAddress, packet.transferId)
                incomingImageTransfers[key] = BleImagePayload.IncomingTransfer(
                    transferId = packet.transferId,
                    messageId = packet.messageId,
                    mimeType = packet.mimeType,
                    width = packet.width,
                    height = packet.height,
                    totalBytes = packet.totalBytes,
                    totalChunks = packet.totalChunks,
                    sha256 = packet.sha256
                )
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = packet.transferId,
                    totalChunks = packet.totalChunks,
                    confirmedChunks = 0,
                    state = ImageTransferState.Initializing
                )
            }

            is BleImagePayload.Packet.Chunk -> {
                val key = imageTransferKey(normalizedAddress, packet.transferId)
                val transfer = incomingImageTransfers[key] ?: return
                val added = transfer.addChunk(packet.chunkIndex, packet.bytes)
                if (!added) {
                    publishIncomingImageProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.chunks.size,
                        state = ImageTransferState.Failed
                    )
                    clearIncomingImageProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendImageAbortPacket(normalizedAddress, transfer.transferId, "invalid_chunk")
                    incomingImageTransfers.remove(key)
                    return
                }
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.chunks.size,
                    state = ImageTransferState.Transferring
                )
                if (!transfer.isComplete()) {
                    return
                }
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = ImageTransferState.Verifying
                )
                val bytes = transfer.composeBytes()
                incomingImageTransfers.remove(key)
                if (bytes == null || bytes.isEmpty()) {
                    publishIncomingImageProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.chunks.size,
                        state = ImageTransferState.Failed
                    )
                    clearIncomingImageProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendImageAbortPacket(normalizedAddress, transfer.transferId, "compose_failed")
                    return
                }
                if (bytes.size > BleImagePayload.MAX_OUTGOING_TOTAL_BYTES) {
                    publishIncomingImageProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.totalChunks,
                        state = ImageTransferState.Failed
                    )
                    clearIncomingImageProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendImageAbortPacket(normalizedAddress, transfer.transferId, "payload_too_large")
                    return
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                if (!digest.contentEquals(transfer.sha256)) {
                    publishIncomingImageProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.totalChunks,
                        state = ImageTransferState.Failed
                    )
                    clearIncomingImageProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendImageAbortPacket(normalizedAddress, transfer.transferId, "sha_mismatch")
                    return
                }
                persistIncomingImageMessage(
                    normalizedAddress = normalizedAddress,
                    sessionCode = sessionCode,
                    transfer = transfer,
                    bytes = bytes,
                    timestampMs = timestampMs
                )
            }

            is BleImagePayload.Packet.Done,
            is BleImagePayload.Packet.Abort -> Unit
        }
    }

    private fun persistIncomingImageMessage(
        normalizedAddress: String,
        sessionCode: String,
        transfer: BleImagePayload.IncomingTransfer,
        bytes: ByteArray,
        timestampMs: Long
    ) {
        val messageId = transfer.messageId.ifBlank { UUID.randomUUID().toString() }
        val fileName = ImageFileUtils.fileNameFor(messageId, transfer.mimeType)
        val thumbnailName = ImageFileUtils.thumbnailNameFor(messageId, transfer.mimeType)
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val contact = upsertContactForNotification(sessionCode, normalizedAddress)
                val destination = imageMessageFile(applicationContext, fileName)
                val thumbnail = imageThumbnailFile(applicationContext, thumbnailName)
                destination.parentFile?.mkdirs()
                destination.writeBytes(bytes)
                val thumbnailCreated = generateImageThumbnail(
                    source = destination,
                    target = thumbnail,
                    mimeType = transfer.mimeType
                )
                saveRemoteImageMessage(
                    context = applicationContext,
                    sessionCode = sessionCode,
                    uuid = messageId,
                    fileName = destination.name,
                    thumbnailName = if (thumbnailCreated) thumbnail.name else null,
                    width = transfer.width,
                    height = transfer.height,
                    mimeType = transfer.mimeType
                )
                BleChatStore.appendRemoteImageMessage(
                    sessionCode = sessionCode,
                    imageFilePath = destination.absolutePath,
                    imageThumbnailPath = if (thumbnailCreated) thumbnail.absolutePath else null,
                    imageWidth = transfer.width,
                    imageHeight = transfer.height,
                    imageMimeType = transfer.mimeType,
                    messageId = messageId
                )
                BleMessageNotifier.notifyIncoming(
                    context = applicationContext,
                    sessionCode = sessionCode,
                    contactName = contact.name,
                    body = getString(R.string.notification_photo_message_body),
                    timestamp = timestampMs
                )
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = ImageTransferState.Completed
                )
                clearIncomingImageProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    delayMs = IMAGE_COMPLETED_BADGE_MS
                )
                sendImageDonePacket(normalizedAddress, transfer.transferId)
                markTraffic()
            }.onFailure {
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = ImageTransferState.Failed
                )
                clearIncomingImageProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId
                )
                sendImageAbortPacket(normalizedAddress, transfer.transferId, "persist_failed")
            }
        }
    }

    private fun sendImageDonePacket(address: String, transferId: String) {
        val packet = BleImagePayload.buildDonePacket(transferId)
        if (packet.isBlank()) {
            return
        }
        serviceScope.launch {
            val sent = runCatching { manager.sendMessageAwait(address, packet) }.getOrElse { false }
            if (!sent) {
                android.util.Log.w(TAG, "[$address] Failed to send image DONE ack for transfer=$transferId")
            }
        }
    }

    private fun sendImageAbortPacket(address: String, transferId: String, reason: String) {
        val packet = BleImagePayload.buildAbortPacket(transferId, reason)
        if (packet.isBlank()) {
            return
        }
        serviceScope.launch {
            val sent = runCatching { manager.sendMessageAwait(address, packet) }.getOrElse { false }
            if (!sent) {
                android.util.Log.w(TAG, "[$address] Failed to send image ABORT ack for transfer=$transferId")
            }
        }
    }

    private fun imageTransferKey(normalizedAddress: String, transferId: String): String {
        return "$normalizedAddress|$transferId"
    }

    private fun publishIncomingImageProgress(
        sessionCode: String,
        transferId: String,
        totalChunks: Int,
        confirmedChunks: Int,
        state: ImageTransferState
    ) {
        BleImageTransferProgressStore.update(
            sessionCode = sessionCode,
            transferId = transferId,
            direction = ImageTransferDirection.Download,
            totalChunks = totalChunks,
            confirmedChunks = confirmedChunks,
            state = state
        )
    }

    private fun clearIncomingImageProgressLater(
        sessionCode: String,
        transferId: String,
        delayMs: Long = IMAGE_FAILED_BADGE_MS
    ) {
        serviceScope.launch {
            delay(delayMs)
            BleImageTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = ImageTransferDirection.Download
            )
        }
    }

    private fun clearImageTransfersForAddress(normalizedAddress: String) {
        val prefix = "$normalizedAddress|"
        val sessionCode = resolveSessionCodeForAddress(normalizedAddress)
        val removedTransferIds = mutableListOf<String>()
        synchronized(incomingImageTransfers) {
            val iterator = incomingImageTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    removedTransferIds += entry.value.transferId
                    iterator.remove()
                }
            }
        }
        removedTransferIds.forEach { transferId ->
            BleImageTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = ImageTransferDirection.Download
            )
        }
    }

    private fun cleanupStaleIncomingImageTransfers() {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<Pair<String, BleImagePayload.IncomingTransfer>>()
        synchronized(incomingImageTransfers) {
            val iterator = incomingImageTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAtMillis > IMAGE_TRANSFER_TTL_MS) {
                    stale += entry.key to entry.value
                    iterator.remove()
                }
            }
        }
        stale.forEach { (key, transfer) ->
            val address = key.substringBefore('|', missingDelimiterValue = "")
            if (address.isNotBlank()) {
                val sessionCode = resolveSessionCodeForAddress(address)
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.chunks.size,
                    state = ImageTransferState.Failed
                )
                clearIncomingImageProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId
                )
                sendImageAbortPacket(address, transfer.transferId, "transfer_timeout")
            }
        }
    }

    private fun resolveContactNameForNotification(
        currentName: String?,
        sessionCode: String,
        preferredName: String? = null
    ): String {
        val preferred = preferredName?.trim().orEmpty()
        if (preferred.isNotEmpty() && !BlePeerIdentityUtils.looksLikeBleIdentifier(preferred, sessionCode)) {
            return preferred
        }
        val trimmed = currentName?.trim().orEmpty()
        if (trimmed.isNotEmpty() && !BlePeerIdentityUtils.looksLikeBleIdentifier(trimmed, sessionCode)) {
            return trimmed
        }
        return getString(R.string.rescue_unknown_user)
    }

    private fun hasRescueAccess(): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                SecurityRepository(applicationContext).hasUsableStoredCertificate(allowExpired = true)
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "GattRescueClientSvc"
        private const val NOTIFICATION_CHANNEL_ID = "rescue_client_channel"
        private const val NOTIFICATION_ID = 2001
        private const val MIN_IDLE_DISCONNECT_MS = 60_000L
        private const val MAX_IDLE_DISCONNECT_MS = 12 * 60_000L
        private const val IDLE_DELAY_ACTIVE_TRAFFIC_MS = 9 * 60_000L
        private const val IDLE_DELAY_WARM_MS = 6 * 60_000L
        private const val IDLE_DELAY_COLD_MS = 4 * 60_000L
        private const val IDLE_DELAY_NO_LINK_MS = 3 * 60_000L
        private const val TRAFFIC_HOT_WINDOW_MS = 2 * 60_000L
        private const val TRAFFIC_WARM_WINDOW_MS = 10 * 60_000L
        private const val CHARGING_BONUS_MS = 2 * 60_000L
        private const val VOICE_TRANSFER_TTL_MS = 90_000L
        private const val VOICE_COMPLETED_BADGE_MS = 900L
        private const val VOICE_FAILED_BADGE_MS = 2_200L
        private const val IMAGE_TRANSFER_TTL_MS = 90_000L
        private const val IMAGE_COMPLETED_BADGE_MS = 1_200L
        private const val IMAGE_FAILED_BADGE_MS = 2_600L
        private const val FILE_TRANSFER_TTL_MS = 90_000L
        private const val OUTBOUND_ROUTE_BLE_GATT_FALLBACK = "ble_gatt_fallback"
    }
}
