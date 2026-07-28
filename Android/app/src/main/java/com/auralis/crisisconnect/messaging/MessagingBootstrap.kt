package com.auralis.crisisconnect.messaging

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.security.ChildProfileManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot setup that makes this device reachable over internet messaging:
 *  - signs in anonymously first if there is no account (QR-only users never see a login screen),
 *  - publishes our long-term identity public key to the directory (so contacts can encrypt to us),
 *  - registers our FCM push token (so the delivery trigger can reach us),
 *  - starts the realtime downlink + wires internet voice calls.
 *
 * Safe to call on every app start; all operations are idempotent. Pass a verified [phone] /
 * [username] to opt into directory discovery — omit them to publish the key without being
 * findable by phone/username (QR key exchange still works and stays the most private path).
 *
 * Robustness: the anonymous sign-in is time-bounded and retried with backoff, and if no identity
 * can be obtained (offline at launch, or a wedged Play Services that makes the sign-in Task hang
 * forever) we arm a one-shot retry for when connectivity returns — otherwise a single failed/hung
 * attempt would leave internet messaging silently dead for the whole session (no key published, no
 * push token, and crucially the realtime receiver never started) until the app is restarted.
 */
object MessagingBootstrap {

    private const val TAG = "MessagingBootstrap"

    // A single anonymous sign-in must never block the whole internet bring-up forever: on a device
    // with broken/absent Play Services the underlying Task can hang and never complete. Bound each
    // attempt, then retry with exponential backoff before giving up for now.
    private const val SIGN_IN_TIMEOUT_MS = 20_000L
    private const val SIGN_IN_MAX_ATTEMPTS = 4
    private const val SIGN_IN_INITIAL_BACKOFF_MS = 1_000L
    private const val SIGN_IN_MAX_BACKOFF_MS = 8_000L

    // Serializes concurrent bootstrap triggers (MainActivity.onCreate + the connectivity retry +
    // the onboarding phone-verify call) so we never fire two anonymous sign-ins in parallel.
    private val registerMutex = Mutex()

    // Long-lived scope for connectivity-triggered retries (outlives the caller's coroutine).
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    // Most specific directory args seen so far, so a connectivity retry re-publishes with the phone
    // even if the original (onCreate) trigger had none. Never downgraded back to null.
    @Volatile private var lastPhone: String? = null
    @Volatile private var lastUsername: String? = null

    suspend fun ensureRegistered(
        context: Context,
        phone: String? = null,
        username: String? = null
    ) {
        phone?.takeIf { it.isNotBlank() }?.let { lastPhone = it }
        username?.takeIf { it.isNotBlank() }?.let { lastUsername = it }
        val appContext = context.applicationContext
        registerMutex.withLock {
            val uid = resolveUid()
            if (uid == null) {
                // Couldn't get a Firebase identity (offline at launch, or Play Services wedged).
                // Arm a one-shot retry for when connectivity returns instead of dying for the whole
                // session; the app can still work over Bluetooth in the meantime.
                Log.w(TAG, "No Firebase uid yet — internet messaging deferred until connectivity returns")
                armConnectivityRetry(appContext)
                return
            }
            registerInternet(appContext, uid)
            clearConnectivityRetry(appContext)
        }
    }

    /** Publishes identity/token and starts the internet downlink + call wiring for [resolvedUid]. */
    private suspend fun registerInternet(context: Context, resolvedUid: String) {
        val client = InternetMessagingClient()

        publishIdentity(context, client, resolvedUid)

        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            client.registerPushToken(token)
        }.onFailure { Log.w(TAG, "Failed to register push token", it) }

        // Signal-protocol (v3) prekeys: publish/rotate + top up one-time pools so peers can start a
        // forward-secret session with us. Best-effort — messaging falls back to the v2 envelope for
        // peers without a session if this fails. On IO: the prekey store is blocking Room and this
        // bootstrap is launched from Main (MainActivity.onCreate).
        runCatching {
            withContext(Dispatchers.IO) {
                com.auralis.crisisconnect.messaging.signal.SignalPreKeyManager.create(context).ensurePublished()
            }
        }.onFailure { Log.w(TAG, "Failed to publish Signal prekeys", it) }

        // Start the realtime downlink so incoming internet messages arrive while the app is open,
        // independent of FCM push delivery (which handles background / closed-app cases).
        runCatching { InternetMessageReceiver.start(context) }
            .onFailure { Log.w(TAG, "Failed to start internet message listener", it) }

        // Wire real-time internet voice calls: the call manager signals SDP/ICE out over the relay.
        runCatching {
            com.auralis.crisisconnect.messaging.call.InternetCallManager.init(context) { peer, json ->
                InternetChatTransport(context).sendCallSignal(peer, json)
            }
        }.onFailure { Log.w(TAG, "Failed to init internet call manager", it) }

