package com.auralis.crisisconnect.messaging

import android.content.Context
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.messaging.call.sfu.MlsHandshakeCodec
import com.auralis.crisisconnect.messaging.call.sfu.MlsWorker
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AuthorityMlsDeviceIdentity(
    val credential: String,
    val accountUid: String,
    val deviceId: String,
    val signingPublicKey: ByteArray,
)

data class AuthorityMlsStep(
    val broadcasts: List<String>,
    val safetyNumber: String?,
    val nextControlSequence: Long,
    val nextApplicationSequence: Long,
    val pendingApplicationMessages: List<AuthorityMlsPendingApplication>,
    val pendingReceivedApplications: List<AuthorityMlsPendingReceivedApplication>,
)

data class AuthorityMlsOutbox(
    val pendingBroadcasts: List<String>,
    val nextControlSequence: Long,
)

data class AuthorityMlsApplicationOutbox(
    val pendingMessages: List<AuthorityMlsPendingApplication>,
)

/**
 * Durable, per-conversation MLS state for AuthorityChat. Every ratchet advance is serialized and
 * persisted through [MlsStateVault] before ciphertext/plaintext is returned to the caller. If that
 * persistence fails, the old snapshot is deleted and the context is closed so a restarted client
 * can never reuse an old sender generation.
 *
 * The caller supplies a verified device directory when processing handshakes. KeyPackages and the
 * complete post-commit roster must match those pinned signing keys exactly; a self-signed package
 * relayed by the service is deliberately insufficient identity.
 */
