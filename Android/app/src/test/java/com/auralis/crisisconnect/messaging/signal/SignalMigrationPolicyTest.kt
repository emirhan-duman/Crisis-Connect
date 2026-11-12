package com.auralis.crisisconnect.messaging.signal

import org.junit.Assert.*
import org.junit.Test

class SignalMigrationPolicyTest {
    @Test fun legacyApplicationIsAllowedOnlyBeforeAnAuthenticatedUpgrade() {
        assertTrue(SignalMigrationPolicy.permitsApplication(false, false, false))
        // The ratchet may have disappeared; the persisted authenticated binding still rejects
        // legacy text, delivery/read receipts, typing and call controls before side effects.
        assertFalse(SignalMigrationPolicy.permitsApplication(false, true, false))
        assertTrue(SignalMigrationPolicy.permitsApplication(true, true, false))
        assertFalse(SignalMigrationPolicy.permitsApplication(true, true, true))
        assertFalse(SignalMigrationPolicy.permitsApplication(false, false, true))
    }
    @Test fun unknownLegacyPeersNeverReceiveNewControlBubblesOrImplicitReplies() {
        assertFalse(SignalMigrationPolicy.permitsBindingControl(true, false, false))
        assertFalse(SignalMigrationPolicy.permitsBindingControl(false, false, true))
        assertTrue(SignalMigrationPolicy.permitsBindingControl(true, false, true))
        assertTrue(SignalMigrationPolicy.permitsBindingControl(false, true, false))
        assertTrue(SignalMigrationPolicy.permitsBindingControl(true, true, false))
    }
}
