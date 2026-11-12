package com.auralis.crisisconnect.service

import android.Manifest
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.service.p2p.P2pGattServerService
import com.auralis.crisisconnect.service.p2p.P2pPrivacyWire
import com.auralis.crisisconnect.service.p2p.call.P2pCallController
import com.auralis.crisisconnect.service.CallState
import com.auralis.crisisconnect.verification.ContactPickerVerificationActivity
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in two-device test. Supply a fresh synthetic QR key to the peer's production verifier.
 * Uses only the separate internal app; never reads the phone's address book or sends SOS.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalBlePairingInstrumentedTest {
    @Test fun freshQrIsConfirmedByPhysicalPeerAndStoredWithEligibleKey() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires an explicitly coordinated physical peer", args.getString("physicalBle") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Never use the store package for this test", context.packageName.endsWith(".internal"))
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val keyBase64 = requireNotNull(args.getString("physicalBleKey"))
        val key = Base64.getDecoder().decode(keyBase64)
        assertEquals(32, key.size)
        val share = requireNotNull(args.getString("physicalBleShare"))
        val code = requireNotNull(args.getString("physicalBleCode"))
        val peerName = requireNotNull(args.getString("physicalBlePeerName"))
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE) + if (args.getString("physicalBleVoice") == "1")
                listOf(Manifest.permission.RECORD_AUDIO) else emptyList()) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        ActivityScenario.launch(ContactPickerVerificationActivity::class.java).use {
            val dao = AppDatabase.getInstance(context).contactDao()
            assertFalse(withContext(Dispatchers.IO) { dao.getContacts().any { it.name == peerName } })
            try {
                P2pGattServerService.startPublishing(context, share, code, "CC Hardware Android", keyBase64)
                val session = withTimeout(15_000) {
                    P2pGattServerService.publishedSession.first { it?.sessionCode == code }!!
                }
                assertFalse(session.bootstrapPayload.toString(Charsets.UTF_8).contains(code))
                assertNotNull(P2pPrivacyWire.open(session.bootstrapPayload, key, P2pPrivacyWire.Purpose.BOOTSTRAP))
                assertNull(P2pPrivacyWire.open(session.bootstrapPayload, ByteArray(32), P2pPrivacyWire.Purpose.BOOTSTRAP))
                Log.i("CCPhysicalBleTest", "READY: physical GATT host has registered the synthetic QR session")
                val peer = withTimeout(300_000) {
                    dao.observeContacts().first { rows -> rows.any { it.name == peerName } }.first { it.name == peerName }
                }
                assertTrue(peer.verified)
                assertEquals(2, peer.offlineKeyVersion)
                assertEquals(keyBase64, peer.aesKey)
                assertEquals("ios", peer.remotePlatform)
                assertTrue(peer.remoteDeviceId.isNotBlank())
                Log.i("CCPhysicalBleTest", "PASS: production radio handshake durably committed an authenticated iOS peer")
                args.getString("physicalBleMessageId")?.let { messageId ->
                    val messages = AppDatabase.getInstance(context).messageDao()
                    val received = withTimeout(60_000) {
                        messages.getMessagesForSession(peer.sessionCode)
                            .first { rows -> rows.any { it.messageUuid == messageId } }
                            .first { it.messageUuid == messageId }
                    }
                    assertFalse(received.isLocal)
                    assertEquals("CC Hardware encrypted message", received.text)
                    Log.i("CCPhysicalBleTest", "PASS: production receiver stored the physical peer's encrypted message")
                }
                if (args.getString("physicalBleVoice") == "1") {
                    val calls = P2pCallController.shared(context)
                    assertTrue(calls.startCall(peer.sessionCode))
                    withTimeout(60_000) { calls.calls.first { it[peer.sessionCode]?.state == CallState.InCall } }
                    Log.i("CCPhysicalBleTest", "VOICE ACTIVE: production Android call controller and microphone are running")
                    val sessionField = calls.javaClass.getDeclaredField("session").apply { isAccessible = true }
                    var received = 0L
                    var transmitted = 0L
                    repeat(75) {
                        val live = sessionField.get(calls)
                        assertNotNull("Call ended before the audio observation window", live)
                        fun counter(name: String): Long {
                            val f = live!!.javaClass.getDeclaredField(name).apply { isAccessible = true }
                            return when (val n = f.get(live)) {
                                is java.util.concurrent.atomic.AtomicInteger -> n.get().toLong()
                                is java.util.concurrent.atomic.AtomicLong -> n.get()
                                else -> error("Unexpected counter")
                            }
                        }
                        received = maxOf(received, counter("rxExpectedSeq"))
                        transmitted = maxOf(transmitted, counter("nextFrameSeq"))
                        delay(200)
                    }
                    assertTrue("No authenticated microphone audio received from iPad", received >= 20)
                    assertTrue("Android microphone produced no outgoing audio", transmitted >= 20)
                    Log.i("CCPhysicalBleTest", "PASS VOICE: authenticated audio frames rx=$received tx=$transmitted")
                    calls.endCall(peer.sessionCode)
                    withTimeout(10_000) { calls.calls.first { it.isEmpty() } }
                }
                // Give the central time to read server_finish before tearing down the real server.
                delay(3_000)
            } finally {
                P2pGattServerService.stopPublishing(context)
                context.stopService(Intent(context, P2pGattServerService::class.java))
                withContext(Dispatchers.IO) {
                    dao.getContacts().filter { it.aesKey == keyBase64 }
                        .forEach { peer ->
                            P2pCallController.shared(context).endCall(peer.sessionCode)
                            args.getString("physicalBleMessageId")?.let { messageId ->
                                AppDatabase.getInstance(context).openHelper.writableDatabase.execSQL(
                                    "DELETE FROM messages WHERE sessionCode = ? AND messageUuid = ?",
                                    arrayOf(peer.sessionCode, messageId))
                            }
                            AppDatabase.getInstance(context).openHelper.writableDatabase.execSQL(
                                "DELETE FROM call_events WHERE sessionCode = ?", arrayOf(peer.sessionCode))
                            dao.deleteBySessionCode(peer.sessionCode)
                        }
                }
            }
        }
    }
}
