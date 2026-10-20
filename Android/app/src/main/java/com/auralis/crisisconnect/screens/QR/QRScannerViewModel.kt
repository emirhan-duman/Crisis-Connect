package com.auralis.crisisconnect.screens.QR

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.DeviceError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ScanError {
    INVALID_QR,
    MISSING_PERMISSIONS,
    DEVICE_NOT_FOUND,
    HANDSHAKE_FAILED,
    PAIRING_FAILED,
    ALREADY_REGISTERED,
    BLUETOOTH_OFF
}

enum class ScanStatusStage {
    SCANNED,
    SEARCHING,
    PAIRING,
    SAVING,
    SAVED,
    ALREADY_REGISTERED,
    ERROR
}

data class ScanStatusMessage(
    val text: String,
    val isLoading: Boolean,
    val stage: ScanStatusStage
)

class QrScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val coordinator = QrPairingCoordinator.get(application)
    internal val connectionState = coordinator.state

    internal fun consumeSuccess(id: String?) = coordinator.consumeSuccess(id)

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission

    private val _showNameDialog = MutableStateFlow(false)
    val showNameDialog: StateFlow<Boolean> = _showNameDialog

    private val _manualName = MutableStateFlow("")
    val manualName: StateFlow<String> = _manualName

    private val _error = MutableStateFlow<ScanError?>(null)
    val error: StateFlow<ScanError?> = _error

    private val _statusMessage = MutableStateFlow<ScanStatusMessage?>(null)
    val statusMessage: StateFlow<ScanStatusMessage?> = _statusMessage

    private val _pendingQr = MutableStateFlow<PendingQr?>(null)

    private data class PendingQr(val raw: String, val data: QrData)

    init {
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state.running) {
                    _showNameDialog.value = false
                    _statusMessage.value = when (state.stage) {
                        QrConnectionStage.PAIRING -> ScanStatusMessage(getString(R.string.qr_scan_status_pairing), true, ScanStatusStage.PAIRING)
                        QrConnectionStage.SAVING -> ScanStatusMessage(getString(R.string.qr_scan_status_saving), true, ScanStatusStage.SAVING)
                        else -> ScanStatusMessage(getString(R.string.qr_scan_status_scanned_searching), true, ScanStatusStage.SEARCHING)
                    }
                }
                when (val result = state.result) {
                    is QrConnectResult.Saved -> {
                        _pendingQr.value = null
                        _manualName.value = ""
                        _statusMessage.value = ScanStatusMessage(getString(R.string.qr_scan_status_saved), false, ScanStatusStage.SAVED)
                    }
                    is QrConnectResult.AlreadyRegistered -> {
                        _pendingQr.value = null
                        _manualName.value = ""
                        setError(ScanError.ALREADY_REGISTERED)
                    }
                    is QrConnectResult.Failure -> setError(when (result.code) {
                        DeviceError.MISSING_PERMISSIONS -> ScanError.MISSING_PERMISSIONS
                        DeviceError.DEVICE_NOT_FOUND -> ScanError.DEVICE_NOT_FOUND
                        DeviceError.HANDSHAKE_FAILED -> ScanError.HANDSHAKE_FAILED
                        DeviceError.PAIRING_FAILED -> ScanError.PAIRING_FAILED
                        DeviceError.BLUETOOTH_OFF -> ScanError.BLUETOOTH_OFF
                    })
                    null -> Unit
                }
            }
        }
    }


    private fun getString(resId: Int): String = getApplication<Application>().getString(resId)

    fun setPermission(granted: Boolean) {
        _hasCameraPermission.value = granted
    }

    fun onQrResult(context: Context, raw: String) {
        if (connectionState.value.running || connectionState.value.result != null || _showNameDialog.value) return
        Log.d("QRScannerViewModel", "QR scanned, raw data length: ${raw.length}")
        val data = runCatching { parseQrPayload(raw) }.onFailure {
            Log.e("QRScannerViewModel", "Failed to parse QR data", it)
        }.getOrElse {
            reportInvalidQr()
            return
        }
        Log.d(
            "QRScannerViewModel",
            "QR parsed - sessionCode: ${data.sessionCode}, name: ${data.name}"
        )

        val name = data.name?.takeIf { it.isNotBlank() }
        if (name == null) {
            Log.d("QRScannerViewModel", "Name is missing, showing dialog")
            _pendingQr.value = PendingQr(raw = raw, data = data)
            _manualName.value = ""
            _statusMessage.value = ScanStatusMessage(
                text = getString(R.string.qr_scan_status_scanned_enter_name),
                isLoading = false,
                stage = ScanStatusStage.SCANNED
            )
            _showNameDialog.value = true
            return
        }

        Log.d("QRScannerViewModel", "Starting connection with name: $name")
        startConnection(context, raw, name)
    }

    private fun startConnection(context: Context, raw: String, overrideName: String?) {
        _showNameDialog.value = false
        coordinator.start(context, raw, overrideName)
    }

    fun updateManualName(value: String) {
        _manualName.value = value
    }

    fun saveManualContact(context: Context) {
        val pending = _pendingQr.value ?: return
        val finalName = _manualName.value.trim().ifEmpty { pending.data.sessionCode }
        _manualName.value = finalName
        startConnection(context, pending.raw, finalName)
    }

    fun dismissError() {
        coordinator.acknowledgeError()
        _error.value = null
    }

    fun dismissNameDialog() {
        _showNameDialog.value = false
        _statusMessage.value = null
    }

    fun reportInvalidQr() {
        setError(ScanError.INVALID_QR)
    }

    fun reportMissingPermissions() {
        setError(ScanError.MISSING_PERMISSIONS)
    }

    private fun setError(error: ScanError) {
        _error.value = error
        val message = when (error) {
            ScanError.INVALID_QR -> getString(R.string.qr_scan_error_invalid_qr)
            ScanError.MISSING_PERMISSIONS -> getString(R.string.qr_scan_error_missing_permissions)
            ScanError.DEVICE_NOT_FOUND -> getString(R.string.qr_scan_error_device_not_found)
            ScanError.HANDSHAKE_FAILED -> getString(R.string.qr_scan_error_handshake_failed)
            ScanError.PAIRING_FAILED -> getString(R.string.qr_scan_error_pairing_failed)
            ScanError.ALREADY_REGISTERED -> getString(R.string.qr_scan_status_already_registered)
            ScanError.BLUETOOTH_OFF -> getString(R.string.qr_scan_error_bluetooth_off)
        }
        val stage = when (error) {
            ScanError.ALREADY_REGISTERED -> ScanStatusStage.ALREADY_REGISTERED
            else -> ScanStatusStage.ERROR
        }
        _statusMessage.value = ScanStatusMessage(
            text = message,
            isLoading = false,
            stage = stage
        )
    }
}
