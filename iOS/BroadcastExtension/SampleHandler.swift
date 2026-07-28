//
//  SampleHandler.swift
//  CrisisConnectBroadcast — ReplayKit Broadcast Upload Extension
//
//  Full-device screen sharing. This extension runs in its OWN process; ReplayKit hands it the whole
//  screen as CMSampleBuffers. We forward the video frames to the main Crisis Connect app over a
//  loopback TCP socket (see BroadcastFrameClient), where the WebRTC PeerConnection injects them into
//  the active call's screen track. Audio sample buffers are ignored (the call already carries the mic).
//
//  Frames go out as native NV12 and the screen's ORIENTATION rides along, so the app can tag the
//  WebRTC frame with a rotation instead of anyone baking a rotate into the pixels.
//

import CoreMedia
import CoreVideo
import ReplayKit

class SampleHandler: RPBroadcastSampleHandler {
    private let client = BroadcastFrameClient()

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        client.connect()
    }

    override func broadcastPaused() {}
    override func broadcastResumed() {}

    override func broadcastFinished() {
        client.close()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let seconds = pts.isValid ? CMTimeGetSeconds(pts) : 0
        client.send(
            pixelBuffer: pixelBuffer,
            rotationDegrees: Self.rotationDegrees(of: sampleBuffer),
            timestampNs: Int64(seconds * Double(NSEC_PER_SEC))
        )
    }

    /// ReplayKit attaches the current screen orientation as a CGImagePropertyOrientation. Translate it
    /// to the clockwise degrees WebRTC expects; anything unexpected is treated as upright.
    private static func rotationDegrees(of sampleBuffer: CMSampleBuffer) -> UInt32 {
        guard let raw = CMGetAttachment(
            sampleBuffer,
            key: RPVideoSampleOrientationKey as CFString,
            attachmentModeOut: nil
        ) as? NSNumber else { return 0 }
        switch raw.uint32Value {
        case 6: return 90   // .right
        case 3: return 180  // .down
        case 8: return 270  // .left
        default: return 0   // .up and the mirrored variants
        }
    }
}
