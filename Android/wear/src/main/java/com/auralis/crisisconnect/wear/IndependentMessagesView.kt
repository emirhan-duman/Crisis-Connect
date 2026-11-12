package com.auralis.crisisconnect.wear

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*
import com.auralis.crisisconnect.wear.independent.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable fun IndependentMessagesView(close:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var contacts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var notice by remember { mutableStateOf("Önce telefondan saati ve kişiyi onayla.") }
    var busy by remember { mutableStateOf(false) }
    fun refresh() { scope.launch { busy=true;try {
        val service=IndependentWatchSetup(context).service();val response=service.refresh();val rows=response.getJSONArray("contacts")
        contacts=(0 until rows.length()).map{rows.getJSONObject(it)}
        for(peer in contacts){val bundle=peer.getJSONObject("bundle");service.signal.pinPeer(peer.getString("ownerUid"),bundle.getInt("deviceId"),bundle.getString("identityKey"))}
        notice=if(contacts.isEmpty())"Karşılıklı onaylanmış kişi yok." else "Kişiler ve teslim durumları güncel."
    } catch(e:Exception){notice=e.message?:"Bağlantı kurulamadı."} finally{busy=false} } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Bağımsız mesajlar")
        Text(notice)
        Button(onClick={refresh()},enabled=!busy){Text("Durumu yenile")}
        for(peer in contacts) {
            Text(peer.optString("name").ifEmpty{peer.getString("ownerUid")})
            for(text in listOf("İyiyim","Yardıma ihtiyacım var","Konumumdayım")) Button(enabled=!busy,onClick={scope.launch{
                busy=true
                try {val service=IndependentWatchSetup(context).service();val bundle=peer.getJSONObject("bundle")
                    service.sendMessage(peer.getString("deviceID"),peer.getString("ownerUid"),bundle.getInt("deviceId"),bundle,
                        JSONObject().put("version",1).put("kind","watchText").put("text",text).toString().toByteArray())
                    notice="Gönderim kabul edildi; teslim durumu için yenile."
                }catch(e:Exception){notice="Gönderim doğrulanamadı. Durumu yenile; otomatik tekrar gönderilmez."}finally{busy=false}
            }}){Text(text)}
        }
        Button(onClick=close){Text("Geri")}
    }
}
