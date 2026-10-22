import Foundation

enum MessagingInstallation {
    /// A backup-excluded random ID, shared by this installation's FCM and PushKit registrations.
    static let id: String = {
        let manager = FileManager.default
        let base = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var directory = base.appendingPathComponent("MessagingInstallation", isDirectory: true)
        try? manager.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? directory.setResourceValues(values)
        let file = directory.appendingPathComponent("id")
        if let existing = try? String(contentsOf: file, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines),
           existing.range(of: #"^[A-Za-z0-9_-]{1,128}$"#, options: .regularExpression) != nil { return existing }
        let id = UUID().uuidString
        try? id.write(to: file, atomically: true, encoding: .utf8)
        return id
    }()
}
