import XCTest
@testable import Crisis_Connect

final class AuthorityMlsMessagePayloadTests: XCTestCase {
    func testRoundTripsCanonicalCrossPlatformEnvelope() throws {
        let payload = AuthorityMlsMessagePayload(
            recipientUid: "peer-uid",
            recipientName: "Peer",
            senderName: "Sender",
            text: "hello",
            sentAtMillis: 1_700_000_000_000,
            attachments: [ChannelAttachment(
                path: "authorityMessageAttachments/am2_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/sender-uid/00000000-0000-4000-8000-000000000051",
                nonce: "AAAAAAAAAAAAAAAA",
                keyBase64: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                name: "incident.pdf",
                mime: "application/pdf",
                size: 42
            )],
            replyToId: "m_parent"
        )
        let decoded = try AuthorityMlsMessagePayloadCodec.decode(
            AuthorityMlsMessagePayloadCodec.encode(payload)
        )
        XCTAssertEqual(decoded.recipientUid, payload.recipientUid)
        XCTAssertEqual(decoded.text, payload.text)
        XCTAssertEqual(decoded.attachments, payload.attachments)
        XCTAssertEqual(decoded.replyToId, payload.replyToId)
    }

    func testRejectsMissingPerFileKey() throws {
        let payload = AuthorityMlsMessagePayload(
            recipientUid: "peer",
            recipientName: "Peer",
            senderName: "Sender",
            text: "file",
            sentAtMillis: 1,
            attachments: [ChannelAttachment(
                path: "authorityMessageAttachments/am2_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/sender/00000000-0000-4000-8000-000000000051",
                nonce: "AAAAAAAAAAAAAAAA",
                name: "x",
                mime: "application/octet-stream",
                size: 1
            )],
            replyToId: nil
        )
        XCTAssertThrowsError(try AuthorityMlsMessagePayloadCodec.encode(payload))
    }
}
