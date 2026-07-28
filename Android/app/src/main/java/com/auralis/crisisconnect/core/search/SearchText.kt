package com.auralis.crisisconnect.core.search

import com.auralis.crisisconnect.core.chat.stripReplyMetadata
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.MessageType

/**
 * Locale-tolerant, index-preserving normalization for chat search.
 *
 * Every input char maps to exactly one output char, so a match range found in the
 * normalized string is also valid in the original string — that's what lets the
 * result rows highlight the matched substring without re-scanning. Case is folded
 * the Turkish way (I→ı→i, İ→i) and common Turkish/Latin diacritics are stripped,
 * so typing "sukru" finds "Şükrü".
 */
fun normalizeForSearch(input: String): String {
    if (input.isEmpty()) return input
    val builder = StringBuilder(input.length)
    for (ch in input) {
        builder.append(foldSearchChar(ch))
    }
    return builder.toString()
}

private fun foldSearchChar(ch: Char): Char {
    val lower = when (ch) {
        // Turkish casing: fold both capital I forms before the generic lowercase,
        // which would otherwise map 'I' to 'i' and lose the ı/i distinction.
        'I' -> 'ı'
        'İ' -> 'i'
        else -> ch.lowercaseChar()
    }
    return when (lower) {
        'ç' -> 'c'
        'ğ' -> 'g'
        'ı' -> 'i'
        'ö' -> 'o'
        'ş' -> 's'
        'ü' -> 'u'
        'â', 'à', 'á', 'ä', 'ã' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'î', 'ì', 'í', 'ï' -> 'i'
        'ô', 'ò', 'ó', 'õ' -> 'o'
        'û', 'ù', 'ú' -> 'u'
        else -> lower
    }
}

/**
 * The human-readable text of a message for search purposes, or null when the
 * message has nothing a user would recognize (voice/image rows, machine payloads
 * like `CC_LOC:`/`CC_FILE:`). Reply headers are stripped so only the visible body
 * is matched — the quoted preview belongs to the other message.
 */
fun searchableMessageBody(message: ChatMessage): String? {
    if (message.messageType != MessageType.TEXT && message.messageType != MessageType.SOS_ALERT) {
        return null
    }
    val trimmed = message.text.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("CC_")) {
        return null
    }
    return stripReplyMetadata(trimmed) ?: trimmed
}
