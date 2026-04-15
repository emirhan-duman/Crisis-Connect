package com.auralis.crisisconnect.service.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.data.BleBroadcastDirectory
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.service.scan.BleScanCoordinator
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class P2pResolvedShare(
    val shareId: String,
    val address: String,
    val sessionCode: String,
    val displayName: String?,
    val avatarBase64: String?,
    val platform: String,
    val remoteDeviceId: String
)

private data class P2pBootstrapPayload(
    val shareId: String,
    val sessionCode: String,
    val displayName: String?,
    val avatarBase64: String?,
    val platform: String,
    val protocolVersion: Int,
    val serverNonce: String,
    val serverDeviceId: String
)

private enum class ControlStage {
    SERVER_HELLO,
    SERVER_FINISH
}

private const val P2P_SCAN_TIMEOUT_MS = 20_000L
private const val P2P_BOOTSTRAP_TIMEOUT_MS = 10_000L
private const val P2P_REQUESTED_MTU = 247
private const val P2P_SERVICE_DISCOVERY_RETRY_DELAY_MS = 700L
private const val DEFAULT_ATT_MTU = 23

private data class P2pScanCandidate(
    val device: BluetoothDevice,
    val rssi: Int
)

private const val MAX_P2P_LOCAL_NAME_LENGTH = 8

private fun hasBleScanPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    } else {
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
}

private fun hasBleConnectPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

suspend fun resolveP2pShare(
    context: Context,
    shareId: String,
    aesKeyBase64: String,
    timeoutMs: Long = P2P_SCAN_TIMEOUT_MS
): P2pResolvedShare? {
    val appContext = context.applicationContext
    val normalizedShareId = P2pBleProtocol.normalizeShareId(shareId)
    val keyBytes = P2pBleProtocol.decodeBase64(aesKeyBase64)?.takeIf { it.isNotEmpty() } ?: return null
    if (normalizedShareId.isBlank()) {
        return null
    }
    if (!hasBleScanPermission(appContext) || !hasBleConnectPermission(appContext)) {
        return null
    }
    val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return null
    val adapter = manager.adapter ?: return null
    if (!adapter.isEnabled) {
        return null
    }

    val localSessionCode = LocalKeyStorage.getOrCreateP2pSessionCode(appContext)
    val localDeviceId = LocalKeyStorage.getOrCreateP2pDeviceId(appContext)
    val localUserName = runCatching { getSavedUserName(appContext).first().trim() }
        .getOrDefault("")
    val localAvatarBase64 = ContactAvatarStorage.localProfileAvatarPayload(appContext)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val cachedAddress = BleBroadcastDirectory.resolveAddress(normalizedShareId)
    if (!cachedAddress.isNullOrBlank()) {
        val cachedDevice = runCatching { adapter.getRemoteDevice(cachedAddress) }.getOrNull()
        if (cachedDevice != null) {
            readBootstrapFromDevice(
                context = appContext,
                device = cachedDevice,
                expectedShareId = normalizedShareId,
                aesKey = keyBytes,
                localSessionCode = localSessionCode,
                localUserName = localUserName,
                localAvatarBase64 = localAvatarBase64,
                localDeviceId = localDeviceId
            )?.let { resolved ->
                return resolved
            }
        }
    }

    val candidates = scanForShareDevices(appContext, adapter, normalizedShareId, timeoutMs)
    for (candidate in candidates) {
        readBootstrapFromDevice(
            context = appContext,
            device = candidate,
            expectedShareId = normalizedShareId,
            aesKey = keyBytes,
            localSessionCode = localSessionCode,
            localUserName = localUserName,
            localAvatarBase64 = localAvatarBase64,
            localDeviceId = localDeviceId
        )?.let { resolved ->
            BleBroadcastDirectory.update(
                broadcastId = normalizedShareId,
                address = candidate.address,
                lastSeen = System.currentTimeMillis()
            )
            return resolved
        }
    }
    return null
}

