import Foundation
import VaultTasksCore

/// Scan results as JSON so the list is shown instantly at launch, then verified by a stat-gated scan (§6).
/// Keyed by vault path + global filter; a mismatch or a decode error simply means "no cache".
struct VaultCache: Sendable {
    struct Payload: Codable {
        var key: String
        var order: [String]
        var files: [String: ScannedFile]
    }

    let url: URL

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "VaultTasks", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        url = base.appending(path: "scan-cache-v1.json", directoryHint: .notDirectory)
    }

    static func key(vaultPath: String, filter: String) -> String { "\(vaultPath)\u{0}\(filter)" }

    func load(key: String) -> Payload? {
        guard let data = try? Data(contentsOf: url), let p = try? JSONDecoder().decode(Payload.self, from: data), p.key == key else { return nil }
        return p
    }

    /// Atomic; a failed write leaves the previous cache intact.
    func save(_ p: Payload) {
        guard let data = try? JSONEncoder().encode(p) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
