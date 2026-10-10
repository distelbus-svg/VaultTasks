import Foundation

public enum FileStatus: Equatable, Sendable {
    case inSync
    /// Changed here, unchanged on the server.
    case localChanged
    /// Changed on the server, unchanged here.
    case remoteChanged
    /// Changed on both sides, or first contact with different content: the newer version wins.
    case bothChanged
    /// Not on the server yet.
    case notOnServer
    /// On the server but missing locally. Not recreated.
    case missingLocally
    /// Was synced before, now gone from the server. Deletions are never propagated.
    case deletedOnServer
    /// Neither side has it.
    case gone
    case error
}

public struct FileSyncState: Equatable, Sendable {
    public let path: String
    public let status: FileStatus
    public let detail: String?
}
public struct Skipped: Equatable, Sendable { public let path: String; public let reason: String }
public struct SyncFailure: Equatable, Sendable { public let path: String; public let message: String }

public struct SyncReport: Equatable, Sendable {
    public var pulled: [String] = []
    public var pushed: [String] = []
    public var skipped: [Skipped] = []
    public var failed: [SyncFailure] = []
    public init() {}
}

/// Two-way sync, newest version wins. A file changed on one side only is carried over; changed on both sides, the one
/// with the later modification time survives (ties go to the server). Uploads are conditional on the server token
/// that was just read, so a server change that lands in between is never overwritten; it is picked up next run.
public struct SyncEngine: Sendable {
    private let local: LocalFiles
    private let remote: RemoteStore
    private let records: SyncRecords

    public init(local: LocalFiles, remote: RemoteStore, records: SyncRecords) {
        self.local = local; self.remote = remote; self.records = records
    }

    private struct Snap {
        var status: FileStatus
        var localBytes: Data? = nil
        var localHash: String? = nil
        var remoteData: RemoteData? = nil
    }

    /// Read-only status of every path (the only side effect is adopting identical content as in sync).
    public func check(_ paths: [String]) async -> [FileSyncState] {
        var out: [FileSyncState] = []
        for path in paths {
            do { out.append(FileSyncState(path: path, status: try await classify(path).status, detail: nil)) }
            catch { out.append(FileSyncState(path: path, status: .error, detail: Self.message(error))) }
        }
        return out
    }

    public func sync(_ paths: [String]) async -> SyncReport {
        var report = SyncReport()
        for path in paths {
            do {
                let snap = try await classify(path)
                switch snap.status {
                case .localChanged: try await push(path, snap, records.get(path)?.token, &report)
                case .notOnServer: try await push(path, snap, nil, &report)
                case .remoteChanged: try await pull(path, snap, &report)
                case .bothChanged:
                    if try await localIsNewer(path, snap) { try await push(path, snap, snap.remoteData?.token, &report) }
                    else { try await pull(path, snap, &report) }
                case .missingLocally: report.skipped.append(Skipped(path: path, reason: "Missing locally, not recreated"))
                case .deletedOnServer: report.skipped.append(Skipped(path: path, reason: "Deleted on the server, kept locally"))
                default: break
                }
            } catch {
                report.failed.append(SyncFailure(path: path, message: Self.message(error)))
            }
        }
        return report
    }

    // ---- internals ----------------------------------------------------------------------------------------

    private static func message(_ e: Error) -> String { (e as? LocalizedError)?.errorDescription ?? String(describing: e) }

    private func localIsNewer(_ path: String, _ snap: Snap) async throws -> Bool {
        let info: VaultFileInfo? = try await local.atomically { try $0.stat(path) }
        guard let localTime = info?.lastModified, let remoteTime = snap.remoteData?.modified else { return false }
        return localTime > remoteTime
    }

    private func push(_ path: String, _ snap: Snap, _ expectedToken: String?, _ report: inout SyncReport) async throws {
        guard let bytes = snap.localBytes else { return }
        switch try await remote.put(path, bytes, expectedToken: expectedToken) {
        case .ok(let token):
            records.put(path, SyncRecord(hash: sha256Hex(bytes), token: token))
            report.pushed.append(path)
        case .preconditionFailed:
            report.skipped.append(Skipped(path: path, reason: "Server changed meanwhile, will sync on the next run"))
        case .failed(let m):
            report.failed.append(SyncFailure(path: path, message: m))
        }
    }

    private func pull(_ path: String, _ snap: Snap, _ report: inout SyncReport) async throws {
        let fetched: RemoteData?
        if let d = snap.remoteData { fetched = d } else { fetched = try await remote.get(path) }
        guard let data = fetched else {
            report.skipped.append(Skipped(path: path, reason: "Disappeared from the server"))
            return
        }
        // Replace only if the local file is still exactly what was classified.
        let expectedHash = snap.localHash
        let bytes = data.bytes
        let replaced: Bool = try await local.atomically { fs in
            if let cur = try fs.read(path), sha256Hex(cur) == expectedHash {
                try fs.write(path, bytes)
                return true
            }
            return false
        }
        if replaced {
            records.put(path, SyncRecord(hash: sha256Hex(data.bytes), token: data.token))
            report.pulled.append(path)
        } else {
            report.skipped.append(Skipped(path: path, reason: "Changed locally meanwhile, will sync on the next run"))
        }
    }

    private func classify(_ path: String) async throws -> Snap {
        let bytes: Data? = try await local.atomically { try $0.read(path) }
        let hash = bytes.map(sha256Hex)
        let stat = try await remote.stat(path)
        guard let bytes, let hash else { return Snap(status: stat == nil ? .gone : .missingLocally) }
        let rec = records.get(path)
        guard let stat else {
            return Snap(status: rec == nil ? .notOnServer : .deletedOnServer, localBytes: bytes, localHash: hash)
        }
        guard let rec else {
            // First contact: identical content is adopted silently, anything else goes to the newer version.
            guard let data = try await remote.get(path) else { return Snap(status: .notOnServer, localBytes: bytes, localHash: hash) }
            return compare(path, bytes, hash, data)
        }
        let localChanged = hash != rec.hash
        let remoteChanged = stat.token != rec.token
        switch (localChanged, remoteChanged) {
        case (false, false): return Snap(status: .inSync, localBytes: bytes, localHash: hash)
        case (true, false): return Snap(status: .localChanged, localBytes: bytes, localHash: hash)
        case (false, true): return Snap(status: .remoteChanged, localBytes: bytes, localHash: hash)
        case (true, true):
            // Both changed: the same edit on both sides needs no action.
            guard let data = try await remote.get(path) else { return Snap(status: .deletedOnServer, localBytes: bytes, localHash: hash) }
            return compare(path, bytes, hash, data)
        }
    }

    private func compare(_ path: String, _ bytes: Data, _ hash: String, _ data: RemoteData) -> Snap {
        if sha256Hex(data.bytes) == hash {
            records.put(path, SyncRecord(hash: hash, token: data.token))
            return Snap(status: .inSync, localBytes: bytes, localHash: hash, remoteData: data)
        }
        return Snap(status: .bothChanged, localBytes: bytes, localHash: hash, remoteData: data)
    }
}
