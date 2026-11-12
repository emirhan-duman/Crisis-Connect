package com.auralis.crisisconnect.screens.QR

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class QrPairingRecoveryTest {
    private class Disk : QrPairingJournal {
        var checkpoint: QrPairingCheckpoint? = null
        var unavailable = false
        override fun read(): QrPairingCheckpoint? {
            check(!unavailable); return checkpoint?.copy()
        }
        override fun write(checkpoint: QrPairingCheckpoint) {
            check(!unavailable); this.checkpoint = checkpoint.copy()
        }
        override fun clear() { check(!unavailable); checkpoint = null }
    }
    @Test fun newProcessRecoversSameQrAndManualNameWithoutResettingDeadline() {
        val disk = Disk()
        QrPairingRecovery(disk) { 1_000L }.begin("original-qr", "Peer", "device:account")
        val resumed = QrPairingRecovery(disk) { 3_000L }.recover("device:account")!!
        assertEquals("original-qr", resumed.raw)
        assertEquals("Peer", resumed.name)
        assertEquals(1_000L, resumed.createdAt)
        assertEquals(1, resumed.recoveries)
    }
    @Test fun committedContactReceiptSurvivesKillBeforeUiConsumesSuccess() {
        val disk = Disk()
        val first = QrPairingRecovery(disk) { 1_000L }
        val pending = first.begin("qr", null, "device:account")
        first.complete(pending, "existing-conversation")
        val reopened = QrPairingRecovery(disk) { 2_000L }
        assertEquals("existing-conversation", reopened.recover("device:account")!!.savedSession)
        reopened.clear()
        assertNull(QrPairingRecovery(disk) { 3_000L }.recover("device:account"))
    }
    @Test fun killBetweenContactCommitAndReceiptKeepsOriginalIntentForIdentityLookup() {
        val disk = Disk()
        QrPairingRecovery(disk) { 1_000L }.begin("same-identity-and-key", null, "owner")
        // Contact DB commit happened, terminal journal update did not. The reopened engine gets
        // the original identity for its pre-handshake DB lookup, rather than creating another QR.
        val resumed = QrPairingRecovery(disk) { 1_500L }.recover("owner")!!
        assertNull(resumed.savedSession)
        assertEquals("same-identity-and-key", resumed.raw)
    }
    @Test fun expiredIntentAndClockRollbackCannotRestartTransport() {
        for (time in listOf(999L, 301_000L)) {
            val disk = Disk()
            QrPairingRecovery(disk) { 1_000L }.begin("qr", null, "owner")
            assertNull(QrPairingRecovery(disk) { time }.recover("owner"))
            assertNull(disk.checkpoint)
        }
    }
    @Test fun accountOrDeviceChangeClearsPendingSecret() {
        val disk = Disk()
        QrPairingRecovery(disk) { 1_000L }.begin("qr", null, "device-a:account-a")
        assertNull(QrPairingRecovery(disk) { 2_000L }.recover("device-a:account-b"))
        assertNull(disk.checkpoint)
    }
    @Test fun repeatedKillsHaveFiniteRetries() {
        val disk = Disk()
        QrPairingRecovery(disk) { 1_000L }.begin("qr", null, "owner")
        repeat(3) { assertNotNull(QrPairingRecovery(disk) { 2_000L }.recover("owner")) }
        assertNull(QrPairingRecovery(disk) { 2_000L }.recover("owner"))
    }
    @Test fun unavailableKeystoreDoesNotDestroyIntentAndCanRecoverLater() {
        val disk = Disk()
        QrPairingRecovery(disk) { 1_000L }.begin("qr", null, "owner")
        disk.unavailable = true
        assertTrue(runCatching { QrPairingRecovery(disk) { 2_000L }.recover("owner") }.isFailure)
        disk.unavailable = false
        assertNotNull(QrPairingRecovery(disk) { 2_000L }.recover("owner"))
    }
    @Test fun failedDurabilityBoundaryDoesNotReportIntentAsSaved() {
        val disk = Disk().apply { unavailable = true }
        assertTrue(runCatching { QrPairingRecovery(disk).begin("qr", null, "owner") }.isFailure)
        assertNull(disk.checkpoint)
    }
    @Test fun clearingDuringDiskCommitCannotResurrectAnAcknowledgedOperation() {
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val clearing = CountDownLatch(1)
        val disk = Disk()
        val blockedDisk = object : QrPairingJournal {
            override fun read() = disk.read()
            override fun write(checkpoint: QrPairingCheckpoint) {
                enteredWrite.countDown()
                check(releaseWrite.await(2, TimeUnit.SECONDS))
                disk.write(checkpoint)
            }
            override fun clear() = disk.clear()
        }
        val owner = QrPairingRecovery(blockedDisk) { 1_000L }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val write = workers.submit { owner.begin("qr", null, "owner") }
            assertTrue(enteredWrite.await(2, TimeUnit.SECONDS))
            val clear = workers.submit { clearing.countDown(); owner.clear() }
            assertTrue(clearing.await(2, TimeUnit.SECONDS))
            try { clear.get(50, TimeUnit.MILLISECONDS); fail("Clear raced ahead of the durable write") }
            catch (_: TimeoutException) { /* Clear waits until the in-flight commit completes. */ }
            releaseWrite.countDown()
            write.get(2, TimeUnit.SECONDS)
            clear.get(2, TimeUnit.SECONDS)
            assertNull(disk.checkpoint)
        } finally { releaseWrite.countDown(); workers.shutdownNow() }
    }
}
