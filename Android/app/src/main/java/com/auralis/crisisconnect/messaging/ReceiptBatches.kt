package com.auralis.crisisconnect.messaging

data class ReceiptBatch(val peer: String, val template: Int, val ids: List<String>)
/** Six maximum-length IDs fit in the 1024-byte encrypted receipt budget. */
object ReceiptBatches {
    fun enqueue(existing: List<ReceiptBatch>, batch: ReceiptBatch): List<ReceiptBatch> {
        require(batch.template in setOf(204, 205) && batch.peer.isNotBlank())
        require(batch.ids.all { it.length in 1..128 && it.all { c -> c.code < 128 && (c.isLetterOrDigit() || c in "._:~-") } })
        val result = existing.toMutableList()
        val index = result.indexOfFirst { it.peer == batch.peer && it.template == batch.template }
        if (index < 0) result.add(batch.copy(ids = batch.ids.distinct()))
        else result[index] = batch.copy(ids = (result[index].ids + batch.ids).distinct())
        check(result.sumOf { it.ids.size } <= 4096) { "Receipt journal capacity reached" }
        return result
    }
    fun next(existing: List<ReceiptBatch>): ReceiptBatch? = existing.firstOrNull()?.let { it.copy(ids = it.ids.take(6)) }
    fun confirm(existing: List<ReceiptBatch>, sent: ReceiptBatch): List<ReceiptBatch> = existing.map {
        if (it.peer == sent.peer && it.template == sent.template) it.copy(ids = it.ids - sent.ids.toSet()) else it
    }.filter { it.ids.isNotEmpty() }
}
