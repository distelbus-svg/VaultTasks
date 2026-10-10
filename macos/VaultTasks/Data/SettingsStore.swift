import Foundation
import VaultTasksCore

/// UserDefaults-backed device-local settings (§6). UserDefaults is thread-safe.
final class SettingsStore: BookmarkStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private enum Key { static let bookmark = "vaultBookmark", filter = "globalFilter", reminderMinutes = "reminderMinutes" }

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func load() -> Data? { defaults.data(forKey: Key.bookmark) }
    func save(_ data: Data) { defaults.set(data, forKey: Key.bookmark) }

    /// Tasks-plugin "global filter"; must match the plugin's setting exactly. Empty = every checkbox is a task.
    var globalFilter: String {
        get { defaults.string(forKey: Key.filter) ?? "" }
        set { defaults.set(newValue, forKey: Key.filter) }
    }
}

extension SettingsStore {
    /// Reminder time for date-only tasks (default 09:00).
    var defaultReminderTime: LocalTime {
        get {
            let m = UserDefaults.standard.object(forKey: "reminderMinutes") as? Int ?? 540
            return LocalTime(hour: m / 60, minute: m % 60) ?? LocalTime(hour: 9, minute: 0)!
        }
        set { UserDefaults.standard.set(newValue.hour * 60 + newValue.minute, forKey: "reminderMinutes") }
    }
}

/// The real security-scope calls.
struct SecurityScopedBookmarks: ScopedBookmarks {
    func resolve(_ data: Data) throws -> (url: URL, stale: Bool) {
        var stale = false
        let url = try URL(resolvingBookmarkData: data, options: [.withSecurityScope], relativeTo: nil, bookmarkDataIsStale: &stale)
        return (url, stale)
    }
    func bookmark(for url: URL) throws -> Data {
        try url.bookmarkData(options: [.withSecurityScope], includingResourceValuesForKeys: nil, relativeTo: nil)
    }
    func startAccessing(_ url: URL) -> Bool { url.startAccessingSecurityScopedResource() }
    func stopAccessing(_ url: URL) { url.stopAccessingSecurityScopedResource() }
    func folderExists(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) && isDir.boolValue
    }
}
