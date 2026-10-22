package com.auralis.crisisconnect.wear.independent

import java.util.UUID

/** No network or timers: reopening or reconnection cannot submit a command. */
data class IndependentOperation(
    val id: String,
    val action: String,
    val subjectID: String,
    val payload: String,
    val state: String = "uncertain"
)
interface IndependentJournalStorage {
    fun read(): List<IndependentOperation>
    /** Must durably and atomically commit or throw. */
    fun write(operations: List<IndependentOperation>)
}
class IndependentWatchJournal(private val storage: IndependentJournalStorage) {
    private var poisoned = false
    private var entries = storage.read().also(::validate)
    @Synchronized fun reload() {
        check(!poisoned) { "Reload journal after a storage error" }
        entries = storage.read().also(::validate)
    }
    @Synchronized fun snapshot() = entries.toList()
    /** Explicit user action only; caller sends ONLY the newly returned operation. */
    @Synchronized fun reserve(action: String, subjectID: String, payload: String): IndependentOperation = reservePrepared(action, subjectID) { payload }
    @Synchronized fun reservePrepared(action: String, subjectID: String, prepare: (String) -> String): IndependentOperation {
        val id = UUID.randomUUID().toString()
        reload()
        require(action in setOf("shortMessage", "startSOS", "stopSOS"))
        require(subjectID.isNotBlank() && subjectID.length <= 128)
        require(entries.size < 512) { "Sync/clear confirmed history before adding commands" }
        require(entries.none { it.action == action && it.subjectID == subjectID && it.state == "uncertain" }) { "Resolve the uncertain operation first" }
        if (action == "startSOS") {
            require(entries.none { it.action == "startSOS" && (it.subjectID == subjectID ||
                (it.state != "rejected" && entries.none { stop -> stop.action == "stopSOS" && stop.subjectID == it.subjectID && stop.state == "accepted" })) }) { "Resolve the existing SOS before starting another" }
        }
        if (action == "stopSOS") {
            require(entries.any { it.action == "startSOS" && it.subjectID == subjectID && it.state != "rejected" })
            require(entries.none { it.action == "stopSOS" && it.subjectID == subjectID && it.state == "accepted" })
        }
        val payload = prepare(id)
        require(payload.toByteArray().size <= 24_576)
        val entry = IndependentOperation(id, action, subjectID, payload)
        val next = entries + entry
        persist(next) // If persistence fails, never send.
        entries = next
        return entry
    }
    @Synchronized fun reconcile(id: String, state: String) {
        reload()
        require(state in setOf("accepted", "rejected", "delivered", "read"))
        val old = entries.singleOrNull { it.id == id } ?: error("Unknown command")
        require(old.action == "shortMessage" || state in setOf("accepted", "rejected"))
        val rank = mapOf("uncertain" to 0, "accepted" to 1, "delivered" to 2, "read" to 3)
        if (old.state == "rejected" || (state == "rejected" && old.state != "uncertain")) return
        if ((rank[state] ?: 0) < (rank[old.state] ?: 0)) return
        val next = entries.map { if (it.id == id) it.copy(state = state) else it }
        persist(next)
        entries = next
    }
    private fun persist(next: List<IndependentOperation>) {
        try { storage.write(next) } catch (error: Exception) { poisoned = true; throw error }
    }
    private fun validate(rows: List<IndependentOperation>) {
        require(rows.size <= 512 && rows.map { it.id }.toSet().size == rows.size)
        rows.forEach {
            require(UUID.fromString(it.id).toString() == it.id)
            require(it.action in setOf("shortMessage", "startSOS", "stopSOS"))
            require(it.state in setOf("uncertain", "accepted", "rejected", "delivered", "read"))
            require(it.action == "shortMessage" || it.state !in setOf("delivered", "read"))
            require(it.subjectID.isNotBlank() && it.subjectID.length <= 128 && it.payload.toByteArray().size <= 24_576)
        }
    }
}
