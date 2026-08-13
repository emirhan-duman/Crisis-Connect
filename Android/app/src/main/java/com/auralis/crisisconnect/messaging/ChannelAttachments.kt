package com.auralis.crisisconnect.messaging

import android.content.Context
import android.util.Base64
import com.auralis.crisisconnect.security.Crypto
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A ready-to-send attachment: an already-prepared blob (compressed image / recorded voice / raw file). */
data class PendingChannelAttachment(
    val bytes: ByteArray,
    val name: String,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    /** Playback length in seconds, for voice notes (audio types). */
    val durationSec: Int? = null,
)

/** A decoded attachment descriptor read off a message doc; its blob is still encrypted in Storage. */
data class ChannelAttachment(
    val path: String,
    val nonce: String,
    /** Per-file AES key. Null only for legacy blobs sealed with the channel key. */
    val key: String? = null,
    val name: String,
    val mime: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationSec: Int? = null,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isAudio: Boolean get() = mime.startsWith("audio/")

    companion object {
        /** Serializes descriptors to a plain (unencrypted) JSON array for the local Room cache. */
        fun toJsonArray(list: List<ChannelAttachment>): String {
            if (list.isEmpty()) return "[]"
            val arr = JSONArray()
            for (a in list) {
                val d = JSONObject()
                    .put("path", a.path)
                    .put("nonce", a.nonce)
                    .put("name", a.name)
                    .put("mime", a.mime)
                    .put("size", a.size)
                a.key?.let { d.put("key", it) }
                a.width?.let { d.put("width", it) }
                a.height?.let { d.put("height", it) }
                a.durationSec?.let { d.put("duration", it) }
                arr.put(d)
            }
            return arr.toString()
        }

        /** Inverse of [toJsonArray]; tolerant of malformed/empty input. */
        fun fromJsonArray(json: String?): List<ChannelAttachment> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val d = arr.optJSONObject(i) ?: continue
                        val path = d.optString("path")
                        val nonce = d.optString("nonce")
                        if (path.isBlank() || nonce.isBlank()) continue
                        add(
                            ChannelAttachment(
                                path = path,
                                nonce = nonce,
                                key = d.optString("key").takeIf(String::isNotBlank),
                                name = d.optString("name", "file"),
                                mime = d.optString("mime", "application/octet-stream"),
                                size = d.optLong("size"),
                                width = if (d.has("width")) d.optInt("width") else null,
                                height = if (d.has("height")) d.optInt("height") else null,
                                durationSec = if (d.has("duration")) d.optInt("duration") else null,
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * Byte-compatible port of the web dashboard's `lib/messaging/attachments.ts`. Each new blob is
 * AES-256-GCM sealed with its own random key (AAD = its Storage path) and uploaded as ciphertext at
 * `messageAttachments/{uid}/{uuid}`; a small descriptor array
 * `{path,nonce,name,mime,size,width?,height?,duration?}` is itself encrypted and stored inside the
 * message doc as `attMeta`/`attMetaNonce`, so filenames and storage paths never leak. A channel member
 * decrypts the descriptor, downloads the blob and decrypts it locally — matching what the web renders.
 */
object ChannelAttachments {
    const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
    const val AUTHORITY_MLS_BLOB_MIME = "application/x-crisisconnect-authority-mls-attachment"
    const val AUTHORITY_MLS_ENVELOPE_MIME = "application/x-crisisconnect-authority-mls-envelope"
    private val CACHE_MAGIC = byteArrayOf(0x43, 0x43, 0x41, 0x54, 0x54, 0x02)
    private val AUTHORITY_CONVERSATION_ID = Regex("^am2_[A-Za-z0-9_-]{43}$")
    private val AUTHORITY_ATTACHMENT_PATH = Regex(
        "^authorityMessageAttachments/am2_[A-Za-z0-9_-]{43}/[^/]{1,256}/" +
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$"
    )

    private fun aad(aadString: String) = aadString.toByteArray(StandardCharsets.UTF_8)

    private fun encryptBytes(key: ByteArray, aadString: String, bytes: ByteArray): Pair<String, ByteArray> {
        val nonce = Crypto.randomBytes(12)
        val cipher = Crypto.aesGcmEncrypt(key = key, nonce = nonce, plaintext = bytes, associatedData = aad(aadString))
        return Base64.encodeToString(nonce, Base64.NO_WRAP) to cipher
    }

    private fun decryptBytes(key: ByteArray, aadString: String, nonceBase64: String, cipher: ByteArray): ByteArray =
        Crypto.aesGcmDecrypt(
            key = key,
            nonce = Base64.decode(nonceBase64, Base64.NO_WRAP),
            ciphertextAndTag = cipher,
            associatedData = aad(aadString),
        )

    /**
     * Prepares MLS-v2 file descriptors. Each blob has an independent AES-256-GCM key carried only
     * inside MLS plaintext, while the Storage path binds access to the exact conversation.
     */
    suspend fun prepareAuthorityMlsAttachments(
        context: Context,
        conversationId: String,
        pendings: List<PendingChannelAttachment>,
    ): List<ChannelAttachment> = withContext(Dispatchers.IO) {
        require(AUTHORITY_CONVERSATION_ID.matches(conversationId)) { "Invalid Authority MLS conversation." }
        require(pendings.size <= 8) { "Too many Authority MLS attachments." }
        if (pendings.isEmpty()) return@withContext emptyList()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?.takeIf { it.length in 1..256 && '/' !in it }
            ?: throw IllegalStateException("Not signed in")
        pendings.map { pending ->
                require(pending.bytes.isNotEmpty() && pending.bytes.size.toLong() <= MAX_ATTACHMENT_BYTES) {
                    "Authority MLS attachment size is invalid."
                }
                require(pending.name.toByteArray(StandardCharsets.UTF_8).size in 1..255 &&
                    pending.mime.toByteArray(StandardCharsets.UTF_8).size in 1..255
                ) { "Authority MLS attachment metadata is invalid." }
                val path = "authorityMessageAttachments/$conversationId/$uid/${UUID.randomUUID()}"
                val fileKey = Crypto.randomBytes(32)
                val (nonce, cipher) = encryptBytes(fileKey, path, pending.bytes)
                check(cacheAuthorityMlsCiphertext(context, path, cipher)) {
                    "Authority MLS attachment ciphertext could not be cached."
                }
                ChannelAttachment(
                    path = path,
                    nonce = nonce,
                    key = Base64.encodeToString(fileKey, Base64.NO_WRAP),
                    name = pending.name,
                    mime = pending.mime,
                    size = pending.bytes.size.toLong(),
                    width = pending.width,
                    height = pending.height,
                    durationSec = pending.durationSec,
                )
            }
    }

    /** Uploads the exact locally cached ciphertext; safe to retry after an offline BLE send. */
    suspend fun ensureAuthorityMlsAttachmentsUploaded(
        context: Context,
        attachments: List<ChannelAttachment>,
    ) = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?.takeIf { it.length in 1..256 && '/' !in it }
            ?: throw IllegalStateException("Not signed in")
        attachments.forEach { attachment ->
            require(AUTHORITY_ATTACHMENT_PATH.matches(attachment.path) &&
                attachment.path.split('/').getOrNull(2) == uid && !attachment.key.isNullOrBlank()) {
                "Authority MLS attachment upload binding is invalid."
            }
            val cipher = readCachedAuthorityMlsCiphertext(context, attachment.path)
                ?: throw IllegalStateException("Authority MLS attachment ciphertext is unavailable.")
            val reference = FirebaseStorage.getInstance().reference.child(attachment.path)
            val digest = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(cipher),
                Base64.NO_WRAP,
            )
            suspend fun uploadedObjectMatches(): Boolean {
                val metadata = runCatching { reference.metadata.await() }.getOrNull() ?: return false
                if (metadata.sizeBytes != cipher.size.toLong()) return false
                val storedDigest = metadata.getCustomMetadata("cc-sha256")
                if (storedDigest != null) return storedDigest == digest
                return runCatching {
                    reference.getBytes(MAX_ATTACHMENT_BYTES + 4096).await().contentEquals(cipher)
                }.getOrDefault(false)
            }
            val metadata = StorageMetadata.Builder()
                .setContentType("application/octet-stream")
                .setCustomMetadata("cc-sha256", digest)
                .build()
            runCatching { reference.putBytes(cipher, metadata).await() }
                .getOrElse { uploadError ->
                    // Storage objects are immutable. A prior successful upload followed by an app
                    // crash is therefore success only when the exact ciphertext already exists.
                    if (!uploadedObjectMatches()) throw uploadError
                }
        }
    }

    /** Stores only opaque AES-GCM ciphertext in the app-private cache. */
    fun cacheAuthorityMlsCiphertext(context: Context, path: String, cipher: ByteArray): Boolean {
        if (!AUTHORITY_ATTACHMENT_PATH.matches(path) || cipher.size !in 17..(MAX_ATTACHMENT_BYTES.toInt() + 16)) {
            return false
        }
        val cacheFile = mediaCacheFile(context, path)
        return runCatching {
            val tmp = File.createTempFile("media", ".tmp", cacheFile.parentFile)
            tmp.writeBytes(CACHE_MAGIC + cipher)
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun readCachedAuthorityMlsCiphertext(context: Context, path: String): ByteArray? {
        if (!AUTHORITY_ATTACHMENT_PATH.matches(path)) return null
        val encoded = runCatching { mediaCacheFile(context, path).readBytes() }.getOrNull() ?: return null
        if (encoded.size <= CACHE_MAGIC.size ||
            !encoded.copyOfRange(0, CACHE_MAGIC.size).contentEquals(CACHE_MAGIC)) return null
        return encoded.copyOfRange(CACHE_MAGIC.size, encoded.size)
            .takeIf { it.size in 17..(MAX_ATTACHMENT_BYTES.toInt() + 16) }
    }

    /** Best-effort rollback for blobs uploaded before MLS staging fails. */
    suspend fun deleteAuthorityMlsAttachments(attachments: List<ChannelAttachment>) = withContext(Dispatchers.IO) {
        attachments
            .asSequence()
            .filter { AUTHORITY_ATTACHMENT_PATH.matches(it.path) }
            .forEach { attachment ->
                runCatching { FirebaseStorage.getInstance().reference.child(attachment.path).delete().await() }
            }
    }

    /**
     * Encrypts + uploads every pending blob, then returns the encrypted descriptor fields to merge into
     * the message doc (`attMeta` + `attMetaNonce`), or null when there are no attachments.
     */
    suspend fun buildAttachmentFields(
        @Suppress("UNUSED_PARAMETER") key: ByteArray,
        @Suppress("UNUSED_PARAMETER") aadString: String,
        @Suppress("UNUSED_PARAMETER") pendings: List<PendingChannelAttachment>,
    ): Map<String, Any>? {
        throw SecurityException("Legacy shared-key attachment uploads are permanently disabled.")
    }

    /** Reads + decrypts the descriptor array off a raw message doc. Empty when there are none/undecodable. */
    fun decodeAttachments(
        key: ByteArray,
        aadString: String,
        attMeta: String?,
        attMetaNonce: String?,
    ): List<ChannelAttachment> {
        if (attMeta.isNullOrBlank() || attMetaNonce.isNullOrBlank()) return emptyList()
        return runCatching {
            val bytes = decryptBytes(key, aadString, attMetaNonce, Base64.decode(attMeta, Base64.NO_WRAP))
            val arr = JSONArray(String(bytes, StandardCharsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    val path = d.optString("path")
                    val nonce = d.optString("nonce")
                    if (path.isBlank() || nonce.isBlank()) continue
                    add(
                        ChannelAttachment(
                                path = path,
                                nonce = nonce,
                                key = d.optString("key").takeIf(String::isNotBlank),
                            name = d.optString("name", "file"),
                            mime = d.optString("mime", "application/octet-stream"),
                            size = d.optLong("size"),
                            width = if (d.has("width")) d.optInt("width") else null,
                            height = if (d.has("height")) d.optInt("height") else null,
                            durationSec = if (d.has("duration")) d.optInt("duration") else null,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Returns the decrypted blob for a descriptor. The offline cache stores only the original
     * ciphertext. Pre-v2 cache entries contained plaintext; they are deleted without being returned.
     */
    suspend fun fetchAttachmentBytes(
        context: Context,
        key: ByteArray?,
        aadString: String?,
        att: ChannelAttachment,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!AUTHORITY_ATTACHMENT_PATH.matches(att.path) || att.key.isNullOrBlank()) return@withContext null
        val cacheFile = mediaCacheFile(context, att.path)
        cacheFile.takeIf { it.isFile && it.length() > 0 }?.let { cached ->
            val encoded = runCatching { cached.readBytes() }.getOrNull()
            if (encoded != null && encoded.size > CACHE_MAGIC.size &&
                encoded.copyOfRange(0, CACHE_MAGIC.size).contentEquals(CACHE_MAGIC)
            ) {
                decryptAttachment(key, aadString, att, encoded.copyOfRange(CACHE_MAGIC.size, encoded.size))
                    ?.let { return@withContext it }
            } else {
                // Legacy plaintext cache: never surface it and remove the insecure at-rest copy.
                runCatching { cached.delete() }
            }
        }
        val cipher = FirebaseStorage.getInstance().reference
            .child(att.path)
            .getBytes(MAX_ATTACHMENT_BYTES + 4096)
            .await()
        val plain = decryptAttachment(key, aadString, att, cipher) ?: return@withContext null
        if (plain.size.toLong() != att.size) return@withContext null
        // Cache ciphertext atomically (temp + rename), never plaintext.
        cacheAuthorityMlsCiphertext(context, att.path, cipher)
        plain
    }

    private fun decryptAttachment(
        legacyChannelKey: ByteArray?,
        legacyAad: String?,
        att: ChannelAttachment,
        cipher: ByteArray,
    ): ByteArray? = runCatching {
        if (!att.key.isNullOrBlank()) {
            val fileKey = Base64.decode(att.key, Base64.NO_WRAP)
            require(fileKey.size == 32) { "Attachment key has an invalid size." }
            decryptBytes(fileKey, att.path, att.nonce, cipher)
        } else {
            val channelKey = legacyChannelKey ?: return null
            val aad = legacyAad ?: return null
            decryptBytes(channelKey, aad, att.nonce, cipher)
        }
    }.getOrNull()

    /** app-private cache path for a blob, named by SHA-256 of its Storage path (which has slashes). */
    private fun mediaCacheFile(context: Context, path: String): File {
        val dir = File(context.filesDir, "authority_media").apply { if (!exists()) mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, hash)
    }
}
