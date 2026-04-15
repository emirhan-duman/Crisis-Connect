package com.auralis.crisisconnect.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Helper that observes an already running [GattSOSServerService] without starting a new instance.
 * The binding exposes the service reference to view models via a [StateFlow].
 */
class SosServerServiceBinding(context: Context) {

    private val appContext = context.applicationContext
    private val _service = MutableStateFlow<GattSOSServerService?>(null)
    val service: StateFlow<GattSOSServerService?> = _service.asStateFlow()

    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? GattSOSServerService.LocalBinder
            if (localBinder == null) {
                isBound = false
                _service.value = null
                return
            }
            isBound = true
            _service.value = localBinder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            _service.value = null
        }
    }

    fun bind() {
        if (isBound) return
        val intent = Intent(appContext, GattSOSServerService::class.java)
        val bound = appContext.bindService(intent, serviceConnection, 0)
        isBound = bound
        if (!bound) {
            _service.value = null
        }
    }

    fun unbind() {
        if (!isBound) return
        runCatching { appContext.unbindService(serviceConnection) }
        isBound = false
        _service.value = null
    }
}
