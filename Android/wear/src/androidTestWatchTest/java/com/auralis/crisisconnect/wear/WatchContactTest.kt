package com.auralis.crisisconnect.wear

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.auralis.crisisconnect.watch.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WatchContactTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    @Test fun cachedContactsAreDisplayOnlyWithoutLivePhone() {
        val prefs=context.getSharedPreferences("watch_display_v1",android.content.Context.MODE_PRIVATE)
        val previous=prefs.getString("state",null)
        try {
            prefs.edit().putString("state",PhoneWatchState(updatedAt=System.currentTimeMillis(),contacts=listOf(WatchSavedContact("saved","Test kişi",canSend=true)),contactCount=1).json().toString()).commit()
            val model=WatchModel(context)
            assertEquals("Test kişi",model.state.contacts.single().name);assertFalse(model.fresh)
            model.send(WatchAction.contactReply,QuickReply.safe,"saved")
            assertFalse(model.busy);model.close()
        } finally {prefs.edit().apply {if(previous==null)remove("state") else putString("state",previous)}.commit()}
    }
    private fun click(text:String) {
        ui.waitUntil(20000) {ui.onAllNodes(hasText(text) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNode(hasText(text) and hasClickAction());node.performScrollTo();node.performClick()
    }
    @Test fun selectConfirmSendDuplicateAndChangedContact() {
        val token=InstrumentationRegistry.getArguments().getString("dev_link_token")!!
        val commands=java.util.Collections.synchronizedList(mutableListOf<WatchCommand>())
        DevLinkTransport.commandObserver={commands.add(it)}
        try {ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("dev_link_token",token)).use {
            click("Kayıtlı kişiler");click("Ayşe · test");click("İyiyim")
            ui.onNodeWithText("Ayşe · test: Bu mesaj gönderilsin mi?").assertExists()
            click("İptal");assertEquals(0,commands.count {it.action==WatchAction.contactReply})
            click("İyiyim");click("İyiyim")
            ui.waitUntil(20000) {ui.onAllNodesWithText("Mesaj telefon bağlantısına verildi. Teslim henüz doğrulanmadı.").fetchSemanticsNodes().isNotEmpty()}
            val first=commands.first {it.action==WatchAction.contactReply};assertEquals("watch-contact-a",first.peerID)
            val now=System.currentTimeMillis()
            assertEquals("accepted",runBlocking {DevLinkTransport(token).exchange(first.copy(issuedAt=now,expiresAt=now+30000))}.result)
            click("Kişiler");click("Mehmet · test");click("Yardıma ihtiyacım var");click("Yardıma ihtiyacım var")
            ui.waitUntil(20000) {ui.onAllNodesWithText("Kişi veya telefonun interneti erişilemiyor. Otomatik tekrar yok.").fetchSemanticsNodes().isNotEmpty()}
            click("Kişiler");click("Anahtarı değişen kişi")
            ui.onNode(hasText("İyiyim") and hasClickAction()).assertIsNotEnabled()
            val changed=runBlocking {DevLinkTransport(token).exchange(WatchCommand(action=WatchAction.contactReply,peerID="watch-contact-key-changed",reply=QuickReply.safe))}
            assertEquals("noPeer",changed.result)
            val missing=runBlocking {DevLinkTransport(token).exchange(WatchCommand(action=WatchAction.contactReply,peerID="removed-contact",reply=QuickReply.safe))}
            assertEquals("noPeer",missing.result)
            Thread.sleep(17000)
            assertEquals(5,commands.count {it.action==WatchAction.contactReply}) // two users, duplicate, changed and removed
            assertFalse(changed.state.sosActive);assertFalse(missing.state.sosActive)
            val file=File(context.getExternalFilesDir(null),"watch-contact-changed.png")
            assertTrue(UiDevice.getInstance(ins).takeScreenshot(file))
            File(context.filesDir,"watch-contact-results.json").writeText(JSONObject().put("passed",true).put("automaticRetries",0)
                .put("commands",JSONArray(commands.filter {it.action==WatchAction.contactReply}.map {JSONObject(it.encode().toString(Charsets.UTF_8))})).toString(2))
        }} finally {DevLinkTransport.commandObserver=null}
    }
}
