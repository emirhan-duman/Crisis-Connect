package com.auralis.crisisconnect.messaging
import org.junit.Assert.*
import org.junit.Test

class ReceiptBatchesTest {
    @Test fun sixLongIdsFitCompactBudgetAndBacklogDrainsWithoutDroppingIds() {
        var batches = ReceiptBatches.enqueue(emptyList(),ReceiptBatch("peer",204,(0..30).map { "a".repeat(124) + it.toString() }))
        val seen = mutableSetOf<String>()
        while (batches.isNotEmpty()) {
            val next = ReceiptBatches.next(batches)!!
            assertTrue(next.ids.size <= 6)
            assertTrue(next.ids.sumOf { it.length + 3 } + 9 < 1024)
            seen.addAll(next.ids)
            batches = ReceiptBatches.confirm(batches,next)
        }
        assertEquals(31,seen.size)
    }
    @Test fun confirmationKeepsConcurrentAddsAndOtherPeersAndReadStatus() {
        var batches = ReceiptBatches.enqueue(emptyList(),ReceiptBatch("a",204,listOf("old")))
        val snapshot = ReceiptBatches.next(batches)!!
        batches = ReceiptBatches.enqueue(batches,ReceiptBatch("a",204,listOf("new")))
        batches = ReceiptBatches.enqueue(batches,ReceiptBatch("a",205,listOf("old")))
        batches = ReceiptBatches.enqueue(batches,ReceiptBatch("b",204,listOf("old")))
        val result = ReceiptBatches.confirm(batches,snapshot)
        assertEquals(listOf("new"),result.first().ids)
        assertEquals(3,result.size)
    }
    @Test fun invalidOrOversizedIdsNeverEnterJournal() {
        for (id in listOf("bad/id","ğ", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { ReceiptBatches.enqueue(emptyList(),ReceiptBatch("a",204,listOf(id))) }
        }
    }
}
