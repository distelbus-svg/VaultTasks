import Testing
import Foundation
@testable import VaultTasksCore

private final class MemFs: VaultFileSystem, LocalFiles, @unchecked Sendable {
    private let lock = NSRecursiveLock()
    private var files: [String: Data] = [:]
    private var mtimes: [String: Int64] = [:]
    func listMarkdown() throws -> [VaultFileInfo] { lock.withLock { files.keys.sorted().map { VaultFileInfo(path: $0, lastModified: mtimes[$0] ?? 0, size: Int64(files[$0]!.count)) } } }
    func stat(_ path: String) throws -> VaultFileInfo? { lock.withLock { files[path].map { VaultFileInfo(path: path, lastModified: mtimes[path] ?? 0, size: Int64($0.count)) } } }
    func read(_ path: String) throws -> Data? { lock.withLock { files[path] } }
    func write(_ path: String, _ bytes: Data) throws { try lock.withLock { guard files[path] != nil else { throw FakeVaultFileSystem.NoSuchFile() }; files[path] = bytes } }
    func atomically<T: Sendable>(_ block: @Sendable (any VaultFileSystem) throws -> T) async throws -> T { try lock.withLock { try block(self) } }
    func text(_ path: String) -> String? { lock.withLock { files[path].map { String(decoding: $0, as: UTF8.self) } } }
    func set(_ path: String, _ text: String, _ mtime: Int64) { lock.withLock { files[path] = Data(text.utf8); mtimes[path] = mtime } }
    func removeFile(_ path: String) { lock.withLock { files[path] = nil } }
}

private final class MemRemote: RemoteStore, @unchecked Sendable {
    private let lock = NSRecursiveLock()
    private var files: [String: String] = [:]
    private var versions: [String: Int] = [:]
    private var mtimes: [String: Int64] = [:]
    var beforePut: (@Sendable () -> Void)?
    var onGet: (@Sendable () -> Void)?

    private func token(_ p: String) -> String { "\"v\(versions[p]!)\"" }
    func text(_ p: String) -> String? { lock.withLock { files[p] } }
    func removeFile(_ p: String) { lock.withLock { files[p] = nil } }
    func set(_ path: String, _ text: String, _ mtime: Int64 = 0) {
        lock.withLock { files[path] = text; versions[path, default: 0] += 1; mtimes[path] = mtime }
    }
    func stat(_ path: String) async throws -> RemoteStat? { lock.withLock { files[path].map { _ in RemoteStat(token: token(path), modified: mtimes[path]) } } }
    func get(_ path: String) async throws -> RemoteData? {
        onGet?()
        return lock.withLock { files[path].map { RemoteData(bytes: Data($0.utf8), token: token(path), modified: mtimes[path]) } }
    }
    func put(_ path: String, _ bytes: Data, expectedToken: String?) async throws -> PutResult {
        beforePut?()
        return lock.withLock {
            let current = files[path].map { _ in token(path) }
            if current != expectedToken { return .preconditionFailed } // create-only: expected nil, exists -> fail
            set(path, String(decoding: bytes, as: UTF8.self), .max)
            return .ok(token: token(path))
        }
    }
}

private final class MemRecords: SyncRecords, @unchecked Sendable {
    private let lock = NSLock()
    private var map: [String: SyncRecord] = [:]
    func get(_ path: String) -> SyncRecord? { lock.withLock { map[path] } }
    func put(_ path: String, _ record: SyncRecord) { lock.withLock { map[path] = record } }
    func clear() { lock.withLock { map = [:] } }
}

@Suite struct SyncEngineTests {
    private let fs = MemFs()
    private let remote = MemRemote()
    private let records = MemRecords()
    private let engine: SyncEngine
    private let p = "Tasks/a.md"

    init() { engine = SyncEngine(local: fs, remote: remote, records: records) }

    private func status() async -> FileStatus { await engine.check([p])[0].status }
    private func sync() async -> SyncReport { await engine.sync([p]) }

    /// Both sides hold `text` and have been synced once.
    private func synced(_ text: String) async {
        fs.set(p, text, 100); remote.set(p, text, 100); _ = await status()
    }

    @Test func identicalContentOnFirstContactMovesNothing() async {
        fs.set(p, "x", 100); remote.set(p, "x", 200)
        #expect(await status() == .inSync)
        let r = await sync()
        #expect(r.pulled.isEmpty && r.pushed.isEmpty)
    }

