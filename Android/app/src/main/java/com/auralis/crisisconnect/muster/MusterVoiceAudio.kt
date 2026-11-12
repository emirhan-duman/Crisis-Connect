package com.auralis.crisisconnect.muster

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Open-and-unlink keeps even an interrupted recording out of backups and off the filesystem. */
private fun anonymousMusterAudio(context: Context): RandomAccessFile {
    val file = File.createTempFile("muster-", ".m4a", context.noBackupFilesDir)
    val opened = RandomAccessFile(file, "rw")
    return try { check(file.delete()); opened }
    catch (failure: Exception) { runCatching { opened.close() }; file.delete(); throw failure }
}

internal class MusterVoiceRecorder(private val context: Context) : Closeable {
    private var recorder: MediaRecorder? = null
    private var output: RandomAccessFile? = null
    private var startedAt = 0L
    fun start(onLimit: () -> Unit) {
        check(recorder == null)
        val file = anonymousMusterAudio(context)
        @Suppress("DEPRECATION")
        val recording = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            recording.setAudioSource(MediaRecorder.AudioSource.MIC)
            recording.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recording.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recording.setAudioChannels(1)
            recording.setAudioSamplingRate(22050)
            recording.setAudioEncodingBitRate(24000)
            recording.setMaxDuration(MUSTER_VOICE_MAX_MILLIS.toInt())
            recording.setMaxFileSize(MUSTER_VOICE_MAX_BYTES.toLong())
            recording.setOutputFile(file.fd)
            recording.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit()
            }
            recording.prepare(); recording.start()
            output = file; recorder = recording; startedAt = SystemClock.elapsedRealtime()
        } catch (failure: Exception) { runCatching { recording.release() }; file.close(); throw failure }
    }
    fun finish(): MusterVoiceDraft {
        val recording = checkNotNull(recorder); val file = checkNotNull(output)
        recorder = null; output = null
        val duration = (SystemClock.elapsedRealtime() - startedAt).coerceIn(1, MUSTER_VOICE_MAX_MILLIS)
        return try {
            recording.stop(); require(file.length() in 1..MUSTER_VOICE_MAX_BYTES.toLong())
            file.seek(0); val bytes = ByteArray(file.length().toInt()); file.readFully(bytes)
            MusterVoiceDraft(bytes, duration)
        } finally { runCatching { recording.release() }; file.close() }
    }
    override fun close() {
        val recording = recorder; recorder = null
        runCatching { recording?.setOnInfoListener(null); recording?.stop() }; runCatching { recording?.release() }
        runCatching { output?.close() }; output = null
    }
}

internal class MusterVoicePlayer(private val context: Context) : Closeable {
    private var player: MediaPlayer? = null
    private var input: RandomAccessFile? = null
    fun play(draft: MusterVoiceDraft, onFinished: () -> Unit) {
        close()
        val file = anonymousMusterAudio(context)
        val playback = MediaPlayer()
        try {
            file.write(draft.bytes); file.seek(0)
            playback.setDataSource(file.fd)
            playback.setOnCompletionListener { close(); onFinished() }
            playback.setOnErrorListener { _, _, _ -> close(); onFinished(); true }
            playback.prepare(); player = playback; input = file; playback.start()
        } catch (failure: Exception) { runCatching { playback.release() }; file.close(); throw failure }
    }
    override fun close() { runCatching { player?.release() }; player = null; runCatching { input?.close() }; input = null }
}
