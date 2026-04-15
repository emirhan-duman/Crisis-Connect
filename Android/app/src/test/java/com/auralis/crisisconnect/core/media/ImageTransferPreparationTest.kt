package com.auralis.crisisconnect.core.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.auralis.crisisconnect.service.BleImagePayload
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageTransferPreparationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `ble profile normalizes to jpeg and clamps dimensions`() {
        val source = createSourceImage(width = 2200, height = 1700, format = Bitmap.CompressFormat.PNG)

        val prepared = prepareImageAttachmentForTransfer(
            context = context,
            uuid = "ble-image-1",
            uri = Uri.fromFile(source),
            mimeType = "image/png",
            fallbackWidth = 2200,
            fallbackHeight = 1700,
            profile = BLE_IMAGE_TRANSFER_PROFILE
        )

        assertNotNull(prepared)
        prepared ?: return
        assertTrue(prepared.mimeType == IMAGE_MIME_JPEG)
        assertTrue((prepared.width ?: 0) <= 1280)
        assertTrue((prepared.height ?: 0) <= 1280)
        assertTrue(prepared.bytes.size <= BleImagePayload.MAX_OUTGOING_TOTAL_BYTES)
        assertTrue(prepared.fileName.endsWith(".jpg"))
    }

    @Test
    fun `preparation rejects payloads above configured maximum`() {
        val source = createSourceImage(width = 1600, height = 1200, format = Bitmap.CompressFormat.PNG)
        val tinyBudgetProfile = BLE_IMAGE_TRANSFER_PROFILE.copy(
            targetBytes = 50_000,
            maxOutputBytes = 32
        )

        val prepared = prepareImageAttachmentForTransfer(
            context = context,
            uuid = "ble-image-2",
            uri = Uri.fromFile(source),
            mimeType = "image/png",
            fallbackWidth = 1600,
            fallbackHeight = 1200,
            profile = tinyBudgetProfile
        )

        assertNull(prepared)
    }

    private fun createSourceImage(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val red = (x * 31 + y * 17) and 0xFF
                val green = (x * 13 + y * 29) and 0xFF
                val blue = (x * 7 + y * 19) and 0xFF
                bitmap.setPixel(x, y, android.graphics.Color.rgb(red, green, blue))
            }
        }
        val file = File.createTempFile("img-prep-", if (format == Bitmap.CompressFormat.PNG) ".png" else ".jpg", context.cacheDir)
        FileOutputStream(file).use { output ->
            bitmap.compress(format, 100, output)
        }
        bitmap.recycle()
        return file
    }
}
