package com.auralis.crisisconnect.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsMainScreenViewModelTest {

    @Test
    fun `metal detector hidden when magnetometer is missing`() {
        val capabilities = ToolDeviceCapabilities(
            hasRotationVector = false,
            hasGameRotationVector = false,
            hasAccelerometer = true,
            hasMagnetometer = false
        )

        assertFalse(isToolSupported("metal_detector", capabilities))
    }

    @Test
    fun `compass shown when rotation vector sensor is present`() {
        val capabilities = ToolDeviceCapabilities(
            hasRotationVector = true,
            hasGameRotationVector = false,
            hasAccelerometer = false,
            hasMagnetometer = false
        )

        assertTrue(isToolSupported("compass", capabilities))
    }

    @Test
    fun `compass hidden when both rotation vector and accel magnetometer fallback are missing`() {
        val capabilities = ToolDeviceCapabilities(
            hasRotationVector = false,
            hasGameRotationVector = false,
            hasAccelerometer = true,
            hasMagnetometer = false
        )

        assertFalse(isToolSupported("compass", capabilities))
    }

    @Test
    fun `crisis sentinel shown without hardware sensors`() {
        val capabilities = ToolDeviceCapabilities(
            hasRotationVector = false,
            hasGameRotationVector = false,
            hasAccelerometer = false,
            hasMagnetometer = false
        )

        assertTrue(isToolSupported("crisis_sentinel", capabilities))
    }
}
