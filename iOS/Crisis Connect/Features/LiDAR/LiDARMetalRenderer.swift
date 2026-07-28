//
//  LiDARMetalRenderer.swift
//  Crisis Connect
//

import ARKit
import CoreVideo
import MetalKit
import simd

struct LiDARRenderFrame {
    let capturedImage: CVPixelBuffer
    let depthMap: CVPixelBuffer?
    let confidenceMap: CVPixelBuffer?
    let displayTransform: CGAffineTransform
    let maxDepthMeters: Float
}

protocol LiDARFrameRendering: AnyObject {
    func enqueue(_ frame: LiDARRenderFrame)
    func clear()
}

final class LiDARMetalRenderer: NSObject, LiDARFrameRendering, MTKViewDelegate {
    private let commandQueue: MTLCommandQueue
    private let pipelineState: MTLRenderPipelineState
    private var textureCache: CVMetalTextureCache
    private let dummyDepthTexture: MTLTexture
    private let dummyConfidenceTexture: MTLTexture
    private let frameLock = NSLock()
    private var latestFrame: LiDARRenderFrame?

    init?(view: MTKView) {
        guard
            let device = MTLCreateSystemDefaultDevice(),
            let commandQueue = device.makeCommandQueue(),
            let library = device.makeDefaultLibrary(),
            let vertexFunction = library.makeFunction(name: "lidarFullscreenVertex"),
            let fragmentFunction = library.makeFunction(name: "lidarNightVisionFragment")
        else {
            return nil
        }

        let pipelineDescriptor = MTLRenderPipelineDescriptor()
        pipelineDescriptor.label = "LiDAR Night Vision Pipeline"
        pipelineDescriptor.vertexFunction = vertexFunction
        pipelineDescriptor.fragmentFunction = fragmentFunction
        pipelineDescriptor.colorAttachments[0].pixelFormat = .bgra8Unorm_srgb

        guard
            let pipelineState = try? device.makeRenderPipelineState(descriptor: pipelineDescriptor),
            let dummyDepthTexture = Self.makeDummyTexture(
                device: device,
                pixelFormat: .r32Float,
                value: Float(0)
            ),
            let dummyConfidenceTexture = Self.makeDummyTexture(
                device: device,
                pixelFormat: .r8Uint,
                value: UInt8(2)
            )
        else {
            return nil
        }

        var cache: CVMetalTextureCache?
        guard CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &cache) == kCVReturnSuccess,
              let cache else {
            return nil
        }

        self.commandQueue = commandQueue
        self.pipelineState = pipelineState
        self.textureCache = cache
        self.dummyDepthTexture = dummyDepthTexture
        self.dummyConfidenceTexture = dummyConfidenceTexture
        super.init()

