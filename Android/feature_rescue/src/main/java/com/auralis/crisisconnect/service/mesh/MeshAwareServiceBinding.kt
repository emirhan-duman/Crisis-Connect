package com.auralis.crisisconnect.service.mesh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Binds to [MeshAwareService] and mirrors its state into a local [StateFlow].
 */
class MeshAwareServiceBinding(context: Context) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(MeshAwareServiceState())
    val state: StateFlow<MeshAwareServiceState> = _state.asStateFlow()

    private var service: MeshAwareService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollector: Job? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? MeshAwareService.LocalBinder
            if (localBinder == null) {
                isBound = false
                return
            }
            isBound = true
            service = localBinder.getService()
            stateCollector?.cancel()
            stateCollector = scope.launch {
                service?.state?.collect {
                    _state.value = it
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateCollector?.cancel()
            stateCollector = null
            isBound = false
            service = null
            _state.update {
                it.copy(isEnabled = false, isBusy = false, connectedPeerCount = 0)
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            super.onNullBinding(name)
            isBound = false
            service = null
            stateCollector?.cancel()
            stateCollector = null
            _state.update {
                it.copy(isEnabled = false, isBusy = false, connectedPeerCount = 0)
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            onServiceDisconnected(name)
        }
    }

    fun bind(createIfNeeded: Boolean = true) {
        tryBind(createIfNeeded = createIfNeeded)
    }

    fun unbind() {
        if (!isBound) return
        runCatching { appContext.unbindService(serviceConnection) }
        stateCollector?.cancel()
        stateCollector = null
        isBound = false
        service = null
        _state.update {
            it.copy(isEnabled = false, isBusy = false, connectedPeerCount = 0, errorMessage = null)
        }
    }

    fun setMeshEnabled(enabled: Boolean) {
        if (!isMeshSupportedByPlatform()) {
            _state.update {
                it.copy(
                    isEnabled = false,
                    isBusy = false,
                    connectedPeerCount = 0,
                    errorMessage = R.string.rescue_mesh_error_unsupported,
                )
            }
            return
        }

        val commandIntent = Intent(appContext, MeshAwareService::class.java).apply {
            action = MeshAwareService.ACTION_SET_MESH_ENABLED
            putExtra(MeshAwareService.EXTRA_MESH_ENABLED, enabled)
        }

        if (enabled) {
            ContextCompat.startForegroundService(appContext, commandIntent)
        } else if (_state.value.isEnabled || _state.value.isBusy || service != null) {
            ContextCompat.startForegroundService(appContext, commandIntent)
        }

        tryBind(createIfNeeded = enabled)
        if (enabled) {
            _state.update { it.copy(isBusy = true, errorMessage = null) }
        }

        service?.setMeshEnabled(enabled) ?: run {
            if (enabled || _state.value.isEnabled || _state.value.isBusy) {
                ContextCompat.startForegroundService(appContext, commandIntent)
            }
            tryBind(createIfNeeded = enabled)
        }

        if (!enabled) {
            _state.update {
                it.copy(isEnabled = false, isBusy = false, connectedPeerCount = 0, errorMessage = null)
            }
        }
    }

    private fun tryBind(createIfNeeded: Boolean = true): Boolean {
        if (!isMeshSupportedByPlatform()) {
            return false
        }
        if (isBound) {
            return true
        }
        val intent = Intent(appContext, MeshAwareService::class.java)
        val flags = if (createIfNeeded) Context.BIND_AUTO_CREATE else 0
        val bound = appContext.bindService(intent, serviceConnection, flags)
        isBound = bound
        return bound
    }

    fun clearPeerAuthEvent() {
        service?.clearPeerAuthEvent()
    }

    fun sendGroupMessage(message: String): Boolean {
        return service?.sendGroupMessage(message) ?: false
    }

    private fun isMeshSupportedByPlatform(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }
        return appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }

    private companion object
}
