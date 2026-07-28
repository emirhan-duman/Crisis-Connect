//
//  ContentProfileToolbar.swift
//  Crisis Connect
//
//  Created by Assistant on 28.03.2026.
//

import Combine
import SwiftUI
import UIKit

struct ProfileToolbarLabelView: View {
    @StateObject private var toolbarState: ProfileToolbarState
    private let avatarSize: CGFloat

    init() {
        let avatarSize: CGFloat = 28
        self.avatarSize = avatarSize
        _toolbarState = StateObject(wrappedValue: ProfileToolbarState(avatarSize: avatarSize))
    }

    var body: some View {
        HStack(spacing: 8) {
            avatarView

            if let displayName = toolbarState.displayName {
                Text(displayName)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .foregroundStyle(Color.primary)
            } else {
                Text("Profile")
                    .lineLimit(1)
                    .foregroundStyle(Color.primary)
            }
        }
        .modifier(LegacyToolbarChipBackground())
        .fixedSize(horizontal: true, vertical: true)
        .transaction { $0.animation = nil }
        .onAppear {
            toolbarState.refresh(avatarSize: avatarSize)
        }
        .onReceive(NotificationCenter.default.publisher(for: .profileMetadataStoreDidChange)) { _ in
            toolbarState.refresh(avatarSize: avatarSize)
        }
    }

    private var avatarView: some View {
        Group {
            if let avatarImage = toolbarState.avatarImage {
                Image(uiImage: avatarImage)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "person.circle.fill")
                    .resizable()
                    .scaledToFit()
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
                    .padding(4)
            }
        }
        .frame(width: avatarSize, height: avatarSize)
        .background(Circle().fill(Color.appRowBackground))
        .clipShape(Circle())
        .overlay(Circle().stroke(Color.primary.opacity(0.2), lineWidth: 1))
    }
}

/// On iOS 26+ the system wraps toolbar items in Liquid Glass, so the chip draws
/// no background of its own — the old solid `appSurfaceMuted` pill is a pale
/// blue that read as a bluish, "pressed" shape. On earlier iOS there's no glass,
/// so keep the original muted capsule.
private struct LegacyToolbarChipBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
        } else {
            content
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(
                    Capsule(style: .continuous)
                        .fill(Color.appSurfaceMuted)
                )
                .overlay(
                    Capsule(style: .continuous)
                        .stroke(Color.appBorder.opacity(0.28), lineWidth: 1)
                )
        }
    }
}

struct ProfileToolbarCompactView: View {
    @StateObject private var toolbarState: ProfileToolbarState
    private let avatarSize: CGFloat

    init() {
        let avatarSize: CGFloat = 24
        self.avatarSize = avatarSize
        _toolbarState = StateObject(wrappedValue: ProfileToolbarState(avatarSize: avatarSize))
    }

    var body: some View {
        Group {
            if let avatarThumbnail = toolbarState.avatarImage {
                Image(uiImage: avatarThumbnail)
                    .renderingMode(.original)
            } else {
                Image(systemName: "person.circle")
                    .resizable()
                    .scaledToFit()
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
                    .padding(4)
            }
        }
        .frame(width: avatarSize, height: avatarSize)
        .background(Circle().fill(Color.appRowBackground))
        .clipShape(Circle())
        .overlay(Circle().stroke(Color.primary.opacity(0.2), lineWidth: 1))
        .onAppear {
            toolbarState.refresh(avatarSize: avatarSize)
        }
        .onReceive(NotificationCenter.default.publisher(for: .profileMetadataStoreDidChange)) { _ in
            toolbarState.refresh(avatarSize: avatarSize)
        }
    }
}

@MainActor
private final class ProfileToolbarState: ObservableObject {
    @Published private(set) var displayName: String?
    @Published private(set) var avatarImage: UIImage?

    private var lastSignature = ""

    init(avatarSize: CGFloat) {
        let resolved = Self.resolveToolbarSnapshot(avatarSize: avatarSize)
        displayName = resolved.displayName
        avatarImage = resolved.avatarImage
        lastSignature = resolved.signature
    }

    func refresh(avatarSize: CGFloat) {
        let resolved = Self.resolveToolbarSnapshot(avatarSize: avatarSize)
        guard resolved.signature != lastSignature else { return }
        lastSignature = resolved.signature
        displayName = resolved.displayName
        avatarImage = resolved.avatarImage
    }

    private static func resolveToolbarSnapshot(avatarSize: CGFloat) -> ToolbarSnapshot {
        let normalizedName = ProfileMetadataStore.loadFullName()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let name = normalizedName.isEmpty ? nil : normalizedName
        let avatarData = ProfileMetadataStore.loadAvatarThumbnailData()
        let signature = "\(name ?? "")|\(avatarData?.count ?? -1)"
        let decodedImage = avatarData.flatMap { data in
            UIImage(data: data)?.normalizedOrientationImage()
        }
        let image = decodedImage?.squareThumbnail(side: avatarSize)

        return ToolbarSnapshot(
            displayName: name,
            avatarImage: image,
            signature: signature
        )
    }
}

private struct ToolbarSnapshot {
    let displayName: String?
    let avatarImage: UIImage?
    let signature: String
}

private extension UIImage {
    func squareThumbnail(side: CGFloat) -> UIImage {
        let minSide = min(size.width, size.height)
        let origin = CGPoint(x: (size.width - minSide) / 2, y: (size.height - minSide) / 2)
        let cropRect = CGRect(origin: origin, size: CGSize(width: minSide, height: minSide))
        guard let cgImage = cgImage?.cropping(to: cropRect) else { return self }
        let cropped = UIImage(cgImage: cgImage, scale: scale, orientation: imageOrientation)
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        return renderer.image { _ in
            cropped.draw(in: CGRect(origin: .zero, size: CGSize(width: side, height: side)))
        }
    }
}
