package com.auralis.crisisconnect.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.service.p2p.P2pGattServerService
import com.auralis.crisisconnect.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

private const val EMPTY_ADDRESS = ""
private const val PAIRING_TIMEOUT_MS = 15_000L
private const val BLE_SESSION_PREFIX = "ble:"
private val CLASSIC_SESSION_CODE_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{3,31}$")
private val HIGH_RANGE_MODE_ENABLED = booleanPreferencesKey("advanced_high_range_mode_enabled")

const val PREFERRED_TRANSPORT_RFCOMM = "RFCOMM"
const val PREFERRED_TRANSPORT_BLE_GATT = "BLE_GATT"
const val REMOTE_PLATFORM_UNKNOWN = "unknown"
const val REMOTE_PLATFORM_ANDROID = "android"
const val REMOTE_PLATFORM_IOS = "ios"

/** Data model representing a saved contact */
data class Contact(
    val name: String,
    val aesKey: String,
    val sessionCode: String,
    val verified: Boolean = false,
    val verifiedIdentityKey: String = "",
    val verifiedAt: Long? = null,
    val address: String = "",
    val remoteSessionCode: String = "",
    val preferredTransport: String = PREFERRED_TRANSPORT_RFCOMM,
    val remotePlatform: String = REMOTE_PLATFORM_UNKNOWN,
    val bleShareId: String = "",
    val lastKnownBleAddress: String = "",
    val remoteDeviceId: String = "",
    val peerUid: String = "",
    val peerPublicKey: String = "",
    val peerPhotoUrl: String = "",
    val peerKeyChanged: Boolean = false,
    // The peer's E.164 number (set when added by number over the internet). Used as the SPAKE2
    // password to auto-bootstrap an offline Bluetooth link when nearby. Empty otherwise.
    val peerPhone: String = "",
    // Whether the peer's device reported running in child profile mode when the contact was
    // exchanged. Child contacts are never offered/used as SOS emergency contacts.
    val peerIsChild: Boolean = false,
    // A hidden transport-only contact auto-created for an authority (kurum) channel peer so the
    // citizen dual-mode pipeline can carry that chat offline over Bluetooth. Never shown in the
    // home list or pickers; deliberately-added contacts never get this flag.
    val isAuthorityBridge: Boolean = false
) {
    /** True when this contact can be reached over the E2E internet transport. */
    val supportsInternet: Boolean
        get() = peerUid.isNotBlank() && peerPublicKey.isNotBlank()
}

sealed class DeviceResult<out T> {
    data class Success<T>(val data: T) : DeviceResult<T>()
    data class Error(val code: DeviceError) : DeviceResult<Nothing>()
}

enum class DeviceError {
    MISSING_PERMISSIONS,
    DEVICE_NOT_FOUND,
    PAIRING_FAILED,
    HANDSHAKE_FAILED,
    BLUETOOTH_OFF
}

private fun contactDao(context: Context) = AppDatabase.getInstance(context).contactDao()

fun getContacts(context: Context): List<Contact> =
    contactDao(context).getContacts().map { it.toContact() }

fun hasAnyBleGattContacts(context: Context): Boolean =
    contactDao(context).hasAnyBleGattContacts()

fun getContact(context: Context, sessionCode: String): Contact? =
    contactDao(context).getContactBySessionCode(sessionCode)?.toContact()

fun getContactByRemoteSessionCode(context: Context, remoteSessionCode: String): Contact? {
    val normalized = remoteSessionCode.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return contactDao(context).getContactByRemoteSessionCode(normalized)?.toContact()
}

fun getContactByAddress(context: Context, address: String): Contact? {
    val normalized = normalizeMacAddress(address)
    if (normalized.isEmpty()) {
        return null
    }
    return contactDao(context).getContactByAddress(normalized)?.toContact()
}

fun getContactByRemoteDeviceId(context: Context, remoteDeviceId: String): Contact? {
    val normalized = remoteDeviceId.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return contactDao(context).getContactByRemoteDeviceId(normalized)?.toContact()
}

/** The local contact we hold for a peer's internet identity (Firebase uid), regardless of the
 *  per-peer Bluetooth session code. Lets internet messages file into the existing thread. */
fun getContactByPeerUid(context: Context, peerUid: String): Contact? {
    val normalized = peerUid.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return contactDao(context).getContactByPeerUid(normalized)?.toContact()
}

fun observeContacts(context: Context): Flow<List<Contact>> =
    contactDao(context).observeContacts().map { list ->
        list.map { it.toContact() }
    }

