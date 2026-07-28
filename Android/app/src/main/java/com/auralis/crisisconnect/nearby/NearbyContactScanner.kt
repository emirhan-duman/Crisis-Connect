package com.auralis.crisisconnect.nearby

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.service.scan.BleScanCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** A nearby device advertising that it is open to nearby-add. Identity is unknown until pairing. */
data class NearbyDevice(
    val bluetoothAddress: String,
    val lastSeenMs: Long
)

/**
 * Scans for devices advertising the SPAKE2 pairing service (0xCCA6) — i.e. Crisis Connect users who
 * opted into nearby-add. It deliberately learns NOTHING about who they are: no phone-number token,
 * no address-book matching. To actually add someone, the user runs a targeted SPAKE2 handshake
 * ([NearbyPairingClient]) with the number they're looking for; only a real match reveals identity.
 * This is what closes the old number-harvesting hole.
 */
object NearbyContactScanner {
    private const val TAG = "NearbyContactScanner"
    private const val OWNER = "nearby-contact-scanner"
    private const val BOOST_OWNER = "nearby-contact-scanner-boost"
    private const val DEVICE_TTL_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serviceParcelUuid = ParcelUuid(NearbySpakePairing.SERVICE_UUID)

    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()

    private val seen = ConcurrentHashMap<String, Long>() // bt address -> lastSeenMs
    // The ScanResult's BluetoothDevice carries the LE address TYPE (these beacons use resolvable
    // private addresses). Connecting must go through this object — getRemoteDevice(addressString)
    // assumes a public address and the connect never completes against an RPA.
    private val seenDevices = ConcurrentHashMap<String, android.bluetooth.BluetoothDevice>()
    private var pruneJob: Job? = null
    @Volatile private var running = false

    private val listener = object : BleScanCoordinator.Listener {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)
        override fun onBatchScanResults(results: List<ScanResult>) = results.forEach(::handleResult)
        override fun onScanFailed(errorCode: Int) {
            // 6 = SCANNING_TOO_FREQUENTLY (Android throttles >5 scan starts per 30s app-wide);
            // swallowing this made a throttled sweep indistinguishable from "no one nearby".
            android.util.Log.w(TAG, "BLE scan failed: errorCode=$errorCode")
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (running) return
        val appContext = context.applicationContext
        if (!hasScanPermission(appContext)) return
        val scanner = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return

        running = true
        pruneJob = scope.launch {
            while (running) {
                delay(5_000L)
                pruneExpired()
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(serviceParcelUuid).build())
        BleScanCoordinator.registerOrUpdate(
            owner = OWNER,
            scanner = scanner,
            mode = ScanSettings.SCAN_MODE_LOW_POWER,
            filters = filters,
            listener = listener
        )
    }

    @Synchronized
    fun stop() {
        running = false
        pruneJob?.cancel(); pruneJob = null
        BleScanCoordinator.unregister(OWNER)
        seen.clear()
        seenDevices.clear()
        _devices.value = emptyList()
    }

    private fun handleResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        seen[address] = System.currentTimeMillis()
        seenDevices[address] = device
        publish()
    }

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - DEVICE_TTL_MS
        val removed = seen.entries.removeIf { it.value < cutoff }
        seenDevices.keys.retainAll(seen.keys)
        if (removed) publish()
    }

    private fun publish() {
        _devices.value = seen.entries
            .sortedByDescending { it.value }
            .map { NearbyDevice(bluetoothAddress = it.key, lastSeenMs = it.value) }
    }

    /**
     * Temporarily raises the shared scan to LOW_LATENCY while a targeted sweep runs (the chat-open
     * auto-link). The always-on LOW_POWER scan only listens ~10% of the time, so a short window can
     * miss a LOW_POWER beacon entirely. The coordinator runs the highest requested mode; dropping
     * the boost restores the battery-friendly baseline. Call [boostStop] when the sweep ends.
     */
    @Synchronized
    fun boostStart(context: Context) {
        if (!running) return
        val appContext = context.applicationContext
        if (!hasScanPermission(appContext)) return
        val scanner = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        BleScanCoordinator.registerOrUpdate(
            owner = BOOST_OWNER,
            scanner = scanner,
            mode = ScanSettings.SCAN_MODE_LOW_LATENCY,
            filters = listOf(ScanFilter.Builder().setServiceUuid(serviceParcelUuid).build()),
            listener = listener
        )
    }

    @Synchronized
    fun boostStop() {
        BleScanCoordinator.unregister(BOOST_OWNER)
    }

    /** Bluetooth addresses currently visible, for a targeted pairing sweep. */
    fun currentAddresses(): List<String> = seen.keys.toList()

    /** The scan-time [android.bluetooth.BluetoothDevice] for [address] (carries the LE address type). */
    fun deviceFor(address: String): android.bluetooth.BluetoothDevice? = seenDevices[address]

    /** Whether a scan is currently active (so an ad-hoc caller knows not to stop a shared scan). */
    fun isRunning(): Boolean = running

    private fun hasScanPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }
}
