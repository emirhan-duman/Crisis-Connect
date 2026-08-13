package com.auralis.crisisconnect

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.crisisconnect.messaging.call.sfu.MlsWorker
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the exact Android JNI export/close/import path used by AuthorityChat. */
@RunWith(AndroidJUnit4::class)
class AuthorityMlsNativePersistenceTest {
    @Test
    fun exportedContextCanBeImportedAfterClose() {
        assertTrue("MLS native worker did not load", MlsWorker.available)
        val contextId = "authority-mls:test:${UUID.randomUUID()}"
        val credential = "test-account/test-device"

        try {
            MlsWorker.nativePersistentNewStateAndCreateGroup(contextId, credential)
            val identityBefore = MlsWorker.nativePersistentIdentity(contextId)
            val snapshot = MlsWorker.nativePersistentExportState(contextId)
            assertNotNull("Native MLS export returned null", snapshot)
            assertTrue("Native MLS close failed", MlsWorker.nativePersistentClose(contextId))
            assertTrue(
                "Native MLS rejected the snapshot it just exported",
                MlsWorker.nativePersistentImportState(contextId, snapshot!!),
            )
            assertEquals(identityBefore, MlsWorker.nativePersistentIdentity(contextId))
        } finally {
            MlsWorker.nativePersistentClose(contextId)
        }
    }
}
