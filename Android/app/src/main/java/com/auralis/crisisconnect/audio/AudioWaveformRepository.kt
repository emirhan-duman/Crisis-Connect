package com.auralis.crisisconnect.audio

import android.content.ContentResolver
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val DEFAULT_BAR_COUNT = 150
private const val LIVE_CAPTURE_BAR_COUNT = 64

/**
 * Handles decoding audio waveforms into a set of normalized RMS bars and caches the results on disk.
 */
class AudioWaveformRepository(private val context: Context) {

    private val cacheDir: File = File(context.cacheDir, "audio_waveforms").apply { mkdirs() }
    private val cacheMutex = Mutex()

    suspend fun loadWaveform(uri: Uri, barCount: Int = DEFAULT_BAR_COUNT): List<Float> =
        cacheMutex.withLock {
            withContext(Dispatchers.IO) {
                val cacheFile = File(cacheDir, uri.toCacheKey())
                val cached = if (cacheFile.exists()) {
                    parseCache(cacheFile, barCount)
                        .takeIf { it.hasMeaningfulAmplitude() }
                } else {
                    null
                }

                if (cached != null) {
                    cached
                } else {
                    val decoded = decodeWaveform(context, uri, barCount)
                    if (decoded.hasMeaningfulAmplitude()) {
                        runCatching {
                            cacheFile.parentFile?.mkdirs()
                            cacheFile.writeText(decoded.joinToString(","))
                        }
                        decoded
                    } else {
                        runCatching { cacheFile.delete() }
                        emptyList()
                    }
                }
            }
        }

