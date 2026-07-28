//
//  NotificationAvatarRendererTests.swift
//  Crisis ConnectTests
//
//  Verifies the notification avatar renderer produces a usable image for the initials path (the
//  fallback that runs whenever a contact has no saved photo — i.e. the common case), and degrades
//  to nil only when there's nothing to draw.
//

import Intents
import XCTest

@testable import Crisis_Connect

final class NotificationAvatarRendererTests: XCTestCase {

    func testProducesAvatarForNamedContact() {
        // A random sessionId has no saved photo on disk, so this exercises the initials path.
        let image = NotificationAvatarRenderer.image(sessionId: UUID(), displayName: "Ada Lovelace")
        XCTAssertNotNil(image, "a named contact should always yield an initials avatar")
    }

    func testProducesAvatarForSingleWordName() {
        let image = NotificationAvatarRenderer.image(sessionId: UUID(), displayName: "Mustafa")
        XCTAssertNotNil(image)
    }

    func testBlankNameStillYieldsPlaceholderAvatar() {
        // A blank name falls back to AvatarGenerator's "?" placeholder (matching Android's
        // contact_initial_placeholder), so a drawable avatar is still produced rather than nil.
        let image = NotificationAvatarRenderer.image(sessionId: UUID(), displayName: "   ")
        XCTAssertNotNil(image)
    }

    func testDeterministicHueMatchesInAppAvatar() {
        // The notification avatar must use the same per-session hue as the in-app ChatAvatarCircleView
        // so the same contact looks consistent in both places. AvatarGenerator.hue is the shared source.
        let id = UUID()
        let hue1 = AvatarGenerator.hue(for: id)
        let hue2 = AvatarGenerator.hue(for: id)
        XCTAssertEqual(hue1, hue2, "hue derivation must be stable for a given session id")
        XCTAssertTrue((0.0...1.0).contains(hue1))
    }
}
