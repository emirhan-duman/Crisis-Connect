package com.auralis.crisisconnect.security

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-level event bus for automatic rescue-certificate provisioning.
 *
 * Emits transient events that the UI can show as a floating banner. Events
 * are not buffered for late subscribers (replay=0); a banner that opens after
 * the event already fired will simply miss it, which is the correct behavior
 * for a transient notification.
 */
object CertificateProvisioningNotifier {

    sealed class Event {
        /** Provisioning has started in the background. */
        object InProgress : Event()

        /**
         * Provisioning finished successfully. The certificate is now cached
         * and usable for rescue actions.
         */
        object Success : Event()

        /**
         * Provisioning failed. [reasonCode] is a short machine-readable hint
         * derived from the throwable (e.g. "IntegrityFailed",
         * "AttestationFailed", "IssuanceFailed"), and [detail] is a short
         * user-facing snippet if available.
         */
        data class Failure(val reasonCode: String, val detail: String?) : Event()
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun emitInProgress() {
        _events.tryEmit(Event.InProgress)
    }

    fun emitSuccess() {
        _events.tryEmit(Event.Success)
    }

    fun emitFailure(throwable: Throwable) {
        val reasonCode = throwable::class.java.simpleName
            .removeSuffix("Exception")
            .ifBlank { "Error" }
        val detail = throwable.message?.trim()?.take(160)
        _events.tryEmit(Event.Failure(reasonCode, detail))
    }
}
