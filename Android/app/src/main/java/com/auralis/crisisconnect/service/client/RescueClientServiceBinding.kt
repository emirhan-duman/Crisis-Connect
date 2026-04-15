package com.auralis.crisisconnect.service.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.auralis.crisisconnect.feature.RescueFeatureManager
import com.auralis.crisisconnect.security.SecurityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Helper that keeps track of the [BleClientManager] exposed by [GattRescueClientService].
 * ViewModels can bind to the foreground service and observe the manager instance via a [StateFlow].
 */
class RescueClientServiceBinding(context: Context) {

    private val appContext = context.applicationContext
    private val rescueFeatureManager = RescueFeatureManager(appContext)
    private val _manager = MutableStateFlow<BleClientManager?>(null)
    val manager: StateFlow<BleClientManager?> = _manager.asStateFlow()

    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val manager = runCatching {
                service
                    ?.javaClass
                    ?.getMethod("getManager")
                    ?.invoke(service) as? BleClientManager
            }.getOrNull()
            if (manager == null) {
                isBound = false
                _manager.value = null
                return
            }
            isBound = true
            _manager.value = manager
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _manager.value = null
            isBound = false
        }
    }

    fun bind() {
        if (!hasRescueAccess()) {
            unbind()
            return
        }
        if (!rescueFeatureManager.isInstalled()) {
            _manager.value = null
            return
        }
        if (isBound) return
        val intent = Intent().setClassName(
            appContext.packageName,
            RescueFeatureManager.RESCUE_CLIENT_SERVICE_CLASS_NAME
        )
        rescueFeatureManager.startRescueClientService()
        val bound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBound = bound
        if (!bound) {
            _manager.value = null
        }
    }

    fun unbind() {
        if (isBound) {
            runCatching { appContext.unbindService(serviceConnection) }
        }
        isBound = false
        _manager.value = null
    }

    private fun hasRescueAccess(): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                SecurityRepository(appContext).hasUsableStoredCertificate(allowExpired = true)
            }
        }.getOrDefault(false)
    }
}
