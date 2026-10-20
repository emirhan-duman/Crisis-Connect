package com.auralis.crisisconnect.watch
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.auralis.crisisconnect.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
class WatchChatScreenTest {
 @get:Rule val ui=createAndroidComposeRule<ChatPreviewActivity>()
 @Test fun deliveryReceiptWinsOverStaleFailure() = runBlocking {
  val db=AppDatabase.getInstance(ui.activity)
  val dao=db.messageDao();val peer="watch-receipt-"+java.util.UUID.randomUUID();val id=java.util.UUID.randomUUID().toString()
  db.contactDao().saveContact(ContactEntity(sessionCode=peer,name="Test receipt",aesKey="k",address="test"))
  try {
   dao.upsertLocalTextMessageAtomic(peer,id,"İyiyim",1000L,null,MessageDeliveryStatus.SENT,0,null,null,null,WatchReplyChat.ROUTE)
   for(receipt in listOf(MessageDeliveryStatus.DELIVERED,MessageDeliveryStatus.READ)) {
    if(receipt==MessageDeliveryStatus.DELIVERED)dao.markLocalMessagesDelivered(listOf(id)) else dao.markLocalMessagesRead(listOf(id))
    dao.upsertLocalTextMessageAtomic(peer,id,"İyiyim",2000L,null,MessageDeliveryStatus.FAILED,0,null,null,"watch_uncertain",WatchReplyChat.ROUTE)
    assertEquals(receipt,dao.getMessageByUuid(id)!!.deliveryStatus)
    assertEquals(1000L,dao.getMessageByUuid(id)!!.timestampMillis)
   }
  } finally {db.contactDao().deleteBySessionCode(peer)}
 }
 @Test fun normalBubblesAndDurableStates() {
  val context=ui.activity
  val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
  // Cold emulator startup can leave a System UI ANR above an otherwise healthy app.
  // Dismiss only that OS dialog; an application ANR remains a failure.
  val systemAnr=By.text("System UI isn't responding")
  if(device.hasObject(systemAnr)) {
   checkNotNull(device.findObject(By.text("Wait"))).click()
   assertTrue(device.wait(Until.gone(systemAnr),10000))
  }
  ui.waitUntil(15000) { ui.onAllNodesWithText("İyiyim").fetchSemanticsNodes().isNotEmpty() }
  ui.onNodeWithText("İyiyim").assertIsDisplayed()
  ui.onNodeWithText("Yaralıyım").assertIsDisplayed()
  ui.onNodeWithText("Yardıma ihtiyacım var").assertIsDisplayed()
  ui.onNodeWithText("Saat · Bağlantıya verildi; teslim doğrulanmadı").assertIsDisplayed()
  ui.onNodeWithText("Saat · Doğrulanmadı; otomatik tekrar gönderilmez").assertIsDisplayed()
  ui.onNodeWithText("Saat · Gönderilemedi").assertIsDisplayed()
  val before=runBlocking { observeMessages(context,ChatPreviewDelegate.PEER).first() }
  assertEquals(3,before.size);assertEquals(3,before.map { it.messageUuid }.toSet().size)
  assertEquals(listOf(MessageDeliveryStatus.SENT,MessageDeliveryStatus.FAILED,MessageDeliveryStatus.FAILED),before.map {it.deliveryStatus})
  runBlocking { WatchReplyChat.reconcile(context);WatchReplyChat.reconcile(context) }
  val after=runBlocking { observeMessages(context,ChatPreviewDelegate.PEER).first() };assertEquals(before,after)
  File(context.filesDir,"watch-chat-phone.json").writeText(org.json.JSONArray(after.map {
   org.json.JSONObject().put("id",it.messageUuid).put("text",it.text).put("status",it.deliveryStatus?.name)
    .put("error",it.lastError).put("route",it.outboundRoute).put("timestamp",it.timestampMillis)
  }).toString(2))
  ui.waitForIdle();UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
  assertFalse(device.hasObject(systemAnr))
  val file=File(context.getExternalFilesDir(null),"watch-chat-normal.png")
  assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
 }
}
