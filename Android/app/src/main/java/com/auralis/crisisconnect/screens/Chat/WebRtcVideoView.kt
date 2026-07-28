package com.auralis.crisisconnect.screens.Chat

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Renders a WebRTC [VideoTrack] into a [SurfaceViewRenderer]. Binds the track as a sink and rebinds
 * when it changes; releases the surface on disposal. Used by [InternetCallOverlay] for the full-screen
 * remote view and the local picture-in-picture preview.
 *
 * @param overlay draw above sibling surfaces (for the local PiP over the full-screen remote).
 * @param mirror  horizontally flip (front-camera self-view convention).
 * @param scaling how the frame fills the view. FILL crops to the view's aspect — right for the small
 *   PiP tiles. On the main stage it is NOT enough to ask for FIT: libwebrtc implements FIT only by
 *   shrinking the View in onMeasure (RendererCommon.VideoLayoutMeasure), and it explicitly yields
 *   ("if the measure specification is forcing a specific size") whenever the parent hands it an
 *   EXACTLY spec — which Compose always does for a fillMaxSize/MATCH_PARENT child. EglRenderer then
 *   crops the texture to the raw view rectangle and never draws bars. So the caller must ALSO size
 *   this view to the frame's aspect; see [onFrameGeometry].
 * @param onFrameGeometry raw decoded frame size + rotation, on the main thread, whenever the incoming
 *   resolution changes. The caller turns it into a Modifier.aspectRatio so the view rect matches the
 *   frame and the letterbox bars come from Compose layout instead of from the (non-existent) renderer
 *   bar path. Rotation is NOT applied here — callers must swap w/h for 90/270 themselves.
 */
@Composable
fun WebRtcVideoView(
    track: VideoTrack?,
    eglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    overlay: Boolean = false,
    scaling: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
    onFrameGeometry: ((width: Int, height: Int, rotation: Int) -> Unit)? = null
) {
    if (eglContext == null) return
    // The factory runs once, so it must capture a stable holder rather than today's lambda.
    val geometryCallback = rememberUpdatedState(onFrameGeometry)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val mainHandler = Handler(Looper.getMainLooper())
            SurfaceViewRenderer(ctx).apply {
                // RendererEvents fire on the render thread; hop to main before touching Compose state.
                init(eglContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {}
                    override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                        Log.i("WebRtcVideoView", "frame ${w}x$h rot=$rotation")
                        mainHandler.post { geometryCallback.value?.invoke(w, h, rotation) }
                    }
                })
                setEnableHardwareScaler(true)
                setScalingType(scaling)
                setMirror(mirror)
                if (overlay) setZOrderMediaOverlay(true)
            }
        },
        update = { view ->
            view.setScalingType(scaling)
            val bound = view.tag as? VideoTrack
            if (bound !== track) {
                bound?.let { runCatching { it.removeSink(view) } }
                track?.let { runCatching { it.addSink(view) } }
                view.tag = track
            }
        },
        onRelease = { view ->
            (view.tag as? VideoTrack)?.let { runCatching { it.removeSink(view) } }
            view.tag = null
            view.release()
        }
    )
}
