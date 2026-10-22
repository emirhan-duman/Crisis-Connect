package com.auralis.crisisconnect.screens.QR

import com.auralis.crisisconnect.data.DeviceError
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.coroutines.CoroutineContext

internal data class QrPairingState(
    val id: String? = null,
    val running: Boolean = false,
    val stage: QrConnectionStage? = null,
    val result: QrConnectResult? = null
)

/** App-owned operation. Collectors and Activity lifetimes never own its coroutine scope. */
internal class QrPairingOperation(
    private val dispatcher: CoroutineContext,
    private val beginLease: () -> Unit,
    private val endLease: () -> Unit
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(QrPairingState())
    val state: StateFlow<QrPairingState> = mutableState.asStateFlow()
    private var operationScope: CoroutineScope? = null
    private var activeID: String? = null

    fun start(engine: (CoroutineScope, (QrConnectionStage) -> Unit, (QrConnectResult) -> Unit) -> Unit): Boolean = synchronized(lock) {
        if (mutableState.value.running || mutableState.value.result != null) return false
        val id = UUID.randomUUID().toString()
        val handler = CoroutineExceptionHandler { _, _ -> finish(id, QrConnectResult.Failure(DeviceError.PAIRING_FAILED)) }
        val scope = CoroutineScope(SupervisorJob() + dispatcher + handler)
        operationScope = scope
        activeID = id
        mutableState.value = QrPairingState(id, true, QrConnectionStage.SEARCHING)
        try {
            beginLease()
            engine(scope, { stage ->
                synchronized(lock) {
                    if (activeID == id && mutableState.value.running) {
                        mutableState.value = mutableState.value.copy(stage = stage)
                    }
                }
            }, { result -> finish(id, result) })
        } catch (_: Exception) {
            finish(id, QrConnectResult.Failure(DeviceError.PAIRING_FAILED))
        }
        true
    }

    private fun finish(id: String, result: QrConnectResult): Unit = synchronized(lock) {
        if (activeID != id) return
        val terminal = mutableState.value.copy(running = false, result = result)
        activeID = null
        val finishedScope = operationScope
        operationScope = null
        finishedScope?.cancel()
        // Finish cleanup before notifying collectors; a collector can consume the result and
        // synchronously start a new operation. Old cleanup must never cancel that new scope.
        runCatching { endLease() }
        mutableState.value = terminal
    }

    fun failLease(code: DeviceError) {
        val id = mutableState.value.id ?: return
        finish(id, QrConnectResult.Failure(code))
    }

    fun consumeSuccess(id: String?): Boolean = synchronized(lock) {
        if (mutableState.value.id != id || mutableState.value.result !is QrConnectResult.Saved) return false
        mutableState.value = QrPairingState()
        true
    }

    fun acknowledgeError() = synchronized(lock) {
        if (!mutableState.value.running && mutableState.value.result !is QrConnectResult.Saved) {
            mutableState.value = QrPairingState()
        }
    }
}
