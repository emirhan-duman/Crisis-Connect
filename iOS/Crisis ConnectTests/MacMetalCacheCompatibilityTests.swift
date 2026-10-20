import XCTest
@testable import Crisis_Connect

final class MacMetalCacheCompatibilityTests: XCTestCase {
    private var root: URL!
    private var source: URL { root.appendingPathComponent("darwin/app/com.apple.metal") }
    private var destination: URL { root.appendingPathComponent("container/Library/Caches/MacMetalShaderCache") }

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: root)
    }

    private func put(_ text: String, in directory: URL, name: String = "shader.data") throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(text.utf8).write(to: directory.appendingPathComponent(name))
    }

    func testMovesExistingCacheAndResolvesFutureWritesInsideContainer() throws {
        try put("existing shader", in: source)
        try MacMetalCacheCompatibility.containCache(at: source, in: destination)
        XCTAssertEqual(source.resolvingSymlinksInPath(), destination.resolvingSymlinksInPath())
        XCTAssertEqual(try String(contentsOf: destination.appendingPathComponent("shader.data"), encoding: .utf8), "existing shader")
        try put("new lock", in: source, name: "lock.mdb")
        XCTAssertTrue(FileManager.default.fileExists(atPath: destination.appendingPathComponent("lock.mdb").path))
    }

    func testRepeatedStartupPreservesCache() throws {
        try MacMetalCacheCompatibility.containCache(at: source, in: destination)
        try put("shader", in: source)
        try MacMetalCacheCompatibility.containCache(at: source, in: destination)
        XCTAssertEqual(try String(contentsOf: source.appendingPathComponent("shader.data"), encoding: .utf8), "shader")
    }

    func testRecreatesDestinationAfterOSPurgesCache() throws {
        try MacMetalCacheCompatibility.containCache(at: source, in: destination)
        try FileManager.default.removeItem(at: destination)
        try MacMetalCacheCompatibility.containCache(at: source, in: destination)
        try put("rebuilt shader", in: source)
        XCTAssertTrue(FileManager.default.fileExists(atPath: destination.appendingPathComponent("shader.data").path))
    }

    func testKeepsBothCachesAfterInterruptedMigration() throws {
        try put("original shader", in: source)
        try put("new shader", in: destination)
        try MacMetalCacheCompatibility.containCache(at: source, in: destination)
        XCTAssertEqual(try String(contentsOf: destination.appendingPathComponent("shader.data"), encoding: .utf8), "new shader")
        let backups = try FileManager.default.contentsOfDirectory(at: destination.deletingLastPathComponent(), includingPropertiesForKeys: nil)
            .filter { $0.lastPathComponent.hasPrefix("MacMetalShaderCache-previous-") }
        XCTAssertEqual(backups.count, 1)
        XCTAssertEqual(try String(contentsOf: XCTUnwrap(backups.first).appendingPathComponent("shader.data"), encoding: .utf8), "original shader")
    }

    func testDoesNotRetargetExistingForeignAlias() throws {
        let other = root.appendingPathComponent("other")
        try put("untouched", in: other)
        try FileManager.default.createDirectory(at: source.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: source, withDestinationURL: other)
        XCTAssertThrowsError(try MacMetalCacheCompatibility.containCache(at: source, in: destination))
        XCTAssertEqual(source.resolvingSymlinksInPath(), other.resolvingSymlinksInPath())
    }

    func testRepairsDanglingManagedAliasAfterContainerReplacement() throws {
        let previous = root.appendingPathComponent("old-container/MacMetalShaderCache")
        try FileManager.default.createDirectory(at: source.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: source, withDestinationURL: previous)
        try MacMetalCacheCompatibility.containCache(at: source, in: destination)
        try put("new shader", in: source)
        XCTAssertTrue(FileManager.default.fileExists(atPath: destination.appendingPathComponent("shader.data").path))
    }

    func testRestoresOriginalCacheIfDestinationCannotBeCreated() throws {
        try put("original shader", in: source)
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("existing file".utf8).write(to: destination)
        XCTAssertThrowsError(try MacMetalCacheCompatibility.containCache(at: source, in: destination))
        XCTAssertEqual(try String(contentsOf: source.appendingPathComponent("shader.data"), encoding: .utf8), "original shader")
        XCTAssertEqual(try String(contentsOf: destination, encoding: .utf8), "existing file")
    }
}