        // App-wide receiver for authority CHANNEL calls (agency + hierarchy callSignals), so an incoming
        // web/Android authority call rings from any screen. No-op for non-agency users.
        runCatching { com.auralis.crisisconnect.messaging.call.AuthorityCallReceiver.start(context) }
            .onFailure { Log.w(TAG, "Failed to start authority call receiver", it) }
    }

    /**
     * Publishes the identity key + directory profile. Falls back to the account's verified phone
     * (Firebase Auth) so directory discoverability persists across restarts — otherwise a no-phone
     * bootstrap would clear the entry that onboarding set. The backend only accepts a phone
     * matching the token's phone_number claim, so this stays the user's own number.
     */
    private suspend fun publishIdentity(
        context: Context,
        client: InternetMessagingClient,
        resolvedUid: String
    ) {
        val effectivePhone = lastPhone?.takeIf { it.isNotBlank() }
            ?: FirebaseAuth.getInstance().currentUser?.phoneNumber?.takeIf { it.isNotBlank() }
        val effectiveUsername = lastUsername?.takeIf { it.isNotBlank() }

        val displayName = runCatching { getSavedUserName(context).first().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() }
        val photoUrl = runCatching {
            FirebaseFirestore.getInstance().document("users/$resolvedUid").get().await().getString("photoURL")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        runCatching {
            val identity = MessagingIdentity(context)
            client.publishIdentityKey(
                publicKeyBase64 = identity.publicKeyBase64(),
                discoverable = !effectivePhone.isNullOrBlank() || !effectiveUsername.isNullOrBlank(),
                phone = effectivePhone,
                username = effectiveUsername,
                displayName = displayName,
                photoUrl = photoUrl,
                isChild = ChildProfileManager.isEnabled(context)
            )
        }.onFailure { Log.w(TAG, "Failed to publish identity key", it) }
    }

    /**
     * Re-publishes the directory entry so a changed profile fact (currently the child-profile
     * flag) becomes visible to future lookups. No sign-in attempts: a missing uid means the full
     * bootstrap hasn't run yet and will publish the current state anyway.
     */
    suspend fun republishIdentity(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        publishIdentity(context.applicationContext, InternetMessagingClient(), uid)
    }

    /**
     * Resolves a Firebase uid: a restored persisted session if present, otherwise a fresh anonymous
     * sign-in — time-bounded per attempt and retried with exponential backoff. Returns null only
     * when no identity could be obtained (offline / Play Services wedged) after all attempts.
     */
    private suspend fun resolveUid(): String? {
        awaitRestoredUid()?.let { return it }
        var backoff = SIGN_IN_INITIAL_BACKOFF_MS
        repeat(SIGN_IN_MAX_ATTEMPTS) { attempt ->
            // A previously-timed-out sign-in Task may have completed in the background meanwhile.
            FirebaseAuth.getInstance().currentUser?.uid?.let { return it }
            signInAnonymouslyWithTimeout()?.let { return it }
            if (attempt < SIGN_IN_MAX_ATTEMPTS - 1) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(SIGN_IN_MAX_BACKOFF_MS)
            }
        }
        return null
    }

    /**
     * Returns the uid of an already-signed-in user, waiting briefly for Firebase to restore a
     * persisted session from disk (which can lag a few hundred ms after launch). Returns null only
     * when there is genuinely no saved session (first-ever launch).
     */
    private suspend fun awaitRestoredUid(): String? {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let { return it }
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            delay(100)
            auth.currentUser?.uid?.let { return it }
        }
        return null
    }

    /** Anonymous sign-in bounded by [SIGN_IN_TIMEOUT_MS] so a wedged Task can't hang forever. */
    private suspend fun signInAnonymouslyWithTimeout(): String? =
        withTimeoutOrNull(SIGN_IN_TIMEOUT_MS) {
            runCatching { FirebaseAuth.getInstance().signInAnonymously().await().user?.uid }
                .onFailure { Log.w(TAG, "Anonymous sign-in failed", it) }
                .getOrNull()
        }

    /** Registers a default-network callback that re-attempts the bootstrap when a network appears. */
    private fun armConnectivityRetry(context: Context) {
        if (connectivityCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                retryScope.launch {
                    runCatching { ensureRegistered(context, lastPhone, lastUsername) }
                        .onFailure { Log.w(TAG, "Connectivity-triggered bootstrap retry failed", it) }
                }
            }
        }
        connectivityCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure {
                Log.w(TAG, "Failed to register connectivity retry", it)
                connectivityCallback = null
            }
    }

    private fun clearConnectivityRetry(context: Context) {
        val callback = connectivityCallback ?: return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(callback) }
        connectivityCallback = null
    }
}