fun observeContact(context: Context, sessionCode: String): Flow<Contact?> =
    contactDao(context).observeContactBySessionCode(sessionCode).map { entity ->
        entity?.toContact()
    }

/**
 * [analyticsSource] marks a deliberate user add ("qr", "directory", …) for the contact_added
 * metric; with [analyticsReceived] it instead marks the passive side (a peer added this user →
 * contact_received). Leave null on bookkeeping saves (identity restores, synthetic contacts,
 * auto-saves) so they never count. Only a save that creates a new row logs the event.
 */
fun saveContact(
    context: Context,
    contact: Contact,
    analyticsSource: String? = null,
    analyticsReceived: Boolean = false
) {
    val appContext = context.applicationContext
    val normalizedTransport = normalizePreferredTransport(contact.preferredTransport)
    val normalizedAddress = normalizeMacAddress(contact.address)
    val normalizedBleAddress = normalizeMacAddress(contact.lastKnownBleAddress)
        .ifEmpty {
            if (normalizedTransport == PREFERRED_TRANSPORT_BLE_GATT) normalizedAddress else EMPTY_ADDRESS
        }
    val normalizedContact = contact.copy(
        sessionCode = contact.sessionCode.trim(),
        address = normalizedAddress,
        remoteSessionCode = contact.remoteSessionCode.trim(),
        preferredTransport = normalizedTransport,
        remotePlatform = normalizeRemotePlatform(contact.remotePlatform),
        bleShareId = normalizeBleShareId(contact.bleShareId),
        lastKnownBleAddress = normalizedBleAddress,
        remoteDeviceId = contact.remoteDeviceId.trim(),
        verifiedIdentityKey = normalizeVerifiedIdentityKey(contact.verifiedIdentityKey)
    )
    val existing = contactDao(appContext).getContactBySessionCode(normalizedContact.sessionCode)?.toContact()
    val mergedContact = mergeVerifiedTrust(
        incoming = normalizedContact,
        existing = existing
    ).let { merged ->
        // Keep the peer's number sticky: writes that construct a fresh Contact (e.g. a nearby
        // SPAKE2 pairing overwriting an online contact at the same sessionCode) don't carry it, and
        // we don't want to lose the SPAKE2 password we used to auto-link.
        if (merged.peerPhone.isBlank() && !existing?.peerPhone.isNullOrBlank()) {
            merged.copy(peerPhone = existing!!.peerPhone)
        } else {
            merged
        }
    }
    contactDao(appContext).saveContact(mergedContact.toEntity())
    if (analyticsSource != null && existing == null && !mergedContact.isAuthorityBridge) {
        if (analyticsReceived) {
            Analytics.contactReceived(via = analyticsSource, transport = normalizedTransport)
        } else {
            Analytics.contactAdded(method = analyticsSource, transport = normalizedTransport)
        }
    }
    if (requiresLockedHighRange(normalizedContact)) {
        P2pGattServerService.ensureHosting(appContext)
    }
}

fun markContactVerified(
    context: Context,
    sessionCode: String,
    verifiedIdentityKey: String? = null,
    verifiedAt: Long = System.currentTimeMillis()
): Boolean {
    val normalized = sessionCode.trim()
    if (normalized.isEmpty()) {
        return false
    }
    val existing = getContact(context, normalized) ?: return false
    val trustKey = normalizeVerifiedIdentityKey(
        verifiedIdentityKey ?: existing.remoteDeviceId
    )
    if (trustKey.isBlank()) {
        return false
    }
    if (existing.verified && existing.verifiedIdentityKey.equals(trustKey, ignoreCase = true)) {
        return true
    }
    saveContact(
        context,
        existing.copy(
            verified = true,
            verifiedIdentityKey = trustKey,
            verifiedAt = verifiedAt
        )
    )
    return true
}

/**
 * Clears the "identity key changed" (TOFU) warning after the user re-confirms the peer.
 * No-op if the contact is missing or the flag is already down.
 */
fun acknowledgePeerKeyChange(context: Context, sessionCode: String): Boolean {
    val normalized = sessionCode.trim()
    if (normalized.isEmpty()) {
        return false
    }
    val existing = getContact(context, normalized) ?: return false
    if (!existing.peerKeyChanged) {
        return true
    }
    saveContact(context, existing.copy(peerKeyChanged = false))
    return true
}

