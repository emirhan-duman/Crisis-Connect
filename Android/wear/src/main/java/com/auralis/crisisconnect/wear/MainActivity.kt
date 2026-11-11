package com.auralis.crisisconnect.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.*
import com.auralis.crisisconnect.watch.*

class MainActivity : ComponentActivity() {
    private lateinit var model: WatchModel
    private val rpc = com.google.android.gms.wearable.MessageClient.RpcService { _, path, bytes ->
        if (path != com.auralis.crisisconnect.wear.independent.IndependentWatchSetup.PATH) null
        else try { com.google.android.gms.tasks.Tasks.forResult(com.auralis.crisisconnect.wear.independent.IndependentWatchSetup(applicationContext).request(org.json.JSONObject(String(bytes))).toString().toByteArray()) }
        catch (error: Exception) { com.google.android.gms.tasks.Tasks.forException(error) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = WatchModel(applicationContext, createWatchSimulation(intent))
        setContent { MaterialTheme { WatchHome(model) } }
    }
    override fun onStart() { super.onStart(); model.start()
        com.google.android.gms.wearable.Wearable.getMessageClient(this).addRpcService(rpc, com.auralis.crisisconnect.wear.independent.IndependentWatchSetup.PATH)
    }
    override fun onStop() {
        com.google.android.gms.wearable.Wearable.getMessageClient(this).removeRpcService(rpc)
        model.stop(); super.onStop()
    }
    override fun onDestroy() { model.close(); super.onDestroy() }
}

@Composable private fun WatchHome(model: WatchModel) {
    var confirmStop by remember { mutableStateOf(false) }
    var independent by remember { mutableStateOf(false) }
    if (independent) { IndependentMessagesView { independent = false }; return }
    var showContacts by remember { mutableStateOf(false) }
    var showConnectionHelp by remember { mutableStateOf(false) }
    // Clock tick invalidates stale status even if the transport produces no callbacks.
    @Suppress("UNUSED_VARIABLE") val tick = model.tick
    val seconds = model.countdown
    if (showContacts) { WatchSavedContacts(model) { showContacts = false }; return }
    if (showConnectionHelp) {
        BackHandler { showConnectionHelp = false }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(model.label("Connection options", "Bağlantı seçenekleri"), fontSize = 16.sp)
            Text(model.label("Crisis Connect sends through your phone. Independent watch Wi-Fi/LTE messaging is not available yet.", "Crisis Connect telefon üzerinden gönderir. Saatten bağımsız Wi-Fi/LTE mesajlaşması henüz yok."), fontSize = 12.sp)
            Text(model.label("Satellite requires supported hardware, region and service. Use the watch's system SOS where available. Crisis Connect does not control that satellite service.", "Uydu için desteklenen donanım, bölge ve servis gerekir. Uygunsa saatin sistem SOS özelliğini kullan. Crisis Connect bu uydu servisini yönetmez."), fontSize = 12.sp)
            WatchButton(model.label("Back", "Geri")) { showConnectionHelp = false }
        }
        return
    }
    BackHandler(seconds != null || confirmStop) {
        model.cancelCountdown()
        confirmStop = false
    }
    if (seconds != null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            Text(model.label("Starting SOS", "SOS başlatılıyor"), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            Text(seconds.toString(), fontSize = 42.sp, lineHeight = 46.sp, color = Color.Red, fontWeight = FontWeight.Bold)
            WatchButton(model.label("Cancel", "İptal")) { model.cancelCountdown() }
            Text(model.label("Cancel to prevent sending.", "Göndermemek için iptal et."), fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
        }
        return
    }
    if (confirmStop && (model.state.sosActive || model.state.recoveryRequired)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            Text(if (model.state.recoveryRequired) model.label("End the saved SOS?", "Kayıtlı SOS bitirilsin mi?") else model.label("End the active SOS?", "Aktif SOS bitirilsin mi?"), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            WatchButton(model.label("End SOS", "SOS’u bitir"), model.fresh && !model.busy, danger = true) {
                confirmStop = false; model.send(WatchAction.stopSOS)
            }
            WatchButton(model.label("Cancel", "İptal")) { confirmStop = false }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Crisis Connect", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
        if (model.simulating) Text(if (model.developerLink) "YEREL TEST" else model.label("EMULATOR TEST", "EMÜLATÖR TESTİ"), fontSize = 10.sp, lineHeight = 12.sp, color = Color.Yellow)
        Text(if (model.fresh) model.label("Phone connected", "Telefon bağlı") else model.label("Status not current", "Durum güncel değil"),
            fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, color = if (model.fresh) Color.Green else Color.Yellow)
        Text(if (model.state.updatedAt == 0L) model.label("SOS status unknown", "SOS durumu bilinmiyor") else if (!model.fresh) {
            if (model.state.recoveryRequired) model.label("Last known: SOS paused", "Son bilinen: SOS duraklatıldı") else if (model.state.sosActive) model.label("Last known: SOS active", "Son bilinen: SOS aktif") else model.label("Last known: SOS inactive", "Son bilinen: SOS kapalı")
        } else if (model.state.recoveryRequired) model.label("SOS saved · paused", "SOS kayıtlı · duraklatıldı") else if (model.state.sosActive) model.label("SOS active", "SOS aktif") else model.label("SOS inactive", "SOS kapalı"),
            fontSize = 20.sp, lineHeight = 24.sp, textAlign = TextAlign.Center, color = if (model.state.sosActive) Color.Red else Color.White, fontWeight = FontWeight.Bold)
        if (model.state.sosActive) Text(if (model.developerLink) "BLE ve dış bildirimler kapalı" else if (model.state.bleBroadcasting) model.label("Bluetooth broadcast active", "Bluetooth yayını aktif")
            else model.label("Bluetooth broadcast not confirmed", "Bluetooth yayını doğrulanmadı"), fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
        if (model.state.recoveryRequired) {
            Text(model.label("Open the phone to resume the saved SOS. No broadcast has restarted.", "Kayıtlı SOS’a devam etmek için telefonu aç. Yayın yeniden başlamadı."), fontSize = 12.sp, textAlign = TextAlign.Center)
            WatchButton(model.label("End saved SOS", "Kayıtlı SOS’u bitir"), model.fresh && !model.busy) { confirmStop = true }
        }
        WatchButton(if (model.state.recoveryRequired) model.label("Resume on phone", "Telefonda devam et") else if (model.state.sosActive) model.label("End SOS", "SOS’u bitir") else model.label("Start SOS", "SOS başlat"),
            model.fresh && !model.busy, danger = !model.state.sosActive) {
            if (model.state.sosActive) confirmStop = true else if (model.state.recoveryRequired) model.send(WatchAction.startSOS) else model.armSOS()
        }
        if (model.busy) Text(model.label("Waiting…", "Bekleniyor…"), fontSize = 12.sp)
        if (model.notice.isNotBlank()) Text(model.notice, fontSize = 12.sp, textAlign = TextAlign.Center)
        if (model.state.peerID.isNotBlank()) {
            Text(model.state.peerName, fontSize = 13.sp, textAlign = TextAlign.Center)
            if (model.state.lastMessage.isNotBlank()) Text(model.state.lastMessage, fontSize = 12.sp, textAlign = TextAlign.Center)
            QuickReply.entries.forEach { reply ->
                WatchButton(if (model.turkish) reply.turkish else reply.english, model.fresh && !model.busy) {
                    model.send(WatchAction.quickReply, reply)
                }
            }
        } else {
            if (model.state.lastMessage.isNotBlank()) {
                Text(model.label("Saved reply", "Kayıtlı cevap"), fontSize = 11.sp)
                Text(model.state.lastMessage, fontSize = 12.sp, textAlign = TextAlign.Center)
                Text(if (model.state.lastReplyResult == "accepted") model.label("Handed to connection; delivery not confirmed", "Bağlantıya verildi; teslim doğrulanmadı") else model.label("Delivery not confirmed", "Teslim doğrulanmadı"), fontSize = 11.sp, textAlign = TextAlign.Center)
            }
            Text(model.label("Quick replies become available when a rescue team connects.", "Kurtarma ekibi bağlandığında kısa cevaplar açılır."), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        WatchButton(model.label("Refresh status", "Durumu yenile"), !model.busy) { model.send(WatchAction.status) }
        WatchButton(model.label("Independent messages", "Bağımsız mesajlar")) { independent = true }
        WatchButton(model.label("Saved contacts", "Kayıtlı kişiler")) { showContacts = true }
        WatchButton(model.label("Connection options", "Bağlantı seçenekleri")) { showConnectionHelp = true }
    }
}

@Composable private fun WatchSavedContacts(model: WatchModel, close: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val tick = model.tick
    var selectedID by remember { mutableStateOf<String?>(null) }
    var pendingReply by remember { mutableStateOf<QuickReply?>(null) }
    val contact = model.state.contacts.firstOrNull { it.id == selectedID }
    BackHandler { if (pendingReply != null) pendingReply = null else if (selectedID != null) selectedID = null else close() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val reply = pendingReply
        if (contact != null && reply != null) {
            Text(contact.name + ": " + model.label("Send this message?", "Bu mesaj gönderilsin mi?"), fontSize = 14.sp, textAlign = TextAlign.Center)
            WatchButton(if (model.turkish) reply.turkish else reply.english, model.fresh && !model.busy && contact.canSend) {
                pendingReply = null; model.send(WatchAction.contactReply, reply, contact.id)
            }
            WatchButton(model.label("Cancel", "İptal")) { pendingReply = null }
        } else if (contact != null) {
            WatchButton(model.label("Contacts", "Kişiler")) { selectedID = null }
            Text(contact.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (contact.lastMessage.isNotBlank()) {
                Text(model.label("Last message", "Son mesaj"), fontSize = 11.sp)
                Text(contact.lastMessage, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            if (contact.lastReplyResult.isNotBlank()) Text(when (contact.lastReplyResult) {
                "read" -> model.label("Read", "Okundu")
                "delivered" -> model.label("Delivered", "Teslim edildi")
                "accepted" -> model.label("Handed to connection · delivery unconfirmed", "Bağlantıya verildi · teslim doğrulanmadı")
                else -> model.label("Last send unconfirmed · no automatic resend", "Son gönderim doğrulanmadı · otomatik tekrar yok")
            }, fontSize = 11.sp, textAlign = TextAlign.Center)
            QuickReply.entries.forEach { answer ->
                WatchButton(if (model.turkish) answer.turkish else answer.english, model.fresh && !model.busy && contact.canSend) { pendingReply = answer }
            }
            if (contact.keyChanged) Text(model.label("Verify the changed contact key on the phone.", "Kişinin değişen anahtarını telefonda doğrula."), fontSize = 11.sp)
            else if (!contact.internetCapable) Text(model.label("This contact has no internet messaging identity.", "Bu kişinin internet mesajlaşma bağlantısı tanımlı değil."), fontSize = 11.sp)
            else if (!contact.canSend) Text(model.label("Phone internet connection required.", "Telefonun internet bağlantısı gerekli."), fontSize = 11.sp)
            if (model.notice.isNotBlank()) Text(model.notice, fontSize = 11.sp, textAlign = TextAlign.Center)
        } else {
            Text(model.label("Saved contacts", "Kayıtlı kişiler"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            model.state.contacts.forEach { saved -> WatchButton(saved.name) { pendingReply = null; selectedID = saved.id } }
            if (model.state.contacts.isEmpty()) Text(model.label("Add contacts in Crisis Connect on your phone, then refresh.", "Telefondaki Crisis Connect’e kişi ekle, ardından yenile."), fontSize = 12.sp)
            if (model.state.contactCount > model.state.contacts.size) Text(model.label("Showing ${model.state.contacts.size} contacts. Use the phone for the full list.", "${model.state.contacts.size} kişi gösteriliyor. Tam liste için telefonu kullan."), fontSize = 11.sp)
            WatchButton(model.label("Back", "Geri"), onClick = close)
        }
        Text(if (model.fresh) model.label("Messages use the phone connection.", "Mesajlar telefon bağlantısıyla gönderilir.") else model.label("Phone unavailable · saved list only. Sending disabled.", "Telefon erişilemiyor · kayıtlı liste. Gönderim kapalı."), fontSize = 11.sp, textAlign = TextAlign.Center)
        WatchButton(model.label("Refresh", "Yenile"), !model.busy) { model.send(WatchAction.status) }
    }
}
@Composable private fun WatchButton(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = Color(0xFF8B202B), contentColor = Color.White) else ButtonDefaults.buttonColors(),
        label = { Text(text, modifier = Modifier.fillMaxWidth(), fontSize = 14.sp, textAlign = TextAlign.Center) })
}
