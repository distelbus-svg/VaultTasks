import Foundation

public struct VaultFileInfo: Equatable, Sendable {
    public let path: String
    /// Modification time in epoch milliseconds (also what sync compares against the server's time).
    public let lastModified: Int64
    public let size: Int64
    public init(path: String, lastModified: Int64, size: Int64) {
        self.path = path; self.lastModified = lastModified; self.size = size
    }
}

/// Vault I/O seam. Blocking; the repository actor serializes callers. Paths are vault-relative and '/'-separated.
/// The app implements it over security-scoped real paths; tests use an in-memory fake.
public protocol VaultFileSystem: Sendable {
    /// Every `.md` file under the vault root, excluding dot-directories (.obsidian, .git, .trash, ...).
    func listMarkdown() throws -> [VaultFileInfo]

    /// nil if the file does not exist.
    func stat(_ path: String) throws -> VaultFileInfo?

    /// nil if the file does not exist.
    func read(_ path: String) throws -> Data?

    /// Replaces the whole file content. Throws on failure. The app's implementation is an atomic replace.
    func write(_ path: String, _ bytes: Data) throws
}

/// Strict UTF-8 decode; nil if the bytes are not valid UTF-8. A BOM is kept as U+FEFF (VaultText handles it).
/// Lossy decoding would silently corrupt a file on the next edit, so callers must refuse such files.
/// (`String(data:encoding:)` is avoided on purpose: it may drop the BOM.)
func decodeUtf8Strict(_ data: Data) -> String? { String(validating: data, as: UTF8.self) }
