import Foundation

/// Which file-system events are worth a rescan: markdown files and extension-less paths (folders created, moved or
/// removed) anywhere in the vault except inside dot-directories and for dot-files (`.obsidian`, `.git`, our own temp files).
public enum VaultEventFilter {
    public static func isRelevant(_ path: String, root: String) -> Bool {
        let prefix = root.hasSuffix("/") ? root : root + "/"
        guard path.hasPrefix(prefix) else { return true } // unknown origin: rescan, it is cheap
        let comps = path.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: true)
        guard let last = comps.last else { return true }
        if comps.contains(where: { $0.hasPrefix(".") }) { return false }
        return last.hasSuffix(".md") || !last.contains(".")
    }
}

/// Pure trailing-edge debounce for file-system events; the caller supplies the clock. A burst of events (Git checkout,
/// sync tool) collapses into one rescan `interval` after the last relevant event.
public struct EventDebouncer: Sendable {
    public let interval: TimeInterval
    private var lastEvent: Date?

    public init(interval: TimeInterval = 0.5) { self.interval = interval }

    /// Records the event batch; returns whether anything in it was relevant.
    @discardableResult
    public mutating func record(_ paths: [String], root: String, now: Date) -> Bool {
        guard paths.contains(where: { VaultEventFilter.isRelevant($0, root: root) }) else { return false }
        lastEvent = now
        return true
    }

    /// Seconds until a rescan is due (0 = now), nil if nothing is pending.
    public func delay(now: Date) -> TimeInterval? {
        guard let last = lastEvent else { return nil }
        return max(0, last.addingTimeInterval(interval).timeIntervalSince(now))
    }

    /// If due, clears the pending state and returns true (the caller rescans exactly once).
    public mutating func consume(now: Date) -> Bool {
        guard let d = delay(now: now), d <= 0 else { return false }
        lastEvent = nil
        return true
    }
}
