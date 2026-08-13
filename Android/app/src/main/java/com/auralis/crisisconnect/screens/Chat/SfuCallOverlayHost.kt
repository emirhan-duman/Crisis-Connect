package com.auralis.crisisconnect.screens.Chat

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.crisisconnect.messaging.call.InternetCallForegroundService
import com.auralis.crisisconnect.messaging.call.InternetCallManager
import com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager
import com.auralis.crisisconnect.messaging.call.sfu.SfuCallManager
import com.auralis.crisisconnect.messaging.call.sfu.SfuCallState
import com.auralis.crisisconnect.messaging.call.sfu.SfuVideoStreams

/**
 * App-wide overlay host for an SFU (Cloudflare Realtime) authority call. Renders the SHARED
 * [InternetCallOverlay] by mapping [SfuAuthorityCallManager]'s state onto an
 * [InternetCallManager.CallInfo], so an SFU call looks exactly like a citizen call — ring,
 * accept/reject, mute, hang up, and the same camera + screen-share tiles/controls. Hosted next to
 * [InternetCallOverlayHost] in MainActivity + RescueActivity.
 */
@Composable
fun SfuCallOverlayHost(activity: Activity) {
    val uiCall by SfuAuthorityCallManager.uiCall.collectAsStateWithLifecycle()
    val call = uiCall?.takeIf { it.state != SfuCallState.IDLE && it.state != SfuCallState.ENDED } ?: return
    // Unconditional subscription to a manager-stable flow: the overlay can never miss a remote track
    // because a per-call media engine appeared after the first composition.
    val streams by SfuAuthorityCallManager.videoStreamsFlow.collectAsStateWithLifecycle()
    val screenShareQuality by SfuAuthorityCallManager.screenShareQuality.collectAsStateWithLifecycle()

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) SfuAuthorityCallManager.accept() }
    // Camera permission → the toggle proceeds once granted (a voice→video upgrade needs it first use).
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) SfuAuthorityCallManager.toggleCamera() }
    // MediaProjection consent → hand the result to the call service, which promotes itself to a
    // mediaProjection FGS and routes the capture to the SFU engine.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            InternetCallForegroundService.startWithScreenShare(activity, data)
        }
    }
    // The SFU stack has no audio-route handling (yet); a plain speakerphone toggle covers the UI.
    var speakerOn by remember { mutableStateOf(false) }

    val info = InternetCallManager.CallInfo(
        callId = "sfu",
        peerUid = call.peerUid,
        peerName = call.peerName,
        incoming = call.incoming,
        state = when (call.state) {
            SfuCallState.OUTGOING -> InternetCallManager.State.DIALING
            SfuCallState.INCOMING -> InternetCallManager.State.INCOMING
            SfuCallState.CONNECTING -> InternetCallManager.State.CONNECTING
            SfuCallState.CONNECTED -> InternetCallManager.State.ACTIVE
            SfuCallState.ENDED -> InternetCallManager.State.ENDED
            SfuCallState.IDLE -> InternetCallManager.State.IDLE
        },
        muted = call.muted,
        speakerOn = speakerOn,
        canUseVideo = true,
        cameraOn = call.cameraOn,
        sharingScreen = call.sharingScreen,
        remoteVideo = streams.remote != null,
        remoteScreen = streams.remoteScreen != null,
        connectedAt = call.connectedAtMillis,
    )

    // Minimized to the floating banner; an incoming ring always brings the full screen back.
    var minimized by remember { mutableStateOf(false) }
    if (minimized && info.state != InternetCallManager.State.INCOMING) {
        InternetCallMiniBanner(
            call = info,
            onOpen = { minimized = false },
            onHangup = { SfuAuthorityCallManager.end() }
        )
        return
    }

    InternetCallOverlay(
        call = info,
        muted = call.muted,
        videoStreams = InternetCallManager.VideoStreams(
            local = streams.local,
            localScreen = streams.localScreen,
            remote = streams.remote,
            remoteScreen = streams.remoteScreen,
        ),
        eglContext = SfuCallManager.sharedEglContext(),
        screenShareQuality = screenShareQuality,
        onScreenShareQualityChanged = SfuAuthorityCallManager::setScreenShareQuality,
        onAccept = {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                SfuAuthorityCallManager.accept()
            } else {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onReject = { SfuAuthorityCallManager.reject() },
        onEnd = { SfuAuthorityCallManager.end() },
        onToggleMute = { SfuAuthorityCallManager.setMuted(!call.muted) },
        onToggleSpeaker = {
            val next = !speakerOn
            runCatching {
                activity.getSystemService(AudioManager::class.java)?.isSpeakerphoneOn = next
            }
            speakerOn = next
        },
        onToggleVideo = {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                SfuAuthorityCallManager.toggleCamera()
            } else {
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onToggleScreenShare = {
            if (call.sharingScreen) {
                SfuAuthorityCallManager.stopScreenShare()
            } else {
                val mpm = activity.getSystemService(MediaProjectionManager::class.java)
                if (mpm != null) projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        },
        onSwitchCamera = { SfuAuthorityCallManager.switchCamera() },
        onMinimize = { minimized = true },
    )
}