    private fun parseCache(file: File, barCount: Int): List<Float> {
        val cached = file.readText()
            .split(',')
            .mapNotNull { it.toFloatOrNull() }
        if (cached.size == barCount) return cached
        if (cached.isEmpty()) return List(barCount) { 0f }
        return resampleWaveform(cached, barCount)
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 10000L

        suspend fun decodeWaveform(context: Context, uri: Uri, barCount: Int): List<Float> =
            withContext(Dispatchers.IO) {
                runCatching { decodeInternal(context, uri, barCount) }
                    .getOrElse { emptyList() }
            }

        @VisibleForTesting
        internal fun decodeInternal(context: Context, uri: Uri, barCount: Int): List<Float> {
            val extractor = MediaExtractor()
            var pfd: ParcelFileDescriptor? = null
            try {
                when (uri.scheme) {
                    ContentResolver.SCHEME_FILE -> {
                        val path = uri.path
                        if (path.isNullOrEmpty()) {
                            throw IOException("Invalid file uri: $uri")
                        }
                        extractor.setDataSource(path)
                    }

                    else -> {
                        if (pfd == null) {
                            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        }
                        val descriptor = pfd?.fileDescriptor
                        if (descriptor != null) {
                            extractor.setDataSource(descriptor)
                        } else {
                            extractor.setDataSource(context, uri, null)
                        }
                    }
                }
            } catch (io: IOException) {
                pfd?.close()
                extractor.release()
                throw io
            } catch (exception: Exception) {
                pfd?.close()
                extractor.release()
                throw exception
            }

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: run {
                pfd?.close()
                extractor.release()
                return List(barCount) { 0f }
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return List(barCount) { 0f }
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcmEncoding = if (Build.VERSION.SDK_INT >= 24 && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                val rmsWindows = ArrayList<Float>()
                var samplesPerWindow = max(sampleRate / 60, 1024)
                var currentSamples = 0
                var sumSquares = 0.0

                fun flushWindow() {
                    if (currentSamples > 0) {
                        val rms = sqrt(sumSquares / currentSamples)
                        rmsWindows += rms.toFloat()
                        currentSamples = 0
                        sumSquares = 0.0
                    }
                }

                var sawInputEos = false
                var sawOutputEos = false

                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex) ?: continue
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                val presentationTime = extractor.sampleTime
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, INPUT_TIMEOUT_US)
                    when {
                        outputIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                processPcmBuffer(
                                    outputBuffer,
                                    bufferInfo,
                                    channelCount,
                                    pcmEncoding,
                                    samplesPerWindow
                                ) { sample ->
                                    currentSamples++
                                    sumSquares += sample.pow(2.0)
                                    if (currentSamples >= samplesPerWindow) {
                                        flushWindow()
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                                flushWindow()
                            }
                        }

                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                val newRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                if (newRate > 0) {
                                    val adjusted = max(newRate / 60, 1024)
                                    if (adjusted != samplesPerWindow) {
                                        flushWindow()
                                        samplesPerWindow = adjusted
                                    }
                                }
                            }
                        }
                    }
                }

                if (rmsWindows.isEmpty()) {
                    return emptyList()
                }

                val normalized = normalizeWaveform(resampleWaveform(rmsWindows, barCount))
                if (!normalized.hasMeaningfulAmplitude()) {
                    return emptyList()
                }
                return normalized
            } finally {
                runCatching { codec.stop() }
                codec.release()
                extractor.release()
                pfd?.close()
            }
        }

        private inline fun processPcmBuffer(
            buffer: ByteBuffer,
            info: MediaCodec.BufferInfo,
            channelCount: Int,
            pcmEncoding: Int,
            samplesPerWindow: Int,
            crossinline onSample: (Double) -> Unit
        ) {
            val data = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            buffer.get(data)
            buffer.clear()

            when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val floatBuffer = ByteBuffer.wrap(data)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()
                    val floatArray = FloatArray(floatBuffer.remaining())
                    floatBuffer.get(floatArray)
                    for (i in floatArray.indices step channelCount) {
                        var channelSum = 0f
                        for (ch in 0 until channelCount) {
                            val idx = i + ch
                            if (idx < floatArray.size) {
                                channelSum += floatArray[idx]
                            }
                        }
                        val sample = (channelSum / channelCount).toDouble()
                        onSample(sample)
                    }
                }

                AudioFormat.ENCODING_PCM_8BIT -> {
                    val step = channelCount
                    for (i in data.indices step step) {
                        var channelSum = 0f
                        for (ch in 0 until channelCount) {
                            val idx = i + ch
                            if (idx < data.size) {
                                channelSum += (data[idx].toInt() / 128f)
                            }
                        }
                        val sample = (channelSum / channelCount).toDouble()
                        onSample(sample)
                    }
                }

                else -> {
                    val shortBuffer = ByteBuffer.wrap(data)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                    val shortArray = ShortArray(shortBuffer.remaining())
                    shortBuffer.get(shortArray)
                    for (i in shortArray.indices step channelCount) {
                        var channelSum = 0f
                        for (ch in 0 until channelCount) {
                            val idx = i + ch
                            if (idx < shortArray.size) {
                                channelSum += shortArray[idx] / Short.MAX_VALUE.toFloat()
                            }
                        }
                        val sample = (channelSum / channelCount).toDouble()
                        onSample(sample)
                    }
                }
            }
        }

        private fun resampleWaveform(source: List<Float>, targetSize: Int): List<Float> {
            if (source.isEmpty() || targetSize <= 0) return emptyList()
            if (source.size == targetSize) return source
            val result = ArrayList<Float>(targetSize)
            val step = source.size.toFloat() / targetSize.toFloat()
            for (i in 0 until targetSize) {
                val index = ((i + 0.5f) * step).toInt().coerceIn(0, source.size - 1)
                result += source[index]
            }
            return result
        }

        private fun normalizeWaveform(values: List<Float>): List<Float> {
            val maxValue = values.maxOrNull()?.takeIf { it > 0f } ?: return List(values.size) { 0f }
            return values.map { (it / maxValue).coerceIn(0f, 1f) }
        }

        fun blendWaveforms(base: List<Float>, live: List<Float>?, blendFactor: Float = 0.2f): List<Float> {
            if (base.isEmpty()) return emptyList()
            if (live.isNullOrEmpty()) return base
            val clampedBlend = blendFactor.coerceIn(0f, 1f)
            val resampledLive = resampleWaveform(live, base.size)
            val baseWeight = 1f - clampedBlend
            return List(base.size) { index ->
                val mixed = base[index] * baseWeight + resampledLive[index] * clampedBlend
                mixed.coerceIn(0f, 1f)
            }
        }

        fun transformVisualizerData(raw: ByteArray, bars: Int = LIVE_CAPTURE_BAR_COUNT): List<Float> {
            if (raw.isEmpty() || bars <= 0) return emptyList()
            val result = FloatArray(bars)
            val step = raw.size.toFloat() / bars
            for (i in 0 until bars) {
                val index = (i * step).roundToInt().coerceIn(0, raw.size - 1)
                result[i] = abs(raw[index].toInt()) / 128f
            }
            val maxValue = result.maxOrNull()?.takeIf { it > 0f } ?: return List(bars) { 0f }
            return result.map { (it / maxValue).coerceIn(0f, 1f) }
        }
    }
}

private fun Uri.toCacheKey(): String {
    val digest = MessageDigest.getInstance("SHA-1")
    val bytes = digest.digest(toString().toByteArray())
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun List<Float>.hasMeaningfulAmplitude(): Boolean = any { it > 0f }
