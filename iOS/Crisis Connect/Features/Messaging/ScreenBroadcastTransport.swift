//
//  ScreenBroadcastTransport.swift
//  Crisis Connect  (app target — server side)
//
//  Full-device screen sharing uses a ReplayKit Broadcast Upload Extension, which runs in its OWN
//  process. This file is the APP side: it listens on a loopback TCP socket for the extension and hands
//  reassembled screen frames back as CVPixelBuffers (InternetCallManager injects them into the call's
//  WebRTC screen track). The extension side (BroadcastFrameClient) agrees on the wire constants below.
//
//  Frames arrive as native NV12 — the same biplanar 4:2:0 layout ReplayKit captures and WebRTC
//  encodes — so nothing here does colour conversion. (An earlier revision shipped BGRA converted with
//  vImage in the extension; it got the channel order wrong AND blew the extension's ~50 MB memory cap.)
//
//  Loopback (127.0.0.1) needs no Local Network permission and — unlike a Unix socket in an App Group
//  container — has no 104-char path limit, so NO App Group entitlement / provisioning is required.
//

import CoreVideo
import Darwin
import Foundation

enum ScreenBroadcast {
    static let loopbackHost = "127.0.0.1"
    static let loopbackPort: UInt16 = 51022
    // Fixed 28-byte little-endian header: magic, width, height, rotation, pixelFormat, timestampNs.
    // Body = tightly packed Y plane (width*height) then CbCr (width*(height/2)).
    static let headerSize = 28
    static let magic: UInt32 = 0x53435232 // "SCR2" — NV12 wire format

    struct Header {
        let width: Int
        let height: Int
        let rotationDegrees: Int
        let pixelFormat: OSType
        let timestampNs: Int64
        /// Tightly packed NV12: full-res luma plus half-height interleaved chroma.
        var bodyLength: Int { width * height + width * (height / 2) }
    }

    static func unpackHeader(_ d: Data) -> Header? {
        guard d.count >= headerSize else { return nil }
        return d.withUnsafeBytes { raw -> Header? in
            let base = raw.baseAddress!
            func u32(_ off: Int) -> UInt32 {
                UInt32(littleEndian: base.loadUnaligned(fromByteOffset: off, as: UInt32.self))
            }
            func i64(_ off: Int) -> Int64 {
                Int64(littleEndian: base.loadUnaligned(fromByteOffset: off, as: Int64.self))
            }
            guard u32(0) == magic else { return nil }
            let w = Int(u32(4)), h = Int(u32(8))
            // Even dimensions are required for 4:2:0; guard against absurd values too.
            guard w > 1, h > 1, w % 2 == 0, h % 2 == 0, w <= 8192, h <= 8192 else { return nil }
            return Header(
                width: w,
                height: h,
                rotationDegrees: Int(u32(12)),
                pixelFormat: OSType(u32(16)),
                timestampNs: i64(20)
            )
        }
    }
}

/// Listens on loopback for the broadcast extension and delivers reassembled frames as NV12
/// CVPixelBuffers plus the screen rotation. The frame callback runs on a background queue.
final class BroadcastFrameServer {
    private var listenFd: Int32 = -1
    private let acceptQueue = DispatchQueue(label: "cc.broadcast.server")
    private var running = false
    /// (frame, rotationDegrees, timestampNs)
    var onFrame: ((CVPixelBuffer, Int, Int64) -> Void)?
    var onClientDisconnected: (() -> Void)?

    /// Reused across frames so a screen share doesn't churn a fresh several-MB buffer 15x a second.
    private var pixelBufferPool: CVPixelBufferPool?
    private var poolWidth = 0
    private var poolHeight = 0
    private var poolFormat: OSType = 0

    func start() {
        acceptQueue.async { [weak self] in self?.run() }
    }

    func stop() {
        running = false
        if listenFd >= 0 { Darwin.close(listenFd); listenFd = -1 }
    }

