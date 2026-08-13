package com.auralis.crisisconnect.screens.Tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbTrailMathTest {
    private fun point(lat: Double, lng: Double, accuracy: Float = 5f) = BreadcrumbPoint(
        latitude = lat,
        longitude = lng,
        altitudeMeters = null,
        accuracyMeters = accuracy,
        timestampMillis = 0L,
    )

    @Test
    fun `distance and bearing are geographically plausible`() {
        val istanbul = point(41.0082, 28.9784)
        val north = point(41.0172, 28.9784)

        assertTrue(BreadcrumbTrailMath.distanceMeters(istanbul, north) in 995.0..1_010.0)
        assertTrue(BreadcrumbTrailMath.bearingDegrees(istanbul, north) in 359.0..360.0 ||
            BreadcrumbTrailMath.bearingDegrees(istanbul, north) in 0.0..1.0)
    }

    @Test
    fun `return cursor walks breadcrumbs in reverse`() {
        val points = listOf(
            point(41.0000, 29.0000),
            point(41.0002, 29.0000),
            point(41.0004, 29.0000),
            point(41.0006, 29.0000),
        )

        val initial = BreadcrumbTrailMath.initialReturnCursor(points, destinationIndex = 0)
        assertEquals(2, initial)
        val advanced = BreadcrumbTrailMath.advanceReturnCursor(
            points = points,
            current = point(41.0004, 29.0000),
            cursor = initial!!,
            destinationIndex = 0,
        )
        assertEquals(1, advanced)
    }

    @Test
    fun `remaining route follows recorded geometry`() {
        val points = listOf(
            point(41.0000, 29.0000),
            point(41.0003, 29.0000),
            point(41.0006, 29.0000),
        )
        val remaining = BreadcrumbTrailMath.remainingRouteDistance(
            points = points,
            current = points.last(),
            cursor = 1,
            destinationIndex = 0,
        )
        assertTrue(remaining in 60.0..70.0)
    }
}
