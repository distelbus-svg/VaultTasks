import Foundation
import Observation
import VaultTasksCore

/// `LocalFiles` on the repository actor: each block runs inside the actor, so a sync's read-check-write can never
/// interleave with a scan or an edit (the lock Android's `opMutex` provided).
struct RepoLocalFiles: LocalFiles {
    let repository: VaultRepository
    func atomically<T: Sendable>(_ block: @Sendable (any VaultFileSystem) throws -> T) async throws -> T {
        try await repository.withFileSystem(block)
    }
}

/// Explicit, user-triggered sync (Check = read-only status, Sync = two-way) plus the silent automatic runs (§8).
/// Port of Android's `SyncRepository`. Network work runs outside the vault actor; only each local read-check-write
/// holds it.
@MainActor @Observable
final class SyncController {
    private(set) var config: WebDavConfig?
    private(set) var busy = false
    /// Result of the last check; empty until the first one.
    private(set) var files: [FileSyncState] = []
    private(set) var report: SyncReport?
    private(set) var lastRun: Date?
    /// Last connection-test result or error, shown under the form.
    private(set) var message: String?
    private(set) var messageIsError = false

    @ObservationIgnored var assignedFiles: @MainActor () -> [String] = { [] }
    @ObservationIgnored var announce: @MainActor (String) -> Void = { _ in }

    @ObservationIgnored private let repository: VaultRepository
    @ObservationIgnored private let settings = WebDavSettings()
    @ObservationIgnored private let records = JSONSyncRecords()
    @ObservationIgnored private let transport = URLSessionTransport()
    @ObservationIgnored private var gate = AutoSyncGate()

    init(repository: VaultRepository) {
        self.repository = repository
        config = settings.load()
    }

    var hasServer: Bool { config != nil }

    // ---- server form ---------------------------------------------------------------------------------------

    private static func cleaned(_ c: WebDavConfig) -> WebDavConfig {
        WebDavConfig(url: c.url.trimmingCharacters(in: .whitespacesAndNewlines), user: c.user.trimmingCharacters(in: .whitespacesAndNewlines),
                     password: c.password,
                     baseFolder: c.baseFolder.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/")))
    }

    func save(_ new: WebDavConfig) {
        let c = Self.cleaned(new)
        guard !c.url.isEmpty else { removeServer(); return }
        // Records describe one specific server location; a different one (or none before) invalidates all of them.
        if let old = config, old.url == c.url, old.baseFolder == c.baseFolder {} else { records.clear() }
        let stored = settings.save(c)
        config = settings.load()
        files = []; report = nil
        note(stored ? "Saved." : "Saved, but the password could not be stored in the Keychain.", error: !stored)
    }

    func removeServer() {
        settings.clear()
        records.clear()
        config = nil
        files = []; report = nil
        note("Server removed.", error: false)
    }

    func test(_ form: WebDavConfig) {
        launchBusy {
            do {
                let client = try WebDavClient(config: Self.cleaned(form), transport: self.transport)
                self.note(try await client.test(), error: false)
            } catch {
                self.note("Failed: " + Self.describe(error), error: true)
            }
        }
    }

    // ---- check / sync ------------------------------------------------------------------------------------------

    func check() {
        launchBusy {
            guard let (engine, paths) = await self.engine() else { return }
            self.files = await engine.check(paths)
            self.report = nil
        }
    }

    /// Two-way sync, newest version wins.
    func sync() { launchBusy { await self.runSync() } }

    private func runSync() async {
        guard let (engine, paths) = await engine() else { return }
        report = await engine.sync(paths)
        await repository.rescan(force: true) // pulled files changed on disk: re-parse, which also re-syncs alarms
        files = await engine.check(paths)
        lastRun = Date()
    }

    /// Silent sync for the automatic triggers (activation, shortly after leaving the app, daily): no toast, no-op
    /// without a server, and it yields to a sync already running. `minGap` keeps quick cycles from hammering the server.
    func autoSync(minGap: TimeInterval = 0) async {
        guard config != nil, !busy else { return }
        guard gate.tryStart(now: Date(), minGap: minGap) else { return }
        busy = true
        message = nil
        await runSync()
        busy = false
    }

    /// ⌘R: sync with the server if one is set (and announce the result), otherwise just rescan.
    func refresh() {
        guard config != nil else { Task { await repository.rescan() }; return }
        guard !busy else { Task { await repository.rescan() }; return }
        launchBusy(announceResult: true) { await self.runSync() }
    }

    // ---- internals -------------------------------------------------------------------------------------------

    private func launchBusy(announceResult: Bool = false, _ block: @escaping @MainActor () async -> Void) {
        guard !busy else { return }
        busy = true
        message = nil
        if announceResult { report = nil }
        Task {
            await block()
            busy = false
            if announceResult { announce(summary()) }
        }
    }

    private func summary() -> String {
        if let m = message { return m } // setup problem
        return report?.summaryLine ?? "Sync finished"
    }

    private func note(_ text: String, error: Bool) { message = text; messageIsError = error }

    /// nil (with `message` set) when sync cannot run.
    private func engine() async -> (SyncEngine, [String])? {
        guard let config else { note("Set up the server first.", error: true); return nil }
        guard await repository.currentState().phase == .ready else { note("No vault selected.", error: true); return nil }
        let paths = assignedFiles().sorted(by: utf16Less)
        guard !paths.isEmpty else { note("No files are assigned to a space yet.", error: true); return nil }
        do {
            let client = try WebDavClient(config: config, transport: transport)
            return (SyncEngine(local: RepoLocalFiles(repository: repository), remote: client, records: records), paths)
        } catch {
            note("Failed: " + Self.describe(error), error: true)
            return nil
        }
    }

    static func describe(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }
}
