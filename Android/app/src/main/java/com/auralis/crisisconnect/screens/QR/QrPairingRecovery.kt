package com.auralis.crisisconnect.screens.QR

internal data class QrPairingCheckpoint(
    val raw: String,
    val name: String?,
    val owner: String,
    val createdAt: Long,
    val recoveries: Int = 0,
    val savedSession: String? = null
) {
    fun canResume(owner: String, now: Long): Boolean = this.owner == owner &&
        now >= createdAt && now - createdAt < 300_000L && recoveries < 3
}

internal interface QrPairingJournal {
    fun read(): QrPairingCheckpoint?
    fun write(checkpoint: QrPairingCheckpoint)
    fun clear()
}

/** Small write-ahead journal. No transport starts before the intent is durable. */
internal class QrPairingRecovery(private val journal: QrPairingJournal, private val now: () -> Long = System::currentTimeMillis) {
    @Synchronized fun begin(raw: String, name: String?, owner: String): QrPairingCheckpoint {
        require(raw.length <= 65_536)
        return QrPairingCheckpoint(raw, name, owner, now()).also(journal::write)
    }
    @Synchronized fun recover(owner: String): QrPairingCheckpoint? {
        val checkpoint = journal.read() ?: return null
        if (!checkpoint.canResume(owner, now())) { journal.clear(); return null }
        return checkpoint.copy(recoveries = checkpoint.recoveries + 1).also(journal::write)
    }
    @Synchronized fun complete(checkpoint: QrPairingCheckpoint, session: String) {
        journal.write(checkpoint.copy(savedSession = session))
    }
    @Synchronized fun clear() = journal.clear()
}
