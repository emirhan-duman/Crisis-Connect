package com.auralis.crisisconnect.data.local

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileImageStorageTest {

    @Test
    fun `normalizeBitmapForStorage scales oversized bitmap down to safe size`() {
        val source = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)

        val normalized = ProfileImageStorage.normalizeBitmapForStorage(source)

        assertEquals(512, normalized.width)
        assertEquals(384, normalized.height)
    }

    @Test
    fun `decodeBitmapForStorage downsamples oversized legacy payload`() {
        val source = Bitmap.createBitmap(4096, 3072, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.JPEG, 95, output)
            output.toByteArray()
        }

        val decoded = ProfileImageStorage.decodeBitmapForStorage(encoded)

        assertNotNull(decoded)
        decoded ?: return
        assertTrue(decoded.width <= 512)
        assertTrue(decoded.height <= 512)
        assertEquals(512, decoded.width)
        assertEquals(384, decoded.height)
    }
}
