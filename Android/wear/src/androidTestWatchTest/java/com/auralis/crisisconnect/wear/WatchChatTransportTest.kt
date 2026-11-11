package com.auralis.crisisconnect.wear
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.watch.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
class WatchChatTransportTest {
 @Test fun sendThreeRepliesWithoutSOS()=runBlocking {
  val token=InstrumentationRegistry.getArguments().getString("dev_link_token")!!
  val transport=DevLinkTransport(token)
  assertFalse(transport.exchange(WatchCommand(action=WatchAction.status)).state.sosActive)
  for(reply in QuickReply.entries) {
   val command=WatchCommand(action=WatchAction.quickReply,peerID="local-dev-test-recipient",reply=reply)
   val receipt=transport.exchange(command)
   assertEquals(when(reply) {QuickReply.safe->"accepted";QuickReply.injured->"uncertain";QuickReply.needHelp->"noPeer"},receipt.result)
   assertFalse(receipt.state.sosActive)
   if(reply==QuickReply.safe)assertEquals("accepted",transport.exchange(command).result)
  }
 }
}
