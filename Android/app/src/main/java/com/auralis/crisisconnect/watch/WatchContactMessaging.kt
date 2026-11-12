package com.auralis.crisisconnect.watch

import android.content.Context
import com.auralis.crisisconnect.data.*
import com.auralis.crisisconnect.messaging.InternetChatTransport
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone owns all keys and sends through the existing encrypted internet/satellite-data route. */
object WatchContactMessaging {
    fun eligible(contact: Contact) = contact.supportsInternet && !contact.peerKeyChanged && !contact.isAuthorityBridge

    suspend fun send(context: Context, command: WatchCommand,
        relay: suspend (Contact, String, String, Long) -> Boolean = { contact, id, text, date ->
            val transport = InternetChatTransport(context)
            transport.isAvailable(allowSatellite = true) && transport.sendText(id, text, date, contact)
        }
    ): String = withContext(Dispatchers.IO) {
        if (!command.valid(System.currentTimeMillis()) || command.action != WatchAction.contactReply || command.reply == null) return@withContext "invalid"
        val store = WatchRecoveryStore.get(context)
        store.state.value.replies.firstOrNull { it.id == command.id }?.let {
            WatchReplyChat.reconcile(context)
            return@withContext if (it.peer == command.peerID) it.result else "stateChanged"
        }
        // Re-read at send time: a cached watch list cannot authorize a removed/changed contact.
        val contact = getContact(context, command.peerID)?.takeIf(::eligible) ?: return@withContext "noPeer"
        val text = if (Locale.getDefault().language == "tr") command.reply.turkish else command.reply.english
        WatchReplyChat.send(context, command.id, contact.sessionCode, text, kind = "contact") {
            relay(contact, command.id, text, store.state.value.replies.first { it.id == command.id }.createdAt)
        }
    }
}
