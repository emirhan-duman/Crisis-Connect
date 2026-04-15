package com.auralis.crisisconnect.screens.QR

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
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
    ALREADY_REGISTERED
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

    private fun getString(resId: Int): String = getApplication<Application>().getString(resId)

    fun setPermission(granted: Boolean) {
        _hasCameraPermission.value = granted
    }

    fun onQrResult(context: Context, raw: String, onSuccess: () -> Unit) {
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
        startConnection(context, raw, name, onSuccess)
    }

    private fun startConnection(
        context: Context,
        raw: String,
        overrideName: String?,
        onSuccess: () -> Unit
    ) {
        Log.d("QRScannerViewModel", "Starting connection - override name: $overrideName")
        _showNameDialog.value = false
        _statusMessage.value = ScanStatusMessage(
            text = getString(R.string.qr_scan_status_scanned_searching),
            isLoading = true,
            stage = ScanStatusStage.SEARCHING
        )

        connectUsingQr(
            context = context,
            rawQrText = raw,
            overrideName = overrideName,
            onStatus = { stage ->
                Log.d("QRScannerViewModel", "Connection status changed: $stage")
                val message = when (stage) {
                    QrConnectionStage.SEARCHING -> ScanStatusMessage(
                        text = getString(R.string.qr_scan_status_scanned_searching),
                        isLoading = true,
                        stage = ScanStatusStage.SEARCHING
                    )

                    QrConnectionStage.PAIRING -> ScanStatusMessage(
                        text = getString(R.string.qr_scan_status_pairing),
                        isLoading = true,
                        stage = ScanStatusStage.PAIRING
                    )

                    QrConnectionStage.SAVING -> ScanStatusMessage(
                        text = getString(R.string.qr_scan_status_saving),
                        isLoading = true,
                        stage = ScanStatusStage.SAVING
                    )
                }
                _statusMessage.value = message
            }
        ) { result ->
            when (result) {
                is QrConnectResult.Saved -> {
                    Log.d("QRScannerViewModel", "Connection successful, contact saved")
                    _statusMessage.value = ScanStatusMessage(
                        text = getString(R.string.qr_scan_status_saved),
                        isLoading = false,
                        stage = ScanStatusStage.SAVED
                    )
                    _pendingQr.value = null
                    _manualName.value = ""
                    onSuccess()
                }

                is QrConnectResult.AlreadyRegistered -> {
                    Log.d(
                        "QRScannerViewModel",
                        "Contact already registered for session: ${result.sessionCode}"
                    )
                    _pendingQr.value = null
                    _manualName.value = ""
                    setError(ScanError.ALREADY_REGISTERED)
                }

                is QrConnectResult.Failure -> {
                    Log.e(
                        "QRScannerViewModel",
                        "Connection failed with error: ${result.code}",
                        IllegalStateException("QR connection error")
                    )
                    val errorCode = when (result.code) {
                        DeviceError.MISSING_PERMISSIONS -> ScanError.MISSING_PERMISSIONS
                        DeviceError.DEVICE_NOT_FOUND -> ScanError.DEVICE_NOT_FOUND
                        DeviceError.HANDSHAKE_FAILED -> ScanError.HANDSHAKE_FAILED
                        DeviceError.PAIRING_FAILED -> ScanError.PAIRING_FAILED
                    }
                    setError(errorCode)
                }
            }
        }
    }

    fun updateManualName(value: String) {
        _manualName.value = value
    }

    fun saveManualContact(context: Context, onSuccess: () -> Unit) {
        val pending = _pendingQr.value ?: return
        val finalName = _manualName.value.trim().ifEmpty { pending.data.sessionCode }
        _manualName.value = finalName
        startConnection(context, pending.raw, finalName, onSuccess)
    }

    fun dismissError() {
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