suspend fun saveBleContactAndMigrateLegacySession(
    context: Context,
    contact: Contact,
    migrateFromSessionCode: String? = null,
    analyticsSource: String? = null,
    analyticsReceived: Boolean = false
): Contact {
    val appContext = context.applicationContext
    val normalizedTransport = normalizePreferredTransport(contact.preferredTransport)
    val normalizedAddress = normalizeMacAddress(contact.address)
    val normalizedBleAddress = normalizeMacAddress(contact.lastKnownBleAddress)
        .ifEmpty {
            if (normalizedTransport == PREFERRED_TRANSPORT_BLE_GATT) normalizedAddress else EMPTY_ADDRESS
        }
    val normalizedTarget = contact.copy(
        sessionCode = contact.sessionCode.trim(),
        address = normalizedAddress,
        remoteSessionCode = contact.remoteSessionCode.trim(),
        preferredTransport = normalizedTransport,
        remotePlatform = normalizeRemotePlatform(contact.remotePlatform),
        bleShareId = normalizeBleShareId(contact.bleShareId),
        lastKnownBleAddress = normalizedBleAddress,
        remoteDeviceId = contact.remoteDeviceId.trim(),
        verifiedIdentityKey = normalizeVerifiedIdentityKey(contact.verifiedIdentityKey)
    )
    val db = AppDatabase.getInstance(appContext)
    val legacySessions = LinkedHashSet<String>()
    migrateFromSessionCode
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(normalizedTarget.sessionCode, ignoreCase = true) }
        ?.let { legacySessions += it }
    normalizedTarget.remoteSessionCode
        .takeIf { it.isNotEmpty() && !it.equals(normalizedTarget.sessionCode, ignoreCase = true) }
        ?.let { legacySessions += it }

    var finalizedContact = normalizedTarget
    val migratedSessions = mutableListOf<String>()
    var isNewContact = false
    db.withTransaction {
        val dao = db.contactDao()
        val existingTarget = dao.getContactBySessionCode(normalizedTarget.sessionCode)?.toContact()
        val sourceContacts = legacySessions
            .mapNotNull { legacyCode ->
                dao.getContactBySessionCode(legacyCode)?.toContact()
            }
            .filterNot { source ->
                source.sessionCode.equals(normalizedTarget.sessionCode, ignoreCase = true)
            }
        // A migrated legacy contact is not a new add — only a save with no prior row anywhere counts.
        isNewContact = existingTarget == null && sourceContacts.isEmpty()

        finalizedContact = mergePreferredContact(
            target = normalizedTarget,
            existingTarget = existingTarget,
            sourceContacts = sourceContacts
        )
        dao.saveContact(finalizedContact.toEntity())
        sourceContacts
            .map { source -> source.sessionCode }
            .distinct()
            .forEach { oldSessionCode ->
                db.messageDao().migrateSessionCode(oldSessionCode, finalizedContact.sessionCode)
                db.callEventDao().migrateSessionCode(oldSessionCode, finalizedContact.sessionCode)
                dao.deleteBySessionCode(oldSessionCode)
                migratedSessions += oldSessionCode
            }
    }

    migratedSessions.forEach { oldSessionCode ->
        ContactAvatarStorage.migrateSession(
            context = appContext,
            fromSessionCode = oldSessionCode,
            toSessionCode = finalizedContact.sessionCode
        )
    }
    if (analyticsSource != null && isNewContact && !finalizedContact.isAuthorityBridge) {
        if (analyticsReceived) {
            Analytics.contactReceived(via = analyticsSource, transport = normalizedTransport)
        } else {
            Analytics.contactAdded(method = analyticsSource, transport = normalizedTransport)
        }
    }
    if (requiresLockedHighRange(finalizedContact)) {
        P2pGattServerService.ensureHosting(appContext)
    }
    return finalizedContact
}

suspend fun normalizeClassicCapableBleContacts(context: Context): Boolean {
    val appContext = context.applicationContext
    val candidates = getContacts(appContext).filter { contact ->
        normalizePreferredTransport(contact.preferredTransport) == PREFERRED_TRANSPORT_BLE_GATT &&
            normalizeRemotePlatform(contact.remotePlatform) == REMOTE_PLATFORM_ANDROID &&
            isBleSessionCode(contact.sessionCode) &&
            looksLikeClassicSessionCode(contact.remoteSessionCode)
    }
    if (candidates.isEmpty()) {
        return false
    }
    candidates.forEach { contact ->
        val classicSessionCode = contact.remoteSessionCode.trim()
        val bleAddress = normalizeMacAddress(contact.lastKnownBleAddress)
            .ifEmpty { normalizeMacAddress(contact.address) }
        saveBleContactAndMigrateLegacySession(
            context = appContext,
            contact = contact.copy(
                sessionCode = classicSessionCode,
                address = EMPTY_ADDRESS,
                remoteSessionCode = classicSessionCode,
                preferredTransport = PREFERRED_TRANSPORT_RFCOMM,
                lastKnownBleAddress = bleAddress
            ),
            migrateFromSessionCode = contact.sessionCode
        )
    }
    if (!hasAnyBleGattContacts(appContext)) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[HIGH_RANGE_MODE_ENABLED] = false
        }
    }
    return true
}

