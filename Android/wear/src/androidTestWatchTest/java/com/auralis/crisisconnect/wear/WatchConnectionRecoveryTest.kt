package com.auralis.crisisconnect.wear

import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.watch.*
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production model; the deliberately stalled transport has no external effects. */
class WatchConnectionRecoveryTest {
    @Test fun timedOutReplyRecoversWithStatusWithoutResending() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val commands = java.util.Collections.synchronizedList(mutableListOf<WatchCommand>())
        val transport = object : WatchSimulation {
            override val turkish = true
            override suspend fun exchange(command: WatchCommand): SimulatedWatchReceipt {
                commands.add(command)
                if (command.action == WatchAction.contactReply) delay(60_000)
                return SimulatedWatchReceipt("accepted", PhoneWatchState(updatedAt = System.currentTimeMillis(),
                    contacts = listOf(WatchSavedContact("saved", "Test kişi", canSend = true)), contactCount = 1))
            }
        }
        lateinit var model: WatchModel
        fun onMain(block: () -> Unit) = ins.runOnMainSync(block)
        fun waitFor(timeout: Long, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeout
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                var done = false
                onMain { done = predicate() }
                if (done) return
                Thread.sleep(50)
            }
            fail("Timed out waiting for watch model")
        }
        onMain { model = WatchModel(ins.targetContext, transport) }
        try {
            onMain { model.send(WatchAction.status) }
            waitFor(5_000) { model.fresh && !model.busy }
            onMain { model.send(WatchAction.contactReply, QuickReply.safe, "saved") }
            waitFor(13_000) { !model.busy && !model.fresh }
            onMain { assertTrue(model.notice.contains("Doğrulama alınamadı")); model.send(WatchAction.status) }
            waitFor(5_000) { model.fresh && !model.busy }
            onMain {
                assertEquals("Telefon yeniden bağlandı. Durum güncellendi.", model.notice)
                assertFalse(model.state.sosActive)
            }
            assertEquals(1, commands.count { it.action == WatchAction.contactReply })
            assertEquals(0, commands.count { it.action == WatchAction.startSOS })
        } finally { onMain { model.close() } }
    }
}
