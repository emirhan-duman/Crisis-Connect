package com.auralis.crisisconnect.service.gattmesh

internal object GattMeshTextSanitizer {
    private val multiWhitespaceRegex = Regex("\\s+")

    fun sanitize(
        raw: String,
        maxLength: Int,
        collapseWhitespace: Boolean
    ): String {
        val filtered = buildString(raw.length.coerceAtMost(maxLength)) {
            raw.forEach { char ->
                if (!char.isUnsafeDisplayChar()) {
                    append(char)
                }
            }
        }
        val normalized = if (collapseWhitespace) {
            multiWhitespaceRegex.replace(filtered.trim(), " ")
        } else {
            filtered.trim()
        }
        return normalized.take(maxLength)
    }

    private fun Char.isUnsafeDisplayChar(): Boolean {
        return when (code) {
            in 0x00..0x1F,
            0x7F,
            0x200E,
            0x200F,
            in 0x202A..0x202E,
            in 0x2066..0x2069 -> true
            else -> false
        }
    }
}
