package com.auralis.crisisconnect.screens.QR

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.BleBroadcastDirectory
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.DeviceError
import com.auralis.crisisconnect.data.DeviceResult
import com.auralis.crisisconnect.data.deleteContact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getContactByRemoteSessionCode
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.markContactVerified
import com.auralis.crisisconnect.data.normalizeMacAddress
import com.auralis.crisisconnect.data.normalizeVerifiedIdentityKey
import com.auralis.crisisconnect.data.pairDevice
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_BLE_GATT
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_RFCOMM
import com.auralis.crisisconnect.data.REMOTE_PLATFORM_IOS
import com.auralis.crisisconnect.data.REMOTE_PLATFORM_UNKNOWN
import com.auralis.crisisconnect.data.normalizeRemotePlatform
import com.auralis.crisisconnect.data.saveBleContactAndMigrateLegacySession
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.data.updateContactName
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.service.BluetoothClassicDiscoveryGuard
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.p2p.resolveP2pShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_DISCOVERY_TIMEOUT = 15_000L
private const val LEGACY_DISCOVERY_FALLBACK_TIMEOUT = 8_000L
private const val MAX_QR_DECODE_ATTEMPTS = 2
private const val MAX_BONDED_HANDSHAKE_PROBE_ADDRESSES = 6

private fun Context.hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private fun hasLegacyLocationPermission(context: Context): Boolean {
    return context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
        context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
}

private fun hasDiscoveryPermission(context: Context): Boolean {
    val hasLocation = hasLegacyLocationPermission(context)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Classic discovery via startDiscovery() may still need location permission/service
        // on some devices even on newer Android versions.
        context.hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasLocation
    } else {
        hasLocation
    }
}

private fun hasPairingPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        true
    }
}

@SuppressLint("MissingPermission")
private fun bondedDevicesOrEmpty(
    context: Context,
    adapter: BluetoothAdapter
): Set<BluetoothDevice> {
    if (!hasPairingPermission(context)) {
        return emptySet()
    }
    return runCatching { adapter.bondedDevices }
        .onFailure { throwable ->
            Log.e("QRHelpers", "Unable to read bonded devices", throwable)
        }
        .getOrDefault(emptySet())
}

@SuppressLint("MissingPermission")
private fun isDeviceBonded(context: Context, device: BluetoothDevice): Boolean {
    if (!hasPairingPermission(context)) {
        return false
    }
    return runCatching { device.bondState == BluetoothDevice.BOND_BONDED }
        .onFailure { throwable ->
            Log.e("QRHelpers", "Unable to read bond state", throwable)
        }
        .getOrDefault(false)
}

@SuppressLint("MissingPermission")
private fun deviceAddressOrNull(context: Context, device: BluetoothDevice): String? {
    if (!hasPairingPermission(context)) {
        return null
    }
    return runCatching { device.address }
        .onFailure { throwable ->
            Log.e("QRHelpers", "Unable to read Bluetooth device address", throwable)
        }
        .getOrNull()
}

@SuppressLint("MissingPermission")
private fun isDiscovering(context: Context, adapter: BluetoothAdapter): Boolean {
    if (!hasDiscoveryPermission(context)) {
        return false
    }
    return runCatching { adapter.isDiscovering }
        .onFailure { throwable ->
            Log.e("QRHelpers", "Unable to check discovery state", throwable)
        }
        .getOrDefault(false)
}

@SuppressLint("MissingPermission")
private fun cancelDiscovery(context: Context, adapter: BluetoothAdapter): Boolean {
    if (!hasDiscoveryPermission(context)) {
        return false
    }
    return runCatching { adapter.cancelDiscovery() }
        .onFailure { throwable ->
            Log.e("QRHelpers", "Unable to cancel discovery", throwable)
        }
        .getOrDefault(false)
}

@SuppressLint("MissingPermission")
private fun startDiscovery(context: Context, adapter: BluetoothAdapter): Boolean {
    if (!hasDiscoveryPermission(context)) {
        return false
    }
    return runCatching { adapter.startDiscovery() }
        .onFailure { throwable ->
            Log.e("QRHelpers", "Unable to start discovery", throwable)
        }
        .getOrDefault(false)
}

private fun decodeUriPayload(raw: String): String {
    var current = raw
    repeat(MAX_QR_DECODE_ATTEMPTS) {
        val decoded = decodeUriCompat(current)
        if (decoded == current) {
            return current
        }
        current = decoded
    }
    return current
}

private fun decodeUriCompat(raw: String): String {
    val androidDecoded = runCatching { Uri.decode(raw) }.getOrNull()
    if (!androidDecoded.isNullOrBlank()) {
        return androidDecoded
    }
    return runCatching {
        URLDecoder.decode(raw, Charsets.UTF_8.name())
    }.getOrElse { raw }
}

private fun looksLikeSessionCode(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.length !in 4..32) return false
    return trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

private fun looksLikeAesKey(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.length !in 12..64) return false
    return trimmed.all { it.isLetterOrDigit() || it == '=' || it == '/' || it == '+' || it == '-' || it == '_' }
}

@SuppressLint("MissingPermission")
private fun resolveBluetoothDeviceName(
    context: Context,
    device: BluetoothDevice
): String? {
    if (!hasPairingPermission(context)) {
        return null
    }
    val directName = runCatching {
        device.name?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
    if (directName != null) {
        return directName
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return null
    }
    return runCatching { device.alias }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun normalizeMatchName(raw: String?): String? = raw
    ?.trim()
    ?.trim('"')
    ?.trim()
    ?.replace('\n', ' ')
    ?.replace('\r', ' ')
    ?.lowercase(Locale.ROOT)
    ?.takeIf { it.isNotBlank() }

private fun isTargetDeviceNameMatch(
    context: Context,
    device: BluetoothDevice,
    targetName: String
): Boolean {
    val target = normalizeMatchName(targetName) ?: return false
    val candidateName = normalizeMatchName(resolveBluetoothDeviceName(context, device)) ?: return false
    return candidateName == target
}

private fun isTargetDeviceNameMatch(candidateName: String?, targetName: String): Boolean {
    val target = normalizeMatchName(targetName) ?: return false
    val candidate = normalizeMatchName(candidateName) ?: return false
    return candidate == target
}

private fun readJsonField(
    obj: JSONObject,
    keys: List<String>
): String? {
    val normalizedKeys = keys.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    val iterator = obj.keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        if (normalizedKeys.contains(key.lowercase(Locale.ROOT))) {
            val value = obj.optString(key).trim()
            if (value.isNotBlank()) {
                return value
            }
        }
    }
    return null
}

private fun readJsonBooleanField(
    obj: JSONObject,
    keys: List<String>
): Boolean? {
    val normalizedKeys = keys.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    val iterator = obj.keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        if (!normalizedKeys.contains(key.lowercase(Locale.ROOT))) {
            continue
        }
        val raw = obj.opt(key) ?: continue
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> {
                when (raw.trim().lowercase(Locale.ROOT)) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> null
                }
            }
            else -> null
        }
    }
    return null
}

