package com.auralis.crisisconnect.service.gattmesh

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * One framed TCP link carrying many blob frames between two authority devices over a Wi-Fi fast lane.
 *
 * Wire frame (one stream carries many): `[u32 MAGIC][u32 initLen][init JSON][u32 cipherLen][cipher]`,
 * where the init JSON is the same IMAGE_INIT packet the BLE lane sends and the cipher is the same
 * group-key AES-GCM blob. Shared by [AuthorityMeshAwareAccelerator] and [WifiDirectAccelerator] so the
 * framing + size guards live in exactly one place.
 */
internal class BlobLink(
    private val socket: Socket,
    private val onFrame: (initPayload: ByteArray, cipher: ByteArray) -> Unit,
) {
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val writeLock = Any()

    /** Frames and sends one blob. Returns false (and closes the link) on any I/O failure. */
    fun writeFrame(initPayload: ByteArray, cipher: ByteArray): Boolean {
        return runCatching {
            synchronized(writeLock) {
                output.writeInt(FRAME_MAGIC)
                output.writeInt(initPayload.size)
                output.write(initPayload)
                output.writeInt(cipher.size)
                output.write(cipher)
                output.flush()
            }
            true
        }.getOrElse {
            close()
            false
        }
    }

    /** Blocks reading frames until the socket closes or a malformed/oversized frame is seen. */
    fun readLoop() {
        runCatching {
            while (true) {
                val magic = input.readInt()
                if (magic != FRAME_MAGIC) {
                    return
                }
                val initLength = input.readInt()
                if (initLength !in 1..MAX_INIT_BYTES) {
                    return
                }
                val initPayload = ByteArray(initLength)
                input.readFully(initPayload)
                val cipherLength = input.readInt()
                if (cipherLength !in 1..MAX_CIPHER_BYTES) {
                    return
                }
                val cipher = ByteArray(cipherLength)
                input.readFully(cipher)
                onFrame(initPayload, cipher)
            }
        }
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val FRAME_MAGIC = 0x43434142 // "CCAB"
        const val MAX_INIT_BYTES = 8_192
        const val MAX_CIPHER_BYTES = 400_064
    }
}
