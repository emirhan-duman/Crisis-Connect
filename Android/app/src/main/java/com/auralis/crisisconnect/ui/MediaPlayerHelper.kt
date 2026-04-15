package com.auralis.crisisconnect.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException

class MediaPlayerHelper {

    private var mediaPlayer: MediaPlayer? = null

    var isPlaying by mutableStateOf(false)
        private set

    var playbackProgress by mutableFloatStateOf(0f)
        private set

    var durationMillis by mutableLongStateOf(0L)
        private set

    var currentPositionMillis by mutableLongStateOf(0L)
        private set

    fun setSource(filePath: String?) {
        stopPlayback()
        releasePlayer()
        if (filePath.isNullOrBlank()) {
            return
        }
        mediaPlayer = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(filePath)
                setOnCompletionListener {
                    it.seekTo(0)
                    this@MediaPlayerHelper.isPlaying = false
                    this@MediaPlayerHelper.currentPositionMillis = 0L
                    this@MediaPlayerHelper.playbackProgress = 0f
                }
                prepare()
                durationMillis = duration.toLong()
                playbackProgress = 0f
                currentPositionMillis = 0L
            }
        } catch (ioException: IOException) {
            null
        } catch (illegalState: IllegalStateException) {
            null
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        try {
            if (isPlaying) {
                player.pause()
                isPlaying = false
                updateProgress()
            } else {
                if (player.currentPosition >= player.duration) {
                    player.seekTo(0)
                }
                player.start()
                isPlaying = true
            }
        } catch (illegalState: IllegalStateException) {
            release()
        }
    }

    fun stopPlayback() {
        val player = mediaPlayer ?: return
        try {
            if (isPlaying) {
                player.pause()
            }
            player.seekTo(0)
        } catch (_: IllegalStateException) {
        } finally {
            isPlaying = false
            currentPositionMillis = 0L
            playbackProgress = 0f
        }
    }

    fun release() {
        stopPlayback()
        releasePlayer()
    }

    fun updateProgress() {
        val player = mediaPlayer ?: return
        try {
            val duration = player.duration.takeIf { it > 0 } ?: return
            val position = player.currentPosition.coerceIn(0, duration)
            durationMillis = duration.toLong()
            currentPositionMillis = position.toLong()
            playbackProgress = position.toFloat() / duration.toFloat()
        } catch (_: IllegalStateException) {
            release()
        }
    }

    fun seekToFraction(fraction: Float) {
        val player = mediaPlayer ?: return
        try {
            val duration = player.duration.takeIf { it > 0 } ?: return
            val clampedFraction = fraction.coerceIn(0f, 1f)
            val targetPosition = (duration * clampedFraction).toInt()
            player.seekTo(targetPosition)
            durationMillis = duration.toLong()
            currentPositionMillis = targetPosition.toLong()
            playbackProgress = targetPosition.toFloat() / duration.toFloat()
        } catch (_: IllegalStateException) {
            release()
        }
    }

    fun seekBy(deltaMillis: Long) {
        val player = mediaPlayer ?: return
        try {
            val duration = player.duration.takeIf { it > 0 } ?: return
            val current = player.currentPosition.toLong()
            val target = (current + deltaMillis).coerceIn(0L, duration.toLong())
            player.seekTo(target.toInt())
            durationMillis = duration.toLong()
            currentPositionMillis = target
            playbackProgress = target.toFloat() / duration.toFloat()
        } catch (_: IllegalStateException) {
            release()
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        currentPositionMillis = 0L
        playbackProgress = 0f
        durationMillis = 0L
    }
}