fun observeHasBleContacts(context: Context): Flow<Boolean> =
    contactDao(context)
        .observeHasAnyBleGattContacts()
        .distinctUntilChanged()

fun updateContactAddress(context: Context, sessionCode: String, address: String) {
    val normalized = normalizeMacAddress(address)
    val finalAddress = normalized.ifEmpty { EMPTY_ADDRESS }
    contactDao(context).updateContactAddress(sessionCode, finalAddress)
}

fun updateContactAesKey(context: Context, sessionCode: String, aesKey: String) {
    contactDao(context).updateContactAesKey(sessionCode, aesKey)
}

fun updateContactName(context: Context, sessionCode: String, name: String) {
    contactDao(context).updateContactName(sessionCode, name.trim())
}

/**
 * Stamps the peer's internet identity (Firebase uid + long-term public key) onto an existing
 * contact. Called when a Bluetooth-connected peer announces its identity over the link, so a
 * Bluetooth-added contact automatically becomes internet-capable (supportsInternet) without a QR
 * re-scan — and self-heals if the peer's uid changed (e.g. after a data wipe), since it refreshes
 * on every Bluetooth connection. No-op when the values are blank or already up to date.
 */
fun updateContactPeerIdentity(
    context: Context,
    sessionCode: String,
    peerUid: String,
    peerPublicKey: String
) {
    val newUid = peerUid.trim()
    val newKey = peerPublicKey.trim()
    if (newUid.isEmpty() || newKey.isEmpty()) return
    val normalizedSession = sessionCode.trim()
    val existing = contactDao(context).getContactBySessionCode(normalizedSession)?.toContact() ?: return
    if (existing.peerUid.trim() == newUid && existing.peerPublicKey.trim() == newKey) return
    saveContact(context, existing.copy(peerUid = newUid, peerPublicKey = newKey))
}

/**
 * Records whether the peer's device reported running in child profile mode during a contact
 * exchange (Bluetooth CONTACT_INFO, QR, or directory lookup). No-op when unchanged.
 */
fun updateContactChildFlag(context: Context, sessionCode: String, isChild: Boolean) {
    val normalizedSession = sessionCode.trim()
    val existing = contactDao(context).getContactBySessionCode(normalizedSession)?.toContact() ?: return
    if (existing.peerIsChild == isChild) return
    saveContact(context, existing.copy(peerIsChild = isChild))
}

private fun isPlaceholderBleDisplayName(name: String, sessionCode: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isBlank() ||
        trimmed.equals("Crisis Connect", ignoreCase = true) ||
        trimmed.equals(sessionCode.trim(), ignoreCase = true)
}

fun updateContactBleRuntimeMetadata(
    context: Context,
    sessionCode: String,
    lastKnownBleAddress: String,
    name: String
) {
    val normalizedSessionCode = sessionCode.trim()
    val existing = contactDao(context).getContactBySessionCode(normalizedSessionCode)?.toContact()
    val incomingName = name.trim()
    val resolvedName = when {
        incomingName.isBlank() -> existing?.name?.trim().orEmpty()
        existing == null -> incomingName
        isPlaceholderBleDisplayName(incomingName, normalizedSessionCode) &&
            !isPlaceholderBleDisplayName(existing.name, normalizedSessionCode) -> existing.name.trim()
        else -> incomingName
    }
    contactDao(context).updateBleRuntimeMetadata(
        sessionCode = normalizedSessionCode,
        lastKnownBleAddress = normalizeMacAddress(lastKnownBleAddress),
        name = resolvedName
    )
}

fun normalizeContactAddresses(context: Context) {
    contactDao(context).normalizeAddresses()
}

