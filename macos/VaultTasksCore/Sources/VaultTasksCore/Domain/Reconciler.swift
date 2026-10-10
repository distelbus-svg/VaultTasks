import Foundation

public struct Changes: Sendable {
    public let inserts: [VaultTask]
    public let updates: [VaultTask]
    public let deletes: [TaskId]
    public var isEmpty: Bool { inserts.isEmpty && updates.isEmpty && deletes.isEmpty }
}

public enum ReconcilerError: Error, Sendable { case duplicateId(TaskId) }

public enum Reconciler {
    /// Pure diff by identity. `cached` and `parsed` must cover the same scope (the file(s) just rescanned).
    /// Never produces two rows for one identity: duplicate ids in `parsed` are a bug and throw.
    public static func reconcile(cached: [VaultTask], parsed: [VaultTask]) throws -> Changes {
        var parsedIds = Set<TaskId>()
        for t in parsed where !parsedIds.insert(t.id).inserted { throw ReconcilerError.duplicateId(t.id) }
        let cachedById = Dictionary(cached.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        var seen = Set<TaskId>()
        let deletes = cached.map(\.id).filter { seen.insert($0).inserted }.filter { !parsedIds.contains($0) }
        return Changes(
            inserts: parsed.filter { cachedById[$0.id] == nil },
            updates: parsed.filter { p in cachedById[p.id].map { $0 != p } ?? false },
            deletes: deletes
        )
    }
}
