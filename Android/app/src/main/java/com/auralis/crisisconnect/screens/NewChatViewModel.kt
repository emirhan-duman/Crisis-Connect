package com.auralis.crisisconnect.screens

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.DeviceError
import com.auralis.crisisconnect.data.DeviceResult
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.normalizeMacAddress
import com.auralis.crisisconnect.data.observeContacts
import com.auralis.crisisconnect.data.pairDevice
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.service.p2p.P2pGattServerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlin.coroutines.resume

private const val TAG = "NewChatViewModel"

class NewChatViewModel(application: Application) : AndroidViewModel(application) {
    private val context = getApplication<Application>()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    private val _permissionGranted = MutableStateFlow(hasAllRuntimePermissions())
    val permissionGranted: StateFlow<Boolean> = _permissionGranted

    private val _sessionCode = MutableStateFlow("")
    val sessionCode: StateFlow<String> = _sessionCode

    private val _aesKey = MutableStateFlow("")
    val aesKey: StateFlow<String> = _aesKey

    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName

    private val _isDiscoverable = MutableStateFlow(false)
    val isDiscoverable: StateFlow<Boolean> = _isDiscoverable

    private val _qrShareRequested = MutableStateFlow(false)
    val qrShareRequested: StateFlow<Boolean> = _qrShareRequested

    private val _qrShareId = MutableStateFlow("")
    val qrShareId: StateFlow<String> = _qrShareId

    private val _manualMacAddress = MutableStateFlow("")
    val manualMacAddress: StateFlow<String> = _manualMacAddress

    private val _manualRemoteName = MutableStateFlow("")
    val manualRemoteName: StateFlow<String> = _manualRemoteName

    private val _manualSessionCode = MutableStateFlow("")
    val manualSessionCode: StateFlow<String> = _manualSessionCode

    private val _manualConnectState = MutableStateFlow<ManualConnectState>(ManualConnectState.Idle)
    val manualConnectState: StateFlow<ManualConnectState> = _manualConnectState

    private val _navigateToMain = MutableStateFlow(false)
    val navigateToMain: StateFlow<Boolean> = _navigateToMain

    private var originalName: String? = null
    private var manualConnectJob: Job? = null
    private var contactObserverJob: Job? = null
    private var knownContactKeys = emptySet<String>()
    private var qrBroadcastStarted = false

    init {
        viewModelScope.launch(exceptionHandler) {
            val code = generateSessionCode()
            _sessionCode.value = code
            val key = LocalKeyStorage.getOrCreateAesKey(context)
            _aesKey.value = key
            _userName.value = getSavedUserName(context).first()
            if (canModifyBluetoothAdapter()) {
                if (originalName == null) {
                    originalName = readBluetoothNameSafely()
                }
                setBluetoothNameSafely(code)
            } else {
                Log.w(
                    "NewChatViewModel",
                    "Bluetooth permission not granted, cannot set name"
                )
            }
        }
        observeRemoteContactAdditions()
    }

    fun refreshBluetoothPermission() {
        val granted = hasAllRuntimePermissions()
        Log.d("NewChatViewModel", "Bluetooth permission granted: $granted")
        _permissionGranted.value = granted
        if (canModifyBluetoothAdapter()) {
            if (originalName == null) {
                originalName = readBluetoothNameSafely()
            }
            setBluetoothNameSafely(_sessionCode.value)
        }
    }

    fun setDiscoverable(value: Boolean) {
        _isDiscoverable.value = value
    }

    fun requestQrShare() {
        if (_qrShareId.value.isBlank()) {
            _qrShareId.value = generateShareId()
        }
        _qrShareRequested.value = true
    }

    fun cancelQrShareRequest() {
        _qrShareRequested.value = false
        qrBroadcastStarted = false
        _qrShareId.value = ""
        P2pGattServerService.stopPublishing(context)
    }