    @Test func changeOnOneSideOnlyIsCarriedOver() async {
        await synced("1")
        fs.set(p, "2", 300)
        #expect(await status() == .localChanged)
        #expect(await sync().pushed == [p])
        #expect(remote.text(p) == "2")
        #expect(await status() == .inSync)

        remote.set(p, "3", 400)
        #expect(await status() == .remoteChanged)
        #expect(await sync().pulled == [p])
        #expect(fs.text(p) == "3")
        #expect(await status() == .inSync)
    }

    @Test func bothChangedNewerLocalSurvives() async {
        await synced("1")
        remote.set(p, "theirs", 300); fs.set(p, "mine", 400)
        #expect(await status() == .bothChanged)
        #expect(await sync().pushed == [p])
        #expect(remote.text(p) == "mine")
        #expect(fs.text(p) == "mine")
    }

    @Test func bothChangedNewerServerSurvives() async {
        await synced("1")
        fs.set(p, "mine", 300); remote.set(p, "theirs", 400)
        #expect(await sync().pulled == [p])
        #expect(fs.text(p) == "theirs")
        #expect(remote.text(p) == "theirs")
    }

    @Test func tieOrUnknownServerTimeGoesToServer() async {
        await synced("1")
        fs.set(p, "mine", 300); remote.set(p, "theirs", 300)
        #expect(await sync().pulled == [p])
        #expect(fs.text(p) == "theirs")
    }

    @Test func firstContactWithDifferentContentNewerWins() async {
        fs.set(p, "local", 500); remote.set(p, "remote", 400)
        #expect(await sync().pushed == [p])
        #expect(remote.text(p) == "local")

        let q = "Tasks/b.md"
        fs.set(q, "local", 400); remote.set(q, "remote", 500)
        #expect(await engine.sync([q]).pulled == [q])
        #expect(fs.text(q) == "remote")
    }

    @Test func sameEditOnBothSidesNeedsNoAction() async {
        await synced("1")
        fs.set(p, "same", 300); remote.set(p, "same", 400)
        #expect(await status() == .inSync)
    }

    @Test func uploadNeverOverwritesServerChangeLandingAfterItWasRead() async {
        await synced("1")
        fs.set(p, "2", 300)
        let remote = self.remote
        remote.beforePut = { remote.set("Tasks/a.md", "sneaky", 350) }
        let r = await sync()
        #expect(r.pushed.isEmpty)
        #expect(r.skipped.count == 1)
        #expect(remote.text(p) == "sneaky")
    }

    @Test func newFileIsCreatedButNeverReplacesOneThatAppearedMeanwhile() async {
        fs.set(p, "new", 100)
        #expect(await status() == .notOnServer)
        #expect(await sync().pushed == [p])
        #expect(remote.text(p) == "new")

        let q = "Tasks/b.md"
        fs.set(q, "mine", 100)
        let remote = self.remote
        remote.beforePut = { remote.set("Tasks/b.md", "theirs", 100) }
        #expect(await engine.sync([q]).pushed.isEmpty)
        #expect(remote.text(q) == "theirs")
    }

    @Test func deletingOnEitherSideIsNeverPropagated() async {
        await synced("1")
        remote.removeFile(p)
        #expect(await status() == .deletedOnServer)
        #expect(await sync().pushed.isEmpty)
        #expect(remote.text(p) == nil)
        #expect(fs.text(p) == "1")

        remote.set(p, "1", 100); fs.removeFile(p)
        #expect(await status() == .missingLocally)
        #expect(await sync().pulled.isEmpty)
    }

    @Test func pullNeverOverwritesALocalEditThatLandedAfterClassification() async {
        await synced("1")
        remote.set(p, "server", 300)
        let fs = self.fs
        remote.onGet = { fs.set("Tasks/a.md", "local edit", 400) }
        let r = await sync()
        #expect(r.pulled.isEmpty)
        #expect(r.skipped.count == 1)
        #expect(fs.text(p) == "local edit")
    }

    @Test func errorsAreReportedPerFileAndDoNotStopTheRun() async {
        struct Boom: Error {}
        final class Failing: RemoteStore, Sendable {
            func stat(_ path: String) async throws -> RemoteStat? { throw Boom() }
            func get(_ path: String) async throws -> RemoteData? { throw Boom() }
            func put(_ path: String, _ bytes: Data, expectedToken: String?) async throws -> PutResult { throw Boom() }
        }
        fs.set(p, "x", 1)
        let e = SyncEngine(local: fs, remote: Failing(), records: records)
        #expect(await e.check([p])[0].status == .error)
        let r = await e.sync([p, "Tasks/b.md"])
        #expect(r.failed.count == 2)
    }
}