data class QrData(
    val sessionCode: String,
    val aesKey: String,
    val name: String? = null,
    val bluetoothName: String? = null,
    val shareId: String? = null,
    val qraId: String? = null,
    val platform: String,
    val bleFallbackCapable: Boolean = false
)

private fun candidateDiscoveryNames(data: QrData, overrideName: String? = null): List<String> {
    return buildList {
        overrideName?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        data.name?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        data.bluetoothName?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        add(data.sessionCode)
    }.asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.lowercase(Locale.ROOT) }
        .distinct()
        .toList()
}

private fun findBondedDeviceByCandidates(
    context: Context,
    adapter: BluetoothAdapter,
    candidateNames: List<String>
): BluetoothDevice? {
    return bondedDevicesOrEmpty(context, adapter).firstOrNull { device ->
        candidateNames.any { candidateName ->
            isTargetDeviceNameMatch(context, device, candidateName)
        }
    }
}

private fun findBondedDeviceByAddress(
    context: Context,
    adapter: BluetoothAdapter,
    address: String?
): BluetoothDevice? {
    val normalizedAddress = address?.trim()?.uppercase()
    if (normalizedAddress.isNullOrBlank()) {
        return null
    }
    return bondedDevicesOrEmpty(context, adapter).firstOrNull { device ->
        deviceAddressOrNull(context, device)?.trim()?.uppercase() == normalizedAddress
    }
}

private fun normalizeQrShareId(raw: String?): String {
    return raw?.trim()?.uppercase(Locale.US).orEmpty()
}

private fun findStoredContactForQr(
    context: Context,
    data: QrData,
    expectedName: String?
): Contact? {
    val expectedShareId = normalizeQrShareId(data.shareId)
    fun canReuseStoredContact(contact: Contact?): Boolean {
        if (contact == null) {
            return false
        }
        val hasReusableIdentity = contact.address.isNotBlank() ||
            contact.lastKnownBleAddress.isNotBlank() ||
            contact.remoteDeviceId.isNotBlank() ||
            normalizeQrShareId(contact.bleShareId).isNotBlank()
        if (!hasReusableIdentity) {
            return false
        }
        if (expectedShareId.isBlank()) {
            return true
        }
        return normalizeQrShareId(contact.bleShareId) == expectedShareId
    }
    val sessionContact = runCatching { getContact(context, data.sessionCode) }.getOrNull()
    if (canReuseStoredContact(sessionContact)) {
        return sessionContact
    }

    val remoteSessionContact = runCatching {
        getContactByRemoteSessionCode(context, data.sessionCode)
    }.getOrNull()
    if (canReuseStoredContact(remoteSessionContact)) {
        return remoteSessionContact
    }

    val contacts = runCatching { getContacts(context) }.getOrNull() ?: return null

    if (expectedShareId.isNotBlank()) {
        contacts.firstOrNull { contact ->
            contact.address.isNotBlank() &&
                normalizeQrShareId(contact.bleShareId) == expectedShareId
        }?.let { return it }
        return null
    }

    val normalizedAesKey = data.aesKey.trim()
    if (normalizedAesKey.isNotBlank()) {
        contacts.firstOrNull { it.aesKey.trim() == normalizedAesKey && it.address.isNotBlank() }?.let {
            return it
        }
    }

    val normalizedExpectedNames = buildSet {
        expectedName?.let { add(normalizeMatchName(it)) }
        data.name?.let { add(normalizeMatchName(it)) }
    }.filterNotNull().toSet()

    val sessionMatch = normalizeMatchName(data.sessionCode)
    if (sessionMatch != null) {
        contacts.firstOrNull {
            it.address.isNotBlank() && normalizeMatchName(it.sessionCode) == sessionMatch
        }?.let { return it }
    }

    if (normalizedExpectedNames.isEmpty()) {
        return null
    }

    contacts.firstOrNull { contact ->
        val contactName = normalizeMatchName(contact.name) ?: return@firstOrNull false
        normalizedExpectedNames.contains(contactName) && contact.address.isNotBlank()
    }?.let { return it }

    return null
}

private fun findBondedDeviceFromStoredContacts(
    context: Context,
    adapter: BluetoothAdapter,
    contact: Contact?
): BluetoothDevice? {
    return contact?.let { findBondedDeviceByAddress(context, adapter, it.address) }
}

private fun resolveStoredName(
    overrideName: String?,
    payloadName: String?,
    sessionCode: String
): String {
    return overrideName?.trim()?.takeIf { it.isNotBlank() }
        ?: payloadName?.trim()?.takeIf { it.isNotBlank() }
        ?: sessionCode.trim()
}

private fun isPlaceholderQrDisplayName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isBlank() || trimmed.equals("Crisis Connect", ignoreCase = true)
}

private fun bleSessionCodeForPeer(identifier: String): String {
    val normalizedIdentifier = identifier.trim().uppercase(Locale.US)
    return BleSessionResolver.normalizeSessionCode("ble:$normalizedIdentifier")
        ?: "ble:$normalizedIdentifier"
}

private fun shouldPersistBlePrimaryTransport(
    remotePlatform: String,
    sessionCode: String
): Boolean {
    val normalizedPlatform = normalizeRemotePlatform(remotePlatform)
    return normalizedPlatform == REMOTE_PLATFORM_IOS ||
        sessionCode.trim().startsWith("ble:", ignoreCase = true)
}

