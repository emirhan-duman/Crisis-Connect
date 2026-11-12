package com.auralis.crisisconnect.core.chat

/**
 * Metadata utilities for forwarded messages in CrisisConnect.
 * Formats, detects, and strips forward indicators across text, voice, and media payloads.
 */
object ForwardMetadata {
    const val FORWARD_PREFIX = "⏩ "

    /**
     * Checks if the message content indicates it was forwarded.
     */
    fun isForwarded(text: String?): Boolean {
        if (text == null) return false
        val trimmed = text.trimStart()
        return trimmed.startsWith("⏩")
    }

    /**
     * Prepends the forward prefix to the message body.
     */
    fun formatForwardedText(body: String?): String {
        val clean = stripForward(body).trim()
        return "$FORWARD_PREFIX$clean"
    }

    /**
     * Removes all leading forward indicators from the text.
     */
    fun stripForward(text: String?): String {
        if (text == null) return ""
        var result = text.trimStart()
        while (result.startsWith("⏩")) {
            result = result.removePrefix("⏩").trimStart()
        }
        return result
    }
}
