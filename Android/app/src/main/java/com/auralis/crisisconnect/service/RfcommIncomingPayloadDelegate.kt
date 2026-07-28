package com.auralis.crisisconnect.service

import android.content.Context
import android.util.Base64
import android.util.Log
import com.auralis.crisisconnect.core.io.normalizeSafeTransferId
import com.auralis.crisisconnect.core.media.ImageFileUtils
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.updateContactAesKey
import com.auralis.crisisconnect.data.updateContactName
import com.auralis.crisisconnect.data.updateContactPeerIdentity
import com.auralis.crisisconnect.data.updateLocalMessageDeliveryState
import com.auralis.crisisconnect.data.extractStoreForwardCreatedAtMillis
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.markLocalMessagesDelivered
import com.auralis.crisisconnect.data.markMessagesAsRead
import com.auralis.crisisconnect.data.saveRemoteAudioMessage
import com.auralis.crisisconnect.data.saveRemoteImageMessage
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.security.AesCipherHelper
import com.auralis.crisisconnect.service.file.FileStreamConstants
import com.auralis.crisisconnect.service.file.FileStreamMetadata
import com.auralis.crisisconnect.service.file.FileStreamReceiver
import com.auralis.crisisconnect.service.file.FileStreamSender
import com.auralis.crisisconnect.service.media.ImageStreamConstants
import com.auralis.crisisconnect.service.media.ImageStreamMetadata
import com.auralis.crisisconnect.service.media.ImageStreamReceiver
import com.auralis.crisisconnect.service.media.ImageStreamSender
import com.auralis.crisisconnect.service.media.ImageTransferDirection
import com.auralis.crisisconnect.service.media.ImageTransferProgress
import com.auralis.crisisconnect.service.media.ImageTransferState
import com.auralis.crisisconnect.service.offline.OfflineMapShareCoordinator
import com.auralis.crisisconnect.service.voice.VoiceStreamConstants
import com.auralis.crisisconnect.service.voice.VoiceStreamMetadata
import com.auralis.crisisconnect.service.voice.VoiceStreamReceiver
import com.auralis.crisisconnect.service.voice.VoiceStreamSender
import com.auralis.crisisconnect.service.voice.VoiceTransferDirection
import com.auralis.crisisconnect.service.voice.VoiceTransferProgress
import com.auralis.crisisconnect.service.voice.VoiceTransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class RfcommIncomingPayloadDelegate(
    private val appContext: Context,
    private val serviceScope: CoroutineScope,
    private val isSessionConnected: (String) -> Boolean,
    private val writeToSession: (String, ByteArray) -> Boolean,
    private val onSessionActive: (String) -> Unit,
    private val onHandleHandshakeRequest: (String, String) -> Unit,
    private val onHandleHandshakeAck: (String, String) -> Unit,
    private val onHandleContactInfo: (String, String) -> Unit,
    private val onHandleCallFrame: (String, JSONObject) -> Boolean,
    private val maybeNotifyIncomingMessage: (String, MessageType, String?) -> Unit,
    private val voiceKey: (String, String) -> String,
    private val imageKey: (String, String) -> String,
    private val fileKey: (String, String) -> String,
    private val mapShareKey: (String, String) -> String,
    private val voiceSenders: ConcurrentHashMap<String, VoiceStreamSender>,
    private val voiceReceivers: ConcurrentHashMap<String, VoiceStreamReceiver>,
    private val imageSenders: ConcurrentHashMap<String, ImageStreamSender>,
    private val imageReceivers: ConcurrentHashMap<String, ImageStreamReceiver>,
    private val fileSenders: ConcurrentHashMap<String, FileStreamSender>,
    private val fileReceivers: ConcurrentHashMap<String, FileStreamReceiver>,
    private val mapShareSenders: ConcurrentHashMap<String, ImageStreamSender>,
    private val mapShareReceivers: ConcurrentHashMap<String, ImageStreamReceiver>,
    private val updateVoiceProgress: (String, VoiceTransferProgress) -> Unit,
    private val clearVoiceProgress: (String) -> Unit,
    private val updateImageProgress: (String, ImageTransferProgress) -> Unit,
    private val clearImageProgress: (String) -> Unit,
    private val offlineMapShareMime: String
) {
    private val incomingTransferLock = Any()
    private val incomingTransfers = mutableMapOf<String, IncomingTransferReservation>()

    fun handleIncomingPayload(sessionCode: String, payload: String) {
        if (payload == "__hb__") {
            return
        }
        val trimmed = payload.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
            if (json != null) {
                val type = json.optString("type")
                if (type.startsWith("call")) {
                    if (onHandleCallFrame(sessionCode, json)) {
                        onSessionActive(sessionCode)
                    }
                    return
                }
                val handled = when {
                    type.startsWith("voice") -> handleVoiceFrame(sessionCode, json)
                    type.startsWith("image") -> handleImageFrame(sessionCode, json)
                    type.startsWith("file") -> handleFileFrame(sessionCode, json)
                    else -> false
                }
                if (handled) {
                    onSessionActive(sessionCode)
                    return
                }
                if (type.startsWith("image")) {
                    Log.w(
                        TAG,
                        "Unhandled image frame type=$type session=$sessionCode " +
                            "uuid=${json.optString("uuid")} payloadLength=${trimmed.length}"
                    )
                }
            }
        }
        when {
            payload.startsWith("HSK_REQ|") -> {
                onHandleHandshakeRequest(sessionCode, payload)
            }

            payload.startsWith("HSK_ACK|") -> {
                onHandleHandshakeAck(sessionCode, payload)
            }

            payload.startsWith("CONTACT_INFO|") -> {
                onHandleContactInfo(sessionCode, payload)
            }

            payload.startsWith("VOICE|") || payload.startsWith("VOICE_PART|") -> {
                Log.i(TAG, "Handling legacy VOICE payload for $sessionCode")
                saveLegacyMessage(sessionCode, payload)
                onSessionActive(sessionCode)
            }

            payload.startsWith("PEER_NAME|") -> {
                val remoteName = payload.substringAfter('|').trim().takeIf { it.isNotBlank() } ?: return
                serviceScope.launch {
                    val contact = getContact(appContext, sessionCode) ?: return@launch
                    val currentName = contact.name.trim()
                    // Only update if the current name looks like a MAC address or session code
                    if (currentName.isBlank() ||
                        currentName.equals(sessionCode, ignoreCase = true) ||
                        MAC_ADDRESS_REGEX.matches(currentName)
                    ) {
                        updateContactName(appContext, sessionCode, remoteName)
                    }
                }
                onSessionActive(sessionCode)
            }

            payload.startsWith("PEER_IDENTITY|") -> {
                // Peer announced its internet identity over the Bluetooth link. Stamp uid + public
                // key onto this contact so it becomes internet-capable (supportsInternet) and can
                // reach the peer when Bluetooth drops — no QR re-scan, self-heals a changed uid.
                val parts = payload.trim().split('|', limit = 3)
                if (parts.size >= 3) {
                    val remoteUid = parts[1].trim()
                    val remoteKey = parts[2].trim()
                    if (remoteUid.isNotBlank() && remoteKey.isNotBlank()) {
                        serviceScope.launch {
                            updateContactPeerIdentity(appContext, sessionCode, remoteUid, remoteKey)
                        }
                    }
                }
                onSessionActive(sessionCode)
            }

            payload.startsWith("DECRYPT_FAIL|") -> {
                val uuid = payload.substringAfter('|').takeIf { it.isNotBlank() } ?: return
                Log.w(TAG, "Remote peer failed to decrypt message $uuid for $sessionCode — clearing AES key to fall back to plaintext")
                serviceScope.launch {
                    // Clear the mismatched AES key so subsequent messages are sent
                    // as plaintext until the users re-pair via QR.
                    updateContactAesKey(appContext, sessionCode, "")
                    // Re-queue the failed message so it is resent unencrypted.
                    updateLocalMessageDeliveryState(
                        context = appContext,
                        uuid = uuid,
                        deliveryStatus = MessageDeliveryStatus.QUEUED,
                        retryCount = 0,
                        nextRetryAtMillis = null,
                        lastAttemptAtMillis = null,
                        lastError = null,
                        outboundRoute = null
                    )
                }
                onSessionActive(sessionCode)
            }

            payload.startsWith("DELIVERED|") -> {
                val uuid = payload.substringAfter('|').takeIf { it.isNotBlank() } ?: return
                serviceScope.launch {
                    markLocalMessagesDelivered(appContext, sessionCode, listOf(uuid))
                }
                onSessionActive(sessionCode)
            }

            payload.startsWith("ACK|") -> {
                val uuid = payload.substringAfter('|').takeIf { it.isNotBlank() } ?: return
                serviceScope.launch {
                    markMessagesAsRead(appContext, listOf(uuid))
                }
                onSessionActive(sessionCode)
            }

            payload.startsWith("SEC_MSG|") -> {
                val parts = payload.split('|', limit = 4)
                val uuid = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                val ivBase64 = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                val cipherBase64 = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                if (uuid != null && ivBase64 != null && cipherBase64 != null) {
                    val originalTimestampMillis = extractStoreForwardCreatedAtMillis(uuid)
                    val contact = getContact(appContext, sessionCode)
                    val keyBytes = contact?.aesKey?.takeIf { it.isNotBlank() }?.let {
                        runCatching { AesCipherHelper.decodeKeyFromBase64(it) }.getOrNull()
                    }
                    val ivBytes = runCatching { Base64.decode(ivBase64, Base64.NO_WRAP) }.getOrNull()
                    val cipherBytes = runCatching { Base64.decode(cipherBase64, Base64.NO_WRAP) }.getOrNull()
                    if (keyBytes != null && ivBytes != null && cipherBytes != null && ivBytes.isNotEmpty()) {
                        val packet = ByteArray(ivBytes.size + cipherBytes.size).apply {
                            System.arraycopy(ivBytes, 0, this, 0, ivBytes.size)
                            System.arraycopy(cipherBytes, 0, this, ivBytes.size, cipherBytes.size)
                        }
                        val decrypted = runCatching {
                            AesCipherHelper.decrypt(keyBytes, packet)
                        }.getOrNull()
                        val text = decrypted?.toString(Charsets.UTF_8)
                        if (text != null) {
                            serviceScope.launch {
                                saveRemoteMessage(
                                    context = appContext,
                                    sessionCode = sessionCode,
                                    uuid = uuid,
                                    text = text,
                                    createdAtMillis = originalTimestampMillis
                                )
                            }
                            sendDeliveryAck(sessionCode, uuid)
                            maybeNotifyIncomingMessage(sessionCode, MessageType.TEXT, text)
                            onSessionActive(sessionCode)
                            return
                        }
                    }
                    // Decryption failed — AES key mismatch or missing key.
                    // Do NOT save as legacy (would show garbled ciphertext to the user).
                    // Notify sender so they can mark the message as failed.
                    Log.w(TAG, "SEC_MSG decryption failed for $sessionCode uuid=$uuid — dropping message")
                    val nack = "DECRYPT_FAIL|$uuid\n"
                    writeToSession(sessionCode, nack.toByteArray(Charsets.UTF_8))
                } else {
                    Log.w(TAG, "SEC_MSG malformed for $sessionCode — dropping message")
                }
                onSessionActive(sessionCode)
            }

            payload.startsWith("MSG|") -> {
                val parts = payload.split('|', limit = 3)
                val uuid = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                val encodedText = parts.getOrNull(2)
                val text = encodedText?.replace("\\n", "\n")
                if (uuid != null && text != null) {
                    val originalTimestampMillis = extractStoreForwardCreatedAtMillis(uuid)
                    serviceScope.launch {
                        saveRemoteMessage(
                            context = appContext,
                            sessionCode = sessionCode,
                            uuid = uuid,
                            text = text,
                            createdAtMillis = originalTimestampMillis
                        )
                    }
                    sendDeliveryAck(sessionCode, uuid)
                    maybeNotifyIncomingMessage(sessionCode, MessageType.TEXT, text)
                } else {
                    saveLegacyMessage(sessionCode, payload)
                }
                onSessionActive(sessionCode)
            }

            else -> {
                if (!isControlPayload(payload)) {
                    saveLegacyMessage(sessionCode, payload)
                }
                onSessionActive(sessionCode)
            }
        }
    }

    private fun isControlPayload(payload: String): Boolean {
        return payload.startsWith("DELIVERED|") ||
            payload.startsWith("DECRYPT_FAIL|") ||
            payload.startsWith("ACK|") ||
            payload.startsWith("PEER_NAME|") ||
            payload.startsWith("PEER_IDENTITY|") ||
            payload.startsWith("HSK_REQ|") ||
            payload.startsWith("HSK_ACK|") ||
            payload.startsWith("CONTACT_INFO|")
    }

    private fun sendDeliveryAck(sessionCode: String, messageUuid: String) {
        val payload = "DELIVERED|$messageUuid\n"
        writeToSession(sessionCode, payload.toByteArray(Charsets.UTF_8))
    }

    fun saveLegacyMessage(sessionCode: String, text: String) {
        val uuid = "legacy-${UUID.randomUUID()}"
        serviceScope.launch {
            saveRemoteMessage(appContext, sessionCode, uuid, text)
        }
        maybeNotifyIncomingMessage(sessionCode, MessageType.TEXT, text)
    }

    fun handleVoiceFrame(sessionCode: String, json: JSONObject): Boolean {
        val type = json.optString("type")?.takeIf { it.isNotBlank() } ?: return false
        val uuid = normalizeSafeTransferId(json.optString("uuid")) ?: run {
            Log.w(TAG, "Rejected incoming voice frame with unsafe transfer id")
            return false
        }
        val key = voiceKey(sessionCode, uuid)
        if (!isSessionConnected(sessionCode)) {
            return false
        }
        return when (type) {
            "voice:init" -> {
                val totalBytes = json.optInt("totalBytes", -1)
                val chunkSize = json.optInt("chunkSize", VoiceStreamConstants.CHUNK_SIZE)
                val chunkCount = json.optInt("chunkCount", -1)
                val encrypted = json.optBoolean("encrypted", false)
                val duration = if (json.isNull("durationMs")) null else json.optLong("durationMs")
                val mime = json.optString("mime", "audio/mp4")
                val shaBase64 = json.optString("sha256B64")
                if (shaBase64.isNullOrBlank() || (duration != null && duration < 0L)) {
                    return false
                }
                val shaBytes = runCatching { Base64.decode(shaBase64, Base64.NO_WRAP) }.getOrNull()
                    ?: return false
                val ivBytes = json.optString("ivB64")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                val aadBytes = json.optString("aadB64")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                if (!VoiceStreamConstants.isValidInboundMetadata(
                        totalBytes = totalBytes,
                        chunkSize = chunkSize,
                        chunkCount = chunkCount,
                        sha256 = shaBytes,
                        encrypted = encrypted,
                        iv = ivBytes,
                        aad = aadBytes
                    )
                ) {
                    return false
                }
                if (!reserveIncomingTransfer(sessionCode, key, "voice")) {
                    return false
                }
                serviceScope.launch {
                    runCatching {
                        val contact = getContact(appContext, sessionCode)
                        val keyBytes = contact?.aesKey?.takeIf { it.isNotBlank() }?.let {
                            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
                        }
                        saveRemoteAudioMessage(
                            appContext,
                            sessionCode,
                            uuid,
                            voiceMessageFileName(uuid, mime),
                            duration
                        )
                        val metadata = VoiceStreamMetadata(
                            uuid = uuid,
                            totalBytes = totalBytes,
                            mime = mime,
                            durationMs = duration,
                            encrypted = encrypted,
                            chunkSize = chunkSize,
                            chunkCount = chunkCount,
                            sha256 = shaBytes,
                            iv = ivBytes,
                            aad = aadBytes
                        )
                        val receiver = VoiceStreamReceiver(
                            context = appContext,
                            sessionCode = sessionCode,
                            metadata = metadata,
                            aesKey = keyBytes,
                            scope = serviceScope,
                            frameWriter = { frame ->
                                writeToSession(sessionCode, (frame.toString() + "\n").toByteArray(Charsets.UTF_8))
                            },
                            onProgress = { progress -> updateVoiceProgress(key, progress) },
                            onCompleted = { success, _ ->
                                voiceReceivers.remove(key)
                                unregisterIncomingTransfer(key)
                                if (success) {
                                    clearVoiceProgress(key)
                                }
                            }
                        )
                        if (!attachIncomingTransfer(key) {
                                voiceReceivers.remove(key)
                                receiver.cancel()
                            }
                        ) {
                            receiver.cancel()
                            return@launch
                        }
                        voiceReceivers[key] = receiver
                        updateVoiceProgress(
                            key,
                            VoiceTransferProgress(
                                sessionCode = sessionCode,
                                uuid = uuid,
                                direction = VoiceTransferDirection.Download,
                                totalChunks = chunkCount,
                                confirmedChunks = 0,
                                pendingChunks = chunkCount,
                                inFlightChunks = 0,
                                state = VoiceTransferState.Initializing
                            )
                        )
                    }.onFailure { throwable ->
                        unregisterIncomingTransfer(key)
                        Log.w(TAG, "Failed to initialize incoming voice transfer uuid=$uuid", throwable)
                    }
                }
                maybeNotifyIncomingMessage(sessionCode, MessageType.AUDIO, null)
                true
            }

            "voice:chunk" -> {
                val index = json.optInt("index", -1)
                val crc32 = json.optLong("crc32", -1)
                val payloadB64 = json.optString("payloadB64")
                if (index < 0 || crc32 < 0 || payloadB64.isNullOrBlank()) {
                    return false
                }
                val bytes = runCatching { Base64.decode(payloadB64, Base64.NO_WRAP) }.getOrNull()
                    ?: return false
                voiceReceivers[key]?.let { receiver ->
                    touchIncomingTransfer(key)
                    receiver.handleChunk(index, crc32, bytes)
                }
                true
            }

            "voice:finish" -> {
                voiceReceivers[key]?.let { receiver ->
                    touchIncomingTransfer(key)
                    receiver.handleFinish()
                }
                true
            }

            "voice:ack" -> {
                val receivedUpTo = json.optInt("receivedUpTo", -1)
                val missing = json.optJSONArray("missing")
                val missingList = mutableListOf<Int>()
                if (missing != null) {
                    for (i in 0 until missing.length()) {
                        missingList.add(missing.optInt(i))
                    }
                }
                voiceSenders[key]?.handleAck(receivedUpTo, missingList)
                true
            }

            "voice:resume" -> {
                val wantArray = json.optJSONArray("want")
                val wants = mutableListOf<Int>()
                if (wantArray != null) {
                    for (i in 0 until wantArray.length()) {
                        wants.add(wantArray.optInt(i))
                    }
                }
                voiceSenders[key]?.handleResume(wants)
                true
            }

            "voice:verify_ok" -> {
                voiceSenders[key]?.handleVerify(true)
                true
            }

            "voice:verify_fail" -> {
                voiceSenders[key]?.handleVerify(false)
                true
            }

            else -> false
        }
    }

    fun handleFileFrame(sessionCode: String, json: JSONObject): Boolean {
        val type = json.optString("type")?.takeIf { it.isNotBlank() } ?: return false
        val uuid = normalizeSafeTransferId(json.optString("uuid")) ?: run {
            Log.w(TAG, "Rejected incoming file frame with unsafe transfer id")
            return false
        }
        val key = fileKey(sessionCode, uuid)
        if (!isSessionConnected(sessionCode)) {
            return false
        }
        return when (type) {
            "file:init" -> {
                val displayName = json.optString("displayName")?.takeIf { it.isNotBlank() }
                    ?: return false
                val totalBytes = json.optInt("totalBytes", -1)
                val originalSize = json.optLong("originalSize", -1L)
                val chunkSize = json.optInt("chunkSize", FileStreamConstants.CHUNK_SIZE)
                val chunkCount = json.optInt("chunkCount", -1)
                val encrypted = json.optBoolean("encrypted", false)
                val mime = json.optString("mime")?.takeIf { it.isNotBlank() }
                val compression = json.optString("compression")
                    ?.takeIf { it.isNotBlank() }
                    ?.lowercase()
                    ?: FileStreamConstants.COMPRESSION_NONE
                val shaBase64 = json.optString("sha256B64")?.takeIf { it.isNotBlank() } ?: return false
                val shaBytes = runCatching { Base64.decode(shaBase64, Base64.NO_WRAP) }.getOrNull()
                    ?: return false
                val ivBytes = json.optString("ivB64")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                val aadBytes = json.optString("aadB64")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                if (!FileStreamConstants.isValidInboundMetadata(
                        originalSizeBytes = originalSize,
                        totalBytes = totalBytes,
                        chunkSize = chunkSize,
                        chunkCount = chunkCount,
                        sha256 = shaBytes,
                        encrypted = encrypted,
                        iv = ivBytes,
                        aad = aadBytes,
                        compression = compression
                    )
                ) {
                    return false
                }
                if (!reserveIncomingTransfer(sessionCode, key, "file")) {
                    return false
                }
                serviceScope.launch {
                    runCatching {
                        val contact = getContact(appContext, sessionCode)
                        val keyBytes = contact?.aesKey?.takeIf { it.isNotBlank() }?.let {
                            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
                        }
                        val metadata = FileStreamMetadata(
                            uuid = uuid,
                            displayName = displayName,
                            originalSizeBytes = originalSize,
                            totalBytes = totalBytes,
                            mime = mime,
                            compression = compression,
                            encrypted = encrypted,
                            chunkSize = chunkSize,
                            chunkCount = chunkCount,
                            sha256 = shaBytes,
                            iv = ivBytes,
                            aad = aadBytes
                        )
                        val receiver = FileStreamReceiver(
                            context = appContext,
                            metadata = metadata,
                            aesKey = keyBytes,
                            scope = serviceScope,
                            frameWriter = { frame ->
                                writeToSession(sessionCode, (frame.toString() + "\n").toByteArray(Charsets.UTF_8))
                            },
                            onCompleted = { success ->
                                fileReceivers.remove(key)
                                unregisterIncomingTransfer(key)
                                if (!success) {
                                    Log.w(TAG, "File transfer failed for uuid=$uuid")
                                }
                            }
                        )
                        if (!attachIncomingTransfer(key) {
                                fileReceivers.remove(key)
                                receiver.cancel()
                            }
                        ) {
                            receiver.cancel()
                            return@launch
                        }
                        fileReceivers[key] = receiver
                    }.onFailure { throwable ->
                        unregisterIncomingTransfer(key)
                        Log.w(TAG, "Failed to initialize incoming file transfer uuid=$uuid", throwable)
                    }
                }
                true
            }

            "file:chunk" -> {
                val index = json.optInt("index", -1)
                val crc32 = json.optLong("crc32", -1)
                val payloadB64 = json.optString("payloadB64")
                if (index < 0 || crc32 < 0 || payloadB64.isNullOrBlank()) {
                    return false
                }
                val bytes = runCatching { Base64.decode(payloadB64, Base64.NO_WRAP) }.getOrNull()
                    ?: return false
                fileReceivers[key]?.let { receiver ->
                    touchIncomingTransfer(key)
                    receiver.handleChunk(index, crc32, bytes)
                }
                true
            }

            "file:finish" -> {
                fileReceivers[key]?.let { receiver ->
                    touchIncomingTransfer(key)
                    receiver.handleFinish()
                }
                true
            }

            "file:ack" -> {
                val receivedUpTo = json.optInt("receivedUpTo", -1)
                val missing = json.optJSONArray("missing")
                val missingList = mutableListOf<Int>()
                if (missing != null) {
                    for (i in 0 until missing.length()) {
                        missingList.add(missing.optInt(i))
                    }
                }
                fileSenders[key]?.handleAck(receivedUpTo, missingList)
                true
            }

            "file:resume" -> {
                val wantArray = json.optJSONArray("want")
                val wants = mutableListOf<Int>()
                if (wantArray != null) {
                    for (i in 0 until wantArray.length()) {
                        wants.add(wantArray.optInt(i))
                    }
                }
                fileSenders[key]?.handleResume(wants)
                true
            }

            "file:verify_ok" -> {
                fileSenders[key]?.handleVerify(true)
                true
            }

            "file:verify_fail" -> {
                fileSenders[key]?.handleVerify(false)
                true
            }

            else -> false
        }
    }

    fun handleImageFrame(sessionCode: String, json: JSONObject): Boolean {
        val type = json.optString("type")?.takeIf { it.isNotBlank() } ?: return false
        val uuid = normalizeSafeTransferId(json.optString("uuid")) ?: run {
            Log.w(TAG, "Rejected incoming image frame with unsafe transfer id")
            return false
        }
        val imageTransferKey = imageKey(sessionCode, uuid)
        val mapShareTransferKey = mapShareKey(sessionCode, uuid)
        if (!isSessionConnected(sessionCode)) {
            return false
        }
        return when (type) {
            "image:init" -> {
                val totalBytes = json.optInt("totalBytes", -1)
                val chunkSize = json.optInt("chunkSize", ImageStreamConstants.CHUNK_SIZE)
                val chunkCount = json.optInt("chunkCount", -1)
                val encrypted = json.optBoolean("encrypted", false)
                val mime = json.optString("mime")?.takeIf { it.isNotBlank() } ?: "image/jpeg"
                val isOfflineMapShare = mime.equals(offlineMapShareMime, ignoreCase = true)
                val width = json.optInt("width").takeIf { json.has("width") && !json.isNull("width") }
                val height = json.optInt("height").takeIf { json.has("height") && !json.isNull("height") }
                val shaBase64 = json.optString("sha256B64")?.takeIf { it.isNotBlank() } ?: return false
                val shaBytes = runCatching { Base64.decode(shaBase64, Base64.NO_WRAP) }.getOrNull()
                    ?: run {
                        Log.w(TAG, "Rejected image:init with invalid sha256 uuid=$uuid")
                        return false
                    }
                val ivBase64 = json.optString("ivB64")?.takeIf { it.isNotBlank() }
                val ivBytes = ivBase64?.let {
                    runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
                } ?: if (ivBase64 == null) null else run {
                    Log.w(TAG, "Rejected image:init with invalid iv uuid=$uuid")
                    return false
                }
                val aadBase64 = json.optString("aadB64")?.takeIf { it.isNotBlank() }
                val aadBytes = aadBase64?.let {
                    runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
                } ?: if (aadBase64 == null) null else run {
                    Log.w(TAG, "Rejected image:init with invalid aad uuid=$uuid")
                    return false
                }
                if (!ImageStreamConstants.isValidInboundMetadata(
                        totalBytes = totalBytes,
                        chunkSize = chunkSize,
                        chunkCount = chunkCount,
                        sha256 = shaBytes,
                        encrypted = encrypted,
                        iv = ivBytes,
                        aad = aadBytes
                    )
                ) {
                    Log.w(
                        TAG,
                        "Rejected image:init metadata uuid=$uuid totalBytes=$totalBytes " +
                            "chunkSize=$chunkSize chunkCount=$chunkCount encrypted=$encrypted"
                    )
                    return false
                }
                val transferKey = if (isOfflineMapShare) mapShareTransferKey else imageTransferKey
                val transferLabel = if (isOfflineMapShare) "offline-map-share" else "image"
                if (!reserveIncomingTransfer(sessionCode, transferKey, transferLabel)) {
                    return false
                }
                Log.i(
                    TAG,
                    "Accepted image:init uuid=$uuid session=$sessionCode totalBytes=$totalBytes " +
                        "chunkCount=$chunkCount encrypted=$encrypted mapShare=$isOfflineMapShare"
                )
                serviceScope.launch {
                    runCatching {
                        val contact = getContact(appContext, sessionCode)
                        val keyBytes = contact?.aesKey?.takeIf { it.isNotBlank() }?.let {
                            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
                        }
                        val metadata = ImageStreamMetadata(
                            uuid = uuid,
                            totalBytes = totalBytes,
                            mime = mime,
                            width = width,
                            height = height,
                            encrypted = encrypted,
                            chunkSize = chunkSize,
                            chunkCount = chunkCount,
                            sha256 = shaBytes,
                            iv = ivBytes,
                            aad = aadBytes
                        )
                        if (!isOfflineMapShare) {
                            saveRemoteImageMessage(
                                appContext,
                                sessionCode,
                                uuid,
                                ImageFileUtils.fileNameFor(uuid, mime),
                                null,
                                width,
                                height,
                                mime
                            )
                        }
                        val receiver = ImageStreamReceiver(
                            context = appContext,
                            sessionCode = sessionCode,
                            metadata = metadata,
                            aesKey = keyBytes,
                            scope = serviceScope,
                            frameWriter = { frame ->
                                writeToSession(sessionCode, (frame.toString() + "\n").toByteArray(Charsets.UTF_8))
                            },
                            onProgress = { progress ->
                                if (!isOfflineMapShare) {
                                    updateImageProgress(imageTransferKey, progress)
                                }
                            },
                            onCompleted = { success, file ->
                                unregisterIncomingTransfer(transferKey)
                                if (isOfflineMapShare) {
                                    mapShareReceivers.remove(mapShareTransferKey)
                                    if (success && file != null) {
                                        serviceScope.launch {
                                            val merged = OfflineMapShareCoordinator.importShareArchive(
                                                context = appContext,
                                                archiveFile = file
                                            )
                                            runCatching { file.delete() }
                                            if (!merged) {
                                                Log.w(TAG, "Incoming offline map share merge failed for uuid=$uuid")
                                            }
                                        }
                                    } else {
                                        runCatching { file?.delete() }
                                    }
                                } else {
                                    imageReceivers.remove(imageTransferKey)
                                    if (success) {
                                        clearImageProgress(imageTransferKey)
                                    }
                                }
                            }
                        )
                        if (!attachIncomingTransfer(transferKey) {
                                if (isOfflineMapShare) {
                                    mapShareReceivers.remove(mapShareTransferKey)
                                } else {
                                    imageReceivers.remove(imageTransferKey)
                                }
                                receiver.cancel()
                            }
                        ) {
                            receiver.cancel()
                            return@launch
                        }
                        if (isOfflineMapShare) {
                            mapShareReceivers[mapShareTransferKey] = receiver
                        } else {
                            imageReceivers[imageTransferKey] = receiver
                            updateImageProgress(
                                imageTransferKey,
                                ImageTransferProgress(
                                    sessionCode = sessionCode,
                                    uuid = uuid,
                                    direction = ImageTransferDirection.Download,
                                    totalChunks = chunkCount,
                                    confirmedChunks = 0,
                                    pendingChunks = chunkCount,
                                    inFlightChunks = 0,
                                    state = ImageTransferState.Initializing
                                )
                            )
                        }
                    }.onFailure { throwable ->
                        unregisterIncomingTransfer(transferKey)
                        Log.w(TAG, "Failed to initialize incoming $transferLabel transfer uuid=$uuid", throwable)
                    }
                }
                if (!isOfflineMapShare) {
                    maybeNotifyIncomingMessage(sessionCode, MessageType.IMAGE, null)
                }
                true
            }

            "image:chunk" -> {
                val index = json.optInt("index", -1)
                val crc32 = json.optLong("crc32", -1)
                val payloadB64 = json.optString("payloadB64")
                if (index < 0 || crc32 < 0 || payloadB64.isNullOrBlank()) {
                    return false
                }
                val bytes = runCatching { Base64.decode(payloadB64, Base64.NO_WRAP) }.getOrNull()
                    ?: return false
                val transferKey = mapShareReceivers[mapShareTransferKey]
                    ?.let { mapShareTransferKey }
                    ?: imageReceivers[imageTransferKey]?.let { imageTransferKey }
                val receiver = transferKey?.let { key ->
                    touchIncomingTransfer(key)
                    mapShareReceivers[mapShareTransferKey] ?: imageReceivers[imageTransferKey]
                }
                if (receiver == null) {
                    Log.w(
                        TAG,
                        "Dropped image:chunk without receiver uuid=$uuid session=$sessionCode index=$index"
                    )
                } else {
                    receiver.handleChunk(index, crc32, bytes)
                }
                true
            }

            "image:finish" -> {
                val transferKey = mapShareReceivers[mapShareTransferKey]
                    ?.let { mapShareTransferKey }
                    ?: imageReceivers[imageTransferKey]?.let { imageTransferKey }
                val receiver = transferKey?.let { key ->
                    touchIncomingTransfer(key)
                    mapShareReceivers[mapShareTransferKey] ?: imageReceivers[imageTransferKey]
                }
                if (receiver == null) {
                    Log.w(TAG, "Dropped image:finish without receiver uuid=$uuid session=$sessionCode")
                } else {
                    receiver.handleFinish()
                }
                true
            }

            "image:ack" -> {
                val receivedUpTo = json.optInt("receivedUpTo", -1)
                val missing = json.optJSONArray("missing")
                val missingList = mutableListOf<Int>()
                if (missing != null) {
                    for (i in 0 until missing.length()) {
                        missingList.add(missing.optInt(i))
                    }
                }
                val sender = mapShareSenders[mapShareTransferKey] ?: imageSenders[imageTransferKey]
                if (sender == null) {
                    Log.w(TAG, "Dropped image:ack without sender uuid=$uuid session=$sessionCode")
                } else {
                    sender.handleAck(receivedUpTo, missingList)
                }
                true
            }

            "image:resume" -> {
                val wantArray = json.optJSONArray("want")
                val wants = mutableListOf<Int>()
                if (wantArray != null) {
                    for (i in 0 until wantArray.length()) {
                        wants.add(wantArray.optInt(i))
                    }
                }
                val sender = mapShareSenders[mapShareTransferKey] ?: imageSenders[imageTransferKey]
                if (sender == null) {
                    Log.w(TAG, "Dropped image:resume without sender uuid=$uuid session=$sessionCode")
                } else {
                    sender.handleResume(wants)
                }
                true
            }

            "image:verify_ok" -> {
                val sender = mapShareSenders[mapShareTransferKey] ?: imageSenders[imageTransferKey]
                if (sender == null) {
                    Log.w(TAG, "Dropped image:verify_ok without sender uuid=$uuid session=$sessionCode")
                } else {
                    sender.handleVerify(true)
                }
                true
            }

            "image:verify_fail" -> {
                val sender = mapShareSenders[mapShareTransferKey] ?: imageSenders[imageTransferKey]
                if (sender == null) {
                    Log.w(TAG, "Dropped image:verify_fail without sender uuid=$uuid session=$sessionCode")
                } else {
                    sender.handleVerify(false)
                }
                true
            }

            else -> false
        }
    }

    private fun reserveIncomingTransfer(
        sessionCode: String,
        transferKey: String,
        typeLabel: String
    ): Boolean {
        synchronized(incomingTransferLock) {
            if (incomingTransfers.containsKey(transferKey)) {
                Log.w(TAG, "Ignoring duplicate incoming $typeLabel transfer key=$transferKey")
                return false
            }
            val sessionActiveCount = incomingTransfers.values.count { it.sessionCode == sessionCode }
            if (incomingTransfers.size >= MAX_ACTIVE_INCOMING_TRANSFERS_TOTAL ||
                sessionActiveCount >= MAX_ACTIVE_INCOMING_TRANSFERS_PER_SESSION
            ) {
                Log.w(
                    TAG,
                    "Rejecting incoming $typeLabel transfer for session=$sessionCode " +
                        "(activeTotal=${incomingTransfers.size}, activeSession=$sessionActiveCount)"
                )
                return false
            }
            incomingTransfers[transferKey] = IncomingTransferReservation(
                sessionCode = sessionCode,
                typeLabel = typeLabel,
                timeoutJob = createIncomingTransferTimeoutJob(transferKey)
            )
        }
        return true
    }

    private fun attachIncomingTransfer(
        transferKey: String,
        cancelReceiver: () -> Unit
    ): Boolean {
        synchronized(incomingTransferLock) {
            val reservation = incomingTransfers[transferKey] ?: return false
            reservation.cancelReceiver = cancelReceiver
            reservation.timeoutJob.cancel()
            reservation.timeoutJob = createIncomingTransferTimeoutJob(transferKey)
            return true
        }
    }

    private fun touchIncomingTransfer(transferKey: String) {
        synchronized(incomingTransferLock) {
            val reservation = incomingTransfers[transferKey] ?: return
            reservation.timeoutJob.cancel()
            reservation.timeoutJob = createIncomingTransferTimeoutJob(transferKey)
        }
    }

    private fun unregisterIncomingTransfer(transferKey: String) {
        synchronized(incomingTransferLock) {
            incomingTransfers.remove(transferKey)?.timeoutJob?.cancel()
        }
    }

    private fun createIncomingTransferTimeoutJob(transferKey: String): Job {
        return serviceScope.launch {
            delay(INCOMING_TRANSFER_IDLE_TIMEOUT_MS)
            expireIncomingTransfer(transferKey)
        }
    }

    private fun expireIncomingTransfer(transferKey: String) {
        val reservation = synchronized(incomingTransferLock) {
            incomingTransfers.remove(transferKey)?.also { it.timeoutJob.cancel() }
        } ?: return
        Log.w(
            TAG,
            "Cancelling stale incoming ${reservation.typeLabel} transfer for session=${reservation.sessionCode}"
        )
        reservation.cancelReceiver?.invoke()
    }

    private companion object {
        private const val TAG = "RfcommService"
        private val MAC_ADDRESS_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        private const val INCOMING_TRANSFER_IDLE_TIMEOUT_MS = 90_000L
        private const val MAX_ACTIVE_INCOMING_TRANSFERS_PER_SESSION = 6
        private const val MAX_ACTIVE_INCOMING_TRANSFERS_TOTAL = 24
    }
}

private data class IncomingTransferReservation(
    val sessionCode: String,
    val typeLabel: String,
    var timeoutJob: Job,
    var cancelReceiver: (() -> Unit)? = null
)
