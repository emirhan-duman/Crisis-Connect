package com.auralis.crisisconnect.watch

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import com.auralis.crisisconnect.data.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/** Explicit account and saved-contact consent. Device fingerprints are never accepted silently. */
class IndependentMessagingActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var content: LinearLayout
    private lateinit var notice: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(32,32,32,32) }
        setContentView(ScrollView(this).apply { addView(content) })
        content.addView(TextView(this).apply { text="Bağımsız saat mesajları";textSize=22f })
        notice=TextView(this);content.addView(notice)
        val user=FirebaseAuth.getInstance().currentUser
        if(user==null || user.isAnonymous){notice.text="Devam etmek için hesabına giriş yap.";return}
        val inbox=IndependentPhoneInbox(applicationContext,user.uid)
        notice.text="Saatten gelen şifreli mesajlar normal sohbetlerinde görünür. Gönderen cihazı ve kişiyi burada onayla."
        button("Bu telefonda mesaj almayı aç") { inbox.enable();notice.text="Telefon alıcısı hazır. Kişinin cihazını seçip onayla." }
        button("Bağlı saatimi bu hesapla yetkilendir") { inbox.pairWatch();notice.text="Saat yetkilendirildi. Kişi cihazlarını onaylayabilirsin." }
        button("Mesajları kontrol et") { inbox.refresh();notice.text="Kayıtlı mesajlar ve teslim durumları güncellendi." }
        button("Bu telefonun yetkisini kaldır") { inbox.disable();notice.text="Alıcı yetkisi kaldırıldı; sohbet geçmişi korundu." }
        scope.launch {
            val contacts=withContext(Dispatchers.IO){observeContacts(applicationContext).first()}.filter(WatchContactMessaging::eligible)
            for(contact in contacts) button(contact.name) {
                require(inbox.isEnabled()) { "Önce bu telefonda mesaj almayı aç." }
                val devices=inbox.devices(contact)
                if(devices.isEmpty())notice.text="Bu kişi henüz bağımsız mesajlaşmayı açmamış."
                for(device in devices) {
                    val identity=device.getJSONObject("bundle").getString("identityKey")
                    val fingerprint=MessageDigest.getInstance("SHA-256").digest(android.util.Base64.decode(identity,android.util.Base64.NO_WRAP)).joinToString(""){"%02x".format(it)}.chunked(8).joinToString(" ")
                    AlertDialog.Builder(this@IndependentMessagingActivity).setTitle("${contact.name} · ${device.optString("kind","watch")}")
                        .setMessage("Bu cihazdan mesaj almaya izin verilsin mi?\n\nCihaz doğrulama kodu:\n$fingerprint\n\nKarşı tarafta da onay gereklidir.")
                        .setNegativeButton("İptal",null).setNeutralButton("Yetkiyi kaldır") { _,_->launchAction { inbox.revokeContact(device);notice.text="Cihazın kişi onayı kaldırıldı." } }.setPositiveButton("Cihazı onayla") { _,_->launchAction { inbox.approve(contact,device);notice.text="Cihaz onaylandı." } }.show()
                }
            }
        }
    }
    private fun button(title:String,action:suspend()->Unit){content.addView(Button(this).apply{text=title;setOnClickListener{launchAction(action)}})}
    private fun launchAction(action:suspend()->Unit){scope.launch{try{action()}catch(e:Exception){notice.text=e.message?:"İşlem tamamlanamadı; tekrar gönderim yapılmadı."}}}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
