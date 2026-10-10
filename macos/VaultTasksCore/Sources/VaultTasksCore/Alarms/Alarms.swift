import Foundation

/// One scheduled alarm. Carries everything needed to post the notification so the delegate needs no lookup.
/// `fireAt` is a local date-time; the platform layer turns it into wall-clock `DateComponents` (no zone attached).
public struct AlarmSpec: Codable, Sendable, Equatable {
    public let code: Int32
    public let id: TaskId
    public let fireAt: LocalDateTime
    public let title: String
    public let dueDate: LocalDate?
    public let dueTime: LocalTime?
    public let spaceName: String

    public init(code: Int32, id: TaskId, fireAt: LocalDateTime, title: String, dueDate: LocalDate?, dueTime: LocalTime?,
                spaceName: String) {
        self.code = code; self.id = id; self.fireAt = fireAt; self.title = title
        self.dueDate = dueDate; self.dueTime = dueTime; self.spaceName = spaceName
    }

    /// Byte-exact on text (§3.1 rule 2): a changed title that is merely canonically equivalent still counts as changed.
    public static func == (a: AlarmSpec, b: AlarmSpec) -> Bool {
        a.code == b.code && a.id == b.id && a.fireAt == b.fireAt && sameBytes(a.title, b.title)
            && a.dueDate == b.dueDate && a.dueTime == b.dueTime && sameBytes(a.spaceName, b.spaceName)
    }
}

public struct ReminderSettings: Sendable {
    public var defaultTime: LocalTime
    public init(defaultTime: LocalTime = LocalTime(hour: 9, minute: 0)!) { self.defaultTime = defaultTime }
}

public enum AlarmPlanner {
    /// Open tasks with a due date; date-only tasks use the default time; the task's lead time is subtracted.
    public static func fireTime(_ task: VaultTask, defaultTime: LocalTime, leadMinutes: Int) -> LocalDateTime? {
        guard task.state == .open, let date = task.dueDate else { return nil }
        return LocalDateTime(date, task.dueTime ?? defaultTime).minusMinutes(leadMinutes)
    }

    /// The desired alarm set. Past times are excluded (overdue is shown in-app, never re-fired).
    /// `tasks` pairs each task with the name of the space it is shown under; `leadMinutes` gives each task's
    /// "remind me before" (0 = at the due time). A hash collision is resolved by probing upward in sorted-key order,
    /// so the result is deterministic.
    public static func plan(
        _ tasks: [(VaultTask, String)],
        settings: ReminderSettings,
        now: LocalDateTime,
        leadMinutes: (VaultTask) -> Int = { _ in 0 }
    ) -> [AlarmSpec] {
        var due: [(task: VaultTask, space: String, at: LocalDateTime, key: String)] = []
        for (t, space) in tasks {
            guard let at = fireTime(t, defaultTime: settings.defaultTime, leadMinutes: leadMinutes(t)), at > now else { continue }
            due.append((t, space, at, keyOf(t.id)))
        }
        due.sort { utf16Less($0.key, $1.key) }
        var used = Set<Int32>()
        return due.map { d in
            var code = hashCode(d.key)
            while !used.insert(code).inserted { code = code &+ 1 }
            return AlarmSpec(code: code, id: d.task.id, fireAt: d.at, title: d.task.description, dueDate: d.task.dueDate,
                             dueTime: d.task.dueTime, spaceName: d.space)
        }
    }

    /// The system caps pending notifications per app, so only the soonest `limit` are scheduled; the rest come back on the
    /// next top-up (every sync, activation and daily timer). Order: fire time, then code. Apply to the desired set
    /// BEFORE `AlarmSyncer.sync`, so an alarm pushed out of the window by a sooner task is cancelled.
    public static func window(_ specs: [AlarmSpec], limit: Int = 60) -> [AlarmSpec] {
        let sorted = specs.sorted { a, b in a.fireAt != b.fireAt ? a.fireAt < b.fireAt : a.code < b.code }
        return Array(sorted.prefix(max(0, limit)))
    }

    /// Key of a task's "remind me before" setting. Deliberately NOT the task identity: identity is the whole line, so
    /// it changes on every date, time or state edit. Path + description survives those (and edits made in Obsidian).
    public static func leadKey(path: String, description: String) -> String { "\(path)\u{0}\(description)" }

