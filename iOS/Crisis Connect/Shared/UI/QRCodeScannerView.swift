//
//  QRCodeScannerView.swift
//  Crisis Connect
//
//  Created by Assistant on 16.01.2026
//

import SwiftUI
import AVFoundation

struct QRCodeCameraState: Equatable {
    var isRunning = false
    var hasTorch = false
    var torchOn = false
}

struct QRCodeScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void
    let onError: (String) -> Void
    var isScanningPaused = false
    var torchEnabled = false
    var onStateChange: ((QRCodeCameraState) -> Void)?

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onScan = onScan
        controller.onError = onError
        controller.onStateChange = onStateChange
        controller.isScanningPaused = isScanningPaused
        return controller
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {
        uiViewController.onScan = onScan
        uiViewController.onError = onError
        uiViewController.onStateChange = onStateChange
        uiViewController.isScanningPaused = isScanningPaused
        uiViewController.setTorchEnabled(torchEnabled)
    }

    static func dismantleUIViewController(_ uiViewController: ScannerViewController, coordinator: ()) {
        uiViewController.stopCapture()
    }
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) -> Void)?
    var onError: ((String) -> Void)?
    var onStateChange: ((QRCodeCameraState) -> Void)?
    var isScanningPaused = false

    private let session = AVCaptureSession()
    private let metadataOutput = AVCaptureMetadataOutput()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didScan = false
    private var sessionConfigured = false
    private var isVisible = false
    private var requestedTorch = false
    private var videoDevice: AVCaptureDevice?
    private var torchStateObservation: NSKeyValueObservation?
    private var torchAvailabilityObservation: NSKeyValueObservation?
    private let sessionQueue = DispatchQueue(label: "com.auralis.crisisconnect.qr-camera", qos: .userInitiated)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        NotificationCenter.default.addObserver(self, selector: #selector(sessionStarted), name: AVCaptureSession.didStartRunningNotification, object: session)
        NotificationCenter.default.addObserver(self, selector: #selector(sessionInterrupted), name: AVCaptureSession.wasInterruptedNotification, object: session)
        NotificationCenter.default.addObserver(self, selector: #selector(sessionInterruptionEnded), name: AVCaptureSession.interruptionEndedNotification, object: session)
        NotificationCenter.default.addObserver(self, selector: #selector(sessionFailed), name: AVCaptureSession.runtimeErrorNotification, object: session)
        configureSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        updateVideoOrientation()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        isVisible = true
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
        stopCapture()
    }

    private func configureSession() {
        guard !sessionConfigured else { return }
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
            session.removeInput(input)
            session.commitConfiguration()
            onError?("CAMERA_UNAVAILABLE")
            return
        }

        session.commitConfiguration()
        sessionConfigured = true
        videoDevice = device
        torchStateObservation = device.observe(\.isTorchActive, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.publishCameraState() }
        }
        torchAvailabilityObservation = device.observe(\.isTorchAvailable, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.publishCameraState() }
        }

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        previewLayer = preview
        view.layer.insertSublayer(preview, at: 0)
        preview.frame = view.bounds
        updateVideoOrientation()
        startSessionIfNeeded()
    }

    private func startSessionIfNeeded() {
        guard isVisible, sessionConfigured else { return }
        didScan = false
        sessionQueue.async { [weak self] in
            guard let self, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

    func stopCapture() {
        isVisible = false
        requestedTorch = false
        sessionQueue.async { [self] in
            if let device = videoDevice, device.hasTorch, device.isTorchActive {
                if (try? device.lockForConfiguration()) != nil {
                    device.torchMode = .off
                    device.unlockForConfiguration()
                }
            }
            if session.isRunning { session.stopRunning() }
        }
    }

    func setTorchEnabled(_ enabled: Bool) {
        guard requestedTorch != enabled else { return }
        requestedTorch = enabled
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard let device = self.videoDevice,
                  device.hasTorch, device.isTorchAvailable else {
                DispatchQueue.main.async {
                    self.publishCameraState()
                    if enabled { self.onError?("CAMERA_TORCH_UNAVAILABLE") }
                }
                return
            }
            do {
                try device.lockForConfiguration()
                device.torchMode = enabled ? .on : .off
                device.unlockForConfiguration()
                DispatchQueue.main.async { self.publishCameraState() }
            } catch {
                DispatchQueue.main.async { self.onError?("CAMERA_TORCH_UNAVAILABLE") }
            }
        }
    }

    private func publishCameraState() {
        guard isVisible, !didScan else { return }
        onStateChange?(QRCodeCameraState(
            isRunning: session.isRunning && !session.isInterrupted,
            hasTorch: videoDevice?.hasTorch == true && videoDevice?.isTorchAvailable == true,
            torchOn: videoDevice?.isTorchActive == true
        ))
    }

    @objc private func sessionStarted() {
        DispatchQueue.main.async { [weak self] in self?.publishCameraState() }
    }

    @objc private func sessionInterrupted() {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isVisible else { return }
            self.requestedTorch = false
            self.onError?("CAMERA_INTERRUPTED")
        }
    }

    @objc private func sessionInterruptionEnded() {
        DispatchQueue.main.async { [weak self] in
            self?.publishCameraState()
            self?.startSessionIfNeeded()
        }
    }

    @objc private func sessionFailed() {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isVisible, !self.didScan else { return }
            self.onError?("CAMERA_UNAVAILABLE")
        }
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard isVisible, !didScan, !isScanningPaused else { return }
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let value = object.stringValue else {
            return
        }
        didScan = true
        sessionQueue.async { [self] in session.stopRunning() }
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
