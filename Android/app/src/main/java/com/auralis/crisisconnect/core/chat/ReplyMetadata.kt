package com.auralis.crisisconnect.core.chat

/**
 * Parsed representation of inline reply metadata embedded in a chat message body.
 */
data class ParsedReply(
    val preview: String,
    val body: String,
    val authorLabel: String?,
    val targetUuid: String?,
)

private val MULTI_WHITESPACE_REGEX = Regex("\\s+")

/**
 * Attempts to parse reply metadata that is prefixed to a chat message body.
 */
fun parseReplyMetadata(text: String): ParsedReply? {
    if (!text.startsWith("↪")) return null
    val newlineIndex = text.indexOf('\n')
    val rawHeader = when {
        newlineIndex == -1 -> text.substring(1)
        newlineIndex > 1 -> text.substring(1, newlineIndex)
        else -> return null
    }.removePrefix(" ").trim()
    val (replyUuid, headerContent) = if (rawHeader.startsWith("[")) {
        val closingIndex = rawHeader.indexOf(']')
        if (closingIndex > 1) {
            val uuid = rawHeader.substring(1, closingIndex).trim().takeIf { it.isNotEmpty() }
            val remaining = rawHeader.substring(closingIndex + 1).trimStart()
            uuid to remaining
        } else {
            null to rawHeader
        }
    } else {
        null to rawHeader
    }

    val separatorIndex = headerContent.indexOf('|')
    val (author, preview) = if (separatorIndex in 1 until headerContent.lastIndex) {
        val authorLabel = headerContent.substring(0, separatorIndex).trim()
        val previewText = headerContent.substring(separatorIndex + 1).trim()
        authorLabel to previewText
    } else {
        null to headerContent
    }

    val body = if (newlineIndex == -1 || newlineIndex == text.lastIndex) {
        ""
    } else {
        text.substring(newlineIndex + 1).trim()
    }
    if (preview.isEmpty()) return null

    return ParsedReply(
        preview = preview,
        body = body,
        authorLabel = author?.takeIf { it.isNotEmpty() },
        targetUuid = replyUuid,
    )
}

fun previewTextForReplyTarget(text: String?): String? {
    val normalizedText = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parsed = parseReplyMetadata(normalizedText)
    val previewSource = when {
        parsed == null -> normalizedText
        parsed.body.isNotBlank() -> parsed.body
        else -> parsed.preview
    }
    return previewSource
        .replace(MULTI_WHITESPACE_REGEX, " ")
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * Removes reply metadata from the provided body and returns the visible text portion.
 */
fun stripReplyMetadata(body: String?): String? {
    val normalizedBody = body?.trim() ?: return null
    val parsed = parseReplyMetadata(normalizedBody)
    return if (parsed != null) {
        parsed.body.takeIf { it.isNotEmpty() }
    } else {
        normalizedBody.takeIf { it.isNotEmpty() }
    }
}
