//
//  CrisisSentinelInputProcessor.swift
//  Crisis Connect
//

import Foundation
import UIKit
@preconcurrency import Vision

enum CrisisSentinelInputProcessor {
    private static let maxExtractedCharacters = 12_000
    private static let maxFileBytes = 64 * 1024

    static func extractText(fromImageData data: Data) async throws -> String {
        guard let image = UIImage(data: data)?.cgImage else {
            throw InputError.unreadableImage
        }

        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let observations = (request.results as? [VNRecognizedTextObservation]) ?? []
                let text = observations
                    .compactMap { $0.topCandidates(1).first?.string }
                    .joined(separator: "\n")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                continuation.resume(returning: String(text.prefix(maxExtractedCharacters)))
            }
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.recognitionLanguages = ["tr-TR", "en-US"]

            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try VNImageRequestHandler(cgImage: image).perform([request])
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    static func extractText(fromFileURL url: URL) throws -> String {
        let accessed = url.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                url.stopAccessingSecurityScopedResource()
            }
        }

        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: maxFileBytes + 1) ?? Data()
        let prefix = data.prefix(maxFileBytes)
        guard let text = String(data: prefix, encoding: .utf8)
            ?? String(data: prefix, encoding: .windowsCP1254)
            ?? String(data: prefix, encoding: .isoLatin1) else {
            throw InputError.unsupportedTextEncoding
        }
        return String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxExtractedCharacters))
    }

    enum InputError: Error {
        case unreadableImage
        case unsupportedTextEncoding
    }
}
