import Foundation

/// Where the app keeps the security-scoped bookmark (UserDefaults in the app, a fake in tests).
public protocol BookmarkStore: Sendable {
    func load() -> Data?
    func save(_ data: Data)
}

/// The security-scope calls behind a protocol, so the state machine below is testable (stale, resolve failure, …).
public protocol ScopedBookmarks: Sendable {
    func resolve(_ data: Data) throws -> (url: URL, stale: Bool)
    func bookmark(for url: URL) throws -> Data
    func startAccessing(_ url: URL) -> Bool
    func stopAccessing(_ url: URL)
    func folderExists(_ url: URL) -> Bool
}

public enum VaultAccessState: Equatable, Sendable {
    case noVault
    /// A bookmark is stored but cannot be used any more (moved/deleted folder, revoked access). Re-pick; spaces are kept.
    case permissionLost
    case ready(URL)
}

/// Bookmark lifecycle (§5): resolve on launch; a stale bookmark is renewed at once; access is started once and held for
/// the app's lifetime, stopped only when the vault changes or the app quits.
public final class VaultAccess: @unchecked Sendable {
    private let store: BookmarkStore
    private let scoped: ScopedBookmarks
    private let lock = NSLock()
    private var accessed: URL?

    public init(store: BookmarkStore, scoped: ScopedBookmarks) { self.store = store; self.scoped = scoped }

    /// Launch path: resolve the stored bookmark and start access.
    @discardableResult
    public func open() -> VaultAccessState {
        lock.lock(); defer { lock.unlock() }
        return openLocked()
    }

    /// The user picked a folder (the URL from the file importer): store a bookmark for it and switch to it.
    @discardableResult
    public func adopt(_ picked: URL) -> VaultAccessState {
        lock.lock(); defer { lock.unlock() }
        let got = scoped.startAccessing(picked)
        defer { if got { scoped.stopAccessing(picked) } }
        guard let data = try? scoped.bookmark(for: picked) else { return .permissionLost }
        store.save(data)
        return openLocked()
    }

    /// App quit / vault change.
    public func close() {
        lock.lock(); defer { lock.unlock() }
        if let a = accessed { scoped.stopAccessing(a); accessed = nil }
    }

    private func openLocked() -> VaultAccessState {
        if let a = accessed { scoped.stopAccessing(a); accessed = nil }
        guard let data = store.load() else { return .noVault }
        guard let (url, stale) = try? scoped.resolve(data) else { return .permissionLost }
        guard scoped.startAccessing(url) else { return .permissionLost }
        if stale, let fresh = try? scoped.bookmark(for: url) { store.save(fresh) }
        guard scoped.folderExists(url) else { scoped.stopAccessing(url); return .permissionLost }
        accessed = url
        return .ready(url)
    }
}
