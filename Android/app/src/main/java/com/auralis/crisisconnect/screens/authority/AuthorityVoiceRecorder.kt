package com.auralis.crisisconnect.screens.authority

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Minimal voice-note recorder for authority channels: captures AAC/M4A — the one container every
 * member of the fleet can play (web <audio>, Android MediaPlayer AND iOS AVAudioPlayer; iOS has no
 * Ogg demuxer, which is why this moved off Ogg/Opus) — and returns the raw bytes + duration for
 * [com.auralis.crisisconnect.messaging.ChannelAttachments] to seal + upload.
 */
class AuthorityVoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /** Begins recording; returns false if the mic couldn't be started (caller should surface an error). */
    fun start(): Boolean {
        if (recorder != null) return false
        val file = runCatching { File.createTempFile("authvoice", ".m4a", context.cacheDir) }.getOrNull()
            ?: return false
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(32000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            startedAtMs = SystemClock.elapsedRealtime()
            true
        }.getOrElse {
            runCatching { rec.release() }
            file.delete()
            false
        }
    }

    /** Stops recording and returns the recorded (bytes, durationSeconds), or null on failure/too short. */
    fun stop(): Pair<ByteArray, Int>? {
        val rec = recorder ?: return null
        val file = outputFile
        val durationSec = ((SystemClock.elapsedRealtime() - startedAtMs) / 1000L).toInt()
        recorder = null
        outputFile = null
        val stoppedCleanly = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        if (!stoppedCleanly || file == null) {
            file?.delete()
            return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        return if (bytes != null && bytes.isNotEmpty()) bytes to durationSec.coerceAtLeast(0) else null
    }

    /** Aborts recording and discards the file. */
    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        val file = outputFile
        outputFile = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        file?.delete()
    }
}
