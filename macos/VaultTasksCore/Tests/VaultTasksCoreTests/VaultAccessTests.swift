import Testing
import Foundation
@testable import VaultTasksCore

private final class MemStore: BookmarkStore, @unchecked Sendable {
    private let lock = NSLock()
    private var d: Data?
    var saves = 0
    init(_ d: Data? = nil) { self.d = d }
    func load() -> Data? { lock.withLock { d } }
    func save(_ data: Data) { lock.withLock { d = data; saves += 1 } }
}

/// Bookmarks are the UTF-8 bytes of "<path>" (+ "!stale" when they should resolve as stale).
private final class FakeScoped: ScopedBookmarks, @unchecked Sendable {
    private let lock = NSLock()
    var failResolve = false, failStart = false, failBookmark = false
    var existing: Set<String> = ["/vault", "/other"]
    private var active: [String: Int] = [:]
    func holds(_ p: String) -> Int { lock.withLock { active[p, default: 0] } }
    struct Boom: Error {}

    func resolve(_ data: Data) throws -> (url: URL, stale: Bool) {
        if failResolve { throw Boom() }
        let s = String(decoding: data, as: UTF8.self)
        let stale = s.hasSuffix("!stale")
        return (URL(fileURLWithPath: stale ? String(s.dropLast(6)) : s), stale)
    }
    func bookmark(for url: URL) throws -> Data { if failBookmark { throw Boom() }; return Data(url.path.utf8) }
    func startAccessing(_ url: URL) -> Bool { if failStart { return false }; lock.withLock { active[url.path, default: 0] += 1 }; return true }
    func stopAccessing(_ url: URL) { lock.withLock { active[url.path, default: 0] -= 1 } }
    func folderExists(_ url: URL) -> Bool { existing.contains(url.path) }
}

@Suite struct VaultAccessTests {
    @Test func noBookmarkMeansNoVault() {
        #expect(VaultAccess(store: MemStore(), scoped: FakeScoped()).open() == .noVault)
    }

    @Test func resolvesAndHoldsAccessOnce() {
        let scoped = FakeScoped()
        let a = VaultAccess(store: MemStore(Data("/vault".utf8)), scoped: scoped)
        #expect(a.open() == .ready(URL(fileURLWithPath: "/vault")))
        #expect(scoped.holds("/vault") == 1)
        a.close()
        #expect(scoped.holds("/vault") == 0)
    }

    @Test func staleBookmarkIsRenewedImmediately() {
        let store = MemStore(Data("/vault!stale".utf8))
        let a = VaultAccess(store: store, scoped: FakeScoped())
        #expect(a.open() == .ready(URL(fileURLWithPath: "/vault")))
        #expect(store.saves == 1)
        #expect(store.load() == Data("/vault".utf8))
    }

    @Test func staleBookmarkThatCannotBeRenewedStillWorks() {
        let scoped = FakeScoped(); scoped.failBookmark = true
        let store = MemStore(Data("/vault!stale".utf8))
        #expect(VaultAccess(store: store, scoped: scoped).open() == .ready(URL(fileURLWithPath: "/vault")))
        #expect(store.saves == 0)
    }

    @Test func resolveFailureIsPermissionLostAndKeepsTheBookmark() {
        let scoped = FakeScoped(); scoped.failResolve = true
        let store = MemStore(Data("/vault".utf8))
        #expect(VaultAccess(store: store, scoped: scoped).open() == .permissionLost)
        #expect(store.load() == Data("/vault".utf8))
    }

    @Test func accessDeniedOrFolderGoneIsPermissionLostWithoutLeakingAccess() {
        let scoped = FakeScoped(); scoped.failStart = true
        #expect(VaultAccess(store: MemStore(Data("/vault".utf8)), scoped: scoped).open() == .permissionLost)
        let gone = FakeScoped(); gone.existing = []
        #expect(VaultAccess(store: MemStore(Data("/vault".utf8)), scoped: gone).open() == .permissionLost)
        #expect(gone.holds("/vault") == 0)
    }

    @Test func adoptStoresBookmarkSwitchesVaultAndBalancesAccess() {
        let scoped = FakeScoped()
        let store = MemStore(Data("/vault".utf8))
        let a = VaultAccess(store: store, scoped: scoped)
        _ = a.open()
        #expect(a.adopt(URL(fileURLWithPath: "/other")) == .ready(URL(fileURLWithPath: "/other")))
        #expect(store.load() == Data("/other".utf8))
        #expect(scoped.holds("/vault") == 0)
        #expect(scoped.holds("/other") == 1) // the picked URL's temporary access was released, the held one remains
    }

    @Test func adoptWithBookmarkFailureChangesNothing() {
        let scoped = FakeScoped(); scoped.failBookmark = true
        let store = MemStore(Data("/vault".utf8))
        let a = VaultAccess(store: store, scoped: scoped)
        #expect(a.adopt(URL(fileURLWithPath: "/other")) == .permissionLost)
        #expect(store.load() == Data("/vault".utf8))
    }
}

@Suite struct EventDebouncerTests {
    let t0 = Date(timeIntervalSince1970: 1_000)
    let root = "/vault"

    @Test func relevanceFilter() {
        func r(_ p: String) -> Bool { VaultEventFilter.isRelevant(p, root: root) }
        #expect(r("/vault/a.md"))
        #expect(r("/vault/sub/b.md"))
        #expect(r("/vault/sub"))            // folder created/moved/removed
        #expect(!r("/vault/.obsidian/workspace.json"))
        #expect(!r("/vault/.git/index"))
        #expect(!r("/vault/.vt-123.tmp"))
        #expect(!r("/vault/img.png"))
        #expect(!r("/vault/sub/.hidden/a.md"))
    }

    @Test func irrelevantBatchesRecordNothing() {
        var d = EventDebouncer()
        let recorded = d.record(["/vault/.git/HEAD"], root: root, now: t0)
        #expect(!recorded)
        #expect(d.delay(now: t0) == nil)
    }

    @Test func burstCollapsesIntoOneRescanAfterTheLastEvent() {
        var d = EventDebouncer(interval: 0.5)
        let first = d.record(["/vault/a.md"], root: root, now: t0)
        #expect(first)
        #expect(abs(d.delay(now: t0.addingTimeInterval(0.2))! - 0.3) < 1e-6)
        let second = d.record(["/vault/b.md"], root: root, now: t0.addingTimeInterval(0.4))
        #expect(second)
        let early = d.consume(now: t0.addingTimeInterval(0.8))   // 0.4 s after the last event: not yet
        let due = d.consume(now: t0.addingTimeInterval(0.9))
        let again = d.consume(now: t0.addingTimeInterval(5))     // exactly once
        #expect(!early && due && !again)
        #expect(d.delay(now: t0.addingTimeInterval(5)) == nil)
    }
}

@Suite struct CacheCodingTests {
    @Test func scannedFilesRoundTripThroughJson() throws {
        let tasks = parseTasks("a.md", "- [ ] é ⏰ 08:00 🔼 📅 2026-10-01 #home", "- [x] done ✅ 2026-10-02", "- [ ] r 🔁 every week 📅 2026-10-05")
        let f = ScannedFile(state: FileState(path: "a.md", lastModified: 5, size: 9, contentHash: "ab"), tasks: tasks)
        let back = try JSONDecoder().decode([String: ScannedFile].self, from: JSONEncoder().encode(["a.md": f]))
        #expect(back["a.md"] == f)
    }
}
