//
//  BroadcastFrameClient.swift
//  CrisisConnectBroadcast (extension target — client side)
//
//  Streams the screen frames captured by the broadcast extension to the main Crisis Connect app over
//  a loopback TCP socket. Self-contained (no cross-target file sharing): the wire constants here must
//  match ScreenBroadcastTransport in the app target.
//
//  We ship the frames as NATIVE NV12 (the biplanar 4:2:0 buffer ReplayKit already hands us, which is
//  also what WebRTC consumes natively). An earlier version converted to BGRA with vImage; that was
//  wrong twice over: it produced ARGB byte order that the app then decoded as BGRA (hence wrong
//  colours), and it allocated a full-size RGB intermediate (~16 MB on an iPad) every frame against
//  ReplayKit's hard ~50 MB extension limit, so the extension was jetsammed moments after the
//  broadcast started. Copying the planes straight out costs ~1.5 bytes/pixel and no colour maths, so
//  the whole class of bug is gone.
//
//  Backpressure: a frame is ~6 MB, far larger than the socket send buffer, so a blocking write would
//  stall ReplayKit's sample-buffer queue. Sends run on a private serial queue and a frame is DROPPED
//  whenever the previous one is still going out — screen share tolerates drops, stalls it does not.
//

import CoreVideo
import Darwin
import Foundation

enum ScreenBroadcast {
    static let loopbackHost = "127.0.0.1"
    static let loopbackPort: UInt16 = 51022
    // Fixed 28-byte little-endian header: magic, width, height, rotation, pixelFormat, timestampNs.
    // Body = tightly packed Y plane (width*height) followed by CbCr (width*(height/2)).
    static let headerSize = 28
    static let magic: UInt32 = 0x53435232 // "SCR2" — NV12 wire format
    static let targetFps: Double = 15

    static func packHeader(
        width: UInt32,
        height: UInt32,
        rotationDegrees: UInt32,
        pixelFormat: UInt32,
        timestampNs: Int64
    ) -> Data {
        var d = Data(capacity: headerSize)
        for v in [magic, width, height, rotationDegrees, pixelFormat] {
            var le = v.littleEndian
            withUnsafeBytes(of: &le) { d.append(contentsOf: $0) }
        }
        var ts = timestampNs.littleEndian
        withUnsafeBytes(of: &ts) { d.append(contentsOf: $0) }
        return d
    }
}

final class BroadcastFrameClient {
    private var fd: Int32 = -1
    private let lock = NSLock()
    private var lastSentAt: CFTimeInterval = 0
    private var inFlight = false
    private let sendQueue = DispatchQueue(label: "cc.broadcast.send", qos: .userInitiated)

    func connect() {
        lock.lock(); defer { lock.unlock() }
        connectLocked()
    }

    private func connectLocked() {
        guard fd < 0 else { return }
        let s = socket(AF_INET, SOCK_STREAM, 0)
        guard s >= 0 else { return }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = ScreenBroadcast.loopbackPort.bigEndian
        inet_pton(AF_INET, ScreenBroadcast.loopbackHost, &addr.sin_addr)
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if ok == 0 {
            var one: Int32 = 1
            setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            // A frame dwarfs the default send buffer; a bigger one keeps the write from chopping into
            // dozens of syscalls.
            var sndbuf: Int32 = 1 << 20
            setsockopt(s, SOL_SOCKET, SO_SNDBUF, &sndbuf, socklen_t(MemoryLayout<Int32>.size))
            fd = s
        } else {
            Darwin.close(s)
        }
    }

    func close() {
        lock.lock(); defer { lock.unlock() }
        if fd >= 0 { Darwin.close(fd); fd = -1 }
    }

    /// Throttle, then hand the frame to the send queue. Called on ReplayKit's sample-buffer queue —
    /// it must return immediately, so the actual socket write happens off this thread and frames are
    /// dropped rather than queued when the socket is still busy.
    func send(pixelBuffer: CVPixelBuffer, rotationDegrees: UInt32, timestampNs: Int64) {
        let now = monotonicSeconds()
        lock.lock()
        if fd < 0 {
            connectLocked()
            if fd < 0 { lock.unlock(); return }
        }
        if now - lastSentAt < (1.0 / ScreenBroadcast.targetFps) { lock.unlock(); return }
        if inFlight { lock.unlock(); return } // previous frame still going out → drop this one
        lastSentAt = now
        inFlight = true
        let socketFd = fd
        lock.unlock()

        // Capturing the pixel buffer retains it, so ReplayKit can't recycle it mid-write.
        sendQueue.async { [weak self] in
            let ok = Self.writeFrame(
                socketFd,
                pixelBuffer: pixelBuffer,
                rotationDegrees: rotationDegrees,
                timestampNs: timestampNs
            )
            guard let self else { return }
            self.lock.lock()
            self.inFlight = false
            let stale = self.fd != socketFd
            self.lock.unlock()
            if !ok && !stale { self.close() } // peer gone → reconnect on the next frame
        }
    }

    /// Write header + tightly packed Y and CbCr planes straight from the locked pixel buffer. No
    /// intermediate frame-sized allocation, which is what keeps us inside the extension memory cap.
    private static func writeFrame(
        _ fd: Int32,
        pixelBuffer: CVPixelBuffer,
        rotationDegrees: UInt32,
        timestampNs: Int64
    ) -> Bool {
        guard CVPixelBufferGetPlaneCount(pixelBuffer) >= 2 else { return true }
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
        guard format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            || format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange else {
            // 10-bit or an unexpected format — dropping beats shipping garbage.
            return true
        }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        guard width > 0, height > 0,
              let yBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0),
              let cbcrBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1) else { return true }
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        let cbcrStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1)
        let chromaHeight = CVPixelBufferGetHeightOfPlane(pixelBuffer, 1)

        let header = ScreenBroadcast.packHeader(
            width: UInt32(width),
            height: UInt32(height),
            rotationDegrees: rotationDegrees,
            pixelFormat: UInt32(format),
            timestampNs: timestampNs
        )
        let headerOk = header.withUnsafeBytes { raw -> Bool in
            writeAll(fd, raw.baseAddress!, raw.count)
        }
        guard headerOk else { return false }

        // Y: `width` bytes per row, skipping the stride padding.
        for row in 0..<height {
            if !writeAll(fd, yBase.advanced(by: row * yStride), width) { return false }
        }
        // CbCr: interleaved, (width/2) pairs = `width` bytes per row, half the rows.
        for row in 0..<chromaHeight {
            if !writeAll(fd, cbcrBase.advanced(by: row * cbcrStride), width) { return false }
        }
        return true
    }

    private static func writeAll(_ fd: Int32, _ buf: UnsafeRawPointer, _ len: Int) -> Bool {
        var off = 0
        while off < len {
            let n = Darwin.write(fd, buf + off, len - off)
            if n <= 0 {
                if n < 0 && (errno == EINTR || errno == EAGAIN) { continue }
                return false
            }
            off += n
        }
        return true
    }

    private func monotonicSeconds() -> CFTimeInterval {
        var ts = timespec()
        clock_gettime(CLOCK_MONOTONIC, &ts)
        return CFTimeInterval(ts.tv_sec) + CFTimeInterval(ts.tv_nsec) / 1_000_000_000
    }
}
