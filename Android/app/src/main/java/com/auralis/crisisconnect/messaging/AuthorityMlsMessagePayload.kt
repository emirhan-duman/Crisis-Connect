package com.auralis.crisisconnect.messaging

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class AuthorityMlsMessagePayload(
    val recipientUid: String,
    val recipientName: String,
    val senderName: String,
    val text: String,
    val sentAtMillis: Long,
    val attachments: List<ChannelAttachment> = emptyList(),
    val replyToId: String? = null,
)

/** Strict, cross-platform AuthorityChat application envelope carried only as MLS plaintext. */
object AuthorityMlsMessagePayloadCodec {
    private const val MAX_PAYLOAD_BYTES = 900_000
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_ATTACHMENTS = 8
    private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val PATH = Regex(
        "^authorityMessageAttachments/am2_[A-Za-z0-9_-]{43}/[^/]{1,256}/" +
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$"
    )

    fun encode(payload: AuthorityMlsMessagePayload): ByteArray {
        validate(payload)
        val root = JSONObject()
            .put("version", 2)
            .put("kind", "message")
            .put("recipientUid", payload.recipientUid)
            .put("recipientName", payload.recipientName)
            .put("senderName", payload.senderName)
            .put("text", payload.text)
            .put("sentAtMillis", payload.sentAtMillis)
        val descriptors = JSONArray()
        payload.attachments.forEach { attachment ->
            val descriptor = JSONObject()
                .put("path", attachment.path)
                .put("nonce", attachment.nonce)
                .put("key", attachment.key)
                .put("name", attachment.name)
                .put("mime", attachment.mime)
                .put("size", attachment.size)
            attachment.width?.let { descriptor.put("width", it) }
            attachment.height?.let { descriptor.put("height", it) }
            attachment.durationSec?.let { descriptor.put("duration", it) }
            descriptors.put(descriptor)
        }
        root.put("attachments", descriptors)
        payload.replyToId?.let { root.put("replyToId", it) }
        return root.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size in 1..MAX_PAYLOAD_BYTES) { "Authority MLS message payload is too large." }
        }
    }

    fun decode(encoded: ByteArray): AuthorityMlsMessagePayload {
        require(encoded.size in 1..MAX_PAYLOAD_BYTES) { "Authority MLS message payload size is invalid." }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded)).toString()
        val root = JSONObject(text)
        require(root.optInt("version", -1) == 2 && root.optString("kind") == "message") {
            "Authority MLS message payload version is invalid."
        }
        val attachments = root.optJSONArray("attachments") ?: throw SecurityException("Attachment list is missing.")
        require(attachments.length() <= MAX_ATTACHMENTS) { "Too many Authority MLS attachments." }
        val decodedAttachments = buildList {
            for (index in 0 until attachments.length()) add(decodeAttachment(attachments.getJSONObject(index)))
        }
        return AuthorityMlsMessagePayload(
            recipientUid = bounded(strictString(root, "recipientUid"), 256, allowEmpty = false),
            recipientName = bounded(strictString(root, "recipientName"), 256, allowEmpty = true),
            senderName = bounded(strictString(root, "senderName"), 256, allowEmpty = true),
            text = bounded(strictString(root, "text"), MAX_TEXT_BYTES, allowEmpty = true),
            sentAtMillis = strictLong(root, "sentAtMillis"),
            attachments = decodedAttachments,
            replyToId = if (root.has("replyToId")) strictString(root, "replyToId") else null,
        ).also(::validate)
    }

    private fun validate(payload: AuthorityMlsMessagePayload) {
        bounded(payload.recipientUid, 256, allowEmpty = false)
        bounded(payload.recipientName, 256, allowEmpty = true)
        bounded(payload.senderName, 256, allowEmpty = true)
        bounded(payload.text, MAX_TEXT_BYTES, allowEmpty = true)
        require(payload.sentAtMillis > 0 && payload.attachments.size <= MAX_ATTACHMENTS) {
            "Authority MLS message timestamp or attachment count is invalid."
        }
        require(payload.text.isNotEmpty() || payload.attachments.isNotEmpty()) { "Authority MLS message is empty." }
        payload.attachments.forEach(::validateAttachment)
        require(payload.replyToId == null || ID.matches(payload.replyToId)) { "Authority MLS reply ID is invalid." }
    }

    private fun decodeAttachment(raw: JSONObject): ChannelAttachment = ChannelAttachment(
        path = strictString(raw, "path"),
        nonce = strictString(raw, "nonce"),
        key = strictString(raw, "key"),
        name = strictString(raw, "name"),
        mime = strictString(raw, "mime"),
        size = strictLong(raw, "size"),
        width = if (raw.has("width")) strictInt(raw, "width") else null,
        height = if (raw.has("height")) strictInt(raw, "height") else null,
        durationSec = if (raw.has("duration")) strictInt(raw, "duration") else null,
    ).also(::validateAttachment)

    private fun validateAttachment(attachment: ChannelAttachment) {
        val key = attachment.key ?: throw SecurityException("Authority MLS attachment key is missing.")
        require(PATH.matches(attachment.path) && decodeCanonicalBase64(attachment.nonce).size == 12 &&
            decodeCanonicalBase64(key).size == 32 && attachment.name.toByteArray().size in 1..255 &&
            attachment.mime.toByteArray().size in 1..255 && attachment.size in 0..ChannelAttachments.MAX_ATTACHMENT_BYTES) {
            "Authority MLS attachment descriptor is invalid."
        }
    }

    private fun bounded(value: String, maxBytes: Int, allowEmpty: Boolean): String {
        val size = value.toByteArray(StandardCharsets.UTF_8).size
        require((allowEmpty || value.isNotEmpty()) && size <= maxBytes &&
            value.none { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it.code == 127 }) {
            "Authority MLS message field is invalid."
        }
        return value
    }

    private fun decodeCanonicalBase64(value: String): ByteArray {
        val decoded = Base64.getDecoder().decode(value)
        require(Base64.getEncoder().encodeToString(decoded) == value) { "Non-canonical base64." }
        return decoded
    }

    private fun strictString(root: JSONObject, name: String): String =
        (root.get(name) as? String) ?: throw SecurityException("Authority MLS $name must be a string.")

    private fun strictLong(root: JSONObject, name: String): Long {
        val number = root.get(name) as? Number ?: throw SecurityException("Authority MLS $name must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Authority MLS $name must be an integer."
        }
        return value
    }

    private fun strictInt(root: JSONObject, name: String): Int {
        val value = strictLong(root, name)
        require(value in 0..1_000_000) { "Authority MLS $name is out of range." }
        return value.toInt()
    }
}
