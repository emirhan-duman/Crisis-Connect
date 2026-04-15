package com.auralis.crisisconnect.screens.Tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.service.BleRadioPolicy
import com.auralis.crisisconnect.service.scan.BleScanCoordinator
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignalFinderViewModel(application: Application) : AndroidViewModel(application) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    data class CellSignal(
        val technology: String,
        val strengthDbm: Int?,
        val level: Int?,
        val isRegistered: Boolean,
        val cellId: String?,
        val operator: String?,
        val additionalInfo: String?
    )

    data class WifiSignal(
        val ssid: String,
        val bssid: String,
        val level: Int,
        val frequencyMhz: Int,
        val channelWidth: Int,
        val capabilities: String
    )

    data class BluetoothSignal(
        val name: String?,
        val address: String,
        val rssi: Int?
    )

    private val context: Context get() = getApplication<Application>().applicationContext

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _cells = MutableStateFlow<List<CellSignal>>(emptyList())
    val cells: StateFlow<List<CellSignal>> = _cells.asStateFlow()

    private val _wifi = MutableStateFlow<List<WifiSignal>>(emptyList())
    val wifi: StateFlow<List<WifiSignal>> = _wifi.asStateFlow()

    private val _bluetooth = MutableStateFlow<List<BluetoothSignal>>(emptyList())
    val bluetooth: StateFlow<List<BluetoothSignal>> = _bluetooth.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<Int?>(null)
    val errorMessage: StateFlow<Int?> = _errorMessage.asStateFlow()

    private val _lastUpdated = MutableStateFlow<Long?>(null)
    val lastUpdated: StateFlow<Long?> = _lastUpdated.asStateFlow()

    private var bluetoothScanJob: Job? = null
    private val scanOwnerId = "signal-finder-${System.identityHashCode(this)}"

    fun refreshSignals() {
        if (_isRefreshing.value) return
        viewModelScope.launch(exceptionHandler) {
            _isRefreshing.value = true
            _errorMessage.value = null

            val hasPermissions = hasRequiredPermissions()
            if (!hasPermissions) {
                _cells.value = emptyList()
                _wifi.value = emptyList()
                _bluetooth.value = emptyList()
                _errorMessage.value = R.string.signal_finder_permission_required
                _isRefreshing.value = false
                return@launch
            }

            withContext(Dispatchers.IO) {
                loadCellSignals()
                loadWifiSignals()
                startBluetoothScan()
            }

            _lastUpdated.value = System.currentTimeMillis()
            _isRefreshing.value = false
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val nearbyWifiGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return (fine || coarse) && nearbyWifiGranted
    }

    @SuppressLint("MissingPermission")
    private fun loadCellSignals() {
        val info = runCatching { telephonyManager?.allCellInfo.orEmpty() }
            .getOrElse {
                _errorMessage.value = R.string.signal_finder_error_telephony
                emptyList()
            }
        if (info.isEmpty()) {
            _cells.value = emptyList()
            return
        }
        val mapped = info.map { mapCellInfo(it) }
            .sortedByDescending { it.strengthDbm ?: Int.MIN_VALUE }
        _cells.value = mapped
    }

    @SuppressLint("MissingPermission")
    private fun loadWifiSignals() {
        val manager = wifiManager ?: run {
            _wifi.value = emptyList()
            return
        }
        manager.startScan()
        val results = manager.scanResults.orEmpty()
        val mapped = results.map {
            WifiSignal(
                ssid = if (it.SSID.isNullOrBlank()) context.getString(R.string.signal_unknown_ssid) else it.SSID,
                bssid = it.BSSID ?: context.getString(R.string.signal_unknown_bssid),
                level = it.level,
                frequencyMhz = it.frequency,
                channelWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    when (it.channelWidth) {
                        0 -> 20
                        1 -> 40
                        2 -> 80
                        3 -> 160
                        4 -> 80
                        else -> 20
                    }
                } else {
                    20
                },
                capabilities = it.capabilities ?: ""
            )
        }.sortedByDescending { it.level }
        _wifi.value = mapped
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        stopBluetoothScan()
        val adapter = bluetoothManager?.adapter ?: run {
            _bluetooth.value = emptyList()
            return
        }
        if (!adapter.isEnabled) {
            _bluetooth.value = emptyList()
            return
        }
        val hasScanPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val hasConnectPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasScanPermission || !hasConnectPermission) {
            if (_errorMessage.value == null) {
                _errorMessage.value = R.string.signal_finder_bluetooth_permission_missing
            }
            _bluetooth.value = emptyList()
            return
        }
        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            _bluetooth.value = emptyList()
            return
        }
        val discovered = mutableMapOf<String, BluetoothSignal>()
        _bluetooth.value = emptyList()
        val listener = object : BleScanCoordinator.Listener {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val key = device.address ?: return
                val signal = BluetoothSignal(
                    name = device.name,
                    address = device.address,
                    rssi = result.rssi
                )
                discovered[key] = signal
                _bluetooth.value = discovered.values.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                _errorMessage.value = R.string.signal_finder_error_bluetooth
            }
        }
        bluetoothScanJob = viewModelScope.launch(exceptionHandler) {
            val scanMode = BleRadioPolicy.resolve(
                context = context,
                preferPerformance = false
            ).scanMode
            val started = BleScanCoordinator.registerOrUpdate(
                owner = scanOwnerId,
                scanner = scanner,
                mode = scanMode,
                filters = null,
                listener = listener
            )
            if (!started) {
                _errorMessage.value = R.string.signal_finder_error_bluetooth
                return@launch
            }
            delay(SCAN_DURATION_MS)
            stopBluetoothScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        BleScanCoordinator.unregister(scanOwnerId)
        bluetoothScanJob?.cancel()
        bluetoothScanJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopBluetoothScan()
    }

    private fun mapCellInfo(cellInfo: CellInfo): CellSignal {
        val signalStrength = extractSignal(cellInfo)
        val cellIdentity = extractIdentity(cellInfo)
        val technology = when {
            cellInfo is CellInfoGsm -> "GSM"
            cellInfo is CellInfoCdma -> "CDMA"
            cellInfo is CellInfoLte -> "LTE"
            cellInfo is CellInfoWcdma -> "WCDMA"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoTdscdma -> "TD-SCDMA"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr -> "NR"
            else -> context.getString(R.string.signal_unknown)
        }
        val additional = buildString {
            val id = cellIdentity
            if (id != null) {
                val (mcc, mnc) = resolveMccMnc(id)
                if (!mcc.isNullOrBlank() && !mnc.isNullOrBlank()) {
                    append(
                        context.getString(
                            R.string.signal_finder_cell_identity_mcc_mnc,
                            mcc,
                            mnc
                        )
                    )
                }
                val details = when {
                    id is android.telephony.CellIdentityLte -> listOfNotNull(
                        id.pci.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                            context.getString(R.string.signal_finder_cell_identity_pci, it)
                        },
                        id.tac.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                            context.getString(R.string.signal_finder_cell_identity_tac, it)
                        }
                    )
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        id is android.telephony.CellIdentityNr -> listOfNotNull(
                            id.pci.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                                context.getString(R.string.signal_finder_cell_identity_pci, it)
                            },
                            id.tac.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                                context.getString(R.string.signal_finder_cell_identity_tac, it)
                            }
                        )
                    id is android.telephony.CellIdentityWcdma -> listOfNotNull(
                        id.psc.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                            context.getString(R.string.signal_finder_cell_identity_psc, it)
                        },
                        id.lac.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                            context.getString(R.string.signal_finder_cell_identity_lac, it)
                        }
                    )
                    id is android.telephony.CellIdentityGsm -> listOfNotNull(
                        id.lac.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                            context.getString(R.string.signal_finder_cell_identity_lac, it)
                        },
                        id.cid.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                            context.getString(R.string.signal_finder_cell_identity_cid, it)
                        }
                    )
                    id is android.telephony.CellIdentityCdma -> listOfNotNull(
                        id.networkId.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                            context.getString(R.string.signal_finder_cell_identity_nid, it)
                        },
                        id.systemId.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                            context.getString(R.string.signal_finder_cell_identity_sid, it)
                        }
                    )
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        id is android.telephony.CellIdentityTdscdma -> listOfNotNull(
                            id.lac.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                                context.getString(R.string.signal_finder_cell_identity_lac, it)
                            },
                            id.cid.takeIf { it != CellInfo.UNAVAILABLE }?.let {
                                context.getString(R.string.signal_finder_cell_identity_cid, it)
                            }
                        )
                    else -> emptyList()
                }
                if (details.isNotEmpty()) {
                    if (isNotEmpty()) append(" • ")
                    append(details.joinToString(" • "))
                }
            }
        }.ifBlank { null }

        return CellSignal(
            technology = technology,
            strengthDbm = signalStrength?.dbm,
            level = signalStrength?.level,
            isRegistered = cellInfo.isRegistered,
            cellId = cellIdentity?.let { formatCellId(it) },
            operator = telephonyManager?.networkOperatorName?.takeIf { it.isNotBlank() },
            additionalInfo = additional
        )
    }

    private fun extractSignal(cellInfo: CellInfo): CellSignalStrength? = when {
        cellInfo is CellInfoGsm -> cellInfo.cellSignalStrength
        cellInfo is CellInfoCdma -> cellInfo.cellSignalStrength
        cellInfo is CellInfoLte -> cellInfo.cellSignalStrength
        cellInfo is CellInfoWcdma -> cellInfo.cellSignalStrength
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr -> cellInfo.cellSignalStrength
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoTdscdma -> cellInfo.cellSignalStrength
        else -> null
    }

    private fun extractIdentity(cellInfo: CellInfo): Any? = when {
        cellInfo is CellInfoGsm -> cellInfo.cellIdentity
        cellInfo is CellInfoCdma -> cellInfo.cellIdentity
        cellInfo is CellInfoLte -> cellInfo.cellIdentity
        cellInfo is CellInfoWcdma -> cellInfo.cellIdentity
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr -> cellInfo.cellIdentity
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoTdscdma -> cellInfo.cellIdentity
        else -> null
    }

    private fun formatCellId(identity: Any): String? {
        return when {
            identity is android.telephony.CellIdentityGsm -> identity.cid.takeIf { it != CellInfo.UNAVAILABLE }?.toString()
            identity is android.telephony.CellIdentityWcdma -> identity.cid.takeIf { it != CellInfo.UNAVAILABLE }?.toString()
            identity is android.telephony.CellIdentityLte -> identity.ci.takeIf { it != CellInfo.UNAVAILABLE }?.toString()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && identity is android.telephony.CellIdentityNr ->
                identity.nci.takeIf {
                    it != CellInfo.UNAVAILABLE.toLong() && it != Long.MAX_VALUE
                }?.toString()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && identity is android.telephony.CellIdentityTdscdma ->
                identity.cid.takeIf { it != CellInfo.UNAVAILABLE }?.toString()
            identity is android.telephony.CellIdentityCdma -> identity.basestationId.takeIf { it != CellInfo.UNAVAILABLE }?.toString()
            else -> null
        }
    }

    private fun resolveMccMnc(identity: Any): Pair<String?, String?> {
        return when {
            identity is android.telephony.CellIdentityGsm -> identity.mcc?.validIdentity()?.toString() to identity.mnc?.validIdentity()?.toString()
            identity is android.telephony.CellIdentityWcdma -> identity.mcc?.validIdentity()?.toString() to identity.mnc?.validIdentity()?.toString()
            identity is android.telephony.CellIdentityLte -> identity.mcc?.validIdentity()?.toString() to identity.mnc?.validIdentity()?.toString()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && identity is android.telephony.CellIdentityTdscdma -> {
                val mcc = identity.mccString?.takeIf { it.isNotBlank() }
                val mnc = identity.mncString?.takeIf { it.isNotBlank() }
                mcc to mnc
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && identity is android.telephony.CellIdentityNr ->
                identity.mccString to identity.mncString
            else -> null to null
        }
    }

    private fun Int.validIdentity(): Int? {
        return if (this == CellInfo.UNAVAILABLE || this == Int.MAX_VALUE || this < 0) {
            null
        } else {
            this
        }
    }

    companion object {
        private const val TAG = "SignalFinderVM"
        private const val SCAN_DURATION_MS = 8_000L
    }
}
