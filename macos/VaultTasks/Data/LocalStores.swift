import Foundation
import VaultTasksCore

enum AppSupport {
    static var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "VaultTasks", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }
}

/// A small JSON file in Application Support (§6). Writes are atomic, so a failure never corrupts the previous file;
/// a decode error means "no data" (callers fall back to empty state), never a crash.
struct JSONFile<T: Codable & Sendable>: Sendable {
    let url: URL

    init(_ name: String, directory: URL? = nil) {
        url = (directory ?? AppSupport.directory).appending(path: name, directoryHint: .notDirectory)
    }

    func load() -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    func save(_ value: T) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }
}

/// Device-local list preferences (§6). Orders are lists of task keys per `spaceId|date` / `spaceId|cat`.
struct ViewPrefs: Codable, Sendable, Equatable {
    var groupByCategory: [String: Bool] = [:]
    var taskOrders: [String: [String]] = [:]
    var categoryOrders: [String: [String]] = [:]
    /// Category name → ARGB, chosen when the category was created (the vault only holds the #tag).
    var categoryColors: [String: UInt32] = [:]

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        groupByCategory = try c.decodeIfPresent([String: Bool].self, forKey: .groupByCategory) ?? [:]
        taskOrders = try c.decodeIfPresent([String: [String]].self, forKey: .taskOrders) ?? [:]
        categoryOrders = try c.decodeIfPresent([String: [String]].self, forKey: .categoryOrders) ?? [:]
        categoryColors = try c.decodeIfPresent([String: UInt32].self, forKey: .categoryColors) ?? [:]
    }
}

/// Per-task "remind me before" in minutes (0 = at the time = no entry). Device-local because the line format has no
/// field for it (§4.1). Keyed by `AlarmPlanner.leadKey` (path + description) so it survives date/time/state edits.
/// Thread-safe: notification actions (Phase 5) read it outside the main actor.
final class LeadStore: @unchecked Sendable {
    private let lock = NSLock()
    private let file: JSONFile<[String: Int]>
    private var leads: [String: Int]

    init(directory: URL? = nil) {
        file = JSONFile("leads_v1.json", directory: directory)
        leads = file.load() ?? [:]
    }

    func get(path: String, description: String) -> Int {
        lock.lock(); defer { lock.unlock() }
        return leads[AlarmPlanner.leadKey(path: path, description: description)] ?? 0
    }

    var all: [String: Int] { lock.lock(); defer { lock.unlock() }; return leads }

    /// After the editor saved: the task may have moved file or changed description, so the old entry goes.
    func replace(oldPath: String?, oldDescription: String?, path: String, description: String, minutes: Int) {
        lock.lock(); defer { lock.unlock() }
        if let oldPath, let oldDescription { leads[AlarmPlanner.leadKey(path: oldPath, description: oldDescription)] = nil }
        let key = AlarmPlanner.leadKey(path: path, description: description)
        if minutes > 0 { leads[key] = minutes } else { leads[key] = nil }
        file.save(leads)
    }

    /// Drops entries whose task is gone. Touches only `keys`, so entries added meanwhile are never pruned by an older scan.
    func remove(keys: some Collection<String>) {
        lock.lock(); defer { lock.unlock() }
        guard !keys.isEmpty else { return }
        for k in keys { leads[k] = nil }
        file.save(leads)
    }
}
