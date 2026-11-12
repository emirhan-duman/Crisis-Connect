package com.auralis.crisisconnect.core.media

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalMediaAdaptersTest {
    private lateinit var context: Context
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LocalMedia.initialize(context) { SecretKeySpec(ByteArray(32) { 6 }, "AES") }
    }
    @Test fun playerDataSourceSupportsSeekingWithoutPlaintextDiskCopy() {
        val file = File(context.filesDir, "voice_messages/seek-test.m4a")
        LocalMedia.write(file, "0123456789".toByteArray())
        val source = LocalMediaDataSourceFactory(context).createDataSource()
        assertEquals(4L, source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(3).setLength(4).build()))
        val bytes = ByteArray(8)
        assertEquals(4, source.read(bytes, 0, bytes.size)); assertEquals("3456", bytes.copyOf(4).toString(Charsets.UTF_8))
        assertEquals(-1, source.read(bytes, 0, 1)); source.close()
        assertTrue(file.readText().startsWith("CCMEDIA1"))
    }
    @Test fun metadataSourceSupportsRandomAccessAndEof() {
        val file = File(context.filesDir, "voice_messages/metadata-test.m4a")
        LocalMedia.write(file, "0123456789".toByteArray())
        val source = LocalMedia.dataSource(file); val bytes = ByteArray(4)
        assertEquals(10L, source.size)
        assertEquals(3, source.readAt(7, bytes, 0, 4)); assertEquals("789", bytes.copyOf(3).toString(Charsets.UTF_8))
        assertEquals(4, source.readAt(1, bytes, 0, 4)); assertEquals("1234", bytes.toString(Charsets.UTF_8))
        assertEquals(-1, source.readAt(10, bytes, 0, 1)); source.close()
    }
    @Test fun explicitExportIsReadableAndExpiredExportsAreRemoved() {
        val file = File(context.filesDir, "image_messages/export-test.png"); LocalMedia.write(file, "image".toByteArray())
        val exported = LocalMedia.export(context, file)
        assertEquals("image", exported.readText()); assertNotEquals(file, exported)
        assertTrue(file.readText().startsWith("CCMEDIA1"))
        exported.setLastModified(System.currentTimeMillis() - 16 * 60_000L)
        LocalMedia.cleanExports(context); assertFalse(exported.exists())
    }
    @Test fun unmanagedRecorderInputRemainsCompatibleWithPlayer() {
        val file = File(context.cacheDir, "recording.m4a").apply { writeText("recording") }
        val source = LocalMediaDataSourceFactory(context).createDataSource()
        assertEquals(9L, source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).build()))
        val bytes = ByteArray(9); assertEquals(9, source.read(bytes, 0, 9)); source.close()
        assertEquals("recording", bytes.toString(Charsets.UTF_8))
    }
}
