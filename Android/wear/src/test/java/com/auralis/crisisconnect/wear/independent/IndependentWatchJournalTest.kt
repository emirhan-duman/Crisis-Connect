package com.auralis.crisisconnect.wear.independent

import org.junit.Assert.*
import org.junit.Test

class IndependentWatchJournalTest {
    private class Disk : IndependentJournalStorage {
        var saved = emptyList<IndependentOperation>()
        var fail = false
        override fun read() = saved
        override fun write(operations: List<IndependentOperation>) { check(!fail); saved = operations }
    }
    @Test fun reopenDoesNotTurnUncertainMessageIntoAnotherSend() {
        val disk = Disk()
        val first = IndependentWatchJournal(disk).reserve("shortMessage", "contact", "ciphertext")
        val reopened = IndependentWatchJournal(disk)
        assertEquals(first, reopened.snapshot().single())
        assertThrows(IllegalArgumentException::class.java) { reopened.reserve("shortMessage", "contact", "different") }
        reopened.reconcile(first.id, "delivered")
        reopened.reconcile(first.id, "accepted")
        assertEquals("delivered", IndependentWatchJournal(disk).snapshot().single().state)
    }
    @Test fun unresolvedOperationDoesNotAdvanceEncryptionAgain() {
        val journal = IndependentWatchJournal(Disk())
        journal.reserve("shortMessage", "contact", "ciphertext")
        var prepared = false
        assertThrows(IllegalArgumentException::class.java) {
            journal.reservePrepared("shortMessage", "contact") { prepared = true; "new ciphertext" }
        }
        assertFalse(prepared)
    }
    @Test fun twoServiceInstancesCannotOverwriteOneAnothersHistory() {
        val disk = Disk(); val first = IndependentWatchJournal(disk); val second = IndependentWatchJournal(disk)
        val a = first.reserve("shortMessage", "a", "first")
        val b = second.reserve("shortMessage", "b", "second")
        first.reconcile(a.id, "delivered")
        assertEquals(listOf(a.id, b.id), IndependentWatchJournal(disk).snapshot().map { it.id })
        assertThrows(IllegalArgumentException::class.java) { first.reserve("shortMessage", "b", "duplicate") }
    }
    @Test fun stopAndReopenPreserveOriginalSOSWithoutStartingAnything() {
        val disk = Disk(); val journal = IndependentWatchJournal(disk)
        val start = journal.reserve("startSOS", "session", "start")
        journal.reconcile(start.id, "accepted")
        assertThrows(IllegalArgumentException::class.java) { journal.reserve("startSOS", "other", "start") }
        val stop = journal.reserve("stopSOS", "session", "stop")
        journal.reconcile(stop.id, "accepted")
        val reopened = IndependentWatchJournal(disk).snapshot()
        assertEquals(listOf(start.id, stop.id), reopened.map { it.id })
        assertEquals(listOf("session", "session"), reopened.map { it.subjectID })
        assertTrue(reopened.all { it.state == "accepted" })
        assertThrows(IllegalArgumentException::class.java) { IndependentWatchJournal(disk).reserve("startSOS", "session", "start") }
    }
    @Test fun failedCommitCannotYieldSendableOperation() {
        val disk = Disk(); val journal = IndependentWatchJournal(disk); disk.fail = true
        assertThrows(IllegalStateException::class.java) { journal.reserve("startSOS", "session", "start") }
        assertTrue(journal.snapshot().isEmpty())
    }
    @Test fun rejectUnknownReceiptsAndCorruptHistory() {
        val disk = Disk(); val journal = IndependentWatchJournal(disk)
        assertThrows(IllegalStateException::class.java) { journal.reconcile("unknown", "accepted") }
        val entry = journal.reserve("shortMessage", "contact", "sealed")
        disk.saved = listOf(entry, entry)
        assertThrows(IllegalArgumentException::class.java) { IndependentWatchJournal(disk) }
    }
}