@SuppressLint("MissingPermission")
private suspend fun scanForShareDevices(
    context: Context,
    adapter: BluetoothAdapter,
    shareId: String,
    timeoutMs: Long
): List<BluetoothDevice> {
    if (!hasBleScanPermission(context)) {
        return emptyList()
    }
    val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: return emptyList()
    val serviceParcelUuid = ParcelUuid(P2pBleProtocol.SERVICE_UUID)
    val fallbackCandidates = linkedMapOf<String, P2pScanCandidate>()

    val exactMatch = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<BluetoothDevice?> { continuation ->
            val completed = AtomicBoolean(false)
            val owner = "p2p-share-resolver-$shareId-${System.identityHashCode(continuation)}"

            fun finish(device: BluetoothDevice?) {
                if (!completed.compareAndSet(false, true)) {
                    return
                }
                BleScanCoordinator.unregister(owner)
                if (continuation.isActive) {
                    continuation.resume(device)
                }
            }

            val listener = object : BleScanCoordinator.Listener {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val advertisedShareId = result.scanRecord
                        ?.serviceData
                        ?.get(serviceParcelUuid)
                        ?.toString(StandardCharsets.UTF_8)
                        ?.trim()
                        ?.uppercase(Locale.US)
                    if (advertisedShareId != null) {
                        if (advertisedShareId != shareId) {
                            return
                        }
                        Log.d(TAG, "Resolved P2P share via service data for $shareId from ${device.address}")
                        BleBroadcastDirectory.update(
                            broadcastId = shareId,
                            address = device.address,
                            lastSeen = System.currentTimeMillis()
                        )
                        finish(device)
                        return
                    }
                    if (!advertisesP2pService(result, serviceParcelUuid)) {
                        if (!advertisesExpectedShareName(result, shareId)) {
                            return
                        }
                    }
                    val current = fallbackCandidates[device.address]
                    if (current == null || result.rssi > current.rssi) {
                        Log.d(
                            TAG,
                            "Queued fallback P2P candidate for $shareId from ${device.address} rssi=${result.rssi}"
                        )
                        fallbackCandidates[device.address] = P2pScanCandidate(
                            device = device,
                            rssi = result.rssi
                        )
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "P2P share scan failed: $errorCode")
                    finish(null)
                }
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val started = runCatching {
                // iOS advertisements can be inconsistent with hardware-level service-data filtering.
                // Run this user-initiated scan unfiltered and verify candidates in-process instead.
                BleScanCoordinator.registerOrUpdate(
                    owner = owner,
                    scanner = scanner,
                    mode = settings.scanMode,
                    filters = null,
                    listener = listener
                )
            }.getOrElse { throwable ->
                Log.w(TAG, "Unable to start coordinated P2P share scan", throwable)
                false
            }
            if (!started) {
                finish(null)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                finish(null)
            }
        }
    }
    if (exactMatch != null) {
        return listOf(exactMatch)
    }
    return fallbackCandidates.values
        .sortedByDescending { it.rssi }
        .map { it.device }
}

private fun advertisesP2pService(
    result: ScanResult,
    expectedServiceUuid: ParcelUuid
): Boolean {
    val advertisedUuids = result.scanRecord?.serviceUuids ?: return false
    return advertisedUuids.any { advertised ->
        advertised.uuid == expectedServiceUuid.uuid
    }
}

private fun advertisesExpectedShareName(
    result: ScanResult,
    expectedShareId: String
): Boolean {
    val expectedName = expectedShareId.take(MAX_P2P_LOCAL_NAME_LENGTH)
    val advertisedName = result.scanRecord?.deviceName
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { it.isNotBlank() }
        ?: remoteDeviceName(result)
        ?: return false
    return advertisedName == expectedName
}

