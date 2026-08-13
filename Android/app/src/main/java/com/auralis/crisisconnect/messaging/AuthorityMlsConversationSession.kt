package com.auralis.crisisconnect.messaging

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AuthorityMlsPreparation(
    val conversationId: String,
    val creatorCredential: String,
    val localCredential: String,
    val safetyNumber: String?,
    val trust: List<AuthorityMlsTrustAssessment>,
    val rejectedDirectoryRecords: Int,
) {
    val ready: Boolean get() = rejectedDirectoryRecords == 0 && trust.isNotEmpty() && trust.all { it.approved }
}

class AuthorityMlsVerificationRequiredException(
    val preparation: AuthorityMlsPreparation,
) : SecurityException("Authority MLS authenticated device directory is incomplete or invalid.")

/**
 * Orchestrates one Android AuthorityChat MLS v2 conversation. It never publishes a KeyPackage,
 * commit or application message until every account has a complete authenticated device set.
 */
class AuthorityMlsConversationSession private constructor(
    private val accountUid: String,
    private val participants: List<String>,
    val conversationId: String,
    private val creatorCredential: String,
    private val crypto: PersistentAuthorityMlsContext,
    private val transport: AuthorityMlsTransport,
    private val trustStore: AuthorityMlsTrustStore,
    private val localIdentity: AuthorityMlsDeviceIdentity,
    initialStep: AuthorityMlsStep,
    private val sessionLease: Mutex,
) {
    val localCredential: String get() = localIdentity.credential
    private val mutex = Mutex()
    private var cursor = initialStep.nextControlSequence
    private var controlOutbox = initialStep.broadcasts
    private var applicationOutbox = initialStep.pendingApplicationMessages
    private var nextApplicationSequence = initialStep.nextApplicationSequence
    private var receivedInbox = initialStep.pendingReceivedApplications
    private var safetyNumber = initialStep.safetyNumber
    private var subscription: AuthorityMlsControlSubscription? = null
    private var applicationSubscription: AuthorityMlsControlSubscription? = null
    private var errorHandler: (Throwable) -> Unit = {}
    private var applicationHandler: (suspend (AuthorityMlsPendingReceivedApplication) -> Unit)? = null
    private var awaitingWelcome = creatorCredential != localIdentity.credential && initialStep.safetyNumber == null
    private var lastRosterDiagnostic: String? = null
    private var closed = false

    suspend fun refreshPreparation(): AuthorityMlsPreparation = mutex.withLock {
        check(!closed) { "Authority MLS session is closed." }
        preparationLocked()
    }

    suspend fun approveDeviceSet(uid: String, expectedFingerprint: String): AuthorityMlsPreparation = mutex.withLock {
        check(!closed) { "Authority MLS session is closed." }
        require(uid in participants) { "Authority MLS approval account is not a participant." }
        val directory = transport.loadDeviceDirectory(conversationId)
        val assessment = trustStore.assess(
            conversationId,
            uid,
            directory.records.filter { it.uid == uid },
        )
        if (assessment.fingerprint != expectedFingerprint || assessment.deviceCommitments.isEmpty()) {
            throw SecurityException("Authority MLS device set changed before approval.")
        }
        trustStore.approve(
            conversationId,
            uid,
            expectedFingerprint,
            assessment.deviceCommitments,
        )
        preparationLocked(directory)
    }

    /** Directory trust is necessary but sending waits until every device is in the live MLS roster. */
    suspend fun isReadyToSend(): Boolean = mutex.withLock {
        check(!closed) { "Authority MLS session is closed." }
        val directoryResult = verifiedDirectoryResultLocked()
        val directory = directoryResult.records.associate { it.credential to it.signingPublicKey.copyOf() }
        val roster = runCatching { crypto.verifiedRoster(directory) }.getOrElse { error ->
            logRosterDiagnostic("unavailable=${error.javaClass.simpleName} awaitingWelcome=$awaitingWelcome")
            return@withLock false
        }
        val ownerByCredential = directoryResult.records.associate { it.credential to it.uid }
        val representedAccounts = roster.mapNotNull { ownerByCredential[it.credential] }.toSet().size
        val ready = isAuthorityMlsRosterReady(
            participants,
            directoryResult.records,
            roster.map { it.credential },
            localIdentity.credential,
        )
        logRosterDiagnostic(
            "ready=$ready members=${roster.size} represented=$representedAccounts/${participants.size} " +
                "local=${roster.any { it.credential == localIdentity.credential }} awaitingWelcome=$awaitingWelcome",
        )
        ready
    }

    suspend fun activate(
        onApplication: suspend (AuthorityMlsPendingReceivedApplication) -> Unit,
        onSecurityError: (Throwable) -> Unit = {},
    ) = mutex.withLock {
        check(!closed) { "Authority MLS session is closed." }
        if (subscription != null) return@withLock
        errorHandler = onSecurityError
        applicationHandler = onApplication
        verifiedDirectoryLocked()
        deliverInboxLocked(onApplication)
        subscription = transport.listenControlEvents(
            conversationId = conversationId,
            fromSequence = cursor,
            onEvent = ::handleControlEvent,
            onError = onSecurityError,
        )
        startApplicationListenerLocked()
        flushControlLocked()
        // Realtime delivery is only a fast path. Deterministically consume the immutable control
        // tail and then the ciphertext tail from the durable cursors, matching the browser client.
        synchronizeControlsBeforeApplicationLocked()
        if (!awaitingWelcome) {
            catchUpApplicationsBeforeLocked(transport.loadApplicationSequence(conversationId))
        }
        flushApplicationsLocked(requireCompleteRoster = true)
    }

    /** Queues, persists and idempotently publishes one already-serialized AuthorityChat payload. */
    suspend fun sendApplication(plaintext: ByteArray, messageId: String? = null): String = mutex.withLock {
        check(!closed && subscription != null && applicationSubscription != null) {
            "Authority MLS session is not active."
        }
        val directoryResult = verifiedDirectoryResultLocked()
        val directory = directoryResult.records.associate { it.credential to it.signingPublicKey.copyOf() }
        val roster = crypto.verifiedRoster(directory)
        if (!isAuthorityMlsRosterReady(
                participants,
                directoryResult.records,
                roster.map { it.credential },
                localIdentity.credential,
            )) {
            throw SecurityException("Authority MLS membership has not converged to the approved device directory.")
        }
        if (messageId != null && applicationOutbox.any { it.messageId == messageId }) {
            // A crash may leave this exact ciphertext in the durable MLS outbox while the separately
            // Keystore-sealed UI outbox still holds its plaintext. Resume; never encrypt it twice.
            flushApplicationsLocked(requireCompleteRoster = false)
            return@withLock messageId
        }
        val entry = if (messageId == null) crypto.queueApplication(plaintext) else crypto.queueApplication(plaintext, messageId)
        applicationOutbox = applicationOutbox + entry
        flushApplicationsLocked(requireCompleteRoster = false)
        entry.messageId
    }

    /**
     * Encrypts exactly once into the crash-safe MLS outbox without requiring the cloud relay. The
     * returned immutable ciphertext is safe to copy over an authenticated nearby link; it remains
     * queued for normal Firestore publication when connectivity returns.
     */
    suspend fun queueApplicationForOfflineRelay(
        plaintext: ByteArray,
        messageId: String,
    ): AuthorityMlsCiphertextMessage = mutex.withLock {
        check(!closed && applicationHandler != null) { "Authority MLS session is not active." }
        val roster = crypto.authenticatedRoster()
        val represented = roster.map { it.accountUid }.toSet()
        if (represented != participants.toSet() || roster.none { it.credential == localIdentity.credential }) {
            throw SecurityException("Authority MLS offline roster is incomplete.")
        }
        val entry = applicationOutbox.firstOrNull { it.messageId == messageId } ?: run {
            crypto.queueApplication(plaintext, messageId).also {
                applicationOutbox = applicationOutbox + it
            }
        }
        AuthorityMlsCiphertextMessage(
            messageId = entry.messageId,
            senderUid = accountUid,
            senderDeviceId = localIdentity.deviceId,
            senderCredential = localIdentity.credential,
            ciphertext = entry.ciphertext,
        )
    }

    /** Accepts one same-MLS ciphertext from the authenticated nearby peer without cloud ordering. */
    suspend fun handleOfflineApplicationMessage(
        message: AuthorityMlsCiphertextMessage,
        authenticatedPeerUid: String,
    ) = mutex.withLock {
        check(!closed && !awaitingWelcome) { "Authority MLS offline session is unavailable." }
        val parsed = AuthorityMlsCredential.decode(message.senderCredential)
            ?: throw SecurityException("Authority MLS offline sender credential is malformed.")
        if (message.senderUid != authenticatedPeerUid || parsed.accountUid != authenticatedPeerUid ||
            parsed.deviceId != message.senderDeviceId) {
            throw SecurityException("Authority MLS offline sender binding is invalid.")
        }
        val handler = applicationHandler
            ?: throw SecurityException("Authority MLS offline application handler is unavailable.")
        receivedInbox = crypto.processOfflineApplicationMessage(
            messageId = message.messageId,
            senderCredential = message.senderCredential,
            ciphertext = message.ciphertext,
            authenticatedPeerUid = authenticatedPeerUid,
        )
        deliverInboxLocked(handler)
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        try {
            subscription?.close()
            applicationSubscription?.close()
            subscription = null
            applicationSubscription = null
            crypto.close()
        } finally {
            sessionLease.unlock()
        }
    }

    private suspend fun handleControlEvent(event: AuthorityMlsControlEvent) = mutex.withLock {
        if (closed) return@withLock
        handleControlEventLocked(event)
    }

    private suspend fun handleControlEventLocked(event: AuthorityMlsControlEvent) {
        when {
            event.sequence < cursor -> return
            event.sequence > cursor -> throw SecurityException("Authority MLS control log contains a gap.")
        }
        if (event.senderCredential == localIdentity.credential) {
            if (controlOutbox.firstOrNull() != event.payload) {
                throw SecurityException("Authority MLS self-authored control event does not match the durable outbox.")
            }
            val outbox = crypto.acknowledgePublishedControlEvent(event.sequence, event.payload)
            cursor = outbox.nextControlSequence
            controlOutbox = outbox.pendingBroadcasts
        } else {
            val directoryResult = verifiedDirectoryResultLocked()
            val directory = directoryResult.records.associate {
                it.credential to it.signingPublicKey.copyOf()
            }
            if (awaitingWelcome) {
                when (classifyAuthorityMlsPreJoinControl(
                    event.payload,
                    event.senderCredential,
                    event.senderUid,
                    localIdentity.credential,
                )) {
                    AuthorityMlsPreJoinControlDisposition.INVALID -> {
                        throw SecurityException("Authority MLS pre-join control event is malformed.")
                    }
                    AuthorityMlsPreJoinControlDisposition.SKIP -> {
                        val skipped = crypto.skipControlBeforeWelcome(event.sequence)
                        cursor = skipped.nextControlSequence
                        controlOutbox = skipped.pendingBroadcasts
                        flushControlLocked()
                        return
                    }
                    AuthorityMlsPreJoinControlDisposition.WELCOME -> Unit
                }
            } else if (isAuthorityMlsLocalKeyPackage(
                    event.payload,
                    event.senderCredential,
                    event.senderUid,
                )) {
                val roster = crypto.verifiedRoster(directory)
                val ownerByCredential = directoryResult.records.associate { it.credential to it.uid }
                val sponsor = authorityMlsJoinSponsor(
                    roster.map { it.credential },
                    ownerByCredential,
                    event.senderUid,
                    event.senderCredential,
                ) ?: throw SecurityException("Authority MLS could not select a verified join sponsor.")
                if (sponsor != localIdentity.credential) {
                    val skipped = crypto.skipControlBeforeWelcome(event.sequence)
                    cursor = skipped.nextControlSequence
                    controlOutbox = skipped.pendingBroadcasts
                    return
                }
            }
            val ordering = authorityMlsControlOrdering(event.payload)
                ?: throw SecurityException("Authority MLS control ordering boundary is malformed.")
            if (!awaitingWelcome && ordering.type == "sendMlsMessage") {
                catchUpApplicationsBeforeLocked(ordering.applicationSequenceBoundary!!)
            }
            val relayApplicationSequence = transport.loadApplicationSequence(conversationId)
            val step = crypto.processHandshake(
                payload = event.payload,
                authenticatedSenderUid = event.senderUid,
                verifiedDirectory = directory,
                sequence = event.sequence,
                relayApplicationSequence = relayApplicationSequence,
            )
            cursor = step.nextControlSequence
            controlOutbox = step.broadcasts
            applicationOutbox = step.pendingApplicationMessages
            safetyNumber = step.safetyNumber ?: safetyNumber
            if (step.nextApplicationSequence < nextApplicationSequence ||
                step.nextApplicationSequence > relayApplicationSequence) {
                throw SecurityException("Authority MLS handshake returned an invalid application boundary.")
            }
            nextApplicationSequence = step.nextApplicationSequence
            if (awaitingWelcome && ordering.type == "sendMlsWelcome") {
                if (step.safetyNumber == null) {
                    throw SecurityException("Authority MLS Welcome did not establish a verified group.")
                }
                awaitingWelcome = false
                startApplicationListenerLocked()
            }
        }
        flushControlLocked()
    }

    private suspend fun handleApplicationMessage(
        message: AuthorityMlsCiphertextMessage,
        onApplication: suspend (AuthorityMlsPendingReceivedApplication) -> Unit,
    ) = mutex.withLock {
        if (closed) return@withLock
        synchronizeControlsBeforeApplicationLocked()
        handleApplicationMessageLocked(message, onApplication)
    }

    private suspend fun synchronizeControlsBeforeApplicationLocked() {
        while (true) {
            val page = transport.loadControlEventsFrom(conversationId, cursor)
            if (page.isEmpty()) return
            val before = cursor
            for (event in page) handleControlEventLocked(event)
            if (cursor <= before) throw SecurityException("Authority MLS control catch-up did not advance.")
            if (page.size < 100) return
        }
    }

    private suspend fun deliverInboxLocked(
        onApplication: suspend (AuthorityMlsPendingReceivedApplication) -> Unit,
    ) {
        while (receivedInbox.isNotEmpty()) {
            val head = receivedInbox.first()
            onApplication(head.copy(plaintext = head.plaintext.copyOf()))
            receivedInbox = crypto.acknowledgeDeliveredApplication(head.messageId)
        }
    }

    private fun startApplicationListenerLocked() {
        if (awaitingWelcome || applicationSubscription != null) return
        val handler = applicationHandler ?: return
        applicationSubscription = transport.listenCiphertexts(
            conversationId = conversationId,
            fromSequence = nextApplicationSequence,
            onMessage = { message -> handleApplicationMessage(message, handler) },
            onError = errorHandler,
        )
    }

    private suspend fun catchUpApplicationsBeforeLocked(boundary: Long) {
        if (boundary < nextApplicationSequence) {
            throw SecurityException("Authority MLS Commit application boundary is invalid.")
        }
        val handler = applicationHandler
            ?: throw SecurityException("Authority MLS application handler is unavailable.")
        while (nextApplicationSequence < boundary) {
            val page = transport.loadCiphertextsBefore(conversationId, nextApplicationSequence, boundary)
            if (page.isEmpty()) throw SecurityException("Authority MLS application boundary contains a gap.")
            for (message in page) handleApplicationMessageLocked(message, handler)
        }
    }

    private suspend fun handleApplicationMessageLocked(
        message: AuthorityMlsCiphertextMessage,
        onApplication: suspend (AuthorityMlsPendingReceivedApplication) -> Unit,
    ) {
        if (closed || message.sequence < nextApplicationSequence) return
        if (message.sequence > nextApplicationSequence || awaitingWelcome) {
            throw SecurityException("Authority MLS application log is not ready for this epoch.")
        }
        receivedInbox = crypto.processApplicationMessage(
            sequence = message.sequence,
            messageId = message.messageId,
            senderCredential = message.senderCredential,
            ciphertext = message.ciphertext,
            verifiedDirectory = verifiedDirectoryLocked(),
        )
        nextApplicationSequence = message.sequence + 1
        deliverInboxLocked(onApplication)
    }

    private suspend fun flushControlLocked() {
        while (controlOutbox.isNotEmpty()) {
            val payload = controlOutbox.first()
            try {
                transport.publishControlEvent(
                    conversationId = conversationId,
                    sequence = cursor,
                    senderUid = accountUid,
                    senderDeviceId = localIdentity.deviceId,
                    senderCredential = localIdentity.credential,
                    payload = payload,
                )
                val outbox = crypto.acknowledgePublishedControlEvent(cursor, payload)
                cursor = outbox.nextControlSequence
                controlOutbox = outbox.pendingBroadcasts
            } catch (error: Throwable) {
                // Another participant may have won this exact sequence. The ordered listener will
                // consume that event and move our durable head to its next valid sequence.
                errorHandler(error)
                return
            }
        }
    }

    private suspend fun flushApplicationsLocked(requireCompleteRoster: Boolean) {
        if (applicationOutbox.isEmpty()) return
        if (requireCompleteRoster) {
            val directoryResult = verifiedDirectoryResultLocked()
            val directory = directoryResult.records.associate { it.credential to it.signingPublicKey.copyOf() }
            val roster = runCatching { crypto.verifiedRoster(directory) }.getOrNull() ?: return
            if (!isAuthorityMlsRosterReady(
                    participants,
                    directoryResult.records,
                    roster.map { it.credential },
                    localIdentity.credential,
                )) return
        }
        while (applicationOutbox.isNotEmpty()) {
            val head = applicationOutbox.first()
            transport.publishCiphertext(
                conversationId,
                AuthorityMlsCiphertextMessage(
                    messageId = head.messageId,
                    senderUid = accountUid,
                    senderDeviceId = localIdentity.deviceId,
                    senderCredential = localIdentity.credential,
                    ciphertext = head.ciphertext,
                ),
            )
            applicationOutbox = crypto.acknowledgePublishedApplication(
                head.messageId,
                head.ciphertext,
            ).pendingMessages
        }
    }

    private suspend fun preparationLocked(
        directory: AuthorityMlsDirectoryResult? = null,
    ): AuthorityMlsPreparation {
        val loaded = directory ?: transport.loadDeviceDirectory(conversationId)
        val grouped = loaded.records.groupBy { it.uid }
        val assessments = participants.map { uid ->
            trustStore.assess(
                conversationId,
                uid,
                grouped[uid].orEmpty(),
            )
        }
        return AuthorityMlsPreparation(
            conversationId,
            creatorCredential,
            localIdentity.credential,
            safetyNumber,
            assessments,
            loaded.rejected,
        )
    }

    private suspend fun verifiedDirectoryLocked(): Map<String, ByteArray> {
        val directory = verifiedDirectoryResultLocked()
        return directory.records.associate { it.credential to it.signingPublicKey.copyOf() }
    }

    private suspend fun verifiedDirectoryResultLocked(): AuthorityMlsDirectoryResult {
        val directory = transport.loadDeviceDirectory(conversationId)
        val preparation = preparationLocked(directory)
        if (!preparation.ready) throw AuthorityMlsVerificationRequiredException(preparation)
        if (directory.rejected != 0) throw AuthorityMlsVerificationRequiredException(preparation)
        val approvedAccounts = preparation.trust.filter { it.approved }.map { it.uid }.toSet()
        if (approvedAccounts != participants.toSet()) throw AuthorityMlsVerificationRequiredException(preparation)
        return directory
    }

    private fun logRosterDiagnostic(diagnostic: String) {
        if (diagnostic == lastRosterDiagnostic) return
        lastRosterDiagnostic = diagnostic
        Log.i(TAG, "MLS roster $diagnostic")
    }

    companion object {
        private const val TAG = "AuthorityChat"
        private const val UNPUBLISHED_RECOVERY_TIMEOUT_MS = 8_000L
        private const val JOIN_SPONSOR_RECOVERY_POLICY_VERSION = 1
        // PersistentAuthorityMlsContext keeps ratchet cursors in memory. A foreground thread and a
        // background wake must therefore never restore the same device/conversation concurrently.
        private val sessionLeases = ConcurrentHashMap<String, Mutex>()

        suspend fun prepare(
            context: Context,
            accountUid: String,
            binding: AuthorityMlsBinding,
            deviceLabel: String,
            transport: AuthorityMlsTransport = AuthorityMlsTransport(),
        ): AuthorityMlsConversationSession {
            val canonicalParticipants = AuthorityMlsIdentifiers.canonicalBinding(binding).participants
            val conversationId = AuthorityMlsIdentifiers.conversationId(binding)
            val lease = sessionLeases.computeIfAbsent("$accountUid\u0000$conversationId") { Mutex() }
            lease.lock()
            var preparingCrypto: PersistentAuthorityMlsContext? = null
            try {
                val appContext = context.applicationContext
                val baseDeviceId = LocalKeyStorage.getOrCreateRescueDeviceId(appContext)
                var activeDeviceId = AuthorityMlsDeviceGenerationStore.load(
                    appContext,
                    accountUid,
                    baseDeviceId,
                    conversationId,
                )
                var crypto = PersistentAuthorityMlsContext.create(
                    appContext,
                    accountUid,
                    conversationId,
                    activeDeviceId,
                ).also { preparingCrypto = it }
                val handle = transport.ensureConversation(binding, accountUid, crypto.credential)
                check(handle.conversationId == conversationId) { "Authority MLS conversation identity changed." }
                var step = try {
                    crypto.restoreOrInitialize(handle.creatorCredential == crypto.credential)
                } catch (error: MlsStateRecoveryRequiredException) {
                    Log.i(TAG, "Recovering an unreadable local MLS ratchet")
                    val unpublishedResetVerified = try {
                        withTimeout(UNPUBLISHED_RECOVERY_TIMEOUT_MS) {
                            transport.resetUnpublishedDeviceForRejoin(
                                conversationId = conversationId,
                                deviceId = activeDeviceId,
                                ownerUid = accountUid,
                                credential = crypto.credential,
                            )
                        }
                        true
                    } catch (_: AuthorityMlsPublishedDeviceRecoveryException) {
                        false
                    } catch (_: TimeoutCancellationException) {
                        // A timeout is not proof that the old leaf was unpublished. Rotating is the
                        // conservative boundary: the unreadable sender generation is never reused.
                        Log.i(TAG, "MLS unpublished-state proof timed out; rotating the local leaf")
                        false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (verificationError: Exception) {
                        // Network/rules failures likewise cannot authorize deleting or reusing the
                        // old leaf. A fresh generation remains safe and bounded to one rollover.
                        Log.w(TAG, "MLS unpublished-state proof failed; rotating the local leaf", verificationError)
                        false
                    }
                    if (unpublishedResetVerified) {
                        Log.i(TAG, "Resetting a verified-unpublished local MLS leaf")
                        crypto.discardForVerifiedRejoin()
                    } else {
                        runCatching { crypto.close() }
                        preparingCrypto = null
                        if (activeDeviceId != baseDeviceId) {
                            throw SecurityException(
                                "Authority MLS automatic published-device recovery limit was reached.",
                                error,
                            )
                        }
                        activeDeviceId = AuthorityMlsDeviceGenerationStore.rotate(
                            appContext,
                            accountUid,
                            baseDeviceId,
                            conversationId,
                            expectedDeviceId = activeDeviceId,
                        )
                        Log.i(TAG, "Selected a fresh per-conversation MLS leaf")
                    }
                    crypto = PersistentAuthorityMlsContext.create(
                        appContext,
                        accountUid,
                        conversationId,
                        activeDeviceId,
                    ).also { preparingCrypto = it }
                    crypto.restoreOrInitialize(handle.creatorCredential == crypto.credential).also {
                        Log.i(TAG, "Fresh local MLS state initialized")
                    }
                }
                // Older clients selected an unwoken same-account browser as the sponsor. Once all
                // online leaves skipped that published KeyPackage, this leaf could never receive a
                // Welcome. Rotate exactly once for this recovery policy and publish a fresh package
                // for the peer that the preparation service actually wakes. The abandoned leaf stays
                // in the authenticated audit directory and its sender ratchet is never reused.
                val publishedButNeverJoined = handle.creatorCredential != crypto.credential &&
                    step.safetyNumber == null &&
                    step.broadcasts.isEmpty() &&
                    step.pendingApplicationMessages.isEmpty() &&
                    step.pendingReceivedApplications.isEmpty()
                if (publishedButNeverJoined) {
                    val recoveredDeviceId = AuthorityMlsDeviceGenerationStore.rotateForStalledJoin(
                        appContext,
                        accountUid,
                        baseDeviceId,
                        conversationId,
                        expectedDeviceId = activeDeviceId,
                        recoveryPolicyVersion = JOIN_SPONSOR_RECOVERY_POLICY_VERSION,
                    )
                    if (recoveredDeviceId != activeDeviceId) {
                        runCatching { crypto.close() }
                        activeDeviceId = recoveredDeviceId
                        crypto = PersistentAuthorityMlsContext.create(
                            appContext,
                            accountUid,
                            conversationId,
                            activeDeviceId,
                        ).also { preparingCrypto = it }
                        step = crypto.restoreOrInitialize(false)
                        Log.i(TAG, "Rotated a stalled MLS join leaf for sponsor-policy recovery")
                    }
                }
                val identity = crypto.localIdentity()
                transport.registerDevice(
                    conversationId,
                    AuthorityMlsDirectoryRecord(
                        uid = accountUid,
                        deviceId = identity.deviceId,
                        credential = identity.credential,
                        signingPublicKey = identity.signingPublicKey,
                        label = deviceLabel.take(64),
                    ),
                )
                if (handle.creatorCredential != crypto.credential && step.safetyNumber == null &&
                    crypto.isPristinePendingDeviceJoin() &&
                    (step.nextControlSequence < handle.nextControlSequence ||
                        step.nextApplicationSequence < handle.nextApplicationSequence)) {
                    val payload = step.broadcasts.single()
                    val alreadyPublished = transport.isControlEventPublished(
                        conversationId = conversationId,
                        sequence = step.nextControlSequence,
                        senderUid = accountUid,
                        senderDeviceId = identity.deviceId,
                        senderCredential = identity.credential,
                        payload = payload,
                    )
                    step = if (alreadyPublished) {
                        val acknowledged = crypto.acknowledgePublishedControlEvent(
                            step.nextControlSequence,
                            payload,
                        )
                        step.copy(
                            broadcasts = acknowledged.pendingBroadcasts,
                            nextControlSequence = acknowledged.nextControlSequence,
                        )
                    } else {
                        crypto.alignFreshJoinRelayCursors(
                            handle.nextControlSequence,
                            handle.nextApplicationSequence,
                        )
                    }
                }
                val session = AuthorityMlsConversationSession(
                    accountUid = accountUid,
                    participants = canonicalParticipants,
                    conversationId = conversationId,
                    creatorCredential = handle.creatorCredential,
                    crypto = crypto,
                    transport = transport,
                    trustStore = AuthorityMlsTrustStore(context.applicationContext),
                    localIdentity = identity,
                    initialStep = step,
                    sessionLease = lease,
                )
                // Ownership of the native context and lease moves to the returned session.
                preparingCrypto = null
                return session
            } catch (error: Throwable) {
                // prepare() can fail after native initialization (for example on a transient
                // Firestore/App Check registration error). Leaving that context live makes the next
                // attempt misclassify a perfectly valid stored snapshot as corrupt. Cleanup must
                // also run when the caller was cancelled while navigating away from the thread.
                withContext(NonCancellable) {
                    runCatching { preparingCrypto?.close() }
                    lease.unlock()
                }
                throw error
            }
        }
    }
}