private suspend fun tryBleShareQrFallback(
    context: Context,
    data: QrData,
    finalName: String,
    originalSessionContact: Contact?,
    onStatus: ((QrConnectionStage) -> Unit)? = null
): DeviceResult<String> {
    val shareId = data.shareId?.trim()?.takeIf { it.isNotBlank() }
        ?: return DeviceResult.Error(DeviceError.DEVICE_NOT_FOUND)
    onStatus?.invoke(QrConnectionStage.SEARCHING)
    val resolved = resolveP2pShare(
        context = context,
        shareId = shareId,
        aesKeyBase64 = data.aesKey
    )
        ?: return DeviceResult.Error(DeviceError.DEVICE_NOT_FOUND)
    if (!resolved.sessionCode.equals(data.sessionCode, ignoreCase = true)) {
        Log.w(
            "QRHelpers",
            "P2P bootstrap session mismatch. expected=${data.sessionCode}, actual=${resolved.sessionCode}"
        )
        return DeviceResult.Error(DeviceError.HANDSHAKE_FAILED)
    }
    onStatus?.invoke(QrConnectionStage.PAIRING)
    val bleSessionCode = bleSessionCodeForPeer(
        resolved.remoteDeviceId.ifBlank { resolved.shareId }
    )
    val resolvedName = resolved.displayName?.trim()?.takeIf { it.isNotBlank() } ?: finalName
    val remotePlatform = normalizeRemotePlatform(
        resolved.platform.ifBlank { data.platform }.ifBlank { REMOTE_PLATFORM_UNKNOWN }
    )
    val classicSessionCode = originalSessionContact?.sessionCode
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: data.sessionCode
    val useBlePrimary = shouldPersistBlePrimaryTransport(
        remotePlatform = remotePlatform,
        sessionCode = classicSessionCode
    )
    val savedContact = withContext(Dispatchers.IO) {
        saveBleContactAndMigrateLegacySession(
            context = context,
            contact = Contact(
                name = resolvedName,
                aesKey = data.aesKey,
                sessionCode = if (useBlePrimary) bleSessionCode else classicSessionCode,
                verified = resolved.remoteDeviceId.isNotBlank(),
                verifiedIdentityKey = normalizeVerifiedIdentityKey(resolved.remoteDeviceId),
                verifiedAt = System.currentTimeMillis(),
                address = if (useBlePrimary) {
                    resolved.address
                } else {
                    originalSessionContact?.address?.trim().orEmpty()
                },
                remoteSessionCode = originalSessionContact?.remoteSessionCode
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: data.sessionCode,
                preferredTransport = if (useBlePrimary) {
                    PREFERRED_TRANSPORT_BLE_GATT
                } else {
                    PREFERRED_TRANSPORT_RFCOMM
                },
                remotePlatform = remotePlatform,
                bleShareId = resolved.shareId,
                lastKnownBleAddress = resolved.address,
                remoteDeviceId = resolved.remoteDeviceId
            ),
            migrateFromSessionCode = if (useBlePrimary) {
                originalSessionContact?.sessionCode ?: data.sessionCode
            } else {
                originalSessionContact?.sessionCode
                    ?.takeIf { !it.equals(classicSessionCode, ignoreCase = true) }
                    ?: bleSessionCode.takeIf { !it.equals(classicSessionCode, ignoreCase = true) }
            }
        )
    }
    withContext(Dispatchers.IO) {
        resolved.avatarBase64
            ?.takeIf { it.isNotBlank() }
            ?.let { avatarPayload ->
                ContactAvatarStorage.saveRemoteAvatarPayload(
                    context = context,
                    sessionCode = savedContact.sessionCode,
                    payloadBase64 = avatarPayload
                )
            }
    }
    return DeviceResult.Success(savedContact.sessionCode)
}

private fun bondedProbeScore(
    deviceName: String?,
    normalizedCandidateNames: Set<String>
): Int {
    val normalizedName = normalizeMatchName(deviceName) ?: return 0
    // Name-based discovery is not reliable across vendors/OS versions.
    // We keep a score so bonded-device handshake probing can still recover
    // when QR name/session does not exactly match the advertised Bluetooth name.
    if (normalizedCandidateNames.any { candidate ->
            normalizedName.contains(candidate) || candidate.contains(normalizedName)
        }) {
        return 3
    }
    if (looksLikeSessionCode(normalizedName)) {
        return 2
    }
    return 1
}

private fun parseLegacyQrParts(parts: List<String>): QrData? {
    val cleaned = parts.map { it.trim() }.filter { it.isNotBlank() }
    if (cleaned.isEmpty()) return null

    fun parseCandidate(session: String, key: String, name: String?): QrData? {
        if (session.isBlank() || key.isBlank()) return null
        if (!looksLikeSessionCode(session) || !looksLikeAesKey(key)) return null
        return QrData(
            sessionCode = session,
            aesKey = key,
            name = name?.trim()?.takeIf { it.isNotBlank() },
            platform = "android"
        )
    }

    if (cleaned.size == 2) {
        val first = cleaned[0]
        val second = cleaned[1]

        if (looksLikeAesKey(first) && !looksLikeAesKey(second)) {
            parseCandidate(session = second, key = first, name = null)?.let { return it }
        }
        if (!looksLikeAesKey(first) && looksLikeAesKey(second)) {
            parseCandidate(session = first, key = second, name = null)?.let { return it }
        }

        return QrData(
            sessionCode = first,
            aesKey = second,
            platform = "android"
        )
    }

    if (cleaned.size == 3) {
        val candidates = listOf(
            Triple(0, 1, 2),
            Triple(2, 1, 0),
            Triple(0, 2, 1),
            Triple(2, 0, 1)
        )
        candidates.forEach { (sessionIndex, keyIndex, nameIndex) ->
            parseCandidate(
                session = cleaned[sessionIndex],
                key = cleaned[keyIndex],
                name = cleaned[nameIndex]
            )?.let { return it }
        }

        return QrData(
            sessionCode = cleaned[2],
            aesKey = cleaned[1],
            name = cleaned[0].takeIf { it.isNotBlank() },
            platform = "android"
        )
    }

    return null
}

private fun parseQrJsonPayload(json: String): QrData? {
    val source = json.trim()
    if (source.isEmpty()) return null

    fun parseObject(raw: String): QrData? = runCatching {
        val obj = JSONObject(raw)
        val code = readJsonField(obj, listOf("code", "sessionCode", "session", "session_code", "sid"))
            ?: return@runCatching null
        val key = readJsonField(obj, listOf("key", "aesKey", "secret", "sharedKey", "sessionKey"))
            ?: return@runCatching null
        val name = readJsonField(obj, listOf("name", "username", "userName", "displayName", "display_name"))
        val qraId = readJsonField(
            obj,
            listOf("qraId", "qrId", "rescueDeviceId", "deviceId", "ccid")
        )
        val shareId = readJsonField(
            obj,
            listOf("shareId", "share_id", "bleShareId", "ble_share_id")
        )
        val platformRaw = readJsonField(
            obj,
            listOf("platform", "clientPlatform", "client_platform", "os")
        ) ?: return@runCatching null
        val platform = when (platformRaw.trim().lowercase(Locale.ROOT)) {
            "android" -> "android"
            "ios" -> "ios"
            else -> return@runCatching null
        }
        val bleFallbackCapable = readJsonBooleanField(
            obj,
            listOf("bleFallbackCapable", "ble_gatt_fallback", "highRange", "high_range")
        ) ?: false
        QrData(
            sessionCode = code,
            aesKey = key,
            name = name,
            bluetoothName = readJsonField(
                obj,
                listOf(
                    "bluetoothName",
                    "bluetooth_name",
                    "btName",
                    "deviceName",
                    "device_name"
                )
            ),
            shareId = shareId,
            qraId = qraId,
            platform = platform,
            bleFallbackCapable = bleFallbackCapable
        )
    }.getOrNull()

    parseObject(source)?.let { return it }

    val firstBrace = source.indexOf('{')
    val lastBrace = source.lastIndexOf('}')
    if (firstBrace >= 0 && lastBrace > firstBrace) {
        parseObject(source.substring(firstBrace, lastBrace + 1))?.let { return it }
    }

    val decoded = decodeUriPayload(source)
    if (decoded != source) {
        parseObject(decoded)?.let { return it }
        val decodedFirst = decoded.indexOf('{')
        val decodedLast = decoded.lastIndexOf('}')
        if (decodedFirst >= 0 && decodedLast > decodedFirst) {
            parseObject(decoded.substring(decodedFirst, decodedLast + 1))?.let { return it }
        }
    }

    return null
}

