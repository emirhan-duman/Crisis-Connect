package com.auralis.crisisconnect.messaging

/**
 * Sending is safe once every bound account has at least one authenticated device in the live MLS
 * roster and this exact local leaf is a member. Extra registered devices are non-blocking until
 * their own KeyPackage is committed; otherwise one stale browser/device can deadlock all clients.
 */
internal fun isAuthorityMlsRosterReady(
    participants: List<String>,
    directory: List<AuthorityMlsDirectoryRecord>,
    rosterCredentials: List<String>,
    localCredential: String,
): Boolean {
    if (participants.size < 2 || rosterCredentials.size < participants.size) return false
    val participantSet = participants.toSet()
    if (participantSet.size != participants.size) return false

    val ownerByCredential = LinkedHashMap<String, String>()
    for (record in directory) {
        if (record.uid !in participantSet || record.credential.isBlank() ||
            ownerByCredential.put(record.credential, record.uid) != null) {
            return false
        }
    }

    val rosterSet = rosterCredentials.toSet()
    if (rosterSet.size != rosterCredentials.size || localCredential !in rosterSet) return false
    val represented = LinkedHashSet<String>()
    for (credential in rosterCredentials) {
        val owner = ownerByCredential[credential] ?: return false
        represented += owner
    }
    return participants.all(represented::contains)
}
