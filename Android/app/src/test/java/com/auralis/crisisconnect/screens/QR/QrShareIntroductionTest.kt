package com.auralis.crisisconnect.screens.QR

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QrShareIntroductionTest {
    private val now = 1000000L
    private fun checkpoint(owner: String = "local:account", deadline: Long = now + 300000) =
        JSONObject().put("owner", owner).put("expiresAt", deadline).put("key", "qr-capability").toString()

    @Test fun liveQrFromThisAccountCanIntroduceTheScanner() {
        assertEquals("qr-capability", qrIntroductionKey(checkpoint(), "local:account", now))
    }
    @Test fun expiredOrClosedQrCannotIntroduceAnUnknownContact() {
        assertNull(qrIntroductionKey(checkpoint(deadline = now), "local:account", now))
        assertNull(qrIntroductionKey("", "local:account", now))
    }
    @Test fun accountSwitchInvalidatesPreviousQr() {
        assertNull(qrIntroductionKey(checkpoint(), "local:another-account", now))
    }
    @Test fun invalidOrUnboundedCheckpointIsRejected() {
        assertNull(qrIntroductionKey("corrupt", "local:account", now))
        assertNull(qrIntroductionKey(checkpoint(deadline = now + 300001), "local:account", now))
    }
}
