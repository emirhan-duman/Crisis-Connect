package com.auralis.crisisconnect.watch

import android.app.Application
import android.util.AtomicFile
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WatchRecoveryStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun disk() = AtomicFile(File(folder.root, "recovery.json"))
    private fun reopen() = WatchRecoveryStore(disk())

    @Test fun `cold launch only reads the same session and replies`() {
        reopen().apply {
            begin(1234)
            reserveReply("one", "rescue-a", "İyiyim")
            completeReply("one", "accepted")
        }
        val recovered = reopen()
        assertEquals(1234L, recovered.state.value.startedAt)
        assertEquals("accepted", recovered.state.value.replies.single().result)
        assertEquals(1234L, recovered.resume("1234"))
        assertThrows(IllegalStateException::class.java) { recovered.begin(2345) }
        assertEquals(1234L, reopen().state.value.startedAt)
    }

    @Test fun `end survives another process and retains message history`() {
        reopen().apply { begin(1234); reserveReply("one", "rescue-a", "Yaralıyım"); finish() }
        val recovered = reopen()
        assertNull(recovered.state.value.startedAt)
        assertEquals(1, recovered.state.value.replies.size)
        assertThrows(IllegalStateException::class.java) { recovered.resume("1234") }
        assertEquals(2345L, recovered.begin(2345))
    }

    @Test fun `crash during handoff stays uncertain and duplicate does not become a new reply`() {
        reopen().reserveReply("same-id", "rescue-a", "Yardıma ihtiyacım var")
        val recovered = reopen()
        val original = recovered.reserveReply("same-id", "other-peer", "changed")
        assertEquals("uncertain", original.result)
        assertEquals("rescue-a", original.peer)
        assertEquals("Yardıma ihtiyacım var", original.text)
        assertEquals(1, reopen().state.value.replies.size)
    }

    @Test fun `stale resume cannot replace a newer session`() {
        val store = reopen()
        store.begin(1234); store.finish(); store.begin(2345)
        assertThrows(IllegalStateException::class.java) { store.resume("1234") }
        assertEquals(2345L, reopen().state.value.startedAt)
    }

    @Test fun `interrupted atomic write retains last committed snapshot`() {
        reopen().begin(1234)
        val atomic = disk()
        val stream = atomic.startWrite()
        stream.write("partial".toByteArray())
        atomic.failWrite(stream)
        assertEquals(1234L, reopen().state.value.startedAt)
    }

    @Test fun `unreadable history cannot silently start or erase SOS`() {
        disk().baseFile.writeText("broken JSON")
        val store = reopen()
        assertTrue(store.state.value.unavailable)
        assertThrows(IllegalStateException::class.java) { store.begin(1234) }
        assertThrows(IllegalStateException::class.java) { store.finish() }
        assertEquals("broken JSON", disk().baseFile.readText())
    }

    @Test fun `additive state fields work with old and new watch payloads`() {
        val old = PhoneWatchState(updatedAt = 1000).json().apply { remove("recoveryRequired"); remove("lastReplyResult") }
        assertFalse(PhoneWatchState.decode(old).recoveryRequired)
        val saved = PhoneWatchState(emergencyID = "1234", updatedAt = 2000, recoveryRequired = true, lastMessage = "İyiyim", lastReplyResult = "uncertain")
        assertEquals(saved, PhoneWatchState.decode(saved.json()))
        assertFalse(saved.sosActive)
        assertFalse(saved.bleBroadcasting)
    }
}
