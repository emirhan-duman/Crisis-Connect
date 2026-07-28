package com.auralis.crisisconnect.screens.Chat

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.messaging.call.InternetCallForegroundService
import com.auralis.crisisconnect.messaging.call.InternetCallManager

/**
 * App-wide overlay host for an internet (WebRTC) call. Renders the shared [InternetCallOverlay] on top
 * of whatever screen is showing whenever [InternetCallManager] has an active call — so an incoming call
 * rings and can be answered, and an outgoing call shows the same full-screen UI, from ANY host activity.
 *
 * Both [com.auralis.crisisconnect.MainActivity] and the rescue-mode
 * `RescueActivity` call this, so authority (kurum) calls placed or received while in rescue mode get the
 * exact same call screen the citizen chat uses — previously RescueActivity hosted no overlay, so those
 * calls went active with no screen to render them. [activity] supplies the Context for permission checks
 * and the MediaProjection consent flow.
 */
@Composable
fun InternetCallOverlayHost(activity: Activity) {
    val call by InternetCallManager.call.collectAsStateWithLifecycle()
    val videoStreams by InternetCallManager.videoStreams.collectAsStateWithLifecycle()
    val active = call?.takeIf {
        it.state != InternetCallManager.State.ENDED && it.state != InternetCallManager.State.IDLE
    } ?: return
    // Minimized per callId: a fresh call (or an incoming ring) always opens the full screen.
    var minimizedCallId by remember { mutableStateOf<String?>(null) }
    if (active.callId == minimizedCallId && active.state != InternetCallManager.State.INCOMING) {
        InternetCallMiniBanner(
            call = active,
            onOpen = { minimizedCallId = null },
            onHangup = { InternetCallManager.end() }
        )
        return
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) InternetCallManager.accept() }
    // Camera runtime permission → once granted, turn the camera on for the call.
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) InternetCallManager.toggleCamera() }
    // MediaProjection consent → hand the result to the call service, which promotes itself to a
    // mediaProjection foreground service and starts the screen capture (Android 14+ ordering).
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            InternetCallForegroundService.startWithScreenShare(activity, data)
        }
    }
    InternetCallOverlay(
        call = active,
        muted = active.muted,
        videoStreams = videoStreams,
        eglContext = InternetCallManager.eglBaseContext(),
        onAccept = {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                InternetCallManager.accept()
            } else {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onReject = { InternetCallManager.reject() },
        onEnd = { InternetCallManager.end() },
        onToggleMute = { InternetCallManager.setMuted(!active.muted) },
        onToggleSpeaker = { InternetCallManager.setSpeaker(!active.speakerOn) },
        onToggleVideo = {
            // Turning the camera off needs no permission; turning it on may prompt once.
            if (active.cameraOn || ContextCompat.checkSelfPermission(
                    activity, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                InternetCallManager.toggleCamera()
            } else {
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onToggleScreenShare = {
            if (active.sharingScreen) {
                InternetCallManager.stopScreenShare()
            } else {
                val mpm = activity.getSystemService(MediaProjectionManager::class.java)
                mpm?.createScreenCaptureIntent()?.let { screenCaptureLauncher.launch(it) }
            }
        },
        onSwitchCamera = { InternetCallManager.switchCamera() },
        onMinimize = { minimizedCallId = active.callId }
    )
}