    public static func keyOf(_ id: TaskId) -> String { "\(id.path)\u{0}\(id.normalizedText)\u{0}\(id.occurrence)" }

    /// Stable across runs and processes (unlike `Hasher`): first 4 bytes of SHA-256, big-endian.
    public static func hashCode(_ key: String) -> Int32 {
        let d = sha256Bytes(Data(key.utf8))
        let v = (UInt32(d[0]) << 24) | (UInt32(d[1]) << 16) | (UInt32(d[2]) << 8) | UInt32(d[3])
        return Int32(bitPattern: v)
    }
}

/// Platform seam: UNUserNotificationCenter on macOS. Both calls must be idempotent per code.
public protocol AlarmBackend: Sendable {
    /// Schedules `spec`, replacing any alarm with the same code. May throw; the syncer then retries on the next sync.
    func set(_ spec: AlarmSpec) async throws
    func cancel(_ code: Int32) async
}

/// Persisted copy of what was last scheduled (a system reset can forget alarms, we must not).
public protocol AlarmStore: Sendable {
    func load() -> [AlarmSpec]
    func save(_ specs: [AlarmSpec])
}

public struct AlarmSyncReport: Equatable, Sendable {
    public let scheduled: Int, cancelled: Int, unchanged: Int, failed: Int
}

/// The only code that touches the backend. `sync` diffs the desired set against the persisted one, so calling it any
/// number of times never duplicates an alarm. An actor, and calls are additionally queued so that two syncs can never
/// interleave at a suspension point (actors are re-entrant).
public actor AlarmSyncer {
    private let backend: AlarmBackend
    private let store: AlarmStore
    private var running = false
    private var queue: [CheckedContinuation<Void, Never>] = []

    public init(backend: AlarmBackend, store: AlarmStore) { self.backend = backend; self.store = store }

    private func acquire() async {
        if running { await withCheckedContinuation { queue.append($0) } } else { running = true }
    }
    private func release() {
        if queue.isEmpty { running = false } else { queue.removeFirst().resume() } // ownership is handed over
    }

    /// - Parameters:
    ///   - desired: alarms from `AlarmPlanner.plan` (after `window`)
    ///   - preservePaths: files that could not be read this scan: their alarms are kept, not cancelled
    ///   - force: re-set every alarm even if unchanged (launch, wake, clock/zone change)
    public func sync(desired: [AlarmSpec], preservePaths: Set<String>, now: LocalDateTime, force: Bool) async -> AlarmSyncReport {
        await acquire()
        defer { release() }

        var prevByCode: [Int32: AlarmSpec] = [:]
        var prevOrder: [AlarmSpec] = []
        for p in store.load() {
            if prevByCode[p.code] != nil { prevOrder.removeAll { $0.code == p.code } } // last one wins, like associateBy
            prevByCode[p.code] = p
            prevOrder.append(p)
        }
        let wanted = Set(desired.map(\.code))

        var carried: [AlarmSpec] = []
        var stale: [Int32] = []
        for p in prevOrder where !wanted.contains(p.code) {
            if p.fireAt > now && preservePaths.contains(p.id.path) { carried.append(p) } else { stale.append(p.code) }
        }
        for code in stale { await backend.cancel(code) }

        var kept: [AlarmSpec] = []
        var scheduled = 0, unchanged = 0, failed = 0
        for spec in desired {
            if !force, let p = prevByCode[spec.code], p == spec {
                unchanged += 1; kept.append(spec)
            } else if await trySet(spec) {
                scheduled += 1; kept.append(spec)
            } else {
                failed += 1 // not stored, so the next sync retries it
            }
        }
        for c in carried {
            if force, !(await trySet(c)) { failed += 1; continue }
            kept.append(c)
        }
        store.save(kept)
        return AlarmSyncReport(scheduled: scheduled, cancelled: stale.count, unchanged: unchanged, failed: failed)
    }

    /// Called when an alarm has fired so it is not re-set by a later forced sync.
    public func markFired(_ code: Int32) async {
        await acquire()
        defer { release() }
        let all = store.load()
        if all.contains(where: { $0.code == code }) { store.save(all.filter { $0.code != code }) }
    }

    private func trySet(_ spec: AlarmSpec) async -> Bool {
        do { try await backend.set(spec); return true } catch { return false }
    }
}