@SuppressLint("MissingPermission")
private fun remoteDeviceName(result: ScanResult): String? {
    return runCatching {
        result.device?.name
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

@SuppressLint("MissingPermission")
private suspend fun readBootstrapFromDevice(
    context: Context,
    device: BluetoothDevice,
    expectedShareId: String,
    aesKey: ByteArray,
    localSessionCode: String,
    localUserName: String,
    localAvatarBase64: String?,
    localDeviceId: String
): P2pResolvedShare? {
    if (!hasBleConnectPermission(context)) {
        return null
    }
    return withTimeoutOrNull(P2P_BOOTSTRAP_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            var gatt: BluetoothGatt? = null
            var bootstrapPayload: P2pBootstrapPayload? = null
            var controlCharacteristic: BluetoothGattCharacteristic? = null
            var controlStage: ControlStage? = null
            var authenticatedServerName: String? = null
            var authenticatedServerAvatarBase64: String? = null
            var clientNonce: String? = null
            var controlReadRetryCount = 0
            var refreshedGattCacheAfterMissingService = false
            var negotiatedMtu = DEFAULT_ATT_MTU

            fun closeGatt() {
                val currentGatt = gatt ?: return
                runCatching { currentGatt.disconnect() }
                runCatching { currentGatt.close() }
                gatt = null
            }

            fun finish(result: P2pResolvedShare?) {
                if (!completed.compareAndSet(false, true)) {
                    return
                }
                closeGatt()
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            fun beginServiceDiscovery(gatt: BluetoothGatt) {
                val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                if (!started) {
                    Log.w(TAG, "Unable to start P2P service discovery for ${device.address}")
                    finish(null)
                }
            }

            fun refreshGattCache(gatt: BluetoothGatt) {
                runCatching {
                    val method = gatt.javaClass.getMethod("refresh")
                    method.isAccessible = true
                    method.invoke(gatt)
                    Log.d(TAG, "P2P GATT cache refresh invoked for ${device.address}")
                }.onFailure { error ->
                    Log.w(TAG, "Unable to refresh P2P GATT cache for ${device.address}", error)
                }
            }

            fun buildRemoteProofPayload(
                remotePlatform: String,
                vararg parts: Pair<String, String>
            ): ByteArray {
                return if (remotePlatform.equals("ios", ignoreCase = true)) {
                    P2pBleProtocol.buildCanonicalProofPayload(*parts)
                } else {
                    P2pBleProtocol.buildProofPayload(*parts)
                }
            }

            fun proofMatchesRemotePlatform(
                actualProof: String,
                remotePlatform: String,
                vararg parts: Pair<String, String>
            ): Boolean {
                val preferred = P2pBleProtocol.hmacBase64(
                    aesKey,
                    buildRemoteProofPayload(remotePlatform, *parts)
                )
                if (P2pBleProtocol.secureEqualsBase64(preferred, actualProof)) {
                    return true
                }
                val fallback = if (remotePlatform.equals("ios", ignoreCase = true)) {
                    P2pBleProtocol.hmacBase64(aesKey, P2pBleProtocol.buildProofPayload(*parts))
                } else {
                    P2pBleProtocol.hmacBase64(aesKey, P2pBleProtocol.buildCanonicalProofPayload(*parts))
                }
                return P2pBleProtocol.secureEqualsBase64(fallback, actualProof)
            }

            data class ClientHelloOptions(
                val includeName: Boolean,
                val includeAvatar: Boolean
            )

            fun buildClientHelloPayload(payload: P2pBootstrapPayload): ByteArray? {
                val maxControlPayloadBytes = (negotiatedMtu - 3).coerceAtLeast(20)
                val options = linkedSetOf(
                    ClientHelloOptions(
                        includeName = localUserName.isNotBlank(),
                        includeAvatar = !localAvatarBase64.isNullOrBlank()
                    ),
                    ClientHelloOptions(
                        includeName = localUserName.isNotBlank(),
                        includeAvatar = false
                    ),
                    ClientHelloOptions(
                        includeName = false,
                        includeAvatar = false
                    )
                )

                for (option in options) {
                    val clientNameValue = if (option.includeName) localUserName else ""
                    val helloProof = P2pBleProtocol.hmacBase64(
                        aesKey,
                        buildRemoteProofPayload(
                            payload.platform,
                            "type" to P2pBleProtocol.TYPE_CLIENT_HELLO,
                            "shareId" to payload.shareId,
                            "serverSessionCode" to payload.sessionCode,
                            "serverDeviceId" to payload.serverDeviceId,
                            "serverNonce" to payload.serverNonce,
                            "clientSessionCode" to localSessionCode,
                            "clientDeviceId" to localDeviceId,
                            "clientNonce" to clientNonce.orEmpty(),
                            "clientName" to clientNameValue,
                            "clientPlatform" to "android"
                        )
                    ) ?: return null

                    val frame = JSONObject().apply {
                        put("type", P2pBleProtocol.TYPE_CLIENT_HELLO)
                        put("protocolVersion", P2pBleProtocol.PROTOCOL_VERSION)
                        put("shareId", payload.shareId)
                        put("clientSessionCode", localSessionCode)
                        put("clientDeviceId", localDeviceId)
                        put("clientNonce", clientNonce)
                        if (option.includeName) {
                            put("clientName", localUserName)
                        }
                        put("clientPlatform", "android")
                        if (option.includeAvatar) {
                            put("avatarB64", localAvatarBase64)
                        }
                        put("proof", helloProof)
                    }
                    val bytes = frame.toString().toByteArray(Charsets.UTF_8)
                    if (bytes.size <= maxControlPayloadBytes) {
                        if (!option.includeAvatar || !option.includeName) {
                            Log.d(
                                TAG,
                                "Using compact P2P client_hello for ${device.address} bytes=${bytes.size}/$maxControlPayloadBytes includeName=${option.includeName} includeAvatar=${option.includeAvatar}"
                            )
                        }
                        return bytes
                    }
                }

                Log.w(
                    TAG,
                    "P2P client_hello does not fit negotiated MTU for ${device.address}: mtu=$negotiatedMtu"
                )
                return null
            }

            fun characteristicDebug(characteristic: BluetoothGattCharacteristic?): String {
                if (characteristic == null) {
                    return "missing"
                }
                return "uuid=${characteristic.uuid} props=0x${characteristic.properties.toString(16)}"
            }

            fun startControlWrite(gatt: BluetoothGatt, payloadBytes: ByteArray): Boolean {
                val control = controlCharacteristic ?: return false
                control.value = payloadBytes
                val started = runCatching { gatt.writeCharacteristic(control) }.getOrDefault(false)
                if (!started) {
                    Log.w(
                        TAG,
                        "Unable to queue P2P control write on ${device.address} bytes=${payloadBytes.size} mtu=$negotiatedMtu ${characteristicDebug(control)}"
                    )
                }
                return started
            }

            fun startControlWrite(gatt: BluetoothGatt, payload: JSONObject): Boolean {
                return startControlWrite(
                    gatt = gatt,
                    payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)
                )
            }

            fun startCharacteristicRead(
                gatt: BluetoothGatt,
                phase: String,
                primary: BluetoothGattCharacteristic?,
                fallback: BluetoothGattCharacteristic? = null
            ): Boolean {
                val candidates = buildList {
                    if (primary != null) add(primary)
                    if (fallback != null && fallback.uuid != primary?.uuid) add(fallback)
                }
                if (candidates.isEmpty()) {
                    Log.w(TAG, "No readable P2P characteristics available for $phase on ${device.address}")
                    return false
                }
                for (candidate in candidates) {
                    val readable =
                        (candidate.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                    Log.d(
                        TAG,
                        "Attempting P2P $phase read on ${device.address} ${characteristicDebug(candidate)} readable=$readable"
                    )
                    if (!readable) {
                        continue
                    }
                    val started = runCatching { gatt.readCharacteristic(candidate) }.getOrDefault(false)
                    if (started) {
                        return true
                    }
                    Log.w(
                        TAG,
                        "Unable to start P2P $phase read on ${device.address} uuid=${candidate.uuid}"
                    )
                }
                return false
            }

            fun retryControlRead(gatt: BluetoothGatt, reason: String): Boolean {
                val control = controlCharacteristic ?: return false
                if (controlReadRetryCount >= 1) {
                    return false
                }
                controlReadRetryCount += 1
                Log.w(TAG, "Retrying P2P control read for ${device.address} after $reason")
                Handler(Looper.getMainLooper()).postDelayed({
                    if (completed.get()) {
                        return@postDelayed
                    }
                    if (!startCharacteristicRead(
                            gatt = gatt,
                            phase = "control-retry",
                            primary = control
                        )
                    ) {
                        finish(null)
                    }
                }, 120L)
                return true
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(null)
                        return
                    }
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            val mtuRequested = runCatching {
                                gatt.requestMtu(P2P_REQUESTED_MTU)
                            }.getOrDefault(false)
                            if (!mtuRequested) {
                                Log.d(
                                    TAG,
                                    "P2P MTU request unavailable for ${device.address}; continuing with service discovery"
                                )
                                beginServiceDiscovery(gatt)
                            }
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> finish(null)
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        negotiatedMtu = mtu.coerceAtLeast(DEFAULT_ATT_MTU)
                        Log.d(TAG, "P2P MTU changed to $mtu for ${device.address}")
                    } else {
                        negotiatedMtu = DEFAULT_ATT_MTU
                        Log.w(TAG, "P2P MTU request failed for ${device.address} status=$status")
                    }
                    beginServiceDiscovery(gatt)
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "P2P service discovery failed for ${device.address} status=$status")
                        finish(null)
                        return
                    }
                    val discoveredServices = runCatching { gatt.services }.getOrDefault(emptyList())
                    val service = discoveredServices.firstOrNull { candidate ->
                        candidate.uuid == P2pBleProtocol.SERVICE_UUID
                    } ?: gatt.getService(P2pBleProtocol.SERVICE_UUID) ?: run {
                        val discoveredSummary = discoveredServices
                            .joinToString(separator = ", ") { it.uuid.toString() }
                            .ifBlank { "none" }
                        if (!refreshedGattCacheAfterMissingService) {
                            refreshedGattCacheAfterMissingService = true
                            Log.w(
                                TAG,
                                "P2P service missing on ${device.address}; refreshing GATT cache. discovered=[$discoveredSummary]"
                            )
                            refreshGattCache(gatt)
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (completed.get()) {
                                    return@postDelayed
                                }
                                beginServiceDiscovery(gatt)
                            }, P2P_SERVICE_DISCOVERY_RETRY_DELAY_MS)
                            return
                        }
                        Log.w(TAG, "P2P service still missing on ${device.address} after cache refresh")
                        finish(null)
                        return
                    }
                    val idCharacteristic = service.getCharacteristic(P2pBleProtocol.ID_CHARACTERISTIC_UUID)
                    val bootstrapCharacteristic = service.getCharacteristic(
                        P2pBleProtocol.BOOTSTRAP_CHARACTERISTIC_UUID
                    )
                    controlCharacteristic = service.getCharacteristic(P2pBleProtocol.CONTROL_CHARACTERISTIC_UUID)
                    Log.d(
                        TAG,
                        "P2P characteristics on ${device.address}: id=${characteristicDebug(idCharacteristic)}, bootstrap=${characteristicDebug(bootstrapCharacteristic)}, control=${characteristicDebug(controlCharacteristic)}"
                    )
                    if (idCharacteristic == null && bootstrapCharacteristic == null) {
                        Log.w(TAG, "P2P readable identity/bootstrap characteristics missing on ${device.address}")
                        finish(null)
                        return
                    }
                    if (!startCharacteristicRead(
                            gatt = gatt,
                            phase = "initial",
                            primary = idCharacteristic,
                            fallback = bootstrapCharacteristic
                        )
                    ) {
                        finish(null)
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "P2P characteristic read failed on ${device.address} uuid=${characteristic.uuid} status=$status")
                        finish(null)
                        return
                    }
                    when (characteristic.uuid) {
                        P2pBleProtocol.ID_CHARACTERISTIC_UUID -> {
                            val identity = value.toString(StandardCharsets.UTF_8).trim()
                            val normalizedIdentity = identity
                                .removePrefix("share:")
                                .removePrefix("SHARE:")
                                .trim()
                                .uppercase(Locale.US)
                            if (normalizedIdentity != expectedShareId) {
                                Log.w(
                                    TAG,
                                    "P2P identity mismatch for ${device.address}: expected=$expectedShareId actual=$normalizedIdentity"
                                )
                                finish(null)
                                return
                            }
                            val target = gatt.getService(P2pBleProtocol.SERVICE_UUID)
                                ?.getCharacteristic(P2pBleProtocol.BOOTSTRAP_CHARACTERISTIC_UUID)
                            if (!startCharacteristicRead(
                                    gatt = gatt,
                                    phase = "bootstrap",
                                    primary = target
                                )
                            ) {
                                finish(null)
                            }
                        }

                        P2pBleProtocol.BOOTSTRAP_CHARACTERISTIC_UUID -> {
                            val payload = parseBootstrapPayload(
                                bytes = value,
                                expectedShareId = expectedShareId
                            ) ?: run {
                                Log.w(TAG, "Invalid P2P bootstrap payload from ${device.address}")
                                finish(null)
                                return
                            }
                            if (
                                payload.protocolVersion != P2pBleProtocol.PROTOCOL_VERSION ||
                                payload.serverNonce.isBlank() ||
                                payload.serverDeviceId.isBlank()
                            ) {
                                Log.w(TAG, "Incomplete P2P bootstrap payload from ${device.address}")
                                finish(null)
                                return
                            }
                            val control = controlCharacteristic
                            if (control == null) {
                                Log.d(
                                    TAG,
                                    "Resolved P2P bootstrap directly for ${payload.shareId} from ${device.address}"
                                )
                                BleBroadcastDirectory.update(
                                    broadcastId = payload.shareId,
                                    address = device.address,
                                    lastSeen = System.currentTimeMillis()
                                )
                                finish(
                                    P2pResolvedShare(
                                        shareId = payload.shareId,
                                        address = device.address,
                                        sessionCode = payload.sessionCode,
                                        displayName = payload.displayName,
                                        avatarBase64 = payload.avatarBase64,
                                        platform = payload.platform,
                                        remoteDeviceId = payload.serverDeviceId
                                    )
                                )
                                return
                            }

                            bootstrapPayload = payload
                            clientNonce = P2pBleProtocol.randomNonceBase64()
                            val helloPayload = buildClientHelloPayload(payload) ?: run {
                                finish(null)
                                return
                            }
                            controlStage = ControlStage.SERVER_HELLO
                            val started = startControlWrite(gatt, helloPayload)
                            if (!started) {
                                Log.w(TAG, "Unable to start P2P client_hello write for ${device.address}")
                                finish(null)
                            }
                        }

                        P2pBleProtocol.CONTROL_CHARACTERISTIC_UUID -> {
                            val controlBytes = if (value.isNotEmpty()) {
                                value
                            } else {
                                characteristic.value ?: ByteArray(0)
                            }
                            if (controlBytes.isEmpty()) {
                                Log.w(TAG, "Empty P2P control payload from ${device.address} stage=$controlStage")
                                if (retryControlRead(gatt, "empty payload")) {
                                    return
                                }
                                finish(null)
                                return
                            }
                            val controlRaw = controlBytes.toString(Charsets.UTF_8)
                            val controlPayload = runCatching {
                                JSONObject(controlRaw)
                            }.getOrNull() ?: run {
                                Log.w(TAG, "Malformed P2P control payload from ${device.address}: $controlRaw")
                                if (retryControlRead(gatt, "malformed payload")) {
                                    return
                                }
                                finish(null)
                                return
                            }
                            val controlType = controlPayload.optString("type").trim()
                            Log.d(
                                TAG,
                                "Received P2P control payload type=$controlType stage=$controlStage from ${device.address}"
                            )

                            when (controlStage) {
                                ControlStage.SERVER_HELLO -> {
                                    if (controlType == P2pBleProtocol.TYPE_ERROR) {
                                        Log.w(
                                            TAG,
                                            "P2P server returned error for ${device.address}: code=${controlPayload.optString("code")} message=${controlPayload.optString("message")}"
                                        )
                                        finish(null)
                                        return
                                    }
                                    val payload = bootstrapPayload ?: run {
                                        finish(null)
                                        return
                                    }
                                    val remoteShareId = controlPayload.optString("shareId").trim().uppercase(Locale.US)
                                    val remoteSessionCode = controlPayload.optString("sessionCode").trim()
                                    val remoteDeviceId = controlPayload.optString("serverDeviceId").trim()
                                    val remoteNonce = controlPayload.optString("serverNonce").trim()
                                    val remoteName = controlPayload.optString("serverName").trim()
                                        .takeIf { it.isNotBlank() }
                                    val remoteAvatarBase64 = controlPayload.optString("avatarB64").trim()
                                        .takeIf { it.isNotBlank() }
                                    val proof = controlPayload.optString("proof").trim()
                                    if (
                                        controlType != P2pBleProtocol.TYPE_SERVER_HELLO ||
                                        remoteShareId != payload.shareId ||
                                        remoteSessionCode != payload.sessionCode ||
                                        remoteDeviceId != payload.serverDeviceId ||
                                        remoteNonce != payload.serverNonce ||
                                        proof.isBlank()
                                    ) {
                                        Log.w(
                                            TAG,
                                            "Invalid P2P server_hello from ${device.address}: type=$controlType shareId=$remoteShareId sessionCode=$remoteSessionCode deviceId=$remoteDeviceId noncePresent=${remoteNonce.isNotBlank()} proofPresent=${proof.isNotBlank()}"
                                        )
                                        finish(null)
                                        return
                                    }
                                    if (!proofMatchesRemotePlatform(
                                            actualProof = proof,
                                            remotePlatform = payload.platform,
                                            "type" to P2pBleProtocol.TYPE_SERVER_HELLO,
                                            "shareId" to payload.shareId,
                                            "serverSessionCode" to payload.sessionCode,
                                            "serverDeviceId" to payload.serverDeviceId,
                                            "serverNonce" to payload.serverNonce,
                                            "clientSessionCode" to localSessionCode,
                                            "clientDeviceId" to localDeviceId,
                                            "clientNonce" to clientNonce.orEmpty(),
                                            "clientName" to localUserName,
                                            "clientPlatform" to "android",
                                            "serverName" to (remoteName ?: payload.displayName.orEmpty()),
                                            "serverPlatform" to payload.platform
                                        )
                                    ) {
                                        Log.w(TAG, "P2P server_hello proof mismatch from ${device.address}")
                                        finish(null)
                                        return
                                    }
                                    authenticatedServerName = remoteName ?: payload.displayName
                                    authenticatedServerAvatarBase64 = remoteAvatarBase64 ?: payload.avatarBase64
                                    controlStage = ControlStage.SERVER_FINISH
                                    val finishProof = P2pBleProtocol.hmacBase64(
                                        aesKey,
                                        buildRemoteProofPayload(
                                            payload.platform,
                                            "type" to P2pBleProtocol.TYPE_CLIENT_FINISH,
                                            "shareId" to payload.shareId,
                                            "serverSessionCode" to payload.sessionCode,
                                            "serverDeviceId" to payload.serverDeviceId,
                                            "serverNonce" to payload.serverNonce,
                                            "clientSessionCode" to localSessionCode,
                                            "clientDeviceId" to localDeviceId,
                                            "clientNonce" to clientNonce.orEmpty(),
                                            "clientPlatform" to "android",
                                            "serverHelloProof" to proof
                                        )
                                    ) ?: run {
                                        finish(null)
                                        return
                                    }
                                    val started = startControlWrite(
                                        gatt,
                                        JSONObject().apply {
                                            put("type", P2pBleProtocol.TYPE_CLIENT_FINISH)
                                            put("proof", finishProof)
                                        }
                                    )
                                    if (!started) {
                                        finish(null)
                                    }
                                }

                                ControlStage.SERVER_FINISH -> {
                                    if (controlType != P2pBleProtocol.TYPE_SERVER_FINISH) {
                                        Log.w(TAG, "Unexpected P2P control type during server_finish from ${device.address}: $controlType")
                                        finish(null)
                                        return
                                    }
                                    if (!controlPayload.optString("status").trim().equals("ok", ignoreCase = true)) {
                                        Log.w(TAG, "P2P server_finish reported non-ok status from ${device.address}")
                                        finish(null)
                                        return
                                    }
                                    val payload = bootstrapPayload ?: run {
                                        finish(null)
                                        return
                                    }
                                    BleBroadcastDirectory.update(
                                        broadcastId = payload.shareId,
                                        address = device.address,
                                        lastSeen = System.currentTimeMillis()
                                    )
                                    finish(
                                        P2pResolvedShare(
                                            shareId = payload.shareId,
                                            address = device.address,
                                            sessionCode = payload.sessionCode,
                                            displayName = authenticatedServerName ?: payload.displayName,
                                            avatarBase64 = authenticatedServerAvatarBase64 ?: payload.avatarBase64,
                                            platform = payload.platform,
                                            remoteDeviceId = payload.serverDeviceId
                                        )
                                    )
                                }

                                null -> finish(null)
                            }
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        return
                    }
                    onCharacteristicRead(
                        gatt,
                        characteristic,
                        characteristic.value ?: ByteArray(0),
                        status
                    )
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "P2P characteristic write failed on ${device.address} uuid=${characteristic.uuid} status=$status")
                        finish(null)
                        return
                    }
                    if (characteristic.uuid != P2pBleProtocol.CONTROL_CHARACTERISTIC_UUID) {
                        finish(null)
                        return
                    }
                    val control = controlCharacteristic ?: run {
                        finish(null)
                        return
                    }
                    if (!startCharacteristicRead(
                            gatt = gatt,
                            phase = "control",
                            primary = control
                        )
                    ) {
                        finish(null)
                    }
                }
            }

            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }

            if (gatt == null) {
                finish(null)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                finish(null)
            }
        }
    }
}

