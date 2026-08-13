package com.auralis.crisisconnect.messaging.call.sfu

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin bridge to the native MLS worker (`liborange_mls_worker.so`, built from `rust-mls-android` which
 * reuses the web's `mls_ops.rs`). Loads the lib and exposes the JNI surface; [MlsSession] drives one
 * call's group handshake, relaying opaque MLS messages over Firestore (`SfuRoomClient`) with
 * [MlsHandshakeCodec] so it interops with the web.
 *
 * [available] is false when the mandatory `.so` cannot load. The live-call state is a native global
 * mutex so the C++ frame cryptor and this coordinator see the same ratchet; durable Messages sessions
 * use separately keyed native contexts and can never overwrite that call state.
 */
object MlsWorker {
    private const val TAG = "MlsWorker"

    val available: Boolean = runCatching { System.loadLibrary("orange_mls_worker") }
        .onFailure { Log.i(TAG, "MLS native lib not present — E2EE disabled (Faz C pending)") }
        .isSuccess

    // JNI surface — mirrors android_ffi.rs. Each handshake op returns JSON {broadcast:[wireMsg…], safetyNumber?}.
    external fun nativeNewState(uid: String): String
    external fun nativeNewStateAndCreateGroup(uid: String): String
    external fun nativeAddUser(keyPkg: ByteArray): String
    external fun nativeRemoveUser(uid: String): String
    external fun nativeJoinGroup(welcome: ByteArray, rtree: ByteArray): String
    external fun nativeHandleCommit(msg: ByteArray, senderId: String): String
    // Per-frame crypto — called by the native C++ FrameEncryptor bridge, not from Kotlin (documented).
    external fun nativeEncryptFrame(frame: ByteArray): ByteArray
    external fun nativeDecryptFrame(frame: ByteArray): ByteArray
    /** Secret ratchet snapshot. Wrap with Android Keystore before writing it to disk. */
    external fun nativeExportState(): ByteArray
    external fun nativeImportState(snapshot: ByteArray): Boolean
    external fun nativeEncryptApplication(plaintext: ByteArray): ByteArray?
    external fun nativeDecryptApplication(ciphertext: ByteArray): ByteArray?

    // Durable AuthorityChat contexts — isolated by stateContext from calls and from other threads.
    external fun nativePersistentNewState(stateContext: String, credential: String): String
    external fun nativePersistentNewStateAndCreateGroup(stateContext: String, credential: String): String
    external fun nativePersistentAddUser(
        stateContext: String,
        keyPkg: ByteArray,
        expectedCredential: String,
        expectedSigningKey: ByteArray,
    ): String
    external fun nativePersistentIdentity(stateContext: String): String
    external fun nativePersistentRoster(stateContext: String): String
    external fun nativePersistentSafetyNumber(stateContext: String): ByteArray?
    external fun nativePersistentRemoveUser(stateContext: String, credential: String): String
    external fun nativePersistentJoinGroup(stateContext: String, welcome: ByteArray, rtree: ByteArray): String
    external fun nativePersistentHandleCommit(stateContext: String, msg: ByteArray, senderCredential: String): String
    external fun nativePersistentExportState(stateContext: String): ByteArray?
    external fun nativePersistentImportState(stateContext: String, snapshot: ByteArray): Boolean
    external fun nativePersistentEncryptApplication(stateContext: String, plaintext: ByteArray): ByteArray?
    external fun nativePersistentDecryptApplication(stateContext: String, ciphertext: ByteArray): ByteArray?
    external fun nativePersistentClose(stateContext: String): Boolean
}

/**
 * Drives the MLS group for ONE SFU call. The room's first member ([isCreator], claimed race-free on the
 * room doc) creates the group; everyone else shares a KeyPackage, gets a Welcome, and joins. All native
 * calls run on a single pinned thread. Handshake bytes ride Firestore via the codec.
 */
class MlsSession(
    private val myUid: String,
    private val room: SfuRoomClient,
    private val onSafetyNumber: (String) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
) {
    private companion object {
        private const val TAG = "MlsSession"
    }

    private val thread = HandlerThread("mls-$myUid").apply { start() }
    private val handler = Handler(thread.looper)
    private var mlsReg: ListenerRegistration? = null
    private val failedOrStopped = AtomicBoolean(false)

    /** Start the group: create it (first member) or announce our KeyPackage to join, then listen. */
    fun start(isCreator: Boolean) {
        if (!MlsWorker.available) {
            fail(IllegalStateException("Mandatory MLS worker is unavailable."))
            return
        }
        Log.i(TAG, "start creator=$isCreator uid=$myUid")
        // Attach the listener BEFORE initializing (mirrors web use-sfu-room ordering): our own initialize
        // may publish (keyPackage), and the peer's reply must never race past an unattached listener.
        mlsReg = room.listenMlsMessages(
            onMessage = { payload, fromUid -> handler.post { onIncoming(payload, fromUid) } },
            onError = ::fail,
        )
        handler.post {
            val json = runCatching {
                if (isCreator) MlsWorker.nativeNewStateAndCreateGroup(myUid) else MlsWorker.nativeNewState(myUid)
            }.onFailure(::fail).getOrNull()
            json?.let(::handleResponse)
        }
    }

    private fun onIncoming(payload: String, fromUid: String) {
        val claimedUid = runCatching { JSONObject(payload).optString("senderId") }.getOrNull()
        if (claimedUid != fromUid) {
            fail(SecurityException("MLS sender identity does not match authenticated relay writer."))
            return
        }
        val msg = MlsHandshakeCodec.decode(payload)
        if (msg == null) {
            fail(IllegalArgumentException("Incoming MLS relay payload is not decodable."))
            return
        }
        Log.i(TAG, "incoming ${msg.javaClass.simpleName}")
        val json = runCatching {
            when (msg) {
                is MlsHandshakeCodec.Incoming.ShareKeyPackage -> MlsWorker.nativeAddUser(msg.keyPkg)
                is MlsHandshakeCodec.Incoming.SendMlsWelcome -> MlsWorker.nativeJoinGroup(msg.welcome, msg.rtree)
                is MlsHandshakeCodec.Incoming.SendMlsMessage -> MlsWorker.nativeHandleCommit(msg.msg, msg.senderId)
            }
        }.onFailure(::fail).getOrNull()
        json?.let(::handleResponse)
    }

    /** Publish each broadcast-able message the worker emitted; surface a new safety number locally. */
    private fun handleResponse(json: String) {
        val obj = runCatching { JSONObject(json) }.onFailure(::fail).getOrNull() ?: return
        obj.optJSONArray("broadcast")?.let { arr ->
            for (i in 0 until arr.length()) {
                val message = arr.optJSONObject(i)
                if (message == null) {
                    fail(IllegalArgumentException("MLS worker returned a malformed broadcast item."))
                    return
                }
                message.let {
                it.put("senderId", myUid)
                Log.i(TAG, "broadcast ${it.optString("type")}")
                room.publishMlsMessage(it.toString(), ::fail)
                }
            }
        }
        obj.optJSONArray("safetyNumber")?.let { arr ->
            val sn = buildString { for (i in 0 until arr.length()) append(arr.optInt(i).toString().padStart(3, '0')) }
            Log.i(TAG, "safety number ready (${sn.take(8)}…) — group established")
            onSafetyNumber(sn)
        }
    }

    fun stop() {
        failedOrStopped.set(true)
        mlsReg?.remove()
        mlsReg = null
        thread.quitSafely()
    }

    private fun fail(error: Throwable) {
        if (!failedOrStopped.compareAndSet(false, true)) return
        Log.e(TAG, "mandatory MLS coordination failed", error)
        onFailure(error)
    }
}
