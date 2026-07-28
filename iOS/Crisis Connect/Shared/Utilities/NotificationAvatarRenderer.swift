//
//  NotificationAvatarRenderer.swift
//  Crisis Connect
//
//  Produces the INImage used as an INPerson avatar in communication notifications, so incoming
//  message / call notifications show the sender's photo (or a colored-initials avatar) instead of
//  the system's generic gray silhouette. iOS port of Android's NotificationAvatarFormatter.
//
//  The generated initials avatar reuses AvatarGenerator's initials + per-session hue, so the
//  notification avatar matches the in-app chat avatar for the same contact exactly.
//

import Intents
import UIKit

enum NotificationAvatarRenderer {

    private static let size: CGFloat = 96

    /// Avatar for a chat session: the saved contact photo if present, else a colored-initials
    /// circle. Returns nil only when nothing can be produced (empty name + no photo).
    static func image(sessionId: UUID, displayName: String) -> INImage? {
        if let photo = savedPhotoAvatar(sessionId: sessionId) {
            return photo
        }
        return initialsAvatar(
            initials: AvatarGenerator.initials(from: displayName),
            hue: AvatarGenerator.hue(for: sessionId)
        )
    }

    private static func savedPhotoAvatar(sessionId: UUID) -> INImage? {
        let fileName = SOSChatStore.sessionAvatarFileName(sessionId: sessionId)
        guard let data = SOSChatStore.loadSessionAvatarData(fileName: fileName),
              let uiImage = UIImage(data: data),
              let circular = circularCrop(uiImage),
              let pngData = circular.pngData() else {
            return nil
        }
        return INImage(imageData: pngData)
    }

    /// A filled circle in the session's hue with the contact's initials centered in white — the
    /// same look as Android's generateInitialAvatarBitmap and the in-app ChatAvatarCircleView.
    private static func initialsAvatar(initials: String, hue: Double) -> INImage? {
        let trimmed = initials.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        let dimension = CGSize(width: size, height: size)
        let renderer = UIGraphicsImageRenderer(size: dimension)
        let image = renderer.image { context in
            let rect = CGRect(origin: .zero, size: dimension)
            // Mid saturation/brightness reads well on both light and dark notification chrome.
            UIColor(hue: CGFloat(hue), saturation: 0.6, brightness: 0.85, alpha: 1).setFill()
            context.cgContext.fillEllipse(in: rect)

            let paragraph = NSMutableParagraphStyle()
            paragraph.alignment = .center
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: size * 0.42, weight: .bold),
                .foregroundColor: UIColor.white,
                .paragraphStyle: paragraph
            ]
            let text = trimmed as NSString
            let textSize = text.size(withAttributes: attributes)
            let textRect = CGRect(
                x: 0,
                y: (size - textSize.height) / 2,
                width: size,
                height: textSize.height
            )
            text.draw(in: textRect, withAttributes: attributes)
        }
        guard let pngData = image.pngData() else { return nil }
        return INImage(imageData: pngData)
    }

    /// Center-crop to a square and mask to a circle so a rectangular photo renders as a round avatar.
    private static func circularCrop(_ image: UIImage) -> UIImage? {
        let side = min(image.size.width, image.size.height)
        guard side > 0 else { return nil }
        let square = CGSize(width: side, height: side)
        let origin = CGPoint(x: (image.size.width - side) / 2, y: (image.size.height - side) / 2)

        let renderer = UIGraphicsImageRenderer(size: square)
        return renderer.image { _ in
            let rect = CGRect(origin: .zero, size: square)
            UIBezierPath(ovalIn: rect).addClip()
            image.draw(in: CGRect(origin: CGPoint(x: -origin.x, y: -origin.y), size: image.size))
        }
    }
}
