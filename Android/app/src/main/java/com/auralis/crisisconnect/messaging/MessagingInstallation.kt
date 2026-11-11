package com.auralis.crisisconnect.messaging

import android.content.Context
import java.io.File
import java.util.UUID

/** Random installation identifier, excluded from backup so a second phone never inherits it. */
internal object MessagingInstallation {
    private var cached: String? = null
    @Synchronized fun start(context: Context) {
        if (cached != null) return
        val file = File(context.noBackupFilesDir, "messaging_installation_id")
        cached = runCatching { file.readText().trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } }.getOrNull()
            ?: UUID.randomUUID().toString().also { id -> runCatching { file.writeText(id) } }
    }
    @Synchronized fun id(): String = cached ?: UUID.randomUUID().toString().also { cached = it }
}
