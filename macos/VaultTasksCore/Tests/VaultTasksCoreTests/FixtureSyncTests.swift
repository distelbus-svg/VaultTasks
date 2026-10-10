import Testing
import Foundation
import CryptoKit
@testable import VaultTasksCore

@Suite struct FixtureSyncTests {
    /// The Swift copy of parser-fixtures.json must be byte-identical to the Android original (single source of truth).
    /// Only checkable while the Android project sits next to macos/ in the monorepo.
    @Test func resourceCopyMatchesAndroidOriginal() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() } // file → tests dir → Tests → package → macos → repo
        let original = root.appendingPathComponent("domain/src/test/resources/parser-fixtures.json")
        guard FileManager.default.fileExists(atPath: original.path) else { return } // standalone checkout: nothing to compare
        let copy = try #require(Bundle.module.url(forResource: "parser-fixtures", withExtension: "json")
            ?? Bundle.module.url(forResource: "parser-fixtures", withExtension: "json", subdirectory: "Resources"))
        let a = SHA256.hash(data: try Data(contentsOf: original)), b = SHA256.hash(data: try Data(contentsOf: copy))
        #expect(a == b, "run macos/scripts/sync-fixtures.sh")
    }
}