        view.device = device
        view.colorPixelFormat = .bgra8Unorm_srgb
        view.framebufferOnly = true
        view.autoResizeDrawable = true
        view.preferredFramesPerSecond = 60
        view.enableSetNeedsDisplay = false
        view.isPaused = false
        view.clearColor = MTLClearColor(red: 0, green: 0.015, blue: 0.005, alpha: 1)
        view.delegate = self
    }

    func enqueue(_ frame: LiDARRenderFrame) {
        frameLock.lock()
        latestFrame = frame
        frameLock.unlock()
    }

    func clear() {
        frameLock.lock()
        latestFrame = nil
        frameLock.unlock()
        CVMetalTextureCacheFlush(textureCache, 0)
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard
            let descriptor = view.currentRenderPassDescriptor,
            let drawable = view.currentDrawable,
            let commandBuffer = commandQueue.makeCommandBuffer()
        else {
            return
        }

        frameLock.lock()
        let frame = latestFrame
        frameLock.unlock()

        descriptor.colorAttachments[0].loadAction = .clear
        descriptor.colorAttachments[0].storeAction = .store

        guard let frame,
              CVPixelBufferGetPlaneCount(frame.capturedImage) >= 2,
              let yTexture = makeTexture(
                from: frame.capturedImage,
                pixelFormat: .r8Unorm,
                planeIndex: 0
              ),
              let cbcrTexture = makeTexture(
                from: frame.capturedImage,
                pixelFormat: .rg8Unorm,
                planeIndex: 1
              ),
              let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor)
        else {
            commandBuffer.present(drawable)
            commandBuffer.commit()
            return
        }

        let depthTexture = frame.depthMap.flatMap {
            makeTexture(from: $0, pixelFormat: .r32Float, planeIndex: 0)
        }
        let confidenceTexture = frame.confidenceMap.flatMap {
            makeTexture(from: $0, pixelFormat: .r8Uint, planeIndex: 0)
        }
        var viewportToImage = Self.matrix(from: frame.displayTransform.inverted())
        var maximumDepth = max(frame.maxDepthMeters, 1)
        var hasDepth: UInt32 = depthTexture == nil ? 0 : 1

        encoder.label = "LiDAR Night Vision Render"
        encoder.setRenderPipelineState(pipelineState)
        encoder.setFragmentTexture(yTexture.texture, index: 0)
        encoder.setFragmentTexture(cbcrTexture.texture, index: 1)
        encoder.setFragmentTexture(depthTexture?.texture ?? dummyDepthTexture, index: 2)
        encoder.setFragmentTexture(confidenceTexture?.texture ?? dummyConfidenceTexture, index: 3)
        encoder.setFragmentBytes(&viewportToImage, length: MemoryLayout<simd_float3x3>.stride, index: 0)
        encoder.setFragmentBytes(&maximumDepth, length: MemoryLayout<Float>.stride, index: 1)
        encoder.setFragmentBytes(&hasDepth, length: MemoryLayout<UInt32>.stride, index: 2)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()

        // Retain the CVMetalTexture wrappers until the GPU has consumed their pixel buffers.
        let retainedTextures = [
            yTexture.reference,
            cbcrTexture.reference,
            depthTexture?.reference,
            confidenceTexture?.reference
        ]
        commandBuffer.addCompletedHandler { _ in
            _ = retainedTextures
        }
        commandBuffer.present(drawable)
        commandBuffer.commit()
    }

    private func makeTexture(
        from pixelBuffer: CVPixelBuffer,
        pixelFormat: MTLPixelFormat,
        planeIndex: Int
    ) -> (texture: MTLTexture, reference: CVMetalTexture)? {
        let isPlanar = CVPixelBufferIsPlanar(pixelBuffer)
        let width = isPlanar
            ? CVPixelBufferGetWidthOfPlane(pixelBuffer, planeIndex)
            : CVPixelBufferGetWidth(pixelBuffer)
        let height = isPlanar
            ? CVPixelBufferGetHeightOfPlane(pixelBuffer, planeIndex)
            : CVPixelBufferGetHeight(pixelBuffer)
        guard width > 0, height > 0 else { return nil }

        var textureReference: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault,
            textureCache,
            pixelBuffer,
            nil,
            pixelFormat,
            width,
            height,
            planeIndex,
            &textureReference
        )
        guard status == kCVReturnSuccess,
              let textureReference,
              let texture = CVMetalTextureGetTexture(textureReference) else {
            return nil
        }
        return (texture, textureReference)
    }

    private static func matrix(from transform: CGAffineTransform) -> simd_float3x3 {
        simd_float3x3(columns: (
            SIMD3(Float(transform.a), Float(transform.b), 0),
            SIMD3(Float(transform.c), Float(transform.d), 0),
            SIMD3(Float(transform.tx), Float(transform.ty), 1)
        ))
    }

    private static func makeDummyTexture<T>(
        device: MTLDevice,
        pixelFormat: MTLPixelFormat,
        value: T
    ) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: pixelFormat,
            width: 1,
            height: 1,
            mipmapped: false
        )
        descriptor.storageMode = .shared
        descriptor.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        var value = value
        withUnsafeBytes(of: &value) { bytes in
            texture.replace(
                region: MTLRegionMake2D(0, 0, 1, 1),
                mipmapLevel: 0,
                withBytes: bytes.baseAddress!,
                bytesPerRow: MemoryLayout<T>.stride
            )
        }
        return texture
    }
}
