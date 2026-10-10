import Foundation

/// Identity of a remote file's content: the strong ETag (quoted, as sent by the server) when there is one, otherwise a
/// synthetic `lm|<last-modified>|<size>` string. Only compared for equality, never parsed.
/// `modified`: server's last-modified time in epoch millis, nil if unknown.
public struct RemoteStat: Equatable, Sendable {
    public let token: String
    public let modified: Int64?
    public init(token: String, modified: Int64? = nil) { self.token = token; self.modified = modified }
}

public struct RemoteData: Equatable, Sendable {
    public let bytes: Data
    public let token: String
    public let modified: Int64?
    public init(bytes: Data, token: String, modified: Int64? = nil) { self.bytes = bytes; self.token = token; self.modified = modified }
}

public enum PutResult: Equatable, Sendable {
    case ok(token: String)
    /// The remote file is not in the state the caller last saw (HTTP 412). Nothing was written.
    case preconditionFailed
    case failed(String)
}

/// WebDAV seam. Paths are vault-relative; the implementation maps them below its base folder.
public protocol RemoteStore: Sendable {
    /// nil if the file does not exist. Throws for network/auth errors.
    func stat(_ path: String) async throws -> RemoteStat?

    /// nil if the file does not exist.
    func get(_ path: String) async throws -> RemoteData?

    /// Writes `bytes`. `expectedToken` nil = create only (`.preconditionFailed` if the file exists); otherwise replaces
    /// only if the remote file still has that token.
    func put(_ path: String, _ bytes: Data, expectedToken: String?) async throws -> PutResult
}

/// Last state both sides agreed on, per file: content hash of the local file and the remote token at that time.
public struct SyncRecord: Equatable, Codable, Sendable {
    public let hash: String
    public let token: String
    public init(hash: String, token: String) { self.hash = hash; self.token = token }
}

public protocol SyncRecords: Sendable {
    func get(_ path: String) -> SyncRecord?
    func put(_ path: String, _ record: SyncRecord)
    func clear()
}

/// Local file access where each block runs with the vault's write lock held, so read-check-write is atomic.
/// The app implements it on the `VaultRepository` actor.
public protocol LocalFiles: Sendable {
    func atomically<T: Sendable>(_ block: @Sendable (any VaultFileSystem) throws -> T) async throws -> T
}
