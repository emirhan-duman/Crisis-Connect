//
//  SfuFrameCryptoTests.swift
//  Crisis ConnectTests
//
//  Exercises the MLS frame-crypto ABI shim against the REAL prebuilt WebRTC binary the app
//  links (see MlsFrameCrypto.mm). This is the load-bearing safety net for the vtable/ABI
//  assumptions: a wrong slot or a wrong parameter-passing class crashes or corrupts right here,
//  on the simulator, instead of on a device mid-call.
//
//  What a green run proves:
//   - the compiled-in `setFrameEncryptor:`/`setFrameDecryptor:` native hooks exist and accept
//     our shim objects (indirect scoped_refptr argument reached the implementation intact);
//   - the binary AddRef'd our object through vtable slot 0 (live count survives the attach);
//   - closing the peer connection Releases it through slot 1 and our destructor runs (live
//     count returns to baseline) — the full refcount round-trip.
//

import WebRTC
import XCTest

@testable import Crisis_Connect

final class SfuFrameCryptoTests: XCTestCase {

    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
    }()

    private func makePeerConnection() throws -> RTCPeerConnection {
        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        return try XCTUnwrap(
            Self.factory.peerConnection(with: config, constraints: constraints, delegate: nil)
        )
    }

    func testNativeHooksAreCompiledIntoTheWebRtcBinary() {
        // If a WebRTC package bump ever drops the +Native category this fails loudly here
        // instead of silently downgrading calls to gated-off E2EE.
        XCTAssertTrue(MlsFrameCryptoBridge.available)
    }

    func testAttachAndReleaseLifecycleThroughPrebuiltBinary() throws {
        let baseline = MlsFrameCryptoBridge.debugLiveInstances

        // Scope every wrapper inside an autoreleasepool: a live RTCRtpSender/RTCRtpTransceiver
        // keeps the native sender (and thus our shim objects) retained, which would make the
        // release poll below a false failure.
        try autoreleasepool {
            let pc = try makePeerConnection()
            // Track-less transceiver: the native RtpSender/RtpReceiver exist without any capture
            // track, so nothing touches the audio session (a real capture track wakes app-host
            // observers that expect a configured FirebaseApp and crash the test host).
            let transceiverInit = RTCRtpTransceiverInit()
            transceiverInit.direction = .sendOnly
            let transceiver = try XCTUnwrap(pc.addTransceiver(of: .audio, init: transceiverInit))

            XCTAssertTrue(MlsFrameCryptoBridge.attachEncryptor(to: transceiver.sender))
            XCTAssertTrue(MlsFrameCryptoBridge.attachDecryptor(to: transceiver.receiver))
            // The binary retained both shim objects (AddRef through vtable slot 0).
            XCTAssertEqual(MlsFrameCryptoBridge.debugLiveInstances, baseline + 2)

            // Re-attaching replaces the previous encryptor: the sender Releases the old one.
            XCTAssertTrue(MlsFrameCryptoBridge.attachEncryptor(to: transceiver.sender))
            XCTAssertEqual(MlsFrameCryptoBridge.debugLiveInstances, baseline + 2)

            pc.close()
        }

        // Tearing the connection down must Release everything (slot 1 → our destructor). The
        // native teardown is asynchronous on WebRTC's signaling thread, hence the poll.
        let deadline = Date().addingTimeInterval(10)
        while MlsFrameCryptoBridge.debugLiveInstances > baseline, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        XCTAssertEqual(
            MlsFrameCryptoBridge.debugLiveInstances, baseline,
            "WebRTC never released the shim objects — refcount plumbing broken"
        )
    }
}
