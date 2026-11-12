package com.auralis.crisisconnect.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.auralis.crisisconnect.data.*
import com.auralis.crisisconnect.screens.Chat.ChatBubble
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Isolated recipient/relay fixture, reusing production contact validation and ordinary bubbles. */
class ContactPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); check(WatchTestHooks.enabled)
        val delegate = ContactPreviewDelegate(applicationContext); WatchTestHooks.install(delegate)
        val prefs = getSharedPreferences("watch_dev_link", MODE_PRIVATE)
        val token = intent.getStringExtra("dev_link_token") ?: prefs.getString("token", null)
        if (token != null) prefs.edit().putString("token",token).commit()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (intent.getBooleanExtra("contact_reset",false)) {
                    for ((index,id) in ContactPreviewDelegate.IDS.withIndex()) {
                        WatchRecoveryStore.get(this@ContactPreviewActivity).removeRepliesForPeer(id)
                        AppDatabase.getInstance(this@ContactPreviewActivity).contactDao().deleteBySessionCode(id)
                        saveContact(this@ContactPreviewActivity,
                            Contact(ContactPreviewDelegate.NAMES[index], "", id, peerUid="isolated-$id",peerPublicKey="isolated-key",peerKeyChanged=index==2))
                    }
                    File(filesDir,"watch-contact-effects.json").delete()
                }
                delegate.restoredContacts = getContacts(this@ContactPreviewActivity).filter { it.sessionCode in ContactPreviewDelegate.IDS }
                File(filesDir,"watch-contact-roster.json").writeText(JSONArray(delegate.restoredContacts.map {
                    JSONObject().put("id",it.sessionCode).put("name",it.name).put("keyChanged",it.peerKeyChanged)
                }).toString(2))
            }
            WatchReplyChat.reconcile(this@ContactPreviewActivity); delegate.export()
            if (token != null) androidx.core.content.ContextCompat.startForegroundService(this@ContactPreviewActivity,android.content.Intent(this@ContactPreviewActivity,DevLinkService::class.java))
            setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                val messages by observeMessagesNewestFirst(this@ContactPreviewActivity).collectAsState(emptyList())
                Column(Modifier.fillMaxSize().padding(top=32.dp)) {
                    Text("Kişi mesaj testi · Dış gönderimler kapalı",Modifier.padding(16.dp))
                    LazyColumn(Modifier.padding(12.dp)) { items(messages.filter {it.sessionCode in ContactPreviewDelegate.IDS}.reversed(),key={it.messageUuid}) { message ->
                        Text(ContactPreviewDelegate.NAMES[ContactPreviewDelegate.IDS.indexOf(message.sessionCode)],Modifier.padding(8.dp))
                        ChatBubble(message,SimpleDateFormat("HH:mm",Locale.getDefault()),null,null,{}, {}, {}, {}, false, {null},false,
                            "Test kişi",message.sessionCode,"Test kullanıcısı",null,null,null,null,false)
                    } }
                }
            } } }
        }
    }
}
class ContactPreviewDelegate(private val context: android.content.Context): WatchTestHooks.Delegate {
    companion object {
        val IDS=listOf("watch-contact-a","watch-contact-b","watch-contact-key-changed")
        val NAMES=listOf("Ayşe · test","Mehmet · test","Anahtarı değişen kişi")
    }
    @Volatile var restoredContacts = emptyList<Contact>()
    override fun productionRecovery()=true
    override fun state(actual:PhoneWatchState)=actual.copy(contacts=restoredContacts.map { contact ->
        val last=WatchRecoveryStore.get(context).state.value.replies.lastOrNull {it.peer==contact.sessionCode}
        WatchSavedContact(contact.sessionCode,contact.name,last?.text.orEmpty(),WatchContactMessaging.eligible(contact),
            contact.supportsInternet,contact.peerKeyChanged,last?.result.orEmpty())
    },contactCount=restoredContacts.size)
    override fun reply(command:WatchCommand)="noPeer"
    override fun contactReply(command:WatchCommand)=runBlocking {
        val outcome=WatchContactMessaging.send(context,command) { recipient,id,_,_ ->
            val file=File(context.filesDir,"watch-contact-effects.json")
            val rows=if(file.exists())JSONArray(file.readText()) else JSONArray()
            rows.put(JSONObject().put("id",id).put("peer",recipient.sessionCode));file.writeText(rows.toString(2))
            if(command.reply==QuickReply.injured)error("Controlled handoff interruption")
            command.reply!=QuickReply.needHelp
        }
        export(); outcome
    }
    suspend fun export() {
        WatchReplyChat.reconcile(context)
        val dao=AppDatabase.getInstance(context).messageDao()
        val rows=dao.observeMessagesNewestFirst().first().filter {it.sessionCode in IDS}
        File(context.filesDir,"watch-contact-phone.json").writeText(JSONArray(rows.map {JSONObject().put("id",it.messageUuid).put("peer",it.sessionCode).put("text",it.text).put("date",it.timestampMillis).put("status",it.deliveryStatus?.name).put("error",it.lastError)}).toString(2))
    }
    override fun record(command:WatchCommand,receipt:JSONObject) {runBlocking {export()}}
}
