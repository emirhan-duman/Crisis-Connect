package com.auralis.crisisconnect.core.io

import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Helper utilities for chunking and hashing binary payloads used by the
 * Bluetooth voice streaming micro-protocol.
 */
object Chunker {

    /**
     * Split [bytes] into fixed [size] chunks. The last chunk will be smaller
     * if the data does not divide evenly.
     */
    fun chunk(bytes: ByteArray, size: Int): List<ByteArray> {
        require(size > 0) { "Chunk size must be positive" }
        if (bytes.isEmpty()) {
            return listOf(ByteArray(0))
        }
        val out = ArrayList<ByteArray>((bytes.size + size - 1) / size)
        var index = 0
        while (index < bytes.size) {
            val end = minOf(index + size, bytes.size)
            out.add(bytes.copyOfRange(index, end))
            index = end
        }
        return out
    }

    /**
     * Compute a CRC32 checksum for [bytes].
     */
    fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    /**
     * Compute a SHA-256 digest for [bytes].
     */
    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