fun ensureContactForDevice(
    context: Context,
    address: String?,
    deviceName: String?,
    isBonded: Boolean
): String? {
    val normalized = normalizeMacAddress(address)
    if (normalized.isEmpty()) {
        return null
    }

    normalizeContactAddresses(context)

    val resolvedName = deviceName?.trim()?.takeIf { it.isNotBlank() }
    val classicContactsForAddress = getContacts(context)
        .filter { !isBleSessionCode(it.sessionCode) }
        .filter { contact ->
            contact.address == normalized || contact.lastKnownBleAddress == normalized
        }
    selectClassicContactForResolvedAddress(
        contacts = classicContactsForAddress,
        normalizedAddress = normalized
    )?.let { contact ->
        if (contact.address.isBlank() || contact.address != normalized) {
            updateContactAddress(context, contact.sessionCode, normalized)
        }
        cleanupEphemeralClassicAliases(
            context = context,
            canonicalContact = contact,
            matchingContacts = classicContactsForAddress
        )
        return contact.sessionCode
    }

    val resolvedSessionCode = resolvedName
        ?.takeIf(::looksLikeClassicSessionCode)
        ?.takeUnless(::isBleSessionCode)
    if (resolvedSessionCode != null) {
        getContact(context, resolvedSessionCode)?.takeUnless { isBleSessionCode(it.sessionCode) }?.let { contact ->
            if (contact.address != normalized) {
                updateContactAddress(context, contact.sessionCode, normalized)
            }
            return contact.sessionCode
        }
    }

    if (isBonded) {
        val sessionCode = resolvedSessionCode ?: normalized
        val contact = Contact(
            name = resolvedName ?: sessionCode,
            aesKey = "",
            sessionCode = sessionCode,
            address = normalized
        )
        saveContact(context, contact)
        return sessionCode
    }

    return null
}

private fun selectClassicContactForResolvedAddress(
    contacts: List<Contact>,
    normalizedAddress: String
): Contact? {
    return contacts.maxWithOrNull(
        compareBy<Contact>(
            { persistentIdentityStrength(it) },
            { addressMatchStrength(contact = it, normalizedAddress = normalizedAddress) }
        )
    )
}

private fun persistentIdentityStrength(contact: Contact): Int {
    var score = 0
    if (contact.remoteDeviceId.isNotBlank()) score += 8
    if (contact.bleShareId.isNotBlank()) score += 4
    if (contact.remoteSessionCode.isNotBlank()) score += 2
    if (contact.aesKey.isNotBlank()) score += 1
    return score
}

private fun addressMatchStrength(contact: Contact, normalizedAddress: String): Int {
    var score = 0
    if (contact.address == normalizedAddress) score += 2
    if (contact.lastKnownBleAddress == normalizedAddress) score += 1
    return score
}

private fun cleanupEphemeralClassicAliases(
    context: Context,
    canonicalContact: Contact,
    matchingContacts: List<Contact>
) {
    if (persistentIdentityStrength(canonicalContact) == 0) {
        return
    }

    val aliasSessionCodes = matchingContacts
        .filterNot { it.sessionCode.equals(canonicalContact.sessionCode, ignoreCase = true) }
        .filter { shouldMergeClassicAliasIntoCanonical(it) }
        .map(Contact::sessionCode)
        .distinct()
    if (aliasSessionCodes.isEmpty()) {
        return
    }

    val appContext = context.applicationContext
    val db = AppDatabase.getInstance(appContext)
    runBlocking {
        db.withTransaction {
            aliasSessionCodes.forEach { aliasSessionCode ->
                db.messageDao().migrateSessionCode(aliasSessionCode, canonicalContact.sessionCode)
                db.callEventDao().migrateSessionCode(aliasSessionCode, canonicalContact.sessionCode)
                db.contactDao().deleteBySessionCode(aliasSessionCode)
            }
        }
    }
    aliasSessionCodes.forEach { aliasSessionCode ->
        ContactAvatarStorage.migrateSession(
            context = appContext,
            fromSessionCode = aliasSessionCode,
            toSessionCode = canonicalContact.sessionCode
        )
    }
}

private fun shouldMergeClassicAliasIntoCanonical(contact: Contact): Boolean {
    return !isBleSessionCode(contact.sessionCode) &&
        !contact.verified &&
        persistentIdentityStrength(contact) == 0
}

private fun isBleSessionCode(sessionCode: String): Boolean {
    return sessionCode.startsWith(BLE_SESSION_PREFIX, ignoreCase = true)
}

private fun looksLikeClassicSessionCode(value: String): Boolean {
    return CLASSIC_SESSION_CODE_REGEX.matches(value.trim())
}

