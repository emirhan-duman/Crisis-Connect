package com.auralis.crisisconnect.screens.QR

import com.auralis.crisisconnect.data.DeviceError
import org.junit.Assert.assertEquals
import org.junit.Test

class QRHelpersTest {

    @Test fun legacyQrNeverQualifiesAsFreshOfflinePairing() {
        val old = QrData(sessionCode = "OLD123", aesKey = "old-device-key", platform = "android")
        org.junit.Assert.assertFalse(old.supportsSecureOfflinePairing)
        org.junit.Assert.assertTrue(old.copy(offlineProtocolVersion = 2).supportsSecureOfflinePairing)
        org.junit.Assert.assertFalse(old.copy(offlineProtocolVersion = 3).supportsSecureOfflinePairing)
    }

    @Test
    fun deviceNotFoundAfterHandshakeAttemptBecomesHandshakeFailed() {
        assertEquals(
            DeviceError.HANDSHAKE_FAILED,
            normalizeQrFailureCodeAfterHandshakeAttempt(
                code = DeviceError.DEVICE_NOT_FOUND,
                attemptedHandshake = true
            )
        )
    }

    @Test
    fun deviceNotFoundWithoutHandshakeAttemptStaysDeviceNotFound() {
        assertEquals(
            DeviceError.DEVICE_NOT_FOUND,
            normalizeQrFailureCodeAfterHandshakeAttempt(
                code = DeviceError.DEVICE_NOT_FOUND,
                attemptedHandshake = false
            )
        )
    }

    @Test
    fun otherFailureCodesArePreserved() {
        assertEquals(
            DeviceError.PAIRING_FAILED,
            normalizeQrFailureCodeAfterHandshakeAttempt(
                code = DeviceError.PAIRING_FAILED,
                attemptedHandshake = true
            )
        )
    }
}
