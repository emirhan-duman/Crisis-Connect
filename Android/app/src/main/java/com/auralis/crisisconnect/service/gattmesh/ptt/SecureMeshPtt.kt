package com.auralis.crisisconnect.service.gattmesh.ptt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** CPT2 wire matches iOS SecureMeshPtt. Only the bounded hop byte is relay-mutable. */
class SecureMeshPtt(
    private val groupKey: () -> ByteArray?,
    private val createProof: (String) -> ByteArray?,
    private val verifyProof: (ByteArray, String) -> VerifiedRole?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class VerifiedRole(val agency: String?, val expiresAt: Long)
    data class Frame(val kind: Int, val origin: String, val label: String, val timestamp: Long,
        val sequence: Long, val hop: Int, val body: ByteArray, val agency: String?)
    private data class Peer(val key: PublicKey, val role: VerifiedRole, var sequence: Long)
    private val signingKey = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private var sequence = 0L
    private var proof: ByteArray? = null
    private var proofAt = 0L
    private val peers = mutableMapOf<String, Peer>()

    @Synchronized
    fun encode(kind: Int, label: String, body: ByteArray): ByteArray? = runCatching {
        val now = clock()
        require(kind in 1..6 && body.size <= 4096 && sequence < 0xffffffffL)
        val key = groupKey()?.takeIf { it.size == 32 } ?: return null
        val pub = signingKey.public.encoded
        if (kind != 5 && (proof == null || now - proofAt >= 30_000L)) {
            proof = createProof(nonce(pub)); proofAt = now
        }
        val proofBytes = proof ?: return null
        require(now >= proofAt && now - proofAt < 60_000L && proofBytes.size <= 3000)
        sequence++
        val name = label.take(16).toByteArray(Charsets.UTF_8)
        require(name.size <= 64)
        val credential = if (kind == 5) byteArrayOf() else ByteArrayOutputStream().apply {
            DataOutputStream(this).apply { writeShort(pub.size); write(pub); write(proofBytes) }
        }.toByteArray()
        val header = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                write(MAGIC); writeByte(0); writeByte(kind); write(origin(pub)); writeLong(now); writeInt(sequence.toInt())
                writeByte(name.size); write(name); writeShort(credential.size); write(credential)
            }
        }.toByteArray()
        val aad = header.copyOfRange(0, 4) + header.copyOfRange(5, header.size)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad)
        }
        val ciphertext = iv + cipher.doFinal(body)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(signingKey.private); update(aad + ciphertext)
        }.sign()
        require(signature.size <= 80)
        header + ciphertext + signature + byteArrayOf(signature.size.toByte())
    }.getOrNull()

    @Synchronized
    fun decode(wire: ByteArray): Frame? = runCatching {
        require(isFrame(wire) && wire.size in 45..4096)
        val key = groupKey()?.takeIf { it.size == 32 } ?: return null
        val stream = ByteArrayInputStream(wire)
        val input = DataInputStream(stream)
        fun read(count: Int): ByteArray {
            require(count >= 0 && count <= stream.available())
            return ByteArray(count).also { input.readFully(it) }
        }
        read(4)
        val hop = input.readUnsignedByte(); val kind = input.readUnsignedByte()
        require(hop <= 4 && kind in 1..6)
        val originBytes = read(16); val ts = input.readLong(); val seq = input.readInt().toLong() and 0xffffffffL
        val nameLength = input.readUnsignedByte(); require(nameLength <= 64 && seq > 0)
        val label = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(read(nameLength))).toString()
        val credentialLength = input.readUnsignedShort(); require(credentialLength <= 3200)
        val credential = read(credentialLength)
        val offset = wire.size - stream.available()
        val now = clock(); require(ts >= now - 10_000L && ts <= now + 2_000L)
        val signatureLength = wire.last().toInt() and 255
        require(signatureLength in 8..80 && offset + 28 + signatureLength + 1 <= wire.size)
        val end = wire.size - signatureLength - 1
        val ciphertext = wire.copyOfRange(offset, end)
        val aad = wire.copyOfRange(0, 4) + wire.copyOfRange(5, offset)
        val originId = originBytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        require(!MessageDigest.isEqual(originBytes, origin(signingKey.public.encoded)))
        peers.entries.removeAll { it.value.role.expiresAt <= now }
        var candidate = peers[originId]
        if (credential.isNotEmpty()) {
            require(credential.size >= 2)
            val pubLength = ((credential[0].toInt() and 255) shl 8) or (credential[1].toInt() and 255)
            require(pubLength in 1..256 && 2 + pubLength < credential.size)
            val pub = credential.copyOfRange(2, 2 + pubLength)
            require(MessageDigest.isEqual(origin(pub), originBytes))
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
            val role = verifyProof(credential.copyOfRange(2 + pubLength, credential.size), nonce(pub)) ?: return null
            require(role.expiresAt > now)
            candidate = Peer(publicKey, role, candidate?.sequence ?: 0L)
        }
        val peer = candidate ?: return null
        require(peer.role.expiresAt > now && seq > peer.sequence)
        val valid = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(peer.key); update(aad + ciphertext)
        }.verify(wire.copyOfRange(end, wire.size - 1))
        require(valid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            updateAAD(aad)
        }
        val body = cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
        require(peers.containsKey(originId) || peers.size < 128)
        peer.sequence = seq; peers[originId] = peer
        Frame(kind, originId, label, ts, seq, hop, body, peer.role.agency)
    }.getOrNull()

    companion object {
        private val MAGIC = "CPT2".toByteArray(Charsets.US_ASCII)
        fun isFrame(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)
        fun relayed(wire: ByteArray, hop: Int): ByteArray? =
            wire.takeIf { isFrame(it) && it.size > 5 && hop in 0..4 }?.copyOf()?.also { it[4] = hop.toByte() }
        private fun nonce(pub: ByteArray): String = "cc-ptt-v2|" + Base64.getEncoder().encodeToString(pub)
        private fun origin(pub: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(pub).copyOf(16)
    }
}