    private func run() {
        let s = socket(AF_INET, SOCK_STREAM, 0)
        guard s >= 0 else { return }
        var yes: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = ScreenBroadcast.loopbackPort.bigEndian
        inet_pton(AF_INET, ScreenBroadcast.loopbackHost, &addr.sin_addr)
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, Darwin.listen(s, 1) == 0 else { Darwin.close(s); return }
        listenFd = s
        running = true
        while running {
            let client = Darwin.accept(s, nil, nil)
            if client < 0 { if running { continue } else { break } }
            var one: Int32 = 1
            setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            serveClient(client)
            onClientDisconnected?()
        }
    }

    private func serveClient(_ fd: Int32) {
        var body = Data()
        while running {
            guard let headerData = readExact(fd, ScreenBroadcast.headerSize, into: nil),
                  let header = ScreenBroadcast.unpackHeader(headerData) else { break }
            let need = header.bodyLength
            guard need > 0, need <= 64 * 1024 * 1024 else { break }
            if body.count != need { body = Data(count: need) }
            guard readExact(fd, need, into: &body) != nil else { break }
            if let pb = makePixelBuffer(nv12: body, header: header) {
                onFrame?(pb, header.rotationDegrees, header.timestampNs)
            }
        }
        Darwin.close(fd)
    }

    /// Reads exactly `count` bytes. When `into` is supplied the bytes land in that (already sized)
    /// buffer and it is returned; otherwise a fresh Data is allocated.
    @discardableResult
    private func readExact(_ fd: Int32, _ count: Int, into buffer: UnsafeMutablePointer<Data>?) -> Data? {
        if let buffer {
            let ok = buffer.pointee.withUnsafeMutableBytes { raw -> Bool in
                readFully(fd, raw.baseAddress!, count)
            }
            return ok ? buffer.pointee : nil
        }
        var out = Data(count: count)
        let ok = out.withUnsafeMutableBytes { raw -> Bool in
            readFully(fd, raw.baseAddress!, count)
        }
        return ok ? out : nil
    }

    private func readFully(_ fd: Int32, _ base: UnsafeMutableRawPointer, _ count: Int) -> Bool {
        var off = 0
        while off < count {
            let n = Darwin.read(fd, base + off, count - off)
            if n <= 0 {
                if n < 0 && errno == EINTR { continue }
                return false
            }
            off += n
        }
        return true
    }

    /// Copy the tightly packed planes into a pooled NV12 buffer, honouring the destination strides.
    private func makePixelBuffer(nv12: Data, header: ScreenBroadcast.Header) -> CVPixelBuffer? {
        let format = (header.pixelFormat == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
            ? kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        guard let pool = pool(width: header.width, height: header.height, format: format) else { return nil }
        var pb: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &pb) == kCVReturnSuccess,
              let buffer = pb else { return nil }

        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let yDst = CVPixelBufferGetBaseAddressOfPlane(buffer, 0),
              let cbcrDst = CVPixelBufferGetBaseAddressOfPlane(buffer, 1) else { return nil }
        let yDstStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
        let cbcrDstStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1)
        let chromaRows = header.height / 2

        nv12.withUnsafeBytes { raw in
            let src = raw.baseAddress!
            for row in 0..<header.height {
                memcpy(yDst + row * yDstStride, src + row * header.width, header.width)
            }
            let chromaSrc = src + header.width * header.height
            for row in 0..<chromaRows {
                memcpy(cbcrDst + row * cbcrDstStride, chromaSrc + row * header.width, header.width)
            }
        }
        return buffer
    }

    private func pool(width: Int, height: Int, format: OSType) -> CVPixelBufferPool? {
        if let pixelBufferPool, poolWidth == width, poolHeight == height, poolFormat == format {
            return pixelBufferPool
        }
        let attrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: format,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ]
        var created: CVPixelBufferPool?
        guard CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attrs as CFDictionary, &created) == kCVReturnSuccess else {
            return nil
        }
        pixelBufferPool = created
        poolWidth = width
        poolHeight = height
        poolFormat = format
        return created
    }
}
