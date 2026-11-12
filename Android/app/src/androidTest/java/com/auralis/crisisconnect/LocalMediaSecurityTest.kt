package com.auralis.crisisconnect

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auralis.crisisconnect.core.media.LocalMedia
import com.auralis.crisisconnect.core.media.LocalMediaDataSourceFactory
import com.auralis.crisisconnect.core.media.localMediaImageLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalMediaSecurityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val created = mutableListOf<File>()
    private fun file(directory: String, extension: String) = File(context.filesDir, "$directory/security-${UUID.randomUUID()}.$extension").also {
        it.parentFile!!.mkdirs(); created += it
    }
    @After fun cleanup() { created.forEach { it.delete() } }

    @Test fun realKeystoreEncryptsMigratesAndRejectsTampering() {
        val file = file("voice_messages", "wav")
        file.writeText("old private attachment")
        assertEquals("old private attachment", LocalMedia.read(file).toString(Charsets.UTF_8))
        assertTrue(file.readBytes().take(8).toByteArray().contentEquals("CCMEDIA1".toByteArray()))
        assertFalse(file.readBytes().contentEquals("old private attachment".toByteArray()))
        val sealed = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(sealed)
        assertThrows(java.io.IOException::class.java) { LocalMedia.read(file) }
        assertArrayEquals(sealed, file.readBytes())
    }

    @Test fun encryptedImageDecodesAndExplicitExportWorksThroughFileProvider() = runBlocking {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val plain = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val file = file("image_messages", "png"); LocalMedia.write(file, plain)
        assertNotNull("Rescue image decoder must read protected files", LocalMedia.decodeSampledImage(file, 16, 12))
        val loader = localMediaImageLoader(context)
        try {
            assertNotNull("Unrelated remote images retain their normal cache", loader.diskCache)
            val result = loader.execute(ImageRequest.Builder(context).data(Uri.fromFile(file)).size(32, 24).build())
            assertTrue("Image decoding failed: $result", result is SuccessResult)
            assertEquals(coil.request.CachePolicy.DISABLED, result.request.diskCachePolicy)
            val exported = LocalMedia.export(context, file).also { created += it }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exported)
            assertArrayEquals(plain, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertFalse(plain.contentEquals(file.readBytes()))
        } finally { loader.shutdown() }
    }

    @Test fun nativeMetadataAndExtractorReadEncryptedAudioFromMemory() {
        val file = file("voice_messages", "wav"); LocalMedia.write(file, wave())
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(LocalMedia.dataSource(file))
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 900)
        } finally { retriever.release() }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(LocalMedia.dataSource(file))
            assertEquals(1, extractor.trackCount)
            extractor.selectTrack(0)
            assertTrue(extractor.readSampleData(ByteBuffer.allocate(32_000), 0) > 0)
        } finally { extractor.release() }
    }

    @Test fun actualExoPlayerPreparesAndSeeksEncryptedAudio() {
        val file = file("voice_messages", "wav"); LocalMedia.write(file, wave())
        val ready = CountDownLatch(1); val error = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(LocalMediaDataSourceFactory(context))).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() }
                override fun onPlayerError(exception: PlaybackException) { error.set(exception); ready.countDown() }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); player.prepare()
        }
        try {
            assertTrue("Player timed out", ready.await(15, TimeUnit.SECONDS)); assertNull(error.get())
            instrumentation.runOnMainSync { assertTrue(player.duration >= 900); player.seekTo(500) }
        } finally { instrumentation.runOnMainSync { player.release() } }
    }

    @Test fun largeOriginalIsSampledBeforeEncryptionAndWorkingFileIsRemoved() {
        val source = File(context.cacheDir, "security-large-${UUID.randomUUID()}.bmp").also { created += it }
        val width = 6000; val height = 8000; val pixels = width * height * 3
        java.io.RandomAccessFile(source, "rw").use { output ->
            val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN).apply {
                put('B'.code.toByte()); put('M'.code.toByte()); putInt(54 + pixels); putInt(0); putInt(54)
                putInt(40); putInt(width); putInt(height); putShort(1); putShort(24)
                putInt(0); putInt(pixels); putInt(0); putInt(0); putInt(0); putInt(0)
            }.array()
            output.write(header); output.setLength(54L + pixels)
        }
        val before = context.cacheDir.listFiles()!!.filter { it.name.startsWith("image-prep-") }.map { it.name }.toSet()
        val id = "security-${UUID.randomUUID()}"
        val prepared = com.auralis.crisisconnect.core.media.prepareImageAttachmentForTransfer(
            context, id, Uri.fromFile(source), "image/bmp", width, height,
            com.auralis.crisisconnect.core.media.DEFAULT_CHAT_IMAGE_TRANSFER_PROFILE)
        assertNotNull("Large valid original must still downsample", prepared)
        prepared!!
        val stored = com.auralis.crisisconnect.data.imageMessageFile(context, prepared.fileName).also { created += it }
        prepared.thumbnailName?.let { created += com.auralis.crisisconnect.data.imageThumbnailFile(context, it) }
        assertTrue(prepared.width!! <= 1600 && prepared.height!! <= 1600)
        assertTrue(stored.readBytes().take(8).toByteArray().contentEquals("CCMEDIA1".toByteArray()))
        assertEquals(before, context.cacheDir.listFiles()!!.filter { it.name.startsWith("image-prep-") }.map { it.name }.toSet())
    }

    private fun wave(): ByteArray {
        val sampleBytes = 16_000
        return ByteBuffer.allocate(44 + sampleBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + sampleBytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(sampleBytes); put(ByteArray(sampleBytes))
        }.array()
    }
}
