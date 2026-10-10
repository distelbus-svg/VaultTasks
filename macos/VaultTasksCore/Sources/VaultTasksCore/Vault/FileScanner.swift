import Foundation

public struct FileState: Equatable, Codable, Sendable {
    public let path: String
    public let lastModified: Int64
    public let size: Int64
    public let contentHash: String
    public init(path: String, lastModified: Int64, size: Int64, contentHash: String) {
        self.path = path; self.lastModified = lastModified; self.size = size; self.contentHash = contentHash
    }
}

public struct ScannedFile: Equatable, Codable, Sendable {
    public let state: FileState
    public let tasks: [VaultTask]
    public init(state: FileState, tasks: [VaultTask]) { self.state = state; self.tasks = tasks }
}

public struct ScanResult: Sendable {
    public let files: [String: ScannedFile]
    /// Paths of `files` in listing order (a dictionary has none; the order keeps `changes` deterministic).
    public let order: [String]
    public let changes: Changes
    /// Files actually read and parsed this pass.
    public let reparsed: Int
    /// Files that could not be read this pass (mid-write, invalid UTF-8, I/O error). Previous state is kept.
    public let unreadable: [String]
    /// Every `.md` path in the vault this pass, regardless of `include` (feeds the space file picker).
    public let available: [String]

    public init(files: [String: ScannedFile], order: [String], changes: Changes, reparsed: Int, unreadable: [String], available: [String]) {
        self.files = files; self.order = order; self.changes = changes; self.reparsed = reparsed
        self.unreadable = unreadable; self.available = available
    }

    public var tasks: [VaultTask] { order.flatMap { files[$0]?.tasks ?? [] } }
}

/// Change detection: a file is re-read only if lastModified or size changed; the content hash then confirms a real
/// change. Pure aside from `VaultFileSystem` calls.
public struct FileScanner: Sendable {
    private let fs: VaultFileSystem
    private let globalFilter: @Sendable () -> String

    public init(fs: VaultFileSystem, globalFilter: @escaping @Sendable () -> String = { "" }) {
        self.fs = fs; self.globalFilter = globalFilter
    }

    /// - Parameters:
    ///   - previous: result of the last scan (empty on first scan)
    ///   - include: limits scope (files assigned to spaces); files outside it are dropped
    ///   - force: re-read and re-parse everything (e.g. after the global filter changed)
    public func scan(previous: [String: ScannedFile], include: (String) -> Bool = { _ in true }, force: Bool = false) throws -> ScanResult {
        var out: [String: ScannedFile] = [:]
        var order: [String] = []
        var unreadable: [String] = []
        var reparsed = 0
        let filter = globalFilter()

        let listing = try fs.listMarkdown()
        for info in listing {
            if !include(info.path) { continue }
            let prev = previous[info.path]
            if !force, let prev, prev.state.lastModified == info.lastModified, prev.state.size == info.size {
                out[info.path] = prev; order.append(info.path)
                continue
            }

            let bytes = (try? fs.read(info.path)) ?? nil
            // If the file changed while we were reading it (sync tool mid-write), skip it this pass.
            let after: VaultFileInfo? = bytes != nil ? ((try? fs.stat(info.path)) ?? nil) : nil
            var text: String? = nil
            if let bytes, let after, after.lastModified == info.lastModified, after.size == info.size {
                text = decodeUtf8Strict(bytes)
            }
            guard let bytes, let text else {
                unreadable.append(info.path)
                if let prev { out[info.path] = prev; order.append(info.path) }
                continue
            }

            let state = FileState(path: info.path, lastModified: info.lastModified, size: info.size, contentHash: sha256Hex(bytes))
            if !force, let prev, prev.state.contentHash == state.contentHash {
                out[info.path] = ScannedFile(state: state, tasks: prev.tasks) // touched, not changed
                order.append(info.path)
                continue
            }
            out[info.path] = ScannedFile(state: state, tasks: TaskParser.parseFile(path: info.path, text: VaultText.parse(text), globalFilter: filter))
            order.append(info.path)
            reparsed += 1
        }

        let previousOrder = previous.keys.sorted(by: utf16Less)
        let changes = try Reconciler.reconcile(
            cached: previousOrder.flatMap { previous[$0]!.tasks },
            parsed: order.flatMap { out[$0]!.tasks }
        )
        return ScanResult(files: out, order: order, changes: changes, reparsed: reparsed, unreadable: unreadable,
                          available: listing.map(\.path))
    }
}
