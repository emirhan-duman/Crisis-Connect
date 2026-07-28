//
//  MeshImageFileStore.swift
//  Crisis Connect
//
//  Disk storage + transfer preparation for authority-mesh image blobs.
//

import Foundation
import UIKit

/// Stores mesh image blobs as plain JPEG files under Application Support, keyed by blob id.
enum MeshImageFileStore {

    private static let directoryURL: URL = {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return baseURL
            .appendingPathComponent("CrisisConnect", isDirectory: true)
            .appendingPathComponent("MeshImages", isDirectory: true)
    }()

    static func url(for fileName: String) -> URL {
        directoryURL.appendingPathComponent(fileName, isDirectory: false)
    }

    /// Writes the JPEG bytes for a blob and returns the stored file name.
    static func save(data: Data, blobId: String) -> String? {
        save(data: data, blobId: blobId, fileExtension: "jpg")
    }

    /// Writes blob bytes with the given extension (e.g. voice notes as .m4a).
    static func save(data: Data, blobId: String, fileExtension: String) -> String? {
        let fileName = "\(blobId).\(fileExtension)"
        do {
            try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
            try data.write(to: url(for: fileName), options: [.atomic])
            return fileName
        } catch {
            return nil
        }
    }

    static func loadImage(fileName: String) -> UIImage? {
        UIImage(contentsOfFile: url(for: fileName).path)
    }

    /// Downscales + JPEG-compresses an image until it fits the mesh blob budget
    /// (mirrors Android's MESH_IMAGE_TRANSFER_PROFILE: max dimension 1280, target ≤360KB, hard cap 400KB).
    static func prepareForTransfer(_ image: UIImage) -> (data: Data, width: Int, height: Int)? {
        let maxDimension: CGFloat = 1_280
        let targetBytes = 360_000
        let hardCapBytes = GattMeshProtocol.meshImageMaxPlainBytes

        var working = image
        let largestSide = max(image.size.width, image.size.height) * image.scale
        if largestSide > maxDimension {
            let scale = maxDimension / largestSide
            let newSize = CGSize(
                width: max(1, image.size.width * image.scale * scale),
                height: max(1, image.size.height * image.scale * scale)
            )
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1
            working = UIGraphicsImageRenderer(size: newSize, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: newSize))
            }
        }

        var quality: CGFloat = 0.84
        var data = working.jpegData(compressionQuality: quality)
        while let current = data, current.count > targetBytes, quality > 0.4 {
            quality -= 0.08
            data = working.jpegData(compressionQuality: quality)
        }
        guard let resolved = data, !resolved.isEmpty, resolved.count <= hardCapBytes else {
            return nil
        }
        let width = Int(working.size.width * working.scale)
        let height = Int(working.size.height * working.scale)
        return (resolved, width, height)
    }
}