enum class QrConnectionStage {
    SEARCHING,
    PAIRING,
    SAVING
}

sealed class QrConnectResult {
    data class Saved(val sessionCode: String) : QrConnectResult()
    data class AlreadyRegistered(val sessionCode: String) : QrConnectResult()
    data class Failure(val code: DeviceError) : QrConnectResult()
}

internal fun normalizeQrFailureCodeAfterHandshakeAttempt(
    code: DeviceError,
    attemptedHandshake: Boolean
): DeviceError {
    return if (attemptedHandshake && code == DeviceError.DEVICE_NOT_FOUND) {
        DeviceError.HANDSHAKE_FAILED
    } else {
        code
    }
}

fun parseQrPayload(raw: String): QrData {
    Log.d("QRHelpers", "Parsing QR payload")
    val text = raw.trim()
    if (text.isEmpty()) {
        Log.e(
            "QRHelpers",
            "Failed to parse QR format: empty payload",
            IllegalArgumentException("Empty payload")
        )
        error("Unsupported QR format")
    }

    if (text.startsWith("dcs://", ignoreCase = true)) {
        Log.d("QRHelpers", "Detected DCS protocol")
        val rawJson = text.substring(text.indexOf("://") + 3)
        val decoded = decodeUriPayload(rawJson)

        parseQrJsonPayload(decoded)?.let { parsed ->
            Log.d("QRHelpers", "Successfully parsed QR JSON payload")
            return parsed
        }

        Log.e("QRHelpers", "Failed to parse DCS payload")
        error("Unsupported QR format")
    }

    if (text.startsWith("{")) {
        return parseQrJsonPayload(text)?.let { parsed ->
            Log.d("QRHelpers", "Successfully parsed QR JSON payload")
            parsed
        } ?: run {
            Log.e("QRHelpers", "Failed to parse JSON payload")
            error("Unsupported QR format")
        }
    }

    Log.e("QRHelpers", "Unsupported QR format: expected DCS JSON payload with platform")
    error("Unsupported QR format")
}

suspend fun findDeviceByName(
    context: Context,
    targetName: String,
    timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT
): BluetoothDevice? = suspendCancellableCoroutine { cont ->
    Log.d("QRHelpers", "Searching for device with name: '$targetName'")
    val adapter = BluetoothAdapter.getDefaultAdapter()
    if (adapter == null) {
        Log.w("QRHelpers", "Bluetooth adapter is null")
        cont.resume(null)
        return@suspendCancellableCoroutine
    }

    if (!hasDiscoveryPermission(context)) {
        Log.w(
            "QRHelpers",
            "Missing discovery prerequisites (scan permission)"
        )
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    val trimmedTarget = targetName.trim()
    if (trimmedTarget.isEmpty()) {
        Log.w("QRHelpers", "Target name is empty")
        cont.resume(null)
        return@suspendCancellableCoroutine
    }

    val bondedDevices = bondedDevicesOrEmpty(context, adapter)
    Log.d("QRHelpers", "Checking ${bondedDevices.size} bonded devices")
    bondedDevices.forEach { device ->
        val name = resolveBluetoothDeviceName(context, device)
        Log.d("QRHelpers", "Bonded device: '${name ?: ""}' vs target: '$trimmedTarget'")
        if (isTargetDeviceNameMatch(context, device, trimmedTarget)) {
            Log.d("QRHelpers", "Found matching bonded device!")
            cont.resume(device)
            return@suspendCancellableCoroutine
        }
    }

    val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }

    var discoveryHold: BluetoothClassicDiscoveryGuard.Hold? = null
    val finished = AtomicBoolean(false)
    val receiverRegistered = AtomicBoolean(false)
    var timeoutJob: kotlinx.coroutines.Job? = null
    var receiverInstance: BroadcastReceiver? = null

    val cleanup: (BluetoothDevice?) -> Unit = cleanup@{ result ->
        if (!finished.compareAndSet(false, true)) return@cleanup
        timeoutJob?.cancel()

        try {
            if (isDiscovering(context, adapter)) {
                cancelDiscovery(context, adapter)
            }
        } catch (ex: Exception) {
            Log.e("QRHelpers", "Failed to cancel discovery during cleanup", ex)
        }

        if (receiverRegistered.compareAndSet(true, false)) {
            receiverInstance?.let { unregisterSafely(context, it) }
        }
        discoveryHold?.release()
        discoveryHold = null

        if (cont.isActive) {
            cont.resume(result)
        }
    }

    receiverInstance = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND,
                BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val broadcastName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    val name = broadcastName ?: device?.let { resolveBluetoothDeviceName(context, it) }
                    val maskedAddress = device?.let { found ->
                        deviceAddressOrNull(context, found)?.let { "***${it.takeLast(5)}" }
                    }
                    Log.d("QRHelpers", "Found device: '$name' (address: $maskedAddress)")
                    if (device != null && isTargetDeviceNameMatch(name, trimmedTarget)) {
                        Log.d("QRHelpers", "Device name matches! Found target device")
                        cleanup(device)
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d("QRHelpers", "Discovery finished - device not found")
                    cleanup(null)
                }
            }
        }
    }
    val discoveryReceiver = receiverInstance ?: run {
        Log.e("QRHelpers", "Failed to initialize discovery receiver")
        cont.resume(null)
        return@suspendCancellableCoroutine
    }

    if (isDiscovering(context, adapter)) {
        runCatching {
            Log.d("QRHelpers", "Discovery already running, cancelling existing scan")
            cancelDiscovery(context, adapter)
        }.onFailure {
            Log.e("QRHelpers", "Failed to cancel existing discovery session", it)
        }
    }

    runCatching {
        discoveryHold = BluetoothClassicDiscoveryGuard.acquire()
        registerReceiverCompat(context, discoveryReceiver, filter)
        receiverRegistered.set(true)
    }.onFailure { ex ->
        Log.e("QRHelpers", "Failed to register discovery receiver", ex)
        cleanup(null)
        return@suspendCancellableCoroutine
    }

    val started = startDiscovery(context, adapter)

    if (!started) {
        Log.w("QRHelpers", "Failed to start discovery")
        cleanup(null)
        return@suspendCancellableCoroutine
    }

    Log.d("QRHelpers", "Discovery started: $started")

    timeoutJob = CoroutineScope(Dispatchers.Main).launch {
        delay(timeoutMs)
        if (cont.isActive && finished.compareAndSet(false, true)) {
            Log.d("QRHelpers", "Discovery timeout reached for '$trimmedTarget'")
            try {
                if (receiverRegistered.compareAndSet(true, false)) {
                    unregisterSafely(context, discoveryReceiver)
                }
                if (isDiscovering(context, adapter)) {
                    cancelDiscovery(context, adapter)
                }
            } catch (ex: Exception) {
                Log.e("QRHelpers", "Failed to cancel discovery on timeout", ex)
            }
            if (cont.isActive) {
                cont.resume(null)
            }
        }
    }

    cont.invokeOnCancellation {
        cleanup(null)
    }
}