private fun mergePreferredContact(
    target: Contact,
    existingTarget: Contact?,
    sourceContacts: List<Contact>
): Contact {
    val existingClassicTarget = existingTarget?.takeIf { contact ->
        normalizePreferredTransport(contact.preferredTransport) != PREFERRED_TRANSPORT_BLE_GATT
    }
    val classicSourceContacts = sourceContacts.filter { contact ->
        normalizePreferredTransport(contact.preferredTransport) != PREFERRED_TRANSPORT_BLE_GATT
    }

    val name = firstNonBlank(
        target.name,
        existingTarget?.name,
        *sourceContacts.map(Contact::name).toTypedArray(),
        target.remoteSessionCode,
        target.sessionCode
    ) ?: target.sessionCode

    val aesKey = firstNonBlank(
        target.aesKey,
        existingTarget?.aesKey,
        *sourceContacts.map(Contact::aesKey).toTypedArray()
    ).orEmpty()

    val preferredTransport = normalizePreferredTransport(
        firstNonBlank(
            target.preferredTransport,
            existingTarget?.preferredTransport,
            *sourceContacts.map(Contact::preferredTransport).toTypedArray()
        )
    )

    val address = if (preferredTransport == PREFERRED_TRANSPORT_BLE_GATT) {
        firstNonBlank(
            target.address,
            existingTarget?.address,
            *sourceContacts.map(Contact::address).toTypedArray()
        ).orEmpty()
    } else {
        firstNonBlank(
            target.address,
            existingClassicTarget?.address,
            *classicSourceContacts.map(Contact::address).toTypedArray()
        ).orEmpty()
    }

    val remoteSessionCode = firstNonBlank(
        target.remoteSessionCode,
        existingTarget?.remoteSessionCode,
        *sourceContacts
            .map { source -> source.remoteSessionCode.ifBlank { source.sessionCode } }
            .toTypedArray()
    ).orEmpty()

    val remotePlatform = normalizeRemotePlatform(
        firstNonBlank(
            target.remotePlatform,
            existingTarget?.remotePlatform,
            *sourceContacts.map(Contact::remotePlatform).toTypedArray()
        )
    )

    // Internet identity: prefer the incoming value, else keep whatever we already had. This makes a
    // BLE/QR-paired contact internet-capable once the peer supplies its identity (client-hello), and
    // never wipes an existing identity on a later re-pair that omits it.
    val peerUid = firstNonBlank(
        target.peerUid,
        existingTarget?.peerUid,
        *sourceContacts.map(Contact::peerUid).toTypedArray()
    ).orEmpty()

    val peerPublicKey = firstNonBlank(
        target.peerPublicKey,
        existingTarget?.peerPublicKey,
        *sourceContacts.map(Contact::peerPublicKey).toTypedArray()
    ).orEmpty()

    val bleShareId = normalizeBleShareId(
        firstNonBlank(
            target.bleShareId,
            existingTarget?.bleShareId,
            *sourceContacts.map(Contact::bleShareId).toTypedArray()
        )
    )

    val lastKnownBleAddress = normalizeMacAddress(
        firstNonBlank(
            target.lastKnownBleAddress,
            existingTarget?.lastKnownBleAddress,
            target.address,
            existingTarget?.address,
            *sourceContacts.flatMap { source ->
                listOf(source.lastKnownBleAddress, source.address)
            }.toTypedArray()
        )
    )

    val remoteDeviceId = firstNonBlank(
        target.remoteDeviceId,
        existingTarget?.remoteDeviceId,
        *sourceContacts.map(Contact::remoteDeviceId).toTypedArray()
    ).orEmpty()

    val trustedSources = buildList {
        existingTarget?.let(::add)
        addAll(sourceContacts)
    }
        .map(::sanitizeVerifiedContact)
        .filter { it.verified && it.verifiedIdentityKey.isNotBlank() }
    val targetTrustKey = trustIdentityCandidate(target)
    val trustedTarget = sanitizeVerifiedContact(target)
    val matchingTrustedSource = trustedSources.firstOrNull { source ->
        source.verifiedIdentityKey.equals(targetTrustKey, ignoreCase = true)
    }
    val verified = when {
        trustedTarget.verified -> true
        targetTrustKey.isNotBlank() && matchingTrustedSource != null -> true
        else -> false
    }
    val verifiedIdentityKey = when {
        trustedTarget.verified -> trustedTarget.verifiedIdentityKey
        matchingTrustedSource != null -> matchingTrustedSource.verifiedIdentityKey
        else -> ""
    }
    val verifiedAt = when {
        trustedTarget.verified -> trustedTarget.verifiedAt
            ?: matchingTrustedSource?.verifiedAt
            ?: System.currentTimeMillis()
        matchingTrustedSource != null -> matchingTrustedSource.verifiedAt
        else -> null
    }

    return Contact(
        name = name,
        aesKey = aesKey,
        sessionCode = target.sessionCode,
        verified = verified,
        verifiedIdentityKey = verifiedIdentityKey,
        verifiedAt = verifiedAt,
        address = address,
        remoteSessionCode = remoteSessionCode,
        preferredTransport = preferredTransport,
        remotePlatform = remotePlatform,
        bleShareId = bleShareId,
        lastKnownBleAddress = lastKnownBleAddress,
        remoteDeviceId = remoteDeviceId,
        peerUid = peerUid,
        peerPublicKey = peerPublicKey
    )
}

private fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }?.trim()
}

internal fun normalizeVerifiedIdentityKey(raw: String?): String {
    val normalized = raw?.trim()?.lowercase(Locale.US).orEmpty()
    if (normalized.isBlank()) {
        return ""
    }
    return if (normalized.startsWith("device:")) {
        normalized
    } else {
        "device:$normalized"
    }
}

private fun trustIdentityCandidate(contact: Contact): String {
    return normalizeVerifiedIdentityKey(contact.verifiedIdentityKey)
        .ifBlank { normalizeVerifiedIdentityKey(contact.remoteDeviceId) }
}

private fun sanitizeVerifiedContact(contact: Contact): Contact {
    val trustKey = trustIdentityCandidate(contact)
    val trusted = contact.verified && trustKey.isNotBlank()
    return contact.copy(
        verified = trusted,
        verifiedIdentityKey = if (trusted) trustKey else "",
        verifiedAt = if (trusted) (contact.verifiedAt ?: System.currentTimeMillis()) else null
    )
}

internal fun mergeVerifiedTrust(
    incoming: Contact,
    existing: Contact?
): Contact {
    val sanitizedIncoming = sanitizeVerifiedContact(incoming)
    val sanitizedExisting = existing?.let(::sanitizeVerifiedContact)
    val incomingCandidateKey = trustIdentityCandidate(incoming)
    val preservesExistingTrust = sanitizedExisting?.verified == true &&
        incomingCandidateKey.isNotBlank() &&
        sanitizedExisting.verifiedIdentityKey.equals(incomingCandidateKey, ignoreCase = true)

    return when {
        sanitizedIncoming.verified -> sanitizedIncoming.copy(
            verifiedAt = sanitizedIncoming.verifiedAt ?: System.currentTimeMillis()
        )
        preservesExistingTrust -> sanitizedIncoming.copy(
            verified = true,
            verifiedIdentityKey = sanitizedExisting?.verifiedIdentityKey.orEmpty(),
            verifiedAt = sanitizedExisting?.verifiedAt
        )
        else -> sanitizedIncoming.copy(
            verified = false,
            verifiedIdentityKey = "",
            verifiedAt = null
        )
    }
}

fun normalizePreferredTransport(raw: String?): String {
    return when (raw?.trim()?.uppercase(Locale.US)) {
        PREFERRED_TRANSPORT_BLE_GATT -> PREFERRED_TRANSPORT_BLE_GATT
        else -> PREFERRED_TRANSPORT_RFCOMM
    }
}

fun normalizeRemotePlatform(raw: String?): String {
    return when (raw?.trim()?.lowercase(Locale.US)) {
        REMOTE_PLATFORM_ANDROID -> REMOTE_PLATFORM_ANDROID
        REMOTE_PLATFORM_IOS -> REMOTE_PLATFORM_IOS
        else -> REMOTE_PLATFORM_UNKNOWN
    }
}

private fun normalizeBleShareId(raw: String?): String {
    return raw?.trim()?.uppercase(Locale.US).orEmpty()
}

private fun requiresLockedHighRange(contact: Contact): Boolean {
    return normalizePreferredTransport(contact.preferredTransport) == PREFERRED_TRANSPORT_BLE_GATT
}

fun deleteContact(context: Context, sessionCode: String) {
    val appContext = context.applicationContext
    contactDao(appContext).deleteBySessionCode(sessionCode)
    CoroutineScope(Dispatchers.IO).launch {
        if (!hasAnyBleGattContacts(appContext)) {
            appContext.settingsDataStore.edit { prefs ->
                prefs[HIGH_RANGE_MODE_ENABLED] = false
            }
            P2pGattServerService.stopPublishing(appContext)
        }
    }
}

/**
 * Scan for a Bluetooth device with the given [deviceName] and invoke [onResult]
 * with its address once discovered or an error code if not found.
 */
