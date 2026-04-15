package com.auralis.crisisconnect.screens.Chat

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenOrientationTest {

    @Test
    fun `resolveNavigationHeadingDegrees uses screen top axis when device is flat`() {
        val heading = resolveNavigationHeadingDegrees(
            rotationMatrix = identityMatrix(),
            displayRotation = Surface.ROTATION_0
        )

        assertEquals(0f, heading ?: 0f, 0.001f)
    }

    @Test
    fun `resolveNavigationHeadingDegrees follows portrait screen top when flat heading is east`() {
        val heading = resolveNavigationHeadingDegrees(
            rotationMatrix = matrixOf(
                0f, 1f, 0f,
                -1f, 0f, 0f,
                0f, 0f, 1f
            ),
            displayRotation = Surface.ROTATION_0
        )

        assertEquals(90f, heading ?: 0f, 0.001f)
    }

    @Test
    fun `resolveNavigationHeadingDegrees respects display rotation for flat landscape usage`() {
        val heading = resolveNavigationHeadingDegrees(
            rotationMatrix = matrixOf(
                0f, 1f, 0f,
                -1f, 0f, 0f,
                0f, 0f, 1f
            ),
            displayRotation = Surface.ROTATION_90
        )

        assertEquals(0f, heading ?: 0f, 0.001f)
    }

    @Test
    fun `resolveNavigationHeadingDegrees uses forward axis when phone is upright facing north`() {
        val heading = resolveNavigationHeadingDegrees(
            rotationMatrix = matrixOf(
                1f, 0f, 0f,
                0f, 0f, -1f,
                0f, 1f, 0f
            ),
            displayRotation = Surface.ROTATION_0
        )

        assertEquals(0f, heading ?: 0f, 0.001f)
    }

    @Test
    fun `resolveNavigationHeadingDegrees uses forward axis when phone is upright facing east`() {
        val heading = resolveNavigationHeadingDegrees(
            rotationMatrix = matrixOf(
                0f, 0f, -1f,
                -1f, 0f, 0f,
                0f, 1f, 0f
            ),
            displayRotation = Surface.ROTATION_0
        )

        assertEquals(90f, heading ?: 0f, 0.001f)
    }

    @Test
    fun `resolveNavigationHeadingDegrees applies declination adjustment`() {
        val heading = resolveNavigationHeadingDegrees(
            rotationMatrix = identityMatrix(),
            displayRotation = Surface.ROTATION_0,
            declinationDegrees = 12f
        )

        assertEquals(12f, heading ?: 0f, 0.001f)
    }

    @Test
    fun `resolveTiltFromFlatDegrees returns expected extremes`() {
        assertEquals(0f, resolveTiltFromFlatDegrees(identityMatrix()), 0.001f)
        assertEquals(
            90f,
            resolveTiltFromFlatDegrees(
                matrixOf(
                    1f, 0f, 0f,
                    0f, 0f, -1f,
                    0f, 1f, 0f
                )
            ),
            0.001f
        )
    }

    private fun identityMatrix(): FloatArray = matrixOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    private fun matrixOf(
        m00: Float,
        m01: Float,
        m02: Float,
        m10: Float,
        m11: Float,
        m12: Float,
        m20: Float,
        m21: Float,
        m22: Float
    ): FloatArray = floatArrayOf(
        m00, m01, m02,
        m10, m11, m12,
        m20, m21, m22
    )
}