fun connectUsingQr(
    context: Context,
    rawQrText: String,
    overrideName: String? = null,
    onStatus: ((QrConnectionStage) -> Unit)? = null,
    onResult: (QrConnectResult) -> Unit
) {
    val data = runCatching { parseQrPayload(rawQrText) }.onFailure {
        Log.e("QRHelpers", "Failed to parse QR payload", it)
    }.getOrElse {
        onResult(QrConnectResult.Failure(DeviceError.DEVICE_NOT_FOUND))
        return
    }
    Log.d(
        "QRHelpers",
        "QR Data parsed - sessionCode: ${data.sessionCode}, key present: ${data.aesKey.isNotEmpty()}, name: ${data.name}, platform: ${data.platform}"
    )
    when (data.platform.trim().lowercase(Locale.ROOT)) {
        "android" -> {
            // Android QR flow remains unchanged.
        }
        "ios" -> {
            Log.d("QRHelpers", "iOS platform QR detected; using BLE direct path")
        }
        else -> {
            Log.w(
                "QRHelpers",
                "Unknown platform '${data.platform}' in QR payload; using current Android connection flow"
            )
        }
    }

    val activity = context.findActivity()
    val lifecycleOwner = activity as? LifecycleOwner
    if (activity == null || lifecycleOwner == null) {
        Log.e("QRHelpers", "Failed to resolve lifecycle owner from context", IllegalStateException("Missing activity"))
        onResult(QrConnectResult.Failure(DeviceError.DEVICE_NOT_FOUND))
        return
    }

    lifecycleOwner.lifecycleScope.launch {
        fun finishFailure(code: DeviceError) {
            onResult(QrConnectResult.Failure(code))
        }

        suspend fun persistContact(contact: Contact) {
            withContext(Dispatchers.IO) {
                saveContact(context, contact)
            }
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.w("QRHelpers", "Bluetooth adapter is null")
            finishFailure(DeviceError.DEVICE_NOT_FOUND)
            return@launch
        }

        val baseName = overrideName?.takeIf { it.isNotBlank() }
            ?: data.name?.takeIf { it.isNotBlank() }
        val storedContact = withContext(Dispatchers.IO) {
            findStoredContactForQr(
                context = context,
                data = data,
                expectedName = baseName
            )
        }
        val originalSessionContact = withContext(Dispatchers.IO) {
            getContact(context, data.sessionCode)
        }
        suspend fun restoreOriginalClassicContact() {
            withContext(Dispatchers.IO) {
                if (originalSessionContact != null) {
                    saveContact(context, originalSessionContact)
                } else {
                    deleteContact(context, data.sessionCode)
                }
            }
        }
        val finalName = resolveStoredName(
            overrideName = overrideName,
            payloadName = data.name,
            sessionCode = data.sessionCode
        )
        storedContact?.sessionCode
            ?.takeIf { it.isNotBlank() }
            ?.let { existingSessionCode ->
                Log.d(
                    "QRHelpers",
                    "QR belongs to an already registered contact: $existingSessionCode"
                )
                onResult(QrConnectResult.AlreadyRegistered(existingSessionCode))
                return@launch
            }
        val forceFinalName = overrideName?.trim()?.isNotBlank() == true
        suspend fun ensureFinalContactName(
            savedSessionCode: String = data.sessionCode,
            forceOverride: Boolean = false
        ) {
            val desiredName = finalName.trim()
            if (desiredName.isBlank()) {
                return
            }
            withContext(Dispatchers.IO) {
                val latest = getContact(context, savedSessionCode) ?: return@withContext
                if (latest.name.equals(desiredName, ignoreCase = true)) {
                    return@withContext
                }
                val latestName = latest.name.trim()
                val shouldOverwrite = when {
                    forceOverride -> true
                    latestName.isBlank() -> true
                    latestName.equals(savedSessionCode, ignoreCase = true) -> true
                    isPlaceholderQrDisplayName(latestName) && !isPlaceholderQrDisplayName(desiredName) -> true
                    else -> false
                }
                if (shouldOverwrite) {
                    updateContactName(context, savedSessionCode, desiredName)
                }
            }
        }
        fun finishSuccess(savedSessionCode: String = data.sessionCode) {
            onStatus?.invoke(QrConnectionStage.SAVING)
            lifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    markContactVerified(context, savedSessionCode)
                }
                ensureFinalContactName(
                    savedSessionCode = savedSessionCode,
                    forceOverride = forceFinalName
                )
                onResult(QrConnectResult.Saved(savedSessionCode))
            }
        }
        suspend fun tryBleFallbackIfAvailable(reason: String): DeviceResult<String>? {
            val shareId = data.shareId?.trim()?.takeIf { it.isNotBlank() } ?: return null
            Log.d("QRHelpers", "Trying P2P BLE fallback for shareId=$shareId (reason=$reason)")
            return tryBleShareQrFallback(
                context = context,
                data = data,
                finalName = finalName,
                originalSessionContact = originalSessionContact,
                onStatus = onStatus
            )
        }
        suspend fun finishWithBleFallbackOrFailure(
            reason: String,
            failureCode: DeviceError = DeviceError.DEVICE_NOT_FOUND,
            attemptedHandshake: Boolean = false
        ) {
            when (val fallback = tryBleFallbackIfAvailable(reason)) {
                is DeviceResult.Success -> finishSuccess(fallback.data)
                is DeviceResult.Error -> finishFailure(
                    normalizeQrFailureCodeAfterHandshakeAttempt(
                        code = fallback.code,
                        attemptedHandshake = attemptedHandshake
                    )
                )
                null -> finishFailure(
                    normalizeQrFailureCodeAfterHandshakeAttempt(
                        code = failureCode,
                        attemptedHandshake = attemptedHandshake
                    )
                )
            }
        }

        if (data.platform.equals("ios", ignoreCase = true)) {
            finishWithBleFallbackOrFailure("ios_direct")
            return@launch
        }

        val discoveryAllowed = hasDiscoveryPermission(context)
        val pairingAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPairingPermission(context)
        } else {
            true
        }
        val locationEnabled = isLocationEnabled(context)

        if (!discoveryAllowed || !pairingAllowed || !locationEnabled) {
            Log.w(
                "QRHelpers",
                "Missing Bluetooth requirements - discoveryAllowed=$discoveryAllowed, pairingAllowed=$pairingAllowed, locationEnabled=$locationEnabled"
            )
            if (!locationEnabled) {
                runCatching {
                    activity.let {
                        val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        it.startActivity(settingsIntent)
                    }
                }.onFailure {
                    Log.e("QRHelpers", "Failed to open location settings", it)
                }
            }
            finishFailure(DeviceError.MISSING_PERMISSIONS)
            return@launch
        }
        val resolvedAddressFromQraId = data.qraId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { BleBroadcastDirectory.resolveAddress(it) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (!resolvedAddressFromQraId.isNullOrBlank()) {
            Log.d(
                "QRHelpers",
                "Resolved QRA ID ${data.qraId} to address ${"***${resolvedAddressFromQraId.takeLast(5)}"}"
            )
            val bondedByQra = findBondedDeviceByAddress(context, adapter, resolvedAddressFromQraId)
            onStatus?.invoke(QrConnectionStage.PAIRING)
            if (bondedByQra != null && isDeviceBonded(context, bondedByQra)) {
                persistContact(
                    Contact(
                        name = finalName,
                        aesKey = data.aesKey,
                        sessionCode = data.sessionCode,
                        address = resolvedAddressFromQraId
                    )
                )
                verifySecureHandshake(
                    context = context,
                    sessionCode = data.sessionCode,
                    aesKeyBase64 = data.aesKey,
                    preferredAddress = resolvedAddressFromQraId
                ) { verified ->
                    if (verified) {
                        finishSuccess()
                    } else {
                        lifecycleOwner.lifecycleScope.launch {
                            when (val fallback = tryBleFallbackIfAvailable("qra_bonded_handshake_failed")) {
                                is DeviceResult.Success -> finishSuccess(fallback.data)
                                else -> {
                                    restoreOriginalClassicContact()
                                    Log.e("QRHelpers", "Handshake verification failed for QRA-resolved bonded device")
                                    finishFailure(DeviceError.HANDSHAKE_FAILED)
                                }
                            }
                        }
                    }
                }
            } else {
                pairDevice(context, resolvedAddressFromQraId) { res ->
                    when (res) {
                        is DeviceResult.Success -> {
                            lifecycleOwner.lifecycleScope.launch {
                                persistContact(
                                    Contact(
                                        name = finalName,
                                        aesKey = data.aesKey,
                                        sessionCode = data.sessionCode,
                                        address = resolvedAddressFromQraId
                                    )
                                )
                                verifySecureHandshake(
                                    context = context,
                                    sessionCode = data.sessionCode,
                                    aesKeyBase64 = data.aesKey,
                                    preferredAddress = resolvedAddressFromQraId
                                ) { verified ->
                                    if (verified) {
                                        finishSuccess()
                                    } else {
                                        lifecycleOwner.lifecycleScope.launch {
                                            when (val fallback = tryBleFallbackIfAvailable("qra_paired_handshake_failed")) {
                                                is DeviceResult.Success -> finishSuccess(fallback.data)
                                                else -> {
                                                    restoreOriginalClassicContact()
                                                    Log.e("QRHelpers", "Handshake verification failed for QRA-resolved paired device")
                                                    finishFailure(DeviceError.HANDSHAKE_FAILED)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is DeviceResult.Error -> {
                            Log.e(
                                "QRHelpers",
                                "Pairing via QRA-resolved address failed with error: ${res.code}",
                                IllegalStateException("Pairing failure")
                            )
                            lifecycleOwner.lifecycleScope.launch {
                                finishWithBleFallbackOrFailure(
                                    reason = "qra_pairing_failed",
                                    failureCode = res.code
                                )
                            }
                        }
                    }
                }
            }
            return@launch
        }

        val candidateNames = buildList {
            addAll(candidateDiscoveryNames(data, finalName))
            storedContact?.sessionCode
                ?.let(::normalizeMatchName)
                ?.let(::add)
            val aesAliases = withContext(Dispatchers.IO) {
                val normalizedKey = data.aesKey.trim()
                if (normalizedKey.isBlank()) {
                    emptyList()
                } else {
                    runCatching { getContacts(context) }
                        .getOrDefault(emptyList())
                        .asSequence()
                        .filter { contact -> contact.aesKey.trim() == normalizedKey }
                        .mapNotNull { contact -> normalizeMatchName(contact.sessionCode) }
                        .toList()
                }
            }
            addAll(aesAliases)
        }.distinct()
        if (candidateNames.size > 2) {
            Log.d("QRHelpers", "Extended candidate names with historical aliases: $candidateNames")
        }
        if (candidateNames.isEmpty()) {
            Log.w("QRHelpers", "No usable discovery target name found in QR payload")
            finishWithBleFallbackOrFailure("missing_classic_discovery_target")
            return@launch
        }

        val storedContactDevice = findBondedDeviceFromStoredContacts(
            context = context,
            adapter = adapter,
            contact = storedContact
        )
        if (storedContactDevice != null && isDeviceBonded(context, storedContactDevice)) {
            val storedAddress = deviceAddressOrNull(context, storedContactDevice)
            if (storedAddress.isNullOrBlank()) {
                finishFailure(DeviceError.MISSING_PERMISSIONS)
                return@launch
            }
            Log.d(
                "QRHelpers",
                "Found previously paired device via stored contact data: ${storedAddress.let { "***${it.takeLast(5)}" }}"
            )
            onStatus?.invoke(QrConnectionStage.PAIRING)
            Log.d(
                "QRHelpers",
                "Contact already paired, saving contact with address: ${storedAddress.let { "***${it.takeLast(5)}" }}"
            )
            persistContact(
                Contact(
                    name = finalName,
                    aesKey = data.aesKey,
                    sessionCode = data.sessionCode,
                    address = storedAddress
                )
            )
            verifySecureHandshake(
                context = context,
                sessionCode = data.sessionCode,
                aesKeyBase64 = data.aesKey,
                preferredAddress = storedAddress
            ) { verified ->
                if (verified) {
                    finishSuccess()
                } else {
                    lifecycleOwner.lifecycleScope.launch {
                        when (val fallback = tryBleFallbackIfAvailable("stored_contact_handshake_failed")) {
                            is DeviceResult.Success -> finishSuccess(fallback.data)
                            else -> {
                                restoreOriginalClassicContact()
                                Log.e("QRHelpers", "Handshake verification failed for known paired contact")
                                finishFailure(DeviceError.HANDSHAKE_FAILED)
                            }
                        }
                    }
                }
            }
            return@launch
        }

        val bondedKnownDevice = findBondedDeviceByCandidates(context, adapter, candidateNames)
        if (bondedKnownDevice != null && isDeviceBonded(context, bondedKnownDevice)) {
            val bondedAddress = deviceAddressOrNull(context, bondedKnownDevice)
            if (bondedAddress.isNullOrBlank()) {
                finishFailure(DeviceError.MISSING_PERMISSIONS)
                return@launch
            }
            Log.d(
                "QRHelpers",
                "Found previously paired matching device: ${bondedAddress.let { "***${it.takeLast(5)}" }}"
            )
            onStatus?.invoke(QrConnectionStage.PAIRING)
            Log.d(
                "QRHelpers",
                "Contact already bonded, saving contact with address: ${bondedAddress.let { "***${it.takeLast(5)}" }}"
            )
            persistContact(
                Contact(
                    name = finalName,
                    aesKey = data.aesKey,
                    sessionCode = data.sessionCode,
                    address = bondedAddress
                )
            )
            verifySecureHandshake(
                context = context,
                sessionCode = data.sessionCode,
                aesKeyBase64 = data.aesKey,
                preferredAddress = bondedAddress
            ) { verified ->
                if (verified) {
                    finishSuccess()
                } else {
                    lifecycleOwner.lifecycleScope.launch {
                        when (val fallback = tryBleFallbackIfAvailable("bonded_device_handshake_failed")) {
                            is DeviceResult.Success -> finishSuccess(fallback.data)
                            else -> {
                                restoreOriginalClassicContact()
                                Log.e("QRHelpers", "Handshake verification failed for known paired device")
                                finishFailure(DeviceError.HANDSHAKE_FAILED)
                            }
                        }
                    }
                }
            }
            return@launch
        }

        Log.d("QRHelpers", "Looking for device with candidate names: $candidateNames")
        onStatus?.invoke(QrConnectionStage.SEARCHING)
        val bondedDevice = bondedDevicesOrEmpty(context, adapter).firstOrNull { device ->
            val name = resolveBluetoothDeviceName(context, device)
            Log.d("QRHelpers", "Checking bonded device: '${name ?: ""}'")
            candidateNames.any { candidateName ->
                isTargetDeviceNameMatch(context, device, candidateName)
            }
        }

        val device = bondedDevice?.also {
            Log.d(
                "QRHelpers",
                "Found bonded device: ${deviceAddressOrNull(context, it)?.let { addr -> "***${addr.takeLast(5)}" }}"
            )
        } ?: run {
            var found: BluetoothDevice? = null
            for ((index, candidateName) in candidateNames.withIndex()) {
                found = findDeviceByName(
                    context,
                    candidateName,
                    timeoutMs = if (index == 0) {
                        DEFAULT_DISCOVERY_TIMEOUT
                    } else {
                        LEGACY_DISCOVERY_FALLBACK_TIMEOUT
                    }
                )
                if (found != null) {
                    break
                }
            }
            found
        }
        if (device == null) {
            Log.w("QRHelpers", "Device not found after discovery")
            val bondedProbeAddresses = withContext(Dispatchers.IO) {
                val contacts = runCatching { getContacts(context) }.getOrDefault(emptyList())
                val normalizedCandidateNames = candidateNames
                    .mapNotNull(::normalizeMatchName)
                    .toSet()
                val ordered = LinkedHashSet<String>()

                fun addAddress(address: String?) {
                    val normalized = address?.let(::normalizeMacAddress)
                        ?.ifBlank { null }
                        ?: return
                    ordered.add(normalized)
                }

                addAddress(storedContact?.address)
                contacts.asSequence()
                    .filter { contact ->
                        contact.aesKey.trim().isNotBlank() &&
                            contact.aesKey.trim() == data.aesKey.trim()
                    }
                    .forEach { contact -> addAddress(contact.address) }
                contacts.asSequence()
                    .filter { contact ->
                        val normalizedSession = normalizeMatchName(contact.sessionCode)
                        val normalizedName = normalizeMatchName(contact.name)
                        normalizedSession != null && normalizedCandidateNames.contains(normalizedSession) ||
                            normalizedName != null && normalizedCandidateNames.contains(normalizedName)
                    }
                    .forEach { contact -> addAddress(contact.address) }
                contacts.forEach { contact -> addAddress(contact.address) }

                // Intentional fallback:
                // Even when discovery misses by name, trying a limited set of bonded
                // addresses and validating via secure handshake prevents false positives.
                val bondedCandidates = bondedDevicesOrEmpty(context, adapter)
                    .mapNotNull { bonded ->
                        val address = deviceAddressOrNull(context, bonded)
                            ?.let(::normalizeMacAddress)
                            ?.ifBlank { null }
                            ?: return@mapNotNull null
                        val name = resolveBluetoothDeviceName(context, bonded)
                        Triple(
                            bondedProbeScore(
                                deviceName = name,
                                normalizedCandidateNames = normalizedCandidateNames
                            ),
                            address,
                            name
                        )
                    }
                    .sortedByDescending { it.first }

                bondedCandidates.forEach { (score, address, name) ->
                    if (score <= 0) {
                        return@forEach
                    }
                    if (ordered.add(address)) {
                        val masked = "***${address.takeLast(5)}"
                        Log.d(
                            "QRHelpers",
                            "Added bonded probe candidate $masked (score=$score, name='${name ?: ""}')"
                        )
                    }
                }
                ordered.toList()
            }
                .filter { probeAddress ->
                    val bonded = findBondedDeviceByAddress(context, adapter, probeAddress)
                    bonded != null && isDeviceBonded(context, bonded)
                }
                .take(MAX_BONDED_HANDSHAKE_PROBE_ADDRESSES)

            val attemptedBondedProbe = bondedProbeAddresses.isNotEmpty()
            if (attemptedBondedProbe) {
                Log.d(
                    "QRHelpers",
                    "Trying bonded handshake probe with ${bondedProbeAddresses.size} candidate addresses"
                )
                onStatus?.invoke(QrConnectionStage.PAIRING)
                for (probeAddress in bondedProbeAddresses) {
                    Log.d(
                        "QRHelpers",
                        "Probing bonded address ${"***${probeAddress.takeLast(5)}"} for QR session ${data.sessionCode}"
                    )
                    persistContact(
                        Contact(
                            name = finalName,
                            aesKey = data.aesKey,
                            sessionCode = data.sessionCode,
                            address = probeAddress
                        )
                    )
                    val verified = verifySecureHandshakeAwait(
                        context = context,
                        sessionCode = data.sessionCode,
                        aesKeyBase64 = data.aesKey,
                        preferredAddress = probeAddress
                    )
                    if (verified) {
                        Log.d("QRHelpers", "Bonded probe handshake succeeded for ${"***${probeAddress.takeLast(5)}"}")
                        finishSuccess()
                        return@launch
                    }
                }
                withContext(Dispatchers.IO) {
                    if (originalSessionContact != null) {
                        saveContact(context, originalSessionContact)
                    } else {
                        deleteContact(context, data.sessionCode)
                    }
                }
                Log.w("QRHelpers", "Bonded probe fallback exhausted without successful handshake")
            }
            finishWithBleFallbackOrFailure(
                reason = "classic_device_not_found",
                attemptedHandshake = attemptedBondedProbe
            )
            return@launch
        }

        val deviceAddress = deviceAddressOrNull(context, device)
        if (deviceAddress.isNullOrBlank()) {
            finishFailure(DeviceError.MISSING_PERMISSIONS)
            return@launch
        }

        cancelDiscovery(context, adapter)

        Log.d(
            "QRHelpers",
            "Device found: ${deviceAddress.let { "***${it.takeLast(5)}" }}, bond state: ${if (isDeviceBonded(context, device)) "BONDED" else "NOT_BONDED"}"
        )
        onStatus?.invoke(QrConnectionStage.PAIRING)
        if (!isDeviceBonded(context, device)) {
            Log.d("QRHelpers", "Starting pairing process for ${deviceAddress.let { "***${it.takeLast(5)}" }}")
            pairDevice(context, deviceAddress) { res ->
                when (res) {
                    is DeviceResult.Success -> {
                        lifecycleOwner.lifecycleScope.launch {
                            Log.d("QRHelpers", "Pairing successful, proceeding to save contact")
                            persistContact(
                                Contact(
                                    name = finalName,
                                    aesKey = data.aesKey,
                                    sessionCode = data.sessionCode,
                                    address = deviceAddress
                                )
                            )
                            Log.d("QRHelpers", "Saving contact with name: $finalName")
                            verifySecureHandshake(
                                context = context,
                                sessionCode = data.sessionCode,
                                aesKeyBase64 = data.aesKey,
                                preferredAddress = deviceAddress
                            ) { verified ->
                                if (verified) {
                                    finishSuccess()
                                } else {
                                    lifecycleOwner.lifecycleScope.launch {
                                        when (val fallback = tryBleFallbackIfAvailable("post_pair_handshake_failed")) {
                                            is DeviceResult.Success -> finishSuccess(fallback.data)
                                            else -> {
                                                restoreOriginalClassicContact()
                                                Log.e("QRHelpers", "Handshake verification failed after pairing")
                                                finishFailure(DeviceError.HANDSHAKE_FAILED)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is DeviceResult.Error -> {
                        Log.e(
                            "QRHelpers",
                            "Pairing failed with error: ${res.code}",
                            IllegalStateException("Pairing failure")
                        )
                        lifecycleOwner.lifecycleScope.launch {
                            finishWithBleFallbackOrFailure(
                                reason = "classic_pairing_failed",
                                failureCode = res.code
                            )
                        }
                    }
                }
            }
        } else {
            Log.d("QRHelpers", "Device already bonded, saving contact")
            persistContact(
                Contact(
                    name = finalName,
                    aesKey = data.aesKey,
                    sessionCode = data.sessionCode,
                    address = deviceAddress
                )
            )
            Log.d("QRHelpers", "Saving contact with name: $finalName")
            verifySecureHandshake(
                context = context,
                sessionCode = data.sessionCode,
                aesKeyBase64 = data.aesKey,
                preferredAddress = deviceAddress
            ) { verified ->
                if (verified) {
                    finishSuccess()
                } else {
                    lifecycleOwner.lifecycleScope.launch {
                        when (val fallback = tryBleFallbackIfAvailable("bonded_handshake_failed")) {
                            is DeviceResult.Success -> finishSuccess(fallback.data)
                            else -> {
                                restoreOriginalClassicContact()
                                Log.e("QRHelpers", "Handshake verification failed for bonded device")
                                finishFailure(DeviceError.HANDSHAKE_FAILED)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun verifySecureHandshake(
    context: Context,
    sessionCode: String,
    aesKeyBase64: String,
    preferredAddress: String? = null,
    onResult: (Boolean) -> Unit
) {
    val appContext = context.applicationContext
    val intent = Intent(appContext, RfcommForegroundService::class.java)
    try {
        ContextCompat.startForegroundService(appContext, intent)
    } catch (ex: Exception) {
        Log.e("QRHelpers", "Failed to start RfcommForegroundService for handshake", ex)
        onResult(false)
        return
    }

    var completed = false
    fun finish(result: Boolean) {
        if (completed) return
        completed = true
        onResult(result)
    }

    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? RfcommForegroundService.LocalBinder)?.getService()
            if (service == null) {
                Log.e("QRHelpers", "Failed to obtain Rfcomm service binder")
                runCatching { appContext.unbindService(this) }
                finish(false)
                return
            }
            service.performHandshake(sessionCode, aesKeyBase64, preferredAddress) { success ->
                runCatching { appContext.unbindService(this) }
                finish(success)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            finish(false)
        }
    }

    val bound = try {
        appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    } catch (ex: Exception) {
        Log.e("QRHelpers", "Failed to bind to Rfcomm service for handshake", ex)
        false
    }

    if (!bound) {
        finish(false)
    }
}

private suspend fun verifySecureHandshakeAwait(
    context: Context,
    sessionCode: String,
    aesKeyBase64: String,
    preferredAddress: String? = null
): Boolean = suspendCancellableCoroutine { cont ->
    verifySecureHandshake(context, sessionCode, aesKeyBase64, preferredAddress) { verified ->
        if (cont.isActive) {
            cont.resume(verified)
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun isLocationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    return manager?.let {
        runCatching {
            it.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    } ?: false
}

private fun unregisterSafely(context: Context, receiver: BroadcastReceiver) {
    runCatching { context.unregisterReceiver(receiver) }
        .onFailure {
            if (it is IllegalArgumentException) {
                Log.d("QRHelpers", "Receiver was already unregistered")
            } else {
                Log.e("QRHelpers", "Failed to unregister receiver", it)
            }
        }
}

private fun registerReceiverCompat(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
    // Use the compat API on all versions so lint sees explicit receiver visibility.
    // We need EXPORTED to receive Bluetooth stack broadcasts from outside our app process.
    androidx.core.content.ContextCompat.registerReceiver(
        context,
        receiver,
        filter,
        androidx.core.content.ContextCompat.RECEIVER_EXPORTED
    )
}