@SuppressLint("MissingPermission")
fun findDeviceAddress(
    context: Context,
    deviceName: String,
    onResult: (DeviceResult<String>) -> Unit
) {
    val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
        onResult(DeviceResult.Error(DeviceError.DEVICE_NOT_FOUND))
        return
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        != PackageManager.PERMISSION_GRANTED
    ) {
        onResult(DeviceResult.Error(DeviceError.MISSING_PERMISSIONS))
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED
    ) {
        onResult(DeviceResult.Error(DeviceError.MISSING_PERMISSIONS))
        return
    }
    val handler = Handler(Looper.getMainLooper())
    val targetName = deviceName.trim()
    fun cancelDiscoverySafely() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
            }
        }
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothDevice.ACTION_FOUND || action == BluetoothDevice.ACTION_NAME_CHANGED) {
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val broadcastName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                val deviceNameSafe = runCatching { device?.name }.getOrNull()
                val deviceAliasSafe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { device?.alias }.getOrNull()
                } else {
                    null
                }
                val resolvedName = when {
                    !broadcastName.isNullOrBlank() -> broadcastName
                    !deviceNameSafe.isNullOrBlank() -> deviceNameSafe
                    !deviceAliasSafe.isNullOrBlank() -> deviceAliasSafe
                    else -> null
                }
                val deviceAddress = runCatching { device?.address }.getOrNull()
                if (
                    resolvedName?.trim()?.equals(targetName, ignoreCase = true) == true &&
                    !deviceAddress.isNullOrBlank()
                ) {
                    handler.removeCallbacksAndMessages(null)
                    cancelDiscoverySafely()
                    try {
                        ctx.unregisterReceiver(this)
                    } catch (_: IllegalArgumentException) {
                    }
                    onResult(DeviceResult.Success(deviceAddress))
                    return
                }
            }
        }
    }
    context.registerReceiver(
        receiver,
        IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
    )
    if (adapter.isDiscovering) {
        cancelDiscoverySafely()
    }
    val started = try {
        adapter.startDiscovery()
    } catch (_: SecurityException) {
        context.unregisterReceiver(receiver)
        onResult(DeviceResult.Error(DeviceError.MISSING_PERMISSIONS))
        return
    }
    if (!started) {
        cancelDiscoverySafely()
        context.unregisterReceiver(receiver)
        onResult(DeviceResult.Error(DeviceError.DEVICE_NOT_FOUND))
        return
    }
    handler.postDelayed({
        cancelDiscoverySafely()
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        onResult(DeviceResult.Error(DeviceError.DEVICE_NOT_FOUND))
    }, 10000L)
}

/**
 * Send a Bluetooth pairing request to the device with the given [address].
 * The [onResult] callback is invoked with [DeviceResult.Success] if the device is successfully
 * paired, otherwise an appropriate [DeviceError].
 */
fun pairDevice(
    context: Context,
    address: String,
    onResult: (DeviceResult<Unit>) -> Unit
) {
    val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
        onResult(DeviceResult.Error(DeviceError.PAIRING_FAILED))
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED
    ) {
        onResult(DeviceResult.Error(DeviceError.MISSING_PERMISSIONS))
        return
    }
    val device = adapter.getRemoteDevice(address)
    if (device.bondState == BluetoothDevice.BOND_BONDED) {
        onResult(DeviceResult.Success(Unit))
        return
    }
    val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    var completed = false

    val handler = Handler(Looper.getMainLooper())

    fun finish(result: DeviceResult<Unit>) {
        if (completed) return
        completed = true
        handler.removeCallbacksAndMessages(null)
        onResult(result)
    }

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != intent.action) {
                return
            }
            val state =
                intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val prev = intent.getIntExtra(
                BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                BluetoothDevice.BOND_NONE
            )
            if (state == BluetoothDevice.BOND_BONDED) {
                runCatching { ctx.unregisterReceiver(this) }
                finish(DeviceResult.Success(Unit))
            } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                runCatching { ctx.unregisterReceiver(this) }
                finish(DeviceResult.Error(DeviceError.PAIRING_FAILED))
            }
        }
    }

    handler.postDelayed({
        if (!completed) {
            runCatching { context.unregisterReceiver(receiver) }
            finish(DeviceResult.Error(DeviceError.PAIRING_FAILED))
        }
    }, PAIRING_TIMEOUT_MS)

    context.registerReceiver(receiver, filter)
    try {
        val started = device.createBond()
        if (!started) {
            handler.removeCallbacksAndMessages(null)
            runCatching { context.unregisterReceiver(receiver) }
            finish(DeviceResult.Error(DeviceError.PAIRING_FAILED))
        }
    } catch (_: SecurityException) {
        handler.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(receiver) }
        finish(DeviceResult.Error(DeviceError.MISSING_PERMISSIONS))
    }
}
