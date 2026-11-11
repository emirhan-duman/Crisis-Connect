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
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Ordinary DB observation and ordinary ChatBubble; transport and bootstrap remain isolated. */
class ChatPreviewActivity : ComponentActivity() {
    companion object { @Volatile var visible=false }
    private fun lifecycleRecord() {
        java.io.File(filesDir,"watch-chat-lifecycle.json").writeText(JSONObject().put("foreground",visible).put("pid",android.os.Process.myPid()).toString())
    }
    override fun onStart() { super.onStart(); visible=true; lifecycleRecord() }
    override fun onStop() { visible=false; lifecycleRecord(); super.onStop() }
    override fun onNewIntent(intent:android.content.Intent) {
        super.onNewIntent(intent)
        if(intent.getBooleanExtra("chat_background",false)) moveTaskToBack(true)
    }
    override fun attachBaseContext(base: android.content.Context) {
        val config=android.content.res.Configuration(base.resources.configuration);config.setLocale(Locale.forLanguageTag("tr"))
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);check(WatchTestHooks.enabled)
        WatchTestHooks.install(ChatPreviewDelegate(applicationContext))
        val prefs=getSharedPreferences("watch_dev_link",MODE_PRIVATE)
        val token=intent.getStringExtra("dev_link_token")?:prefs.getString("token",null)
        prefs.edit().putBoolean("recovery",true).apply { if(token!=null)putString("token",token) }.commit()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if(intent.getBooleanExtra("chat_reset",false)) {
                    for(name in listOf("watch-chat-effects.json","watch-chat-commands.json","watch-chat-drop-receipt")) java.io.File(filesDir,name).delete()
                    WatchRecoveryStore.get(this@ChatPreviewActivity).removeRepliesForPeer(ChatPreviewDelegate.PEER)
                    AppDatabase.getInstance(this@ChatPreviewActivity).contactDao().deleteBySessionCode(ChatPreviewDelegate.PEER)
                }
                saveContact(this@ChatPreviewActivity,Contact("Saat test sohbeti","",ChatPreviewDelegate.PEER))
            }
            WatchReplyChat.reconcile(this@ChatPreviewActivity)
            (WatchTestHooks.delegate as ChatPreviewDelegate).exportChat()
            if(token!=null) androidx.core.content.ContextCompat.startForegroundService(this@ChatPreviewActivity,android.content.Intent(this@ChatPreviewActivity,DevLinkService::class.java))
            setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                val messages by observeMessages(this@ChatPreviewActivity,ChatPreviewDelegate.PEER).collectAsState(emptyList())
                Column(Modifier.fillMaxSize().padding(top=32.dp,bottom=24.dp)) {
                    Text("KONTROLLÜ TEST · Dış gönderimler kapalı",Modifier.padding(16.dp))
                    Text("Saat test sohbeti",Modifier.padding(16.dp),style=MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.weight(1f).padding(12.dp)) { items(messages,key={it.messageUuid}) { message ->
                        ChatBubble(message,SimpleDateFormat("HH:mm",Locale.getDefault()),null,null,{}, {}, {}, {}, false, {null},false,
                            "Saat test sohbeti",ChatPreviewDelegate.PEER,"Test kullanıcısı",null,null,null,null,false)
                    } }
                }
            } } }
        }
    }
}
class ChatPreviewDelegate(private val context: android.content.Context): WatchTestHooks.Delegate {
    companion object { const val PEER="local-dev-test-recipient" }
    override fun productionRecovery()=true
    override fun state(actual:PhoneWatchState)=actual.copy(peerID=PEER,peerName="Telefondaki test alıcısı",lastMessage=WatchRecoveryStore.get(context).state.value.replies.lastOrNull { it.peer==PEER }?.text.orEmpty())
    override fun reply(command:WatchCommand):String=runBlocking {
        if(command.peerID!=PEER)return@runBlocking "noPeer"
        val reply=command.reply?:return@runBlocking "invalid"
        val outcome=runCatching { WatchReplyChat.send(context,command.id,PEER,reply.turkish) {
            append("watch-chat-effects.json",JSONObject().put("id",command.id).put("reply",reply.name).put("pid",android.os.Process.myPid()).put("foreground",ChatPreviewActivity.visible))
            if(reply==QuickReply.injured)error("Controlled interruption before handoff confirmation")
            reply==QuickReply.safe
        } }.getOrDefault("uncertain")
        WatchReplyChat.reconcile(context)
        exportChat()
        outcome
    }
    suspend fun exportChat() {
        val msgs=observeMessages(context,PEER).first()
        java.io.File(context.filesDir,"watch-chat-phone.json").writeText(org.json.JSONArray(msgs.map { JSONObject().put("id",it.messageUuid).put("text",it.text).put("status",it.deliveryStatus?.name).put("error",it.lastError).put("route",it.outboundRoute).put("timestamp",it.timestampMillis) }).toString(2))
    }
    @Synchronized private fun append(name:String,row:JSONObject) {
        val file=java.io.File(context.filesDir,name)
        val rows=if(file.exists())org.json.JSONArray(file.readText()) else org.json.JSONArray()
        rows.put(row);file.writeText(rows.toString(2))
    }
    override fun record(command:WatchCommand,receipt:JSONObject) {
        append("watch-chat-commands.json",JSONObject().put("command",JSONObject(command.encode().toString(Charsets.UTF_8))).put("result",receipt.getString("result")).put("foreground",ChatPreviewActivity.visible).put("pid",android.os.Process.myPid()))
    }
}
