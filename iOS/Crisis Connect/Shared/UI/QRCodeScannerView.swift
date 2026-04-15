//
//  QRCodeScannerView.swift
//  Crisis Connect
//
//  Created by Assistant on 16.01.2026
//

import SwiftUI
import AVFoundation

struct QRCodeScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void
    let onError: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onScan = onScan
        controller.onError = onError
        return controller
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {}
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) -> Void)?
    var onError: ((String) -> Void)?

    private let session = AVCaptureSession()
    private let metadataOutput = AVCaptureMetadataOutput()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didScan = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        updateVideoOrientation()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleOrientationChange),
            name: UIDevice.orientationDidChangeNotification,
            object: nil
        )
        startSessionIfNeeded()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        NotificationCenter.default.removeObserver(self, name: UIDevice.orientationDidChangeNotification, object: nil)
        session.stopRunning()
    }

    private func configureSession() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            break
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureSession()
                    } else {
                        self?.onError?("CAMERA_PERMISSION_DENIED")
                    }
                }
            }
            return
        default:
            onError?("CAMERA_PERMISSION_DENIED")
            return
        }

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else {
            onError?("CAMERA_UNAVAILABLE")
            return
        }

        session.beginConfiguration()
        if session.canAddInput(input) {
            session.addInput(input)
        } else {
            session.commitConfiguration()
            onError?("CAMERA_UNAVAILABLE")
            return
        }

        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            metadataOutput.metadataObjectTypes = [.qr]
        } else {
            session.commitConfiguration()
            onError?("CAMERA_UNAVAILABLE")
            return
        }

        session.commitConfiguration()

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        previewLayer = preview
        view.layer.insertSublayer(preview, at: 0)
    }

    private func startSessionIfNeeded() {
        guard !session.isRunning else { return }
        didScan = false
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didScan else { return }
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let value = object.stringValue else {
            return
        }
        didScan = true
        session.stopRunning()
        onScan?(value)
    }

    @objc private func handleOrientationChange() {
        updateVideoOrientation()
    }

    private func updateVideoOrientation() {
        let interfaceOrientation = view.window?.windowScene?.interfaceOrientation ?? .unknown
        let rotationAngle = videoRotationAngle(interfaceOrientation: interfaceOrientation)
            ?? videoRotationAngle(deviceOrientation: UIDevice.current.orientation)

        applyVideoRotationAngle(rotationAngle, to: previewLayer?.connection)
        applyVideoRotationAngle(rotationAngle, to: metadataOutput.connection(with: .video))
    }

    private func applyVideoRotationAngle(_ rotationAngle: CGFloat?, to connection: AVCaptureConnection?) {
        guard let connection, let rotationAngle else { return }
        guard connection.isVideoRotationAngleSupported(rotationAngle) else { return }
        connection.videoRotationAngle = rotationAngle
    }
}

private extension ScannerViewController {
    func videoRotationAngle(interfaceOrientation: UIInterfaceOrientation) -> CGFloat? {
        switch interfaceOrientation {
        case .portrait:
            return 90
        case .portraitUpsideDown:
            return 270
        case .landscapeLeft:
            return 0
        case .landscapeRight:
            return 180
        case .unknown:
            return nil
        @unknown default:
            return nil
        }
    }

    func videoRotationAngle(deviceOrientation: UIDeviceOrientation) -> CGFloat? {
        switch deviceOrientation {
        case .portrait:
            return 90
        case .portraitUpsideDown:
            return 270
        case .landscapeLeft:
            return 180
        case .landscapeRight:
            return 0
        default:
            return nil
        }
    }
}
