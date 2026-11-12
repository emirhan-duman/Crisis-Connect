package com.auralis.crisisconnect.service

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.watch.WatchRecoveryStore
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device filesystem recovery, with a private fixture and no service/radio/network startup. */
@RunWith(AndroidJUnit4::class)
class PhysicalSOSRecoveryInstrumentedTest {
    @Test fun durableSosRequiresConfirmationAndCannotResumeAfterStopOrCorruption() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "physical-sos-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(root, "state.json")
        fun reopen() = WatchRecoveryStore(AtomicFile(file))
        try {
            val first = reopen()
            val id = first.begin(System.currentTimeMillis())
            val restarted = reopen()
            assertEquals(id, restarted.state.value.startedAt)
            assertThrows(IllegalStateException::class.java) { restarted.begin(id + 1) }
            assertEquals(id, restarted.resume(id.toString()))
            first.reserveReply("synthetic", "owned-test-peer", "Test reply")
            val uncertain = reopen()
            uncertain.reserveReply("synthetic", "other", "Must not replace original")
            assertEquals(1, uncertain.state.value.replies.size)
            assertEquals("uncertain", uncertain.state.value.replies.single().result)
            uncertain.finish()
            assertNull(reopen().state.value.startedAt)
            assertThrows(IllegalStateException::class.java) { reopen().resume(id.toString()) }
            file.writeText("corrupt")
            assertTrue(reopen().state.value.unavailable)
            assertThrows(IllegalStateException::class.java) { reopen().begin(id + 1) }
        } finally { root.deleteRecursively() }
    }
}