private fun parseBootstrapPayload(bytes: ByteArray, expectedShareId: String): P2pBootstrapPayload? {
    val raw = bytes.toString(StandardCharsets.UTF_8).trim()
    if (raw.isBlank()) {
        return null
    }
    return runCatching {
        val json = JSONObject(raw)
        val shareId = json.optString("shareId")
            .trim()
            .uppercase(Locale.US)
            .takeIf { it.isNotBlank() }
            ?: return@runCatching null
        if (shareId != expectedShareId) {
            return@runCatching null
        }
        val sessionCode = json.optString("sessionCode")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val platform = json.optString("platform")
            .trim()
            .lowercase(Locale.US)
            .takeIf { it.isNotBlank() }
            ?: "android"
        val displayName = json.optString("name")
            .trim()
            .takeIf { it.isNotBlank() }
        val avatarBase64 = json.optString("avatarB64")
            .trim()
            .takeIf { it.isNotBlank() }
        val serverNonce = json.optString("serverNonce")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val serverDeviceId = json.optString("serverDeviceId")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return@runCatching null
        P2pBootstrapPayload(
            shareId = shareId,
            sessionCode = sessionCode,
            displayName = displayName,
            avatarBase64 = avatarBase64,
            platform = platform,
            protocolVersion = json.optInt("protocolVersion", 1),
            serverNonce = serverNonce,
            serverDeviceId = serverDeviceId
        )
    }.getOrNull()
}

private const val TAG = "P2pShareResolver"
