package com.auralis.crisisconnect.service.gattmesh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GattMeshServiceBinding(
    context: Context,
    private val controller: MeshServiceController = PublicMeshServiceController,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(GattMeshServiceState())
    val state: StateFlow<GattMeshServiceState> = _state.asStateFlow()

    private var service: GattMeshForegroundService? = null
    private var stateCollector: Job? = null
    private var bindRetryJob: Job? = null
    private var stateWatchdogJob: Job? = null
    private var isBound = false
    private var keepBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? GattMeshForegroundService.LocalBinder
            if (localBinder == null) {
                isBound = false
                service = null
                scheduleRebind()
                return
            }
            bindRetryJob?.cancel()
            bindRetryJob = null
            isBound = true
            service = localBinder.getService()
            stateCollector?.cancel()
            stateCollector = scope.launch {
                service?.state?.collect { current ->
                    _state.value = current
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateCollector?.cancel()
            stateCollector = null
            service = null
            isBound = false
            if (!keepBound) {
                _state.update {
                    it.copy(
                        isEnabled = false,
                        isScanning = false,
                        connectedPeerCount = 0,
                        discoveredPeerCount = 0,
                        sendReadyPeerCount = 0,
                        connectedPeers = emptyList()
                    )
                }
            }
            scheduleRebind()
        }

        override fun onNullBinding(name: ComponentName?) {
            onServiceDisconnected(name)
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }
    }

    fun bind(createIfNeeded: Boolean = true) {
        keepBound = true
        tryBind(createIfNeeded)
        startStateWatchdog()
        scheduleRebind()
    }

    fun unbind() {
        keepBound = false
        bindRetryJob?.cancel()
        bindRetryJob = null
        stateWatchdogJob?.cancel()
        stateWatchdogJob = null
        if (isBound) {
            runCatching { appContext.unbindService(serviceConnection) }
        }
        stateCollector?.cancel()
        stateCollector = null
        service = null
        isBound = false
        _state.value = GattMeshServiceState()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            keepBound = true
            controller.start(appContext)
            tryBind(createIfNeeded = true)
            startStateWatchdog()
            scheduleRebind()
            return
        }

        keepBound = false
        bindRetryJob?.cancel()
        bindRetryJob = null
        stateWatchdogJob?.cancel()
        stateWatchdogJob = null
        controller.stop(appContext)
        _state.update {
            it.copy(
                isEnabled = false,
                isScanning = false,
                connectedPeerCount = 0,
                discoveredPeerCount = 0,
                sendReadyPeerCount = 0,
                connectedPeers = emptyList(),
                errorMessage = null,
            )
        }
    }

    fun sendGroupMessage(
        message: String,
        messageId: String? = null
    ): GattMeshSendResult? {
        // Caller can provide a stable message ID so retries keep receipt correlation.
        service?.let { return it.sendGroupMessage(message, messageId) }
        if (keepBound && !isBound) {
            tryBind(createIfNeeded = true)
        }
        return service?.sendGroupMessage(message, messageId)
    }

    fun sendImageMessage(uri: Uri): String? {
        service?.let { return it.sendImageMessage(uri) }
        if (keepBound && !isBound) {
            tryBind(createIfNeeded = true)
        }
        return service?.sendImageMessage(uri)
    }

    fun sendVoiceMessage(audioFile: java.io.File, durationMillis: Long): String? {
        service?.let { return it.sendVoiceMessage(audioFile, durationMillis) }
        if (keepBound && !isBound) {
            tryBind(createIfNeeded = true)
        }
        return service?.sendVoiceMessage(audioFile, durationMillis)
    }

    fun sendReadReceipt(messageIds: Collection<String>): Boolean {
        service?.let { return it.sendReadReceipt(messageIds) }
        if (keepBound && !isBound) {
            tryBind(createIfNeeded = true)
        }
        return service?.sendReadReceipt(messageIds) ?: false
    }

    private fun tryBind(createIfNeeded: Boolean): Boolean {
        if (isBound) {
            return true
        }
        if (createIfNeeded) {
            controller.start(appContext)
        }
        val intent = Intent(appContext, controller.serviceClass)
        val flags = if (createIfNeeded) Context.BIND_AUTO_CREATE else 0
        val bound = appContext.bindService(intent, serviceConnection, flags)
        isBound = bound
        return bound
    }

    private fun scheduleRebind() {
        if (!keepBound || isBound) {
            return
        }
        if (bindRetryJob?.isActive == true) {
            return
        }
        bindRetryJob = scope.launch {
            repeat(BIND_RETRY_ATTEMPTS) { attempt ->
                if (!keepBound || isBound) {
                    return@launch
                }
                delay(BIND_RETRY_BASE_DELAY_MS * (attempt + 1))
                tryBind(createIfNeeded = true)
            }
        }
    }

    private fun startStateWatchdog() {
        if (!keepBound || stateWatchdogJob?.isActive == true) {
            return
        }
        stateWatchdogJob = scope.launch {
            while (keepBound) {
                val directState = controller.currentStateSnapshot()
                if (directState != null) {
                    _state.value = directState
                    if (!isBound) {
                        tryBind(createIfNeeded = false)
                    }
                } else {
                    if (_state.value != GattMeshServiceState()) {
                        _state.value = GattMeshServiceState()
                    }
                    if (!isBound && !controller.isRunning()) {
                        tryBind(createIfNeeded = true)
                    }
                }
                delay(STATE_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private companion object {
        private const val BIND_RETRY_ATTEMPTS = 6
        private const val BIND_RETRY_BASE_DELAY_MS = 250L
        private const val STATE_WATCHDOG_INTERVAL_MS = 750L
    }
}
