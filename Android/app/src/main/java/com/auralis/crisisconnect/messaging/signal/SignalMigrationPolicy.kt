package com.auralis.crisisconnect.messaging.signal

/** A durable authenticated identity, rather than a disposable ratchet session, pins the protocol. */
object SignalMigrationPolicy {
    fun permitsApplication(isSignal: Boolean, hasAuthenticatedBinding: Boolean, identityChanged: Boolean): Boolean =
        !identityChanged && (isSignal || !hasAuthenticatedBinding)

    fun permitsBindingControl(requestReply: Boolean, alreadyBound: Boolean, cohortEnabled: Boolean): Boolean =
        alreadyBound || (requestReply && cohortEnabled)
}
