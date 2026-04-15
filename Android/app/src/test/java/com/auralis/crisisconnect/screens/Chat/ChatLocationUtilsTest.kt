package com.auralis.crisisconnect.screens.Chat

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLocationUtilsTest {

    @Test
    fun `parseSharedLocationPayload parses inline payload when wrapper text precedes prefix`() {
        val payload = parseSharedLocationPayload(
            "Paylasilan konum: CC_LOC:41.008200,28.978400,12.0,1710000000000,18.0,gps"
        )

        assertNotNull(payload)
        payload ?: return
        assertEquals(41.0082, payload.latitude, 0.000001)
        assertEquals(28.9784, payload.longitude, 0.000001)
        assertEquals(12.0f, payload.accuracyMeters ?: 0f, 0.001f)
        assertEquals(1710000000000L, payload.timestampMillis)
        assertEquals(18.0f, payload.confidenceRadiusMeters ?: 0f, 0.001f)
        assertEquals(LOCATION_SOURCE_GPS, payload.source)
    }

    @Test
    fun `parseSharedLocationPayload parses payload from a later message line`() {
        val payload = parseSharedLocationPayload(
            "Konum paylasildi\nCC_LOC:40.992100,29.124500,7.5,1710000000456,10.0,last_known"
        )

        assertNotNull(payload)
        payload ?: return
        assertEquals(40.9921, payload.latitude, 0.000001)
        assertEquals(29.1245, payload.longitude, 0.000001)
        assertEquals(LOCATION_SOURCE_LAST_KNOWN, payload.source)
    }

    @Test
    fun `resolveHeadingForNavigation returns null without sensor heading`() {
        val resolved = resolveHeadingForNavigation(sensorHeadingDegrees = null)

        assertNull(resolved)
    }

    @Test
    fun `resolveHeadingForNavigation normalizes valid sensor heading`() {
        val resolved = resolveHeadingForNavigation(sensorHeadingDegrees = 725f)

        assertEquals(5f, resolved ?: 0f, 0.001f)
    }

    @Test
    fun `comparisonCameraSpanMeters zooms tighter for very close points`() {
        val span = comparisonCameraSpanMeters(
            distanceMeters = 2.0,
            ownAccuracyMeters = 8.0,
            sharedAccuracyMeters = 8.0,
            cameraBearingDegrees = 0.0
        )

        assertTrue(span < 20.0)
    }

    @Test
    fun `markerScaleForComparisonDistance compacts markers when very close`() {
        assertEquals(0.68f, markerScaleForComparisonDistance(4.0), 0.001f)
        assertEquals(0.82f, markerScaleForComparisonDistance(12.0), 0.001f)
        assertEquals(1f, markerScaleForComparisonDistance(30.0), 0.001f)
    }

    @Test
    fun `resolveComparisonMarkerLayout separates overlapping markers`() {
        val ownLocation = mockk<Location> {
            every { latitude } returns 41.0082
            every { longitude } returns 28.9784
        }
        val shared = SharedLocationPayload(
            latitude = 41.0082,
            longitude = 28.9784
        )

        val layout = resolveComparisonMarkerLayout(
            ownLocation = ownLocation,
            shared = shared,
            cameraBearingDegrees = 0.0
        )
        val displayDistance = calculateDistanceMeters(
            fromLatitude = layout.ownMarkerLatLng.latitude,
            fromLongitude = layout.ownMarkerLatLng.longitude,
            toLatitude = layout.sharedMarkerLatLng.latitude,
            toLongitude = layout.sharedMarkerLatLng.longitude
        )

        assertTrue(displayDistance >= 9.5)
        assertTrue(layout.cameraDistanceMeters >= 10.0)
    }
}