class PersistentAuthorityMlsContext private constructor(
    context: Context,
    private val accountUid: String,
    val channelId: String,
    val deviceId: String,
) {
    private val appContext = context.applicationContext
    val credential: String = AuthorityMlsCredential.encode(accountUid, deviceId)
    private val stateContext = "authority-mls:v2:$accountUid:$deviceId:$channelId"
    private val mutex = locks.computeIfAbsent(stateContext) { Mutex() }
    private var initialized = false
    private var nextControlSequence = 0L
    private var nextApplicationSequence = 0L
    private var pendingControlEvents = emptyList<String>()
    private var pendingApplicationMessages = emptyList<AuthorityMlsPendingApplication>()
    private var pendingReceivedApplications = emptyList<AuthorityMlsPendingReceivedApplication>()
    private var offlineReceipts = emptyList<AuthorityMlsOfflineReceipt>()

    init {
        require(channelId.isNotBlank() && channelId.toByteArray().size <= 256) {
            "Authority MLS channel ID is invalid."
        }
        require(stateContext.toByteArray().size <= 512) { "Authority MLS context is too long." }
    }

    suspend fun restoreOrInitialize(isCreator: Boolean): AuthorityMlsStep = locked {
        checkAvailable()
        val encoded = try {
            MlsStateVault.load(appContext, stateContext)
        } catch (error: MlsStateRecoveryRequiredException) {
            throw error
        } catch (error: Exception) {
            throw MlsStateRecoveryRequiredException(
                "Stored Authority MLS state could not be opened safely.",
                error,
            )
        }
        if (encoded != null) {
            try {
                val state = AuthorityMlsDurableStateCodec.decode(encoded)
                check(MlsWorker.nativePersistentImportState(stateContext, state.snapshot)) {
                    "Stored Authority MLS state could not be authenticated."
                }
                initialized = true
                nextControlSequence = state.nextControlSequence
                nextApplicationSequence = state.nextApplicationSequence
                pendingControlEvents = state.pendingControlEvents
                pendingApplicationMessages = state.pendingApplicationMessages
                pendingReceivedApplications = state.pendingReceivedApplications
                offlineReceipts = state.offlineReceipts
                verifyLocalIdentity()
                return@locked AuthorityMlsStep(
                    pendingControlEvents,
                    currentSafetyNumber(),
                    nextControlSequence,
                    nextApplicationSequence,
                    pendingApplicationMessages,
                    pendingReceivedApplications,
                )
            } catch (error: MlsStateRecoveryRequiredException) {
                throw error
            } catch (error: Exception) {
                // Import/identity validation failed before this process could use the sender
                // ratchet. Leave the wrapped snapshot intact until the session proves whether this
                // leaf was unpublished or selects a fresh generation; never retry it in a loop.
                runCatching { MlsWorker.nativePersistentClose(stateContext) }
                initialized = false
                throw MlsStateRecoveryRequiredException(
                    "Stored Authority MLS state could not be authenticated.",
                    error,
                )
            }
        }
        advanceRatchet {
            val response = if (isCreator) {
                MlsWorker.nativePersistentNewStateAndCreateGroup(stateContext, credential)
            } else {
                MlsWorker.nativePersistentNewState(stateContext, credential)
            }
            initialized = true
            incorporate(parseResponse(response))
        }
    }

    suspend fun localIdentity(): AuthorityMlsDeviceIdentity = locked {
        checkAvailable()
        checkInitialized()
        verifyLocalIdentity()
    }

    suspend fun alignFreshJoinRelayCursors(
        nextControl: Long,
        nextApplication: Long,
    ): AuthorityMlsStep = locked {
        checkAvailable()
        checkInitialized()
        val pendingJoin = pendingControlEvents.singleOrNull()?.let { payload ->
            runCatching { JSONObject(payload) }.getOrNull()?.let { envelope ->
                envelope.optString("type") == "shareKeyPackage" &&
                    envelope.optString("senderId") == credential &&
                    envelope.optString("senderUid") == accountUid
            }
        } == true
        if (!pendingJoin || pendingApplicationMessages.isNotEmpty() || pendingReceivedApplications.isNotEmpty() ||
            nextControl < nextControlSequence || nextApplication < nextApplicationSequence) {
            throw SecurityException("Authority MLS state is not a pristine pending device join.")
        }
        advanceRatchet {
            nextControlSequence = nextControl
            nextApplicationSequence = nextApplication
            AuthorityMlsStep(
                pendingControlEvents,
                null,
                nextControlSequence,
                nextApplicationSequence,
                pendingApplicationMessages,
                pendingReceivedApplications,
            )
        }
    }

    /** True only while this leaf has a single, never-acknowledged KeyPackage and no ratchet traffic. */
    suspend fun isPristinePendingDeviceJoin(): Boolean = locked {
        checkAvailable()
        checkInitialized()
        pendingControlEvents.singleOrNull()?.let { payload ->
            runCatching { JSONObject(payload) }.getOrNull()?.let { envelope ->
                envelope.optString("type") == "shareKeyPackage" &&
                    envelope.optString("senderId") == credential &&
                    envelope.optString("senderUid") == accountUid
            }
        } == true && pendingApplicationMessages.isEmpty() && pendingReceivedApplications.isEmpty()
    }

    /** Advances over an authenticated control event from an epoch before this leaf's Welcome. */
    suspend fun skipControlBeforeWelcome(sequence: Long): AuthorityMlsOutbox = locked {
        checkAvailable()
        checkInitialized()
        if (sequence != nextControlSequence || sequence >= MAX_SAFE_SEQUENCE) {
            throw SecurityException("Authority MLS pre-join control cursor is out of order.")
        }
        advanceRatchet {
            nextControlSequence = sequence + 1
            AuthorityMlsOutbox(pendingControlEvents, nextControlSequence)
        }
    }

    suspend fun verifiedRoster(verifiedDirectory: Map<String, ByteArray>): List<AuthorityMlsDeviceIdentity> = locked {
        checkAvailable()
        checkInitialized()
        verifyRoster(verifiedDirectory)
    }

    /** Cryptographically authenticated roster from the already-established local MLS group. */
    suspend fun authenticatedRoster(): List<AuthorityMlsDeviceIdentity> = locked {
        checkAvailable()
        checkInitialized()
        readRoster().map { it.copy(signingPublicKey = it.signingPublicKey.copyOf()) }
    }

    suspend fun processHandshake(
        payload: String,
        authenticatedSenderUid: String,
        verifiedDirectory: Map<String, ByteArray>,
        sequence: Long,
        relayApplicationSequence: Long,
    ): AuthorityMlsStep = locked {
        checkAvailable()
        checkInitialized()
        if (sequence != nextControlSequence || sequence >= MAX_SAFE_SEQUENCE) {
            throw SecurityException("Authority MLS control event is missing, duplicated, or out of order.")
        }
        if (relayApplicationSequence !in 0 until MAX_SAFE_SEQUENCE) {
            throw SecurityException("Authority MLS relay application boundary is invalid.")
        }
        val envelope = runCatching { JSONObject(payload) }.getOrNull()
            ?: throw SecurityException("Authority MLS relay payload is not JSON.")
        val senderUid = envelope.optString("senderUid")
        val senderCredential = envelope.optString("senderId")
        val parsed = AuthorityMlsCredential.decode(senderCredential)
            ?: throw SecurityException("Authority MLS sender credential is malformed.")
        if (senderUid != authenticatedSenderUid || parsed.accountUid != authenticatedSenderUid) {
            throw SecurityException("Authority MLS sender does not match the authenticated relay writer.")
        }
        val expectedSigningKey = verifiedDirectory[senderCredential]
            ?: throw SecurityException("Authority MLS sender device is not verified.")
        val message = MlsHandshakeCodec.decode(payload)
            ?: throw SecurityException("Authority MLS handshake is not decodable.")
        val addressedElsewhere = if (message is MlsHandshakeCodec.Incoming.SendMlsWelcome) {
            val recipient = envelope.optString("recipientId")
            if (recipient.isBlank()) throw SecurityException("Authority MLS welcome is not bound to a recipient device.")
            recipient != credential
        } else {
            false
        }
        advanceRatchet {
            val controlType = envelope.optString("type")
            val applicationBoundary = if (controlType == "shareKeyPackage") {
                if (envelope.has("applicationSequenceBoundary")) {
                    throw SecurityException("Authority MLS KeyPackage has an unexpected application boundary.")
                }
                null
            } else {
                val rawBoundary = envelope.opt("applicationSequenceBoundary") as? Number
                    ?: throw SecurityException("Authority MLS epoch transition has no application boundary.")
                rawBoundary.toLong().also {
                    if (rawBoundary.toDouble() != it.toDouble() || it !in 0 until MAX_SAFE_SEQUENCE) {
                        throw SecurityException("Authority MLS epoch transition boundary is malformed.")
                    }
                }
            }
            if (message is MlsHandshakeCodec.Incoming.SendMlsWelcome && !addressedElsewhere) {
                val boundary = applicationBoundary!!
                if (currentSafetyNumber() != null || pendingApplicationMessages.isNotEmpty() ||
                    pendingReceivedApplications.isNotEmpty() || boundary < nextApplicationSequence ||
                    boundary > relayApplicationSequence) {
                    throw SecurityException("Authority MLS Welcome application boundary is invalid for this fresh leaf.")
                }
                nextApplicationSequence = boundary
            }
            val step = if (addressedElsewhere) {
                AuthorityMlsStep(
                    emptyList(), currentSafetyNumber(), nextControlSequence, nextApplicationSequence,
                    pendingApplicationMessages, pendingReceivedApplications,
                )
            } else {
                val response = when (message) {
                    is MlsHandshakeCodec.Incoming.ShareKeyPackage -> MlsWorker.nativePersistentAddUser(
                        stateContext,
                        message.keyPkg,
                        senderCredential,
                        expectedSigningKey,
                    )
                    is MlsHandshakeCodec.Incoming.SendMlsWelcome -> MlsWorker.nativePersistentJoinGroup(
                        stateContext,
                        message.welcome,
                        message.rtree,
                    )
                    is MlsHandshakeCodec.Incoming.SendMlsMessage -> MlsWorker.nativePersistentHandleCommit(
                        stateContext,
                        message.msg,
                        senderCredential,
                    )
                }
                parseResponse(
                    response,
                    welcomeRecipient = if (message is MlsHandshakeCodec.Incoming.ShareKeyPackage) {
                        senderCredential
                    } else {
                        null
                    },
                    applicationSequenceBoundary = relayApplicationSequence,
                )
            }
            val safetyNumber = currentSafetyNumber()
            if (!addressedElsewhere && safetyNumber != null) verifyRoster(verifiedDirectory)
            nextControlSequence = sequence + 1
            incorporate(step.copy(safetyNumber = safetyNumber ?: step.safetyNumber))
        }
    }

    /** Call only after this exact outbox head is durably present at [sequence] in Firestore. */
    suspend fun acknowledgePublishedControlEvent(sequence: Long, payload: String): AuthorityMlsOutbox = locked {
        checkAvailable()
        checkInitialized()
        if (sequence != nextControlSequence || pendingControlEvents.firstOrNull() != payload || sequence >= MAX_SAFE_SEQUENCE) {
            throw SecurityException("Authority MLS control acknowledgement does not match the durable outbox.")
        }
        advanceRatchet {
            nextControlSequence = sequence + 1
            pendingControlEvents = pendingControlEvents.drop(1)
            AuthorityMlsOutbox(pendingControlEvents, nextControlSequence)
        }
    }

    suspend fun encryptApplication(plaintext: ByteArray): ByteArray = locked {
        checkAvailable()
        checkInitialized()
        advanceRatchet {
            MlsWorker.nativePersistentEncryptApplication(stateContext, plaintext)
                ?: throw SecurityException("Authority MLS encryption failed.")
        }
    }

    /** Encrypts once and persists the exact ciphertext before exposing it to Firestore transport. */
    suspend fun queueApplication(
        plaintext: ByteArray,
        messageId: String = randomMessageId(),
    ): AuthorityMlsPendingApplication = locked {
        checkAvailable()
        checkInitialized()
        require(messageId.length <= 128 && BASE64URL.matches(messageId)) { "Authority MLS message ID is invalid." }
        advanceRatchet {
            val ciphertext = MlsWorker.nativePersistentEncryptApplication(stateContext, plaintext)
                ?: throw SecurityException("Authority MLS encryption failed.")
            val entry = AuthorityMlsPendingApplication(messageId, base64url(ciphertext))
            pendingApplicationMessages = pendingApplicationMessages + entry
            entry
        }
    }

    /** Returns a durable sender-outbox entry without mutating it. Used by the Bluetooth relay. */
    suspend fun pendingApplication(messageId: String): AuthorityMlsPendingApplication? = locked {
        checkAvailable()
        checkInitialized()
        pendingApplicationMessages.firstOrNull { it.messageId == messageId }
    }

    suspend fun acknowledgePublishedApplication(
        messageId: String,
        ciphertext: String,
    ): AuthorityMlsApplicationOutbox = locked {
        checkAvailable()
        checkInitialized()
        val head = pendingApplicationMessages.firstOrNull()
        if (head?.messageId != messageId || head.ciphertext != ciphertext) {
            throw SecurityException("Authority MLS application acknowledgement does not match the durable outbox.")
        }
        advanceRatchet {
            pendingApplicationMessages = pendingApplicationMessages.drop(1)
            AuthorityMlsApplicationOutbox(pendingApplicationMessages)
        }
    }

    suspend fun decryptApplication(ciphertext: ByteArray): ByteArray = locked {
        checkAvailable()
        checkInitialized()
        advanceRatchet {
            MlsWorker.nativePersistentDecryptApplication(stateContext, ciphertext)
                ?: throw SecurityException("Authority MLS authentication failed.")
        }
    }

    /** Commits the ordered receive cursor, receiver ratchet and plaintext inbox atomically. */
    suspend fun processApplicationMessage(
        sequence: Long,
        messageId: String,
        senderCredential: String,
        ciphertext: String,
        verifiedDirectory: Map<String, ByteArray>,
    ): List<AuthorityMlsPendingReceivedApplication> = locked {
        checkAvailable()
        checkInitialized()
        if (sequence != nextApplicationSequence || sequence >= MAX_SAFE_SEQUENCE) {
            throw SecurityException("Authority MLS application log is missing, duplicated, or out of order.")
        }
        if (!BASE64URL.matches(messageId) || messageId.length > 128 || senderCredential !in verifiedDirectory) {
            throw SecurityException("Authority MLS application sender is not verified.")
        }
        advanceRatchet {
            val offline = offlineReceipts.firstOrNull { it.messageId == messageId }
            if (offline != null) {
                val expectedHash = ciphertextHash(ciphertext)
                if (offline.senderCredential != senderCredential || offline.ciphertextHash != expectedHash) {
                    throw SecurityException("Authority MLS cloud copy does not match its offline receipt.")
                }
                offlineReceipts = offlineReceipts.filterNot { it.messageId == messageId }
                nextApplicationSequence = sequence + 1
                return@advanceRatchet pendingReceivedApplications.map(::copyReceived)
            }
            if (senderCredential != credential) {
                val plaintext = MlsWorker.nativePersistentDecryptApplication(stateContext, decodeBase64url(ciphertext))
                    ?: throw SecurityException("Authority MLS authentication failed.")
                pendingReceivedApplications = pendingReceivedApplications + AuthorityMlsPendingReceivedApplication(
                    messageId,
                    senderCredential,
                    plaintext,
                )
            }
            nextApplicationSequence = sequence + 1
            pendingReceivedApplications.map(::copyReceived)
        }
    }

    /**
     * Authenticates an unsequenced application ciphertext delivered by the nearby transport. The
     * sender credential must already be a member of the locally authenticated MLS roster and must
     * belong to the P2P-authenticated peer account. The cloud cursor is intentionally untouched;
     * its later immutable relay copy is reconciled through [offlineReceipts].
     */
    suspend fun processOfflineApplicationMessage(
        messageId: String,
        senderCredential: String,
        ciphertext: String,
        authenticatedPeerUid: String,
    ): List<AuthorityMlsPendingReceivedApplication> = locked {
        checkAvailable()
        checkInitialized()
        require(BASE64URL.matches(messageId) && messageId.length <= 128) {
            "Authority MLS offline message ID is malformed."
        }
        val parsed = AuthorityMlsCredential.decode(senderCredential)
            ?: throw SecurityException("Authority MLS offline sender credential is malformed.")
        if (parsed.accountUid != authenticatedPeerUid || senderCredential == credential) {
            throw SecurityException("Authority MLS offline sender does not match the authenticated peer.")
        }
        val roster = readRoster()
        if (roster.none { it.credential == senderCredential && it.accountUid == authenticatedPeerUid }) {
            throw SecurityException("Authority MLS offline sender is outside the authenticated roster.")
        }
        val hash = ciphertextHash(ciphertext)
        offlineReceipts.firstOrNull { it.messageId == messageId }?.let { existing ->
            if (existing.senderCredential != senderCredential || existing.ciphertextHash != hash) {
                throw SecurityException("Authority MLS offline message identity was reused.")
            }
            return@locked pendingReceivedApplications.map(::copyReceived)
        }
        if (offlineReceipts.size >= MAX_OFFLINE_RECEIPTS) {
            throw SecurityException("Authority MLS offline receipt ledger is full.")
        }
        advanceRatchet {
            val plaintext = MlsWorker.nativePersistentDecryptApplication(stateContext, decodeBase64url(ciphertext))
                ?: throw SecurityException("Authority MLS offline authentication failed.")
            pendingReceivedApplications = pendingReceivedApplications + AuthorityMlsPendingReceivedApplication(
                messageId,
                senderCredential,
                plaintext,
            )
            offlineReceipts = offlineReceipts + AuthorityMlsOfflineReceipt(messageId, senderCredential, hash)
            pendingReceivedApplications.map(::copyReceived)
        }
    }

    /** ACK only after the UI/local encrypted database durably accepted this exact inbox head. */
    suspend fun acknowledgeDeliveredApplication(messageId: String): List<AuthorityMlsPendingReceivedApplication> = locked {
        checkAvailable()
        checkInitialized()
        if (pendingReceivedApplications.firstOrNull()?.messageId != messageId) {
            throw SecurityException("Authority MLS delivered-message acknowledgement does not match the durable inbox.")
        }
        advanceRatchet {
            pendingReceivedApplications = pendingReceivedApplications.drop(1)
            pendingReceivedApplications.map(::copyReceived)
        }
    }

    suspend fun close() = locked {
        if (MlsWorker.available) MlsWorker.nativePersistentClose(stateContext)
        initialized = false
        Unit
    }

    /** Use only after the relay has verified that this exact leaf never published. */
    suspend fun discardForVerifiedRejoin() = locked {
        runCatching { MlsWorker.nativePersistentClose(stateContext) }
        MlsStateVault.delete(appContext, stateContext)
        initialized = false
        nextControlSequence = 0
        nextApplicationSequence = 0
        pendingControlEvents = emptyList()
        pendingApplicationMessages = emptyList()
        pendingReceivedApplications = emptyList()
        offlineReceipts = emptyList()
    }

    private fun verifyLocalIdentity(): AuthorityMlsDeviceIdentity {
        val raw = MlsWorker.nativePersistentIdentity(stateContext)
        val identity = parseIdentity(JSONObject(raw))
        check(identity.credential == credential) { "Stored Authority MLS identity changed." }
        return identity
    }

    private fun readRoster(): List<AuthorityMlsDeviceIdentity> {
        val root = JSONObject(MlsWorker.nativePersistentRoster(stateContext))
        if (root.has("error")) throw SecurityException("Authority MLS roster could not be verified.")
        val members = root.optJSONArray("members")
            ?: throw SecurityException("Authority MLS roster is missing.")
        check(members.length() > 0) { "Authority MLS roster is empty." }
        return buildList {
            for (index in 0 until members.length()) {
                add(parseIdentity(members.getJSONObject(index)))
            }
        }
    }

    private fun verifyRoster(verifiedDirectory: Map<String, ByteArray>): List<AuthorityMlsDeviceIdentity> {
        return readRoster().onEach { member ->
            val expected = verifiedDirectory[member.credential]
                ?: throw SecurityException("Authority MLS contains an unverified device.")
            if (!expected.contentEquals(member.signingPublicKey)) {
                throw SecurityException("Authority MLS device signing key changed.")
            }
        }
    }

    private fun parseIdentity(root: JSONObject): AuthorityMlsDeviceIdentity {
        if (root.has("error")) throw SecurityException("Authority MLS identity is unavailable.")
        val identityCredential = root.optString("credential")
        val parsed = AuthorityMlsCredential.decode(identityCredential)
            ?: throw SecurityException("Authority MLS identity credential is malformed.")
        val key = decodeByteField(root.optJSONObject("signingPublicKey"))
            ?: throw SecurityException("Authority MLS identity signing key is malformed.")
        check(key.size == 32) { "Authority MLS identity signing key has the wrong size." }
        return AuthorityMlsDeviceIdentity(identityCredential, parsed.accountUid, parsed.deviceId, key)
    }

    private fun parseResponse(
        raw: String,
        welcomeRecipient: String? = null,
        applicationSequenceBoundary: Long? = null,
    ): AuthorityMlsStep {
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw SecurityException("Authority MLS worker returned invalid JSON.")
        if (root.has("error")) throw SecurityException("Authority MLS cryptographic operation failed.")
        val broadcasts = buildList {
            val array = root.optJSONArray("broadcast") ?: return@buildList
            for (index in 0 until array.length()) {
                val message = array.optJSONObject(index)
                    ?: throw SecurityException("Authority MLS worker returned a malformed broadcast.")
                message.put("senderId", credential)
                message.put("senderUid", accountUid)
                if (message.optString("type") == "sendMlsWelcome") {
                    val recipient = welcomeRecipient
                        ?: throw SecurityException("Authority MLS welcome recipient is missing.")
                    message.put("recipientId", recipient)
                }
                if (message.optString("type") == "sendMlsWelcome" ||
                    message.optString("type") == "sendMlsMessage") {
                    val boundary = applicationSequenceBoundary
                        ?: throw SecurityException("Authority MLS generated epoch transition has no application boundary.")
                    message.put("applicationSequenceBoundary", boundary)
                }
                add(message.toString())
            }
        }
        val safety = root.optJSONArray("safetyNumber")?.let { values ->
            buildString {
                for (index in 0 until values.length()) {
                    append(values.optInt(index).toString().padStart(3, '0'))
                }
            }.takeIf { it.isNotEmpty() }
        }
        return AuthorityMlsStep(
            broadcasts, safety, nextControlSequence, nextApplicationSequence,
            pendingApplicationMessages, pendingReceivedApplications,
        )
    }

    private fun incorporate(step: AuthorityMlsStep): AuthorityMlsStep {
        pendingControlEvents = pendingControlEvents + step.broadcasts
        return AuthorityMlsStep(
            pendingControlEvents,
            step.safetyNumber,
            nextControlSequence,
            nextApplicationSequence,
            pendingApplicationMessages,
            pendingReceivedApplications,
        )
    }

    private fun currentSafetyNumber(): String? =
        MlsWorker.nativePersistentSafetyNumber(stateContext)?.joinToString(separator = "") {
            (it.toInt() and 0xff).toString().padStart(3, '0')
        }

    private fun persist() {
        val snapshot = MlsWorker.nativePersistentExportState(stateContext)
            ?: throw SecurityException("Authority MLS state export failed.")
        val state = AuthorityMlsDurableState(
            snapshot,
            nextControlSequence,
            nextApplicationSequence,
            pendingControlEvents,
            pendingApplicationMessages,
            pendingReceivedApplications,
            offlineReceipts,
        )
        MlsStateVault.save(appContext, stateContext, AuthorityMlsDurableStateCodec.encode(state))
    }

    private inline fun <T> advanceRatchet(operation: () -> T): T {
        MlsStateVault.beginAdvance(appContext, stateContext)
        try {
            val result = operation()
            persist()
            return result
        } catch (error: Throwable) {
            // The write-ahead marker was durable before the native mutation. Never continue with an
            // unknown in-memory state, and never permit the older snapshot to be restored.
            runCatching { MlsStateVault.delete(appContext, stateContext) }
            runCatching { MlsWorker.nativePersistentClose(stateContext) }
            initialized = false
            throw SecurityException("Authority MLS state could not be committed safely.", error)
        }
    }

    private fun checkAvailable() {
        check(MlsWorker.available) { "Mandatory Authority MLS worker is unavailable." }
    }

    private fun checkInitialized() {
        check(initialized) { "Authority MLS context has not been initialized." }
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    companion object {
        private const val MAX_SAFE_SEQUENCE = 9_007_199_254_740_991L
        private const val MAX_OFFLINE_RECEIPTS = 256
        private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")
        private val random = SecureRandom()
        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun randomMessageId(): String = "m_" + base64url(ByteArray(16).also(random::nextBytes))

        private fun base64url(value: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value)

        private fun decodeBase64url(value: String): ByteArray {
            require(value.length <= 900_000 && BASE64URL.matches(value)) {
                "Authority MLS ciphertext is malformed."
            }
            return Base64.getUrlDecoder().decode(value).also {
                require(base64url(it) == value) { "Authority MLS ciphertext is not canonical base64url." }
            }
        }

        private fun ciphertextHash(ciphertext: String): String =
            base64url(MessageDigest.getInstance("SHA-256").digest(decodeBase64url(ciphertext)))

        private fun copyReceived(value: AuthorityMlsPendingReceivedApplication) =
            value.copy(plaintext = value.plaintext.copyOf())

        fun create(
            context: Context,
            accountUid: String,
            channelId: String,
            deviceId: String = LocalKeyStorage.getOrCreateRescueDeviceId(context.applicationContext),
        ): PersistentAuthorityMlsContext {
            val uid = accountUid.trim()
            require(uid.isNotEmpty()) { "Authority MLS account UID is missing." }
            val normalizedDeviceId = deviceId.trim()
            require(normalizedDeviceId.isNotEmpty() && normalizedDeviceId.toByteArray().size <= 128) {
                "Authority MLS device ID is invalid."
            }
            return PersistentAuthorityMlsContext(context, uid, channelId.trim(), normalizedDeviceId)
        }

        private fun decodeByteField(field: JSONObject?): ByteArray? {
            field ?: return null
            if (!field.optBoolean("FLAG_ARRAY_BUFFER") && !field.optBoolean("FLAG_TYPED_ARRAY")) return null
            val data = field.optJSONArray("data") ?: return null
            return ByteArray(data.length()) { index -> (data.optInt(index) and 0xff).toByte() }
        }
    }
}
