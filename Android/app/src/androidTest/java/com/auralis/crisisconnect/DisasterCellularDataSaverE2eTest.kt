package com.auralis.crisisconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.connectivity.DisasterTrafficController
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit emulator-only verification of the real OS Data Saver callback and global pacing. */
@RunWith(AndroidJUnit4::class)
class DisasterCellularDataSaverE2eTest {
    @Test fun cellularDataSaverAutomaticallyActivatesPacingAndRecovers(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDisasterE2E") == "true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val manager=context.getSystemService(ConnectivityManager::class.java)
        assertTrue(manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true)
        assertFalse(DisasterTrafficController.saving.value)
        fun dataSaver(active:Boolean) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cmd netpolicy set restrict-background $active").close()
        }
        try {
            dataSaver(true)
            withTimeout(15_000) { while(!DisasterTrafficController.saving.value) delay(100) }
            assertFalse(DisasterTrafficController.allowsText(201,"typing"))
            DisasterTrafficController.awaitSendSlot(false)
            val start=SystemClock.elapsedRealtime()
            DisasterTrafficController.awaitSendSlot(false)
            assertTrue("Ordinary cellular messages are paced at least five seconds",SystemClock.elapsedRealtime()-start>=5_000)
            val sosStart=SystemClock.elapsedRealtime()
            DisasterTrafficController.awaitSendSlot(true)
            assertTrue("Urgent SOS bypasses the ordinary text wait",SystemClock.elapsedRealtime()-sosStart<1_500)
        } finally { dataSaver(false) }
        assertTrue("Leaving restricted mode retains a recovery grace",DisasterTrafficController.saving.value)
        withTimeout(85_000) { while(DisasterTrafficController.saving.value) delay(500) }
        assertFalse(DisasterTrafficController.saving.value)
    }
}
