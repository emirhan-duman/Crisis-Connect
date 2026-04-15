//
//  ChatImageTransfer.swift
//  Crisis Connect
//
//  Created by Codex on 17.03.2026.
//

import Foundation
import UIKit

struct PreparedBleChatImageAttachment {
    let imageRelativePath: String
    let thumbnailRelativePath: String?
    let mimeType: String
    let width: Int
    let height: Int
}

enum ChatImageTransfer {
    static let jpegMimeType = "image/jpeg"

    private static let maxDimension: CGFloat = 1280
    private static let targetBytes = 425_000
    private static let maxOutputBytes = 512 * 1024
    private static let initialCompressionQuality: CGFloat = 0.82
    private static let minCompressionQuality: CGFloat = 0.46
    private static let compressionStep: CGFloat = 0.06
    private static let minimumScaleFactor: CGFloat = 0.55
    private static let maximumScaleFactor: CGFloat = 0.95
    private static let minimumEdge: CGFloat = 320
    private static let thumbnailSide: CGFloat = 512

    static func prepareBleImageAttachment(
        sourceData: Data,
        messageId: String
    ) -> PreparedBleChatImageAttachment? {
        guard let image = UIImage(data: sourceData)?.normalizedOrientationImage() else {
            return nil
        }

        guard let optimized = optimizedJpegImage(from: image) else {
            return nil
        }

        guard let imageRelativePath = SOSChatStore.persistImageData(
            optimized.data,
            messageId: messageId,
            mimeType: jpegMimeType
        ) else {
            return nil
        }

        let thumbnailRelativePath: String?
        if let thumbnailData = optimized.image.thumbnailJPEGData(side: thumbnailSide, compressionQuality: 0.78) {
            thumbnailRelativePath = SOSChatStore.persistImageThumbnailData(
                thumbnailData,
                messageId: messageId,
                mimeType: jpegMimeType
            )
        } else {
            thumbnailRelativePath = nil
        }

        return PreparedBleChatImageAttachment(
            imageRelativePath: imageRelativePath,
            thumbnailRelativePath: thumbnailRelativePath,
            mimeType: jpegMimeType,
            width: Int(optimized.image.size.width.rounded()),
            height: Int(optimized.image.size.height.rounded())
        )
    }
}

private struct OptimizedJpegImage {
    let image: UIImage
    let data: Data
}

private extension ChatImageTransfer {
    static func optimizedJpegImage(from image: UIImage) -> OptimizedJpegImage? {
        var workingImage = image.scaledToFit(maxDimension: maxDimension)

        while true {
            guard let jpegData = workingImage.jpegDataFittingTarget(
                targetBytes: targetBytes,
                initialCompressionQuality: initialCompressionQuality,
                minCompressionQuality: minCompressionQuality,
                compressionStep: compressionStep
            ) else {
                return nil
            }

            if jpegData.count <= maxOutputBytes {
                return OptimizedJpegImage(image: workingImage, data: jpegData)
            }

            guard let nextImage = workingImage.scaledToOutputBudget(
                currentBytes: jpegData.count,
                maxBytes: maxOutputBytes,
                minEdge: minimumEdge,
                minimumScaleFactor: minimumScaleFactor,
                maximumScaleFactor: maximumScaleFactor
            ) else {
                return nil
            }
            workingImage = nextImage
        }
    }
}

extension UIImage {
    func normalizedOrientationImage() -> UIImage? {
        if imageOrientation == .up {
            return self
        }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let rendered = renderer.image { _ in
            draw(in: CGRect(origin: .zero, size: size))
        }
        return rendered
    }

    func scaledToFit(maxDimension: CGFloat) -> UIImage {
        let largestEdge = max(size.width, size.height)
        guard largestEdge > maxDimension, largestEdge > 0 else {
            return self
        }
        let scale = maxDimension / largestEdge
        let targetSize = CGSize(
            width: max(1, floor(size.width * scale)),
            height: max(1, floor(size.height * scale))
        )
        return rendered(to: targetSize) ?? self
    }

    func thumbnailJPEGData(side: CGFloat, compressionQuality: CGFloat) -> Data? {
        let minSide = min(size.width, size.height)
        guard minSide > 0 else { return nil }

        let origin = CGPoint(
            x: (size.width - minSide) / 2,
            y: (size.height - minSide) / 2
        )
        let cropRect = CGRect(origin: origin, size: CGSize(width: minSide, height: minSide))
        let square = cropped(to: cropRect) ?? self
        let thumb = square.rendered(to: CGSize(width: side, height: side)) ?? square
        return thumb.jpegData(compressionQuality: compressionQuality)
    }

    func jpegDataFittingTarget(
        targetBytes: Int,
        initialCompressionQuality: CGFloat,
        minCompressionQuality: CGFloat,
        compressionStep: CGFloat
    ) -> Data? {
        var quality = initialCompressionQuality
        guard var data = jpegData(compressionQuality: quality) else {
            return nil
        }
        while data.count > targetBytes && quality > minCompressionQuality {
            quality = max(minCompressionQuality, quality - compressionStep)
            guard let next = jpegData(compressionQuality: quality) else {
                return nil
            }
            data = next
        }
        return data
    }

    func scaledToOutputBudget(
        currentBytes: Int,
        maxBytes: Int,
        minEdge: CGFloat,
        minimumScaleFactor: CGFloat,
        maximumScaleFactor: CGFloat
    ) -> UIImage? {
        guard currentBytes > maxBytes else { return self }
        let largestEdge = max(size.width, size.height)
        guard largestEdge > minEdge else { return nil }

        let byteRatio = min(CGFloat(maxBytes) / CGFloat(currentBytes), maximumScaleFactor)
        let scale = max(minimumScaleFactor, min(maximumScaleFactor, sqrt(byteRatio)))
        let targetSize = CGSize(
            width: max(1, floor(size.width * scale)),
            height: max(1, floor(size.height * scale))
        )
        guard max(targetSize.width, targetSize.height) < largestEdge else {
            return nil
        }
        return rendered(to: targetSize)
    }

    func rendered(to size: CGSize) -> UIImage? {
        guard size.width > 0, size.height > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in
            draw(in: CGRect(origin: .zero, size: size))
        }
    }

    func cropped(to rect: CGRect) -> UIImage? {
        guard let cgImage else { return nil }
        let scaledRect = CGRect(
            x: rect.origin.x * scale,
            y: rect.origin.y * scale,
            width: rect.size.width * scale,
            height: rect.size.height * scale
        ).integral
        guard let cropped = cgImage.cropping(to: scaledRect) else { return nil }
        return UIImage(cgImage: cropped, scale: scale, orientation: .up)
    }
}
