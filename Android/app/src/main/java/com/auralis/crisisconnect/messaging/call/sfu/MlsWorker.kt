package com.auralis.crisisconnect.messaging.call.sfu

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject

/**
 * Kotlin bridge to the native MLS worker (`liborange_mls_worker.so`, built from `rust-mls-android` which
 * reuses the web's `mls_ops.rs`). Loads the lib and exposes the JNI surface; [MlsSession] drives one
 * call's group handshake, relaying opaque MLS messages over Firestore (`SfuRoomClient`) with
 * [MlsHandshakeCodec] so it interops with the web.
 *
 * ⚠️ [available] is false until the `.so` is built + placed in jniLibs — so this stays inert (no crash)
 * while Faz C's native build is pending. THREADING: the native `mls_ops::STATE` is thread_local; ALL
 * native calls (handshakes here + per-frame crypto in the C++ FrameEncryptor) must hit ONE thread OR
 * `STATE` must become a global `Mutex` (see rust-mls-android build notes) — the per-frame path is the
 * open native question.
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
}

/**
 * Drives the MLS group for ONE SFU call. The room's first member ([isCreator], claimed race-free on the
 * room doc) creates the group; everyone else shares a KeyPackage, gets a Welcome, and joins. All native
 * calls run on a single pinned thread (thread_local state). Handshake bytes ride Firestore via the codec.
 */
class MlsSession(
    private val myUid: String,
    private val room: SfuRoomClient,
    private val onSafetyNumber: (String) -> Unit = {},
) {
    private companion object {
        private const val TAG = "MlsSession"
    }

    private val thread = HandlerThread("mls-$myUid").apply { start() }
    private val handler = Handler(thread.looper)
    private var mlsReg: ListenerRegistration? = null

    /** Start the group: create it (first member) or announce our KeyPackage to join, then listen. */
    fun start(isCreator: Boolean) {
        if (!MlsWorker.available) return
        Log.i(TAG, "start creator=$isCreator uid=$myUid")
        // Attach the listener BEFORE initializing (mirrors web use-sfu-room ordering): our own initialize
        // may publish (keyPackage), and the peer's reply must never race past an unattached listener.
        mlsReg = room.listenMlsMessages { payload -> handler.post { onIncoming(payload) } }
        handler.post {
            val json = runCatching {
                if (isCreator) MlsWorker.nativeNewStateAndCreateGroup(myUid) else MlsWorker.nativeNewState(myUid)
            }.onFailure { Log.w(TAG, "native init failed", it) }.getOrNull()
            json?.let(::handleResponse)
        }
    }

    private fun onIncoming(payload: String) {
        val msg = MlsHandshakeCodec.decode(payload)
        if (msg == null) {
            Log.w(TAG, "incoming MLS payload not decodable (${payload.take(120)})")
            return
        }
        Log.i(TAG, "incoming ${msg.javaClass.simpleName}")
        val json = runCatching {
            when (msg) {
                is MlsHandshakeCodec.Incoming.ShareKeyPackage -> MlsWorker.nativeAddUser(msg.keyPkg)
                is MlsHandshakeCodec.Incoming.SendMlsWelcome -> MlsWorker.nativeJoinGroup(msg.welcome, msg.rtree)
                is MlsHandshakeCodec.Incoming.SendMlsMessage -> MlsWorker.nativeHandleCommit(msg.msg, msg.senderId)
            }
        }.onFailure { Log.w(TAG, "native handshake op failed", it) }.getOrNull()
        json?.let(::handleResponse)
    }

    /** Publish each broadcast-able message the worker emitted; surface a new safety number locally. */
    private fun handleResponse(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        obj.optJSONArray("broadcast")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let {
                Log.i(TAG, "broadcast ${it.optString("type")}")
                room.publishMlsMessage(it.toString())
            }
        }
        obj.optJSONArray("safetyNumber")?.let { arr ->
            val sn = buildString { for (i in 0 until arr.length()) append(arr.optInt(i).toString().padStart(3, '0')) }
            Log.i(TAG, "safety number ready (${sn.take(8)}…) — group established")
            onSafetyNumber(sn)
        }
    }

    fun stop() {
        mlsReg?.remove()
        mlsReg = null
        thread.quitSafely()
    }
}
