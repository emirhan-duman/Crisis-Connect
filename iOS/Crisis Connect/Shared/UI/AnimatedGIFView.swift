//
//  AnimatedGIFView.swift
//  Crisis Connect
//
//  Created by Codex on 28.03.2026.
//

import ImageIO
import SwiftUI
import UIKit

struct AnimatedGIFView: UIViewRepresentable {
    let assetName: String

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UIImageView, context: Context) -> CGSize? {
        guard let width = proposal.width, let height = proposal.height else { return nil }
        return CGSize(width: width, height: height)
    }

    func makeUIView(context: Context) -> UIImageView {
        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        imageView.backgroundColor = .clear
        loadGIF(into: imageView)
        return imageView
    }

    func updateUIView(_ imageView: UIImageView, context: Context) {
        guard imageView.accessibilityIdentifier != assetName else { return }
        loadGIF(into: imageView)
    }

    private func loadGIF(into imageView: UIImageView) {
        imageView.accessibilityIdentifier = assetName

        if let cached = PreparedGIFAssetStore.shared.cachedAnimatedImage(named: assetName) {
            imageView.image = cached
            imageView.startAnimating()
            return
        }

        // Show first frame immediately while decoding all frames in background
        if let placeholder = PreparedGIFAssetStore.shared.cachedPlaceholder(named: assetName) {
            imageView.image = placeholder
        }

        PreparedGIFAssetStore.shared.loadAnimatedImage(named: assetName) { animatedImage in
            DispatchQueue.main.async {
                guard imageView.accessibilityIdentifier == assetName else { return }
                imageView.image = animatedImage
                imageView.startAnimating()
            }
        }
    }
}

final class PreparedGIFAssetStore: @unchecked Sendable {
    static let shared = PreparedGIFAssetStore()

    private let queue = DispatchQueue(label: "com.crisisconnect.gif-decode", qos: .userInitiated)
    private var animatedImages: [String: UIImage] = [:]
    private var placeholders: [String: UIImage] = [:]
    private let lock = NSLock()

    func cachedAnimatedImage(named assetName: String) -> UIImage? {
        lock.lock()
        defer { lock.unlock() }
        return animatedImages[assetName]
    }

    func cachedPlaceholder(named assetName: String) -> UIImage? {
        lock.lock()
        defer { lock.unlock() }
        if let existing = placeholders[assetName] {
            return existing
        }
        // Decode first frame synchronously (fast, single frame)
        guard let dataAsset = NSDataAsset(name: assetName),
              let source = CGImageSourceCreateWithData(dataAsset.data as CFData, nil),
              let cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            return nil
        }
        let placeholder = UIImage(cgImage: cgImage)
        placeholders[assetName] = placeholder
        return placeholder
    }

    func loadAnimatedImage(named assetName: String, completion: @escaping (UIImage?) -> Void) {
        queue.async { [weak self] in
            guard let self else { return }
            if let cached = self.cachedAnimatedImage(named: assetName) {
                completion(cached)
                return
            }
            let image = self.decodeGIF(named: assetName)
            if let image {
                self.lock.lock()
                self.animatedImages[assetName] = image
                self.lock.unlock()
            }
            completion(image)
        }
    }

    /// Pre-warm a GIF asset on a background queue so it's ready when needed.
    func warmUp(named assetName: String) {
        queue.async { [weak self] in
            guard let self else { return }
            if self.cachedAnimatedImage(named: assetName) != nil { return }
            if let image = self.decodeGIF(named: assetName) {
                self.lock.lock()
                self.animatedImages[assetName] = image
                self.lock.unlock()
            }
        }
    }

    private func decodeGIF(named assetName: String) -> UIImage? {
        guard let dataAsset = NSDataAsset(name: assetName) else { return nil }
        guard let source = CGImageSourceCreateWithData(dataAsset.data as CFData, nil) else { return nil }

        let frameCount = CGImageSourceGetCount(source)
        guard frameCount > 0 else { return nil }

        var frames: [UIImage] = []
        var totalDuration: TimeInterval = 0

        for i in 0..<frameCount {
            guard let cgImage = CGImageSourceCreateImageAtIndex(source, i, nil) else { continue }
            frames.append(UIImage(cgImage: cgImage))

            let frameDuration = Self.frameDuration(at: i, source: source)
            totalDuration += frameDuration
        }

        guard !frames.isEmpty else { return nil }
        return UIImage.animatedImage(with: frames, duration: totalDuration)
    }

    private static func frameDuration(at index: Int, source: CGImageSource) -> TimeInterval {
        let defaultDuration: TimeInterval = 0.1

        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any],
              let gifProperties = properties[kCGImagePropertyGIFDictionary] as? [CFString: Any] else {
            return defaultDuration
        }

        if let unclamped = gifProperties[kCGImagePropertyGIFUnclampedDelayTime] as? Double, unclamped > 0 {
            return unclamped
        }
        if let delay = gifProperties[kCGImagePropertyGIFDelayTime] as? Double, delay > 0 {
            return delay
        }
        return defaultDuration
    }
}
