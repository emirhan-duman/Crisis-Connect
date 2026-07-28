package com.auralis.crisisconnect.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Base64
import android.util.Log
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.data.normalizeMacAddress
import com.auralis.crisisconnect.data.updateContactAesKey
import com.auralis.crisisconnect.data.updateContactChildFlag
import com.auralis.crisisconnect.data.updateContactName
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.security.ChildProfileManager
import com.auralis.crisisconnect.security.decryptAesGcm
import com.auralis.crisisconnect.security.encryptAesGcm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class RfcommHandshakeDelegate(
    private val appContext: Context,
    private val serviceScope: CoroutineScope,
    private val handler: Handler,
    private val hasConnectedSession: (String) -> Boolean,
    private val connectToContact: (String, String?, (Boolean) -> Unit) -> Unit,
    private val writeToSession: (String, ByteArray) -> Boolean,
    private val onSessionActive: (String) -> Unit,
    private val currentBluetoothSessionName: () -> String?
) {
    private data class PendingHandshake(
        val sessionCode: String,
        val aesKey: ByteArray,
        val challenge: String,
        val callback: (Boolean) -> Unit
    ) {
        @Volatile
        var timeoutRunnable: Runnable? = null
    }

    private val pendingHandshakes = ConcurrentHashMap<String, PendingHandshake>()

    fun performHandshake(
        sessionCode: String,
        aesKeyBase64: String,
        preferredAddress: String? = null,
        callback: (Boolean) -> Unit
    ) {
        val keyBytes = try {
            Base64.decode(aesKeyBase64, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            handler.post { callback(false) }
            return
        }
        if (keyBytes.isEmpty()) {
            handler.post { callback(false) }
            return
        }

        if (hasConnectedSession(sessionCode)) {
            sendHandshakeRequest(sessionCode, keyBytes, callback)
            return
        }

        connectToContact(sessionCode, preferredAddress) { success ->
            if (!success) {
                handler.post { callback(false) }
                return@connectToContact
            }
            sendHandshakeRequest(sessionCode, keyBytes, callback)
        }
    }

    fun handleHandshakeRequest(sessionCode: String, payload: String) {
        // Keep limit=5 so requests from older clients carrying an extra trailing field remain parseable.
        val parts = payload.split('|', limit = 5)
        val challenge = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
        val ivBase64 = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return
        val cipherBase64 = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: return

        serviceScope.launch {
            val contact = getContact(appContext, sessionCode)
            val storedKeyBase64 = contact?.aesKey?.takeIf { it.isNotBlank() }
            val localDeviceKeyBase64 = runCatching {
                LocalKeyStorage.getOrCreateAesKey(appContext)
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
            val candidateKeys = mutableListOf<String>()
            if (!storedKeyBase64.isNullOrBlank()) {
                candidateKeys.add(storedKeyBase64)
            }
            if (!localDeviceKeyBase64.isNullOrBlank() && localDeviceKeyBase64 != storedKeyBase64) {
                candidateKeys.add(localDeviceKeyBase64)
            }
            if (candidateKeys.isEmpty()) {
                Log.w(TAG, "Ignoring handshake request for $sessionCode: no candidate AES key available")
                return@launch
            }

            var selectedKeyBytes: ByteArray? = null
            var selectedKeyBase64: String? = null
            for (candidate in candidateKeys) {
                val decoded = try {
                    Base64.decode(candidate, Base64.NO_WRAP)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val decrypted = decryptAesGcm(decoded, ivBase64, cipherBase64)
                    ?.toString(Charsets.UTF_8)
                    ?: continue
                if (decrypted == "CHALLENGE:$challenge") {
                    selectedKeyBytes = decoded
                    selectedKeyBase64 = candidate
                    break
                }
            }
            if (selectedKeyBytes == null || selectedKeyBase64 == null) {
                return@launch
            }

            if (storedKeyBase64.isNullOrBlank() || storedKeyBase64 != selectedKeyBase64) {
                updateContactAesKey(appContext, sessionCode, selectedKeyBase64)
            }

            val responsePayload = encryptAesGcm(
                selectedKeyBytes,
                "RESPONSE:$challenge".toByteArray(Charsets.UTF_8)
            ) ?: return@launch
            val (responseIv, responseCipher) = responsePayload
            writeToSession(
                sessionCode,
                buildString {
                    append("HSK_ACK|")
                    append(challenge)
                    append('|')
                    append(responseIv)
                    append('|')
                    append(responseCipher)
                    append('\n')
                }.toByteArray(Charsets.UTF_8)
            )

            onSessionActive(sessionCode)
            sendContactInfo(sessionCode, selectedKeyBase64)
        }
    }

    fun handleHandshakeAck(sessionCode: String, payload: String) {
        val parts = payload.split('|', limit = 4)
        val challenge = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
        val ivBase64 = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return
        val cipherBase64 = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: return
        val pending = pendingHandshakes.remove(challenge) ?: return
        pending.timeoutRunnable?.let { handler.removeCallbacks(it) }
        pending.timeoutRunnable = null

        if (pending.sessionCode != sessionCode) {
            handler.post { pending.callback(false) }
            return
        }

        val decrypted = decryptAesGcm(pending.aesKey, ivBase64, cipherBase64)
            ?.toString(Charsets.UTF_8)
        val success = decrypted == "RESPONSE:$challenge"
        handler.post { pending.callback(success) }
        onSessionActive(sessionCode)
        if (success) {
            sendContactInfo(sessionCode, Base64.encodeToString(pending.aesKey, Base64.NO_WRAP))
        }
    }

    fun handleContactInfo(sessionCode: String, payload: String) {
        val parts = payload.split('|', limit = 3)
        val ivBase64 = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
        val cipherBase64 = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return

        serviceScope.launch {
            val contact = getContact(appContext, sessionCode) ?: return@launch
            val keyBytes = contact.aesKey.takeIf { it.isNotBlank() }
                ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: return@launch
            val decrypted = decryptAesGcm(keyBytes, ivBase64, cipherBase64)
                ?.toString(Charsets.UTF_8)
                ?: return@launch
            val json = runCatching { JSONObject(decrypted) }.getOrNull() ?: return@launch
            val remoteSession = json.optString("sessionCode").trim()
            val remoteName = json.optString("name").trim()
            val remoteAvatar = json.optString("avatarB64").trim().takeIf { it.isNotBlank() }
            val remoteKey = json.optString("aesKey").takeIf { it.isNotBlank() } ?: contact.aesKey
            val bondedDeviceName = resolveBondedDeviceName(contact.address)

            if (remoteSession.isNotBlank() && !remoteSession.equals(sessionCode, ignoreCase = true)) {
                Log.d(
                    TAG,
                    "Ignoring remote session mismatch for $sessionCode, payload session=$remoteSession"
                )
            }

            if (contact.aesKey.isBlank() && remoteKey.isNotBlank()) {
                updateContactAesKey(appContext, sessionCode, remoteKey)
            }

            val shouldUpdateName = remoteName.isNotBlank() &&
                shouldReplaceContactName(
                    currentName = contact.name,
                    sessionCode = sessionCode,
                    address = contact.address,
                    hasStoredAesKey = contact.aesKey.isNotBlank(),
                    bondedDeviceName = bondedDeviceName,
                    candidateName = remoteName
                )
            if (remoteName.isNotBlank()) {
                Log.d(
                    TAG,
                    "Contact info name resolution for $sessionCode: current='${contact.name}', remote='$remoteName', bonded='${bondedDeviceName.orEmpty()}', replace=$shouldUpdateName"
                )
            }
            if (shouldUpdateName) {
                updateContactName(appContext, sessionCode, remoteName)
            }
            if (remoteAvatar != null) {
                ContactAvatarStorage.saveRemoteAvatarPayload(
                    context = appContext,
                    sessionCode = sessionCode,
                    payloadBase64 = remoteAvatar
                )
            }
            // Optional field: old app versions never send it, so absence means "leave as is"
            // rather than "adult" — only a peer that reports the flag can change it.
            if (json.has("isChild")) {
                updateContactChildFlag(appContext, sessionCode, json.optBoolean("isChild", false))
            }

            onSessionActive(sessionCode)
        }
    }

    fun failPendingForSession(sessionCode: String) {
        val iterator = pendingHandshakes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pending = entry.value
            if (pending.sessionCode == sessionCode) {
                pending.timeoutRunnable?.let { handler.removeCallbacks(it) }
                pending.timeoutRunnable = null
                iterator.remove()
                handler.post { pending.callback(false) }
            }
        }
    }

    fun failAllPending() {
        val pendingEntries = pendingHandshakes.entries.toList()
        pendingEntries.forEach { entry ->
            val pending = pendingHandshakes.remove(entry.key) ?: return@forEach
            pending.timeoutRunnable?.let { handler.removeCallbacks(it) }
            pending.timeoutRunnable = null
            handler.post { pending.callback(false) }
        }
    }

    private fun sendHandshakeRequest(
        sessionCode: String,
        keyBytes: ByteArray,
        callback: (Boolean) -> Unit
    ) {
        val challenge = UUID.randomUUID().toString()
        val payloadBytes = "CHALLENGE:$challenge".toByteArray(Charsets.UTF_8)
        val encryptionResult = encryptAesGcm(keyBytes, payloadBytes)
        if (encryptionResult == null) {
            handler.post { callback(false) }
            return
        }
        val (ivBase64, cipherBase64) = encryptionResult
        val request = PendingHandshake(sessionCode, keyBytes, challenge, callback)
        pendingHandshakes.put(challenge, request)?.timeoutRunnable?.let { timeout ->
            handler.removeCallbacks(timeout)
        }

        val success = writeToSession(
            sessionCode,
            buildString {
                append("HSK_REQ|")
                append(challenge)
                append('|')
                append(ivBase64)
                append('|')
                append(cipherBase64)
                append('\n')
            }.toByteArray(Charsets.UTF_8)
        )
        if (!success) {
            pendingHandshakes.remove(challenge)
            handler.post { callback(false) }
            return
        }

        val timeoutTask = Runnable {
            val removed = pendingHandshakes.remove(challenge)
            if (removed != null) {
                removed.timeoutRunnable = null
                handler.post { removed.callback(false) }
            }
        }
        request.timeoutRunnable = timeoutTask
        handler.postDelayed(timeoutTask, HANDSHAKE_TIMEOUT_MS)
    }

    private fun sendContactInfo(sessionCode: String, aesKeyBase64: String) {
        val keyBytes = runCatching { Base64.decode(aesKeyBase64, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return

        serviceScope.launch {
            val localSession = currentBluetoothSessionName().orEmpty()
            val localName = getSavedUserName(appContext).first().trim()
            val avatarPayload = ContactAvatarStorage.localProfileAvatarPayload(appContext)
            val payload = JSONObject().apply {
                if (localSession.isNotBlank()) {
                    put("sessionCode", localSession)
                }
                if (localName.isNotBlank()) {
                    put("name", localName)
                }
                if (!avatarPayload.isNullOrBlank()) {
                    put("avatarB64", avatarPayload)
                }
                put("aesKey", aesKeyBase64.trim())
                put("isChild", ChildProfileManager.isEnabled(appContext))
            }.toString().toByteArray(Charsets.UTF_8)
            val encrypted = encryptAesGcm(keyBytes, payload) ?: return@launch
            val (ivBase64, cipherBase64) = encrypted
            val message = "CONTACT_INFO|$ivBase64|$cipherBase64\n"
            writeToSession(sessionCode, message.toByteArray(Charsets.UTF_8))
        }
    }

    private fun shouldReplaceContactName(
        currentName: String,
        sessionCode: String,
        address: String?,
        hasStoredAesKey: Boolean,
        bondedDeviceName: String?,
        candidateName: String
    ): Boolean {
        val existing = currentName.trim()
        val candidate = candidateName.trim()
        if (candidate.isEmpty()) {
            return false
        }
        if (existing.isEmpty()) {
            return true
        }
        if (existing.equals(candidate, ignoreCase = true)) {
            return false
        }
        if (existing.equals(sessionCode, ignoreCase = true)) {
            return true
        }
        if (
            !bondedDeviceName.isNullOrBlank() &&
            existing.equals(bondedDeviceName.trim(), ignoreCase = true)
        ) {
            return true
        }
        val normalizedSession = normalizeMacAddress(sessionCode)
        val normalizedAddress = normalizeMacAddress(address.orEmpty())
        if (normalizedAddress.isNotBlank() && existing.equals(normalizedAddress, ignoreCase = true)) {
            return true
        }
        if (
            !hasStoredAesKey &&
            normalizedSession.isNotBlank() &&
            normalizedAddress.isNotBlank() &&
            normalizedSession.equals(normalizedAddress, ignoreCase = true)
        ) {
            return true
        }
        if (CLASSIC_SESSION_CODE_REGEX.matches(existing)) {
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun resolveBondedDeviceName(address: String?): String? {
        val normalizedAddress = normalizeMacAddress(address.orEmpty())
        if (normalizedAddress.isBlank()) {
            return null
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val matchedDevice = runCatching {
            adapter.bondedDevices
                ?.firstOrNull { bonded ->
                    normalizeMacAddress(
                        runCatching { bonded.address }.getOrDefault("")
                    ).equals(normalizedAddress, ignoreCase = true)
                }
        }.getOrNull() ?: return null

        val aliasName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { matchedDevice.alias }.getOrNull()
        } else {
            null
        }
        return aliasName?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching { matchedDevice.name }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        private const val TAG = "RfcommService"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private val CLASSIC_SESSION_CODE_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{3,31}$")
    }
}
