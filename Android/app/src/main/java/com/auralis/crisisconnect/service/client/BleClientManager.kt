package com.auralis.crisisconnect.service.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.data.BleSessionResolver
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * Coordinates a pool of [ClientConnection] instances. The manager keeps connections alive while the
 * foreground service is running and exposes Flows for UI components.
 */
class BleClientManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val connections = ConcurrentHashMap<String, ClientConnection>()
    private val lastStates = ConcurrentHashMap<String, ConnectionState>()
    private val sessionAliases = ConcurrentHashMap<String, String>()

    private val _incomingMessages = MutableSharedFlow<IncomingBleMessage>(extraBufferCapacity = 32)
    val incomingMessages: Flow<IncomingBleMessage> = _incomingMessages.asSharedFlow()

    private val _connectionStates = MutableSharedFlow<ConnectionState>(replay = 1, extraBufferCapacity = 8)
    val connectionStates: Flow<ConnectionState> = _connectionStates.asSharedFlow()

    init {
        scope.launch(Dispatchers.Default) {
            _connectionStates.collect { state ->
                val normalized = normalizeAddress(state.address)
                lastStates[normalized] = state.copy(address = normalized)
            }
        }
    }

    fun connectTo(address: String) {
        val normalized = normalizeAddress(address)
        lastStates[normalized]?.let { current ->
            emitState(current.copy(address = normalized))
        }
        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "Bluetooth adapter unavailable; dropping connection request for $normalized")
            emitState(ConnectionState(normalized, ConnectionStatus.Failed, reason = "ADAPTER_UNAVAILABLE"))
            return
        }
        val device = runCatching { adapter.getRemoteDevice(normalized) }.getOrNull()
        if (device == null) {
            emitState(ConnectionState(normalized, ConnectionStatus.Failed, reason = "UNKNOWN_DEVICE"))
            return
        }
        val connection = connections.getOrPut(normalized) {
            ClientConnection(
                context = appContext,
                device = device,
                scope = scope,
                messageSink = _incomingMessages,
                stateSink = _connectionStates,
            ) { removedAddress ->
                connections.remove(removedAddress)
            }
        }
        sessionAliases[normalized]?.let { alias ->
            connection.registerSessionAlias(alias)
        }
        scope.launch {
            BleConnectQueue.enqueue(normalized) {
                connection.connect()
            }
        }
    }

    fun disconnect(address: String) {
        val normalized = normalizeAddress(address)
        connections[normalized]?.disconnect(manual = true)
    }

    fun shutdown() {
        connections.values.forEach { it.disconnect(manual = true) }
        connections.clear()
        lastStates.clear()
    }

    fun sendMessage(address: String, text: String) {
        val normalized = normalizeAddress(address)
        val connection = connections[normalized] ?: run {
            emitSendFailureState(normalized, "NO_CONNECTION")
            requestConnectionRecovery(normalized)
            return
        }
        scope.launch {
            val sent = runCatching {
                connection.sendMessage(text)
                true
            }.getOrElse { throwable ->
                Log.w(TAG, "Failed to send message to $normalized", throwable)
                emitSendFailureState(normalized, "SEND_FAILED")
                requestConnectionRecovery(normalized)
                false
            }
            if (!sent) {
                return@launch
            }
        }
    }

    suspend fun sendMessageWithoutAck(address: String, text: String): Boolean {
        val normalized = normalizeAddress(address)
        val connection = connections[normalized] ?: return false
        if (!scope.isActive) {
            return false
        }
        return runCatching {
            connection.sendMessageWithoutAck(text)
            true
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to send message without ack to $normalized", throwable)
            emitSendFailureState(normalized, "SEND_FAILED")
            requestConnectionRecovery(normalized)
            false
        }
    }

    suspend fun sendMessageAwait(address: String, text: String): Boolean {
        val normalized = normalizeAddress(address)
        val connection = connections[normalized] ?: run {
            emitSendFailureState(normalized, "NO_CONNECTION")
            requestConnectionRecovery(normalized)
            return false
        }
        return runCatching {
            connection.sendMessage(text)
            true
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to send message (await) to $normalized", throwable)
            emitSendFailureState(normalized, "SEND_FAILED")
            requestConnectionRecovery(normalized)
            false
        }
    }

    fun sendReadReceipt(address: String, messageIds: Collection<String>) {
        val normalized = normalizeAddress(address)
        val connection = connections[normalized] ?: run {
            emitSendFailureState(normalized, "NO_CONNECTION")
            requestConnectionRecovery(normalized)
            return
        }
        scope.launch {
            runCatching {
                connection.sendReadReceipt(messageIds)
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to send read receipt to $normalized", throwable)
                emitSendFailureState(normalized, "SEND_FAILED")
                requestConnectionRecovery(normalized)
            }
        }
    }

    suspend fun sendReadReceiptAwait(address: String, messageIds: Collection<String>): Boolean {
        val normalized = normalizeAddress(address)
        val connection = connections[normalized] ?: run {
            emitSendFailureState(normalized, "NO_CONNECTION")
            requestConnectionRecovery(normalized)
            return false
        }
        return runCatching {
            connection.sendReadReceipt(messageIds)
            true
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to send read receipt (await) to $normalized", throwable)
            emitSendFailureState(normalized, "SEND_FAILED")
            requestConnectionRecovery(normalized)
            false
        }
    }

    private fun requestConnectionRecovery(normalizedAddress: String) {
        scope.launch {
            connectTo(normalizedAddress)
        }
    }

    private fun emitSendFailureState(normalizedAddress: String, reason: String) {
        val current = lastStates[normalizedAddress]
        if (current != null) {
            emitState(current.copy(reason = reason))
        } else {
            emitState(ConnectionState(normalizedAddress, ConnectionStatus.Failed, reason = reason))
        }
    }

    fun registerSessionAlias(address: String, sessionCode: String) {
        val normalizedAddress = normalizeAddress(address)
        val normalizedSessionCode = BleSessionResolver.normalizeSessionCode(sessionCode)
            ?: sessionCode.trim()
        if (normalizedAddress.isBlank() || normalizedSessionCode.isBlank()) {
            return
        }
        sessionAliases[normalizedAddress] = normalizedSessionCode
        connections[normalizedAddress]?.registerSessionAlias(normalizedSessionCode)
    }

    private fun emitState(state: ConnectionState) {
        scope.launch {
            _connectionStates.emit(state)
        }
    }

    fun currentState(address: String): ConnectionState? {
        val normalized = normalizeAddress(address)
        return lastStates[normalized]
    }

    private fun normalizeAddress(address: String): String {
        return address.trim().uppercase(Locale.US)
    }

    data class ConnectionState(
        val address: String,
        val status: ConnectionStatus,
        val userId: String? = null,
        val deviceName: String? = null,
        val peerBatteryPercent: Int? = null,
        val reason: String? = null,
    )

    enum class ConnectionStatus {
        Connecting,
        Connected,
        Discovering,
        Authenticating,
        Ready,
        Reconnecting,
        Disconnected,
        Failed,
    }

    companion object {
        private const val TAG = "BleClientManager"
    }
}
