package com.auralis.crisisconnect.messaging.call.sfu

import org.webrtc.FrameDecryptor
import org.webrtc.FrameEncryptor

/**
 * JNI bridge to the native MLS FrameEncryptor/FrameDecryptor (libmls_frame_crypto.so).
 *
 * org.webrtc only exposes per-frame crypto as a native pointer — `RtpSender.setFrameEncryptor(long)` /
 * `RtpReceiver.setFrameDecryptor(long)` — with no Java per-frame hook (unlike the browser's
 * RTCRtpScriptTransform the web uses). The native side implements `webrtc::FrameEncryptorInterface` /
 * `FrameDecryptorInterface` and routes every frame through the shared MLS group (Rust
 * liborange_mls_worker.so, driven by [MlsWorker]/[MlsSession]), so authority-call audio interops with
 * the web dashboard's MLS-E2EE. Until the group handshake completes the native path emits empty frames
 * (silence), matching the web — so it is always safe to attach, no readiness gate needed.
 */
object MlsFrameCrypto {
    /** True once libmls_frame_crypto.so loads. Pair with [MlsWorker.available] before enabling E2EE. */
    val available: Boolean = runCatching { System.loadLibrary("mls_frame_crypto") }.isSuccess

    private external fun nativeCreateEncryptor(): Long
    private external fun nativeCreateDecryptor(): Long

    /** A fresh native FrameEncryptor to attach to an outgoing RtpSender, or null if unavailable. */
    fun newEncryptor(): FrameEncryptor? =
        if (available) NativeFrameEncryptor(nativeCreateEncryptor()) else null

    /** A fresh native FrameDecryptor to attach to an inbound RtpReceiver, or null if unavailable. */
    fun newDecryptor(): FrameDecryptor? =
        if (available) NativeFrameDecryptor(nativeCreateDecryptor()) else null
}

/**
 * Thin holder of the native `webrtc::FrameEncryptorInterface*`. The C++ object is ref-counted and
 * owned by the RtpSender once attached (it AddRefs on setFrameEncryptor, deletes on drop); this Kotlin
 * object only carries the pointer and has no finalizer, so GC never frees the native object early.
 */
private class NativeFrameEncryptor(private val ptr: Long) : FrameEncryptor {
    override fun getNativeFrameEncryptor(): Long = ptr
}

private class NativeFrameDecryptor(private val ptr: Long) : FrameDecryptor {
    override fun getNativeFrameDecryptor(): Long = ptr
}
