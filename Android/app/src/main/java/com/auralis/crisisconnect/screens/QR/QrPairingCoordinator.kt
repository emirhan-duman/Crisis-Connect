package com.auralis.crisisconnect.screens.QR

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.service.QrPairingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.google.firebase.auth.FirebaseAuth
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

internal class QrPairingCoordinator private constructor(context: Context) {
    private val app = context.applicationContext
    private val recovery = QrPairingRecovery(EncryptedQrPairingJournal(app))
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recoveryChecked = false
    private var recovering = false
    private val operation = QrPairingOperation(Dispatchers.Main.immediate, beginLease = {
        // connectedDevice FGS requires Bluetooth permission. Internet-only fallbacks remain usable
        // without that permission; no Bluetooth operation is attempted by the engine in that case.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            ContextCompat.startForegroundService(app, Intent(app, QrPairingForegroundService::class.java))
        }
    }, endLease = { app.stopService(Intent(app, QrPairingForegroundService::class.java)) })
    val state = operation.state

    fun start(context: Context, raw: String, name: String?): Boolean {
        if (recovering || state.value.running || state.value.result != null) return false
        recoveryChecked = true
        return launchPairing(context, raw, name)
    }

    private fun owner(): String = LocalKeyStorage.getOrCreateP2pSessionCode(app) + ":" +
        (FirebaseAuth.getInstance().currentUser?.uid ?: "")

    /** Called only after the Activity is foreground, never from a boot/background receiver. */
    fun resumePending(context: Context) {
        if (recoveryChecked || recovering || state.value.running || state.value.result != null) return
        recovering = true
        val uiContext = WeakReference(context)
        recoveryScope.launch {
            try {
                val identity = owner()
                val pending = withContext(Dispatchers.IO) {
                    QrShareRecovery.restore(app)
                    recovery.recover(identity)
                }
                if (owner() != identity) { recovery.clear(); return@launch }
                recoveryChecked = true
                if (pending != null) launchPairing(uiContext.get() ?: app, pending.raw, pending.name, pending)
            } catch (_: Exception) {
                // Keystore can be temporarily unavailable. Preserve the intent for the next open.
            } finally { recovering = false }
        }
    }

    private fun launchPairing(context: Context, raw: String, name: String?, resumed: QrPairingCheckpoint? = null): Boolean {
        val uiContext = WeakReference(context)
        val identity = owner()
        return operation.start { scope, status, result ->
            val completionClaimed = AtomicBoolean(false)
            scope.launch {
                val checkpoint = withContext(Dispatchers.IO) { resumed ?: recovery.begin(raw, name, identity) }
                if (owner() != identity) { recovery.clear(); result(QrConnectResult.Failure(com.auralis.crisisconnect.data.DeviceError.PAIRING_FAILED)); return@launch }
                connectUsingQr(requestContext = uiContext.get() ?: app, rawQrText = raw,
                    overrideName = name, operationScope = scope, onStatus = status,
                    recoveringSession = resumed?.savedSession, isRecovery = resumed != null,
                    onResult = { completed ->
                        if (completionClaimed.compareAndSet(false, true)) scope.launch {
                            if (owner() != identity) {
                                recovery.clear()
                                result(QrConnectResult.Failure(com.auralis.crisisconnect.data.DeviceError.PAIRING_FAILED))
                                return@launch
                            }
                            val session = when (completed) {
                                is QrConnectResult.Saved -> completed.sessionCode
                                is QrConnectResult.AlreadyRegistered -> completed.sessionCode
                                else -> null
                            }
                            if (session != null) withContext(Dispatchers.IO) { recovery.complete(checkpoint, session) }
                            result(if (resumed != null && session != null) QrConnectResult.Saved(session) else completed)
                        }
                    })
            }
        }
    }
    fun consumeSuccess(id: String?): Boolean {
        if (state.value.id != id || state.value.result !is QrConnectResult.Saved) return false
        if (runCatching { recovery.clear() }.isFailure) return false
        return operation.consumeSuccess(id)
    }
    fun acknowledgeError() {
        if (!state.value.running) { runCatching { recovery.clear() }; operation.acknowledgeError() }
    }
    fun failLease(code: com.auralis.crisisconnect.data.DeviceError) = operation.failLease(code)

    companion object {
        @Volatile private var instance: QrPairingCoordinator? = null
        fun get(context: Context): QrPairingCoordinator = instance ?: synchronized(this) {
            instance ?: QrPairingCoordinator(context).also { instance = it }
        }
    }
}
