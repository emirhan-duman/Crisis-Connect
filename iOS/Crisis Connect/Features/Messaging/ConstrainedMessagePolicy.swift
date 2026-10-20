import Foundation

enum ConstrainedMessagePolicy {
    static let maximumTextBytes = 1_024

    static func allowsText(templateCode: Int, text: String) -> Bool {
        (templateCode == freeTextTemplate || templateCode == sosAlertTemplate) &&
            text.utf8.count <= maximumTextBytes
    }

    private static let freeTextTemplate = 0
    private static let sosAlertTemplate = 202
}
