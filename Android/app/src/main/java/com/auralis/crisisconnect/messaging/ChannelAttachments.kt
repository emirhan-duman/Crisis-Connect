package com.auralis.crisisconnect.messaging

import android.content.Context
import android.util.Base64
import com.auralis.crisisconnect.security.Crypto
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
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
 * Byte-compatible port of the web dashboard's `lib/messaging/attachments.ts`. Each blob is AES-256-GCM
 * sealed with the channel key (AAD = the channel's AAD — `channelId` for hierarchy, `agencySlug` for
 * agency, same as the text seal) and uploaded to Firebase Storage as opaque ciphertext at
 * `messageAttachments/{uid}/{uuid}`; a small descriptor array
 * `{path,nonce,name,mime,size,width?,height?,duration?}` is itself encrypted and stored inside the
 * message doc as `attMeta`/`attMetaNonce`, so filenames and storage paths never leak. A channel member
 * decrypts the descriptor, downloads the blob and decrypts it locally — matching what the web renders.
 */
object ChannelAttachments {
    const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

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
     * Encrypts + uploads every pending blob, then returns the encrypted descriptor fields to merge into
     * the message doc (`attMeta` + `attMetaNonce`), or null when there are no attachments.
     */
    suspend fun buildAttachmentFields(
        key: ByteArray,
        aadString: String,
        pendings: List<PendingChannelAttachment>,
    ): Map<String, Any>? = withContext(Dispatchers.IO) {
        if (pendings.isEmpty()) return@withContext null
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: throw IllegalStateException("Not signed in")
        val storage = FirebaseStorage.getInstance()
        val descriptors = JSONArray()
        for (p in pendings) {
            val (nonce, cipher) = encryptBytes(key, aadString, p.bytes)
            val path = "messageAttachments/$uid/${UUID.randomUUID()}"
            storage.reference.child(path).putBytes(cipher).await()
            val d = JSONObject()
                .put("path", path)
                .put("nonce", nonce)
                .put("name", p.name)
                .put("mime", p.mime)
                .put("size", p.bytes.size)
            p.width?.let { d.put("width", it) }
            p.height?.let { d.put("height", it) }
            p.durationSec?.let { d.put("duration", it) }
            descriptors.put(d)
        }
        val (metaNonce, metaCipher) = encryptBytes(
            key,
            aadString,
            descriptors.toString().toByteArray(StandardCharsets.UTF_8),
        )
        mapOf(
            "attMeta" to Base64.encodeToString(metaCipher, Base64.NO_WRAP),
            "attMetaNonce" to metaNonce,
        )
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
     * Returns the decrypted blob for a descriptor. Offline-first: once fetched, the plaintext is cached
     * in app-private storage keyed by [ChannelAttachment.path], so later views (including offline ones)
     * read from disk without hitting Storage or needing the channel key again — a disaster app should
     * keep its media on the device. App-private files are sandboxed from other apps (WhatsApp-style at-
     * rest handling); the SQLCipher DB still holds the message metadata.
     */
    suspend fun fetchAttachmentBytes(
        context: Context,
        key: ByteArray?,
        aadString: String?,
        att: ChannelAttachment,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val cacheFile = mediaCacheFile(context, att.path)
        cacheFile.takeIf { it.isFile && it.length() > 0 }?.let { cached ->
            runCatching { cached.readBytes() }.getOrNull()?.let { return@withContext it }
        }
        // Not cached yet: need the channel key to download + decrypt. Without it (a cold, still-syncing
        // open, or offline), there's nothing more we can do here.
        if (key == null || aadString == null) return@withContext null
        val cipher = FirebaseStorage.getInstance().reference
            .child(att.path)
            .getBytes(MAX_ATTACHMENT_BYTES + 4096)
            .await()
        val plain = decryptBytes(key, aadString, att.nonce, cipher)
        // Cache atomically (temp + rename) so a crash mid-write never leaves a truncated "complete" file.
        runCatching {
            val tmp = File.createTempFile("media", ".tmp", cacheFile.parentFile)
            tmp.writeBytes(plain)
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
        }
        plain
    }

    /** app-private cache path for a blob, named by SHA-256 of its Storage path (which has slashes). */
    private fun mediaCacheFile(context: Context, path: String): File {
        val dir = File(context.filesDir, "authority_media").apply { if (!exists()) mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, hash)
    }
}