    fun activateQrShareIfReady() {
        if (!_qrShareRequested.value) {
            return
        }
        if (!hasAllRuntimePermissions()) {
            return
        }
        if (!_isDiscoverable.value) {
            return
        }
        val shareId = _qrShareId.value.ifBlank {
            generateShareId().also { generated -> _qrShareId.value = generated }
        }
        val publishedSession = P2pGattServerService.publishedSession.value
        if (
            qrBroadcastStarted &&
            P2pGattServerService.isRunning.value &&
            publishedSession?.shareId == shareId &&
            publishedSession.sessionCode == _sessionCode.value
        ) {
            return
        }
        P2pGattServerService.startPublishing(
            context = context,
            shareId = shareId,
            sessionCode = _sessionCode.value,
            displayName = _userName.value.trim().ifBlank { null },
            aesKeyBase64 = _aesKey.value
        )
        qrBroadcastStarted = true
    }

    fun restoreBluetoothSettings() {
        cancelQrShareRequest()
        if (canModifyBluetoothAdapter()) {
            setBluetoothNameSafely(originalName)
            if (_isDiscoverable.value) {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val method = BluetoothAdapter::class.java.getMethod(
                        "setScanMode",
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE)
                } catch (_: Exception) {
                }
            }
        }
        _isDiscoverable.value = false
    }

    private fun readBluetoothNameSafely(): String? {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.name
        } catch (securityException: SecurityException) {
            Log.w(
                "NewChatViewModel",
                "Cannot read Bluetooth name because BLUETOOTH_CONNECT is missing",
                securityException
            )
            null
        }
    }

    private fun setBluetoothNameSafely(name: String?) {
        if (name == null) {
            return
        }
        try {
            BluetoothAdapter.getDefaultAdapter()?.name = name
        } catch (securityException: SecurityException) {
            Log.w(
                "NewChatViewModel",
                "Cannot set Bluetooth name because BLUETOOTH_CONNECT is missing",
                securityException
            )
        }
    }

    fun updateManualMac(value: String) {
        clearManualErrorIfNeeded()
        _manualMacAddress.value = value
    }

    fun updateManualName(value: String) {
        clearManualErrorIfNeeded()
        _manualRemoteName.value = value
    }

    fun updateManualSessionCode(value: String) {
        clearManualErrorIfNeeded()
        _manualSessionCode.value = value
    }

    fun resetManualConnectState() {
        manualConnectJob?.cancel()
        manualConnectJob = null
        _manualConnectState.value = ManualConnectState.Idle
        Log.d("NewChatViewModel", "Manual connect state reset to Idle")
    }

    fun consumeNavigateToMain() {
        _navigateToMain.value = false
    }

    private fun clearManualErrorIfNeeded() {
        if (_manualConnectState.value is ManualConnectState.Error) {
            _manualConnectState.value = ManualConnectState.Idle
            Log.d("NewChatViewModel", "Cleared manual connect error state")
        }
    }

    fun connectToManualContact() {
        manualConnectJob?.cancel()
        manualConnectJob = viewModelScope.launch(exceptionHandler) {
            val mac = normalizeMacAddress(_manualMacAddress.value)
            val sessionCode = _manualSessionCode.value.trim()
            Log.d("NewChatViewModel", "Manual connect started")
            if (!isValidMac(mac)) {
                Log.w("NewChatViewModel", "Invalid MAC address format")
                _manualConnectState.value = ManualConnectState.Error(
                    context.getString(com.auralis.crisisconnect.R.string.manual_connect_error_mac)
                )
                return@launch
            }
            if (sessionCode.isEmpty()) {
                Log.w("NewChatViewModel", "Session code is empty")
                _manualConnectState.value = ManualConnectState.Error(
                    context.getString(com.auralis.crisisconnect.R.string.manual_connect_error_session)
                )
                return@launch
            }

            _manualConnectState.value = ManualConnectState.Loading
            Log.d("NewChatViewModel", "Manual connect state changed to Loading")

            when (val result = suspendCancellableCoroutine<DeviceResult<Unit>> { continuation ->
                Log.d("NewChatViewModel", "Starting manual pairing")
                pairDevice(context, mac) { res ->
                    if (continuation.isActive) {
                        continuation.resume(res)
                    }
                }
            }) {
                is DeviceResult.Success -> {
                    Log.d("NewChatViewModel", "Manual pairing successful")
                    val name = _manualRemoteName.value.trim().ifEmpty { sessionCode }
                    saveContact(
                        context,
                        Contact(
                            name = name,
                            aesKey = "",
                            sessionCode = sessionCode,
                            address = mac
                        )
                    )
                    _manualMacAddress.value = ""
                    _manualRemoteName.value = ""
                    _manualSessionCode.value = ""
                    _manualConnectState.value = ManualConnectState.Success
                    Log.d("NewChatViewModel", "Manual connect state changed to Success")
                }

                is DeviceResult.Error -> {
                    Log.e("NewChatViewModel", "Manual pairing failed with error: ${result.code}", IllegalStateException("Manual pairing error"))
                    val errorMessage = when (result.code) {
                        DeviceError.MISSING_PERMISSIONS -> context.getString(
                            com.auralis.crisisconnect.R.string.manual_connect_error_permissions
                        )

                        DeviceError.DEVICE_NOT_FOUND, DeviceError.PAIRING_FAILED -> context.getString(
                            com.auralis.crisisconnect.R.string.manual_connect_error_pairing
                        )

                        DeviceError.HANDSHAKE_FAILED -> context.getString(
                            com.auralis.crisisconnect.R.string.manual_connect_error_pairing
                        )
                    }
                    _manualConnectState.value = ManualConnectState.Error(errorMessage)
                    Log.d("NewChatViewModel", "Manual connect state changed to Error")
                }
            }
        }.also { job ->
            job.invokeOnCompletion { manualConnectJob = null }
        }
    }

    override fun onCleared() {
        manualConnectJob?.cancel()
        contactObserverJob?.cancel()
        restoreBluetoothSettings()
        super.onCleared()
    }

    private fun hasAllRuntimePermissions(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val connectGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                val scanGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
                val advertiseGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ) == PackageManager.PERMISSION_GRANTED
                connectGranted && scanGranted && advertiseGranted
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val fineGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarseGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                fineGranted || coarseGranted
            }

            else -> true
        }
    }

    private fun canModifyBluetoothAdapter(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun observeRemoteContactAdditions() {
        contactObserverJob?.cancel()
        contactObserverJob = viewModelScope.launch(exceptionHandler) {
            knownContactKeys = withContext(Dispatchers.IO) {
                runCatching {
                    getContacts(context).asSequence()
                        .map { contact -> contact.sessionCode.ifBlank { contact.address }.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()
                }.getOrElse { error ->
                    Log.e(TAG, "Unable to load existing contacts for observer", error)
                    emptySet()
                }
            }
            val contactsFlow = runCatching { observeContacts(context) }
                .getOrElse { error ->
                    Log.e(TAG, "Unable to initialize contact observer", error)
                    return@launch
                }

            contactsFlow
                .catch { error ->
                    Log.e(TAG, "Contact observer stream failed", error)
                    emit(emptyList())
                }
                .collect { contacts ->
                    val keys = contacts.asSequence()
                        .map { contact ->
                            contact.sessionCode.ifBlank { contact.address }.trim()
                        }
                        .filter { it.isNotBlank() }
                        .toSet()

                    val hasNewContact = keys.any { it !in knownContactKeys }
                    knownContactKeys = keys
                    if (hasNewContact) {
                        _navigateToMain.value = true
                    }
                }
        }
    }

}

private fun isValidMac(value: String): Boolean {
    val macRegex = "^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$".toRegex()
    return macRegex.matches(value)
}

sealed class ManualConnectState {
    object Idle : ManualConnectState()
    object Loading : ManualConnectState()
    data class Error(val message: String) : ManualConnectState()
    object Success : ManualConnectState()
}

fun generateSessionCode(length: Int = 6): String {
    val chars = (('A'..'Z') + ('0'..'9'))
    return (1..length).map { chars.random() }.joinToString("")
}

fun generateShareId(length: Int = 10): String {
    val chars = (('A'..'Z') + ('0'..'9'))
    return (1..length).map { chars.random() }.joinToString("")
}
