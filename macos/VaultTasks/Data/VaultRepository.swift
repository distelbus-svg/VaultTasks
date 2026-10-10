import Foundation
import VaultTasksCore

enum VaultPhase: Equatable, Sendable { case loading, noVault, permissionLost, ready }

/// Immutable snapshot the UI renders. Views never own task data (§12).
struct RepoState: Sendable {
    var phase: VaultPhase = .loading
    var vaultPath: String?
    /// Tasks of the files assigned to at least one space (the scan scope).
    var tasks: [VaultTask] = []
    /// Every `.md` file of the vault (feeds the space file picker).
    var available: [String] = []
    var unreadable: [String] = []
    var lastScan: Date?
    var fromCache = false
    var scanError: String?
}

/// Result of one write-protocol operation (port of `EditOutcome`).
enum EditOutcome: Sendable {
    case done
    case removed(RemovedLine)
    case stale
    case failed(String)
}

struct VaultUnavailable: Error, LocalizedError {
    var errorDescription: String? { "No vault selected" }
}

/// Owns scanning and all writes. Everything runs inside the actor, so a scan can never interleave with another vault
/// operation. The file work is blocking and contains no suspension points, which is what serializes it.
actor VaultRepository {
    nonisolated let states: AsyncStream<RepoState>
    private let continuation: AsyncStream<RepoState>.Continuation

    private let access: VaultAccess
    private let settings: SettingsStore
    private let cache: VaultCache
    private var watcher: FSEventsWatcher?

    private var state = RepoState()
    private var fs: BookmarkVaultFileSystem?
    private var writer: VaultWriter?
    private var files: [String: ScannedFile] = [:]
    private var order: [String] = []
    private var scope: Set<String> = []
    private var cacheKey = ""
    private var debouncer = EventDebouncer(interval: 0.5)
    private var debounceTask: Task<Void, Never>?

    init(access: VaultAccess, settings: SettingsStore, cache: VaultCache) {
        (states, continuation) = AsyncStream.makeStream(of: RepoState.self, bufferingPolicy: .bufferingNewest(1))
        self.access = access; self.settings = settings; self.cache = cache
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------

    func start() {
        publish { $0.phase = .loading }
        apply(access.open())
    }

    func pickVault(_ url: URL) { apply(access.adopt(url)) }

    func shutdown() {
        debounceTask?.cancel()
        watcher?.stop(); watcher = nil
        access.close()
    }

    /// Manual refresh, app activation, after sync. `force` re-parses everything (e.g. the global filter changed).
    func rescan(force: Bool = false) { scan(force: force) }

    func globalFilterChanged() { scan(force: true) }

    /// Files assigned to spaces: only these are scanned. A change rescans (files that left the scope drop out).
    func setScope(_ paths: [String]) {
        let s = Set(paths)
        guard s != scope else { return }
        scope = s
        if fs != nil { scan(force: false) }
    }

    /// The latest snapshot, for callers that just awaited a write and must not race the `states` stream.
    func currentState() -> RepoState { state }

    /// Runs one block on the vault file system inside the actor, so it cannot interleave with a scan or a write
    /// (sync's read-check-write, `LocalFiles.atomically`).
    func withFileSystem<T: Sendable>(_ block: @Sendable (any VaultFileSystem) throws -> T) throws -> T {
        guard let fs else { throw VaultUnavailable() }
        return try block(fs)
    }

    // ---- writes (Android `VaultRepository.write`) -----------------------------------------------------------

    func setDone(_ task: VaultTask, done: Bool) -> EditOutcome {
        write { $0.setDone(task, done: done, doneDate: done ? LocalDate.today() : nil) }
    }

    func create(path: String, fields: TaskFields) -> EditOutcome { write { $0.create(path, fields) } }

    func update(_ task: VaultTask, fields: TaskFields) -> EditOutcome { write { $0.update(task, fields) } }

    /// Edit and move to another file of the space. The writer rolls the removal back if the append fails.
    func move(_ task: VaultTask, fields: TaskFields, to path: String) -> EditOutcome {
        write(rescanAfter: true) { $0.move(task, fields, to: path) }
    }

    func delete(_ task: VaultTask) -> EditOutcome { write { $0.delete(task) } }

    func restore(_ removed: RemovedLine) -> EditOutcome { write { $0.restore(removed) } }

    // ---- internals ---------------------------------------------------------------------------------------

    /// Runs one write-protocol operation and folds its verified result into the snapshot.
    private func write(rescanAfter: Bool = false, _ block: (VaultWriter) -> WriteResult) -> EditOutcome {
        guard let writer else { return .failed("No vault selected") }
        switch block(writer) {
        case .ok(let file, let removed):
            let path = file.state.path
            files[path] = file
            if !order.contains(path) { order.append(path) }
            publish { $0.tasks = tasksNow() }
            persistCache()
            if rescanAfter { scan(force: false) }
            return removed.map(EditOutcome.removed) ?? .done
        case .stale:
            scan(force: false)
            return .stale
        case .missing:
            scan(force: false)
            return .failed("File no longer exists")
        case .failed(let e):
            return .failed((e as? LocalizedError)?.errorDescription ?? e.localizedDescription)
        }
    }

    private func apply(_ s: VaultAccessState) {
        watcher?.stop(); watcher = nil
        debounceTask?.cancel()
        switch s {
        case .noVault:
            fs = nil; writer = nil; files = [:]; order = []
            publish { $0 = RepoState(phase: .noVault) }
        case .permissionLost:
            // Keep what we know on screen (stale but useful); the banner asks for a re-pick.
            fs = nil; writer = nil
            publish { $0.phase = .permissionLost }
        case .ready(let url):
            let path = url.path
            let vfs = BookmarkVaultFileSystem(root: url)
            fs = vfs
            writer = VaultWriter(fs: vfs, globalFilter: { [settings] in settings.globalFilter })
            cacheKey = VaultCache.key(vaultPath: path, filter: settings.globalFilter)
            if let c = cache.load(key: cacheKey) {
                files = c.files; order = c.order
                publish { $0 = RepoState(phase: .ready, vaultPath: path, tasks: tasksNow(), fromCache: true) }
            } else {
                files = [:]; order = []
                publish { $0 = RepoState(phase: .ready, vaultPath: path) }
            }
            let w = FSEventsWatcher { [weak self] paths in Task { await self?.noteFileEvents(paths) } }
            w.start(path: url.resolvingSymlinksInPath().path)
            watcher = w
            scan(force: false)
        }
    }

    private func tasksNow() -> [VaultTask] { order.filter { scope.contains($0) }.flatMap { files[$0]?.tasks ?? [] } }

    private func persistCache() {
        guard fs != nil else { return }
        cache.save(.init(key: cacheKey, order: order, files: files))
    }

    private func scan(force: Bool) {
        guard let fs, let path = state.vaultPath else { return }
        let filter = settings.globalFilter
        let key = VaultCache.key(vaultPath: path, filter: filter)
        let forceNow = force || key != cacheKey // a changed filter invalidates every parse
        let inScope = scope
        do {
            let r = try FileScanner(fs: fs, globalFilter: { filter }).scan(previous: files, include: { inScope.contains($0) }, force: forceNow)
            files = r.files; order = r.order; cacheKey = key
            let changed = forceNow || r.reparsed > 0 || !r.changes.isEmpty || state.fromCache
            publish {
                $0.tasks = r.tasks; $0.available = r.available; $0.unreadable = r.unreadable
                $0.lastScan = Date(); $0.fromCache = false; $0.scanError = nil
            }
            if changed { persistCache() }
        } catch {
            publish { $0.scanError = (error as? LocalizedError)?.errorDescription ?? "\(error)" }
        }
    }

    // ---- file events -------------------------------------------------------------------------------------

    func noteFileEvents(_ paths: [String]) {
        guard let root = state.vaultPath.map({ URL(fileURLWithPath: $0).resolvingSymlinksInPath().path }) else { return }
        if debouncer.record(paths, root: root, now: Date()) { armDebounce() }
    }

    private func armDebounce() {
        debounceTask?.cancel()
        guard let delay = debouncer.delay(now: Date()) else { return }
        debounceTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            await self?.fireDebounce()
        }
    }

    private func fireDebounce() {
        if debouncer.consume(now: Date()) { scan(force: false) } else { armDebounce() }
    }

    private func publish(_ change: (inout RepoState) -> Void) {
        change(&state)
        continuation.yield(state)
    }
}
