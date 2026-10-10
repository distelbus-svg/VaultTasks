import Testing
import Foundation
@testable import VaultTasksCore

fileprivate actor FakeBackend: AlarmBackend {
    var live: [Int32: AlarmSpec] = [:]
    var setCalls = 0
    var failCodes: Set<Int32> = []
    struct Denied: Error {}

    func set(_ spec: AlarmSpec) async throws {
        if failCodes.contains(spec.code) { throw Denied() }
        setCalls += 1
        live[spec.code] = spec
    }
    func cancel(_ code: Int32) async { live[code] = nil }
    func setFail(_ c: Set<Int32>) { failCodes = c }
    func clearLive() { live = [:] }
}

fileprivate final class FakeStore: AlarmStore, @unchecked Sendable {
    private let lock = NSLock()
    private var _specs: [AlarmSpec] = []
    var specs: [AlarmSpec] { lock.lock(); defer { lock.unlock() }; return _specs }
    func load() -> [AlarmSpec] { specs }
    func save(_ s: [AlarmSpec]) { lock.lock(); _specs = s; lock.unlock() }
}

@Suite struct AlarmsTests {
    let now = DT(2026, 9, 30, 12, 0)
    let settings = ReminderSettings()
    fileprivate let backend = FakeBackend()
    fileprivate let store = FakeStore()
    let syncer: AlarmSyncer

    init() { syncer = AlarmSyncer(backend: backend, store: store) }

    private func task(_ text: String, date: LocalDate? = D(2026, 10, 1), time: LocalTime? = T(19, 0), state: TaskState = .open,
                      path: String = "a.md", occ: Int = 0) -> VaultTask {
        VaultTask(id: TaskId(path: path, normalizedText: text, occurrence: occ), lineIndex: 0, state: state, description: text,
                  dueDate: date, dueTime: time, priority: nil, isRecurring: false, doneDate: nil, rawLine: "- [ ] \(text)")
    }

    private func plan(_ tasks: [VaultTask], leads: [String: Int] = [:]) -> [AlarmSpec] {
        AlarmPlanner.plan(tasks.map { ($0, "Space") }, settings: settings, now: now) { leads[$0.description] ?? 0 }
    }

    private func sync(_ d: [AlarmSpec], keep: Set<String> = [], force: Bool = false) async -> AlarmSyncReport {
        await syncer.sync(desired: d, preservePaths: keep, now: now, force: force)
    }

    // ---- planner ----

    @Test func timedTaskFiresAtDueTime() { #expect(plan([task("x")]).first?.fireAt == DT(2026, 10, 1, 19, 0)) }

    @Test func dateOnlyUsesDefaultTimeAndLeadIsSubtracted() {
        let t = task("x", time: nil)
        #expect(plan([t]).first?.fireAt == DT(2026, 10, 1, 9, 0))
        #expect(plan([t], leads: ["x": 30]).first?.fireAt == DT(2026, 10, 1, 8, 30))
    }

    @Test func pastUndatedDoneAndCancelledAreExcluded() {
        let out = plan([
            task("past", date: D(2026, 9, 29)),
            task("now", date: D(2026, 9, 30), time: T(12, 0)),
            task("just ahead", date: D(2026, 9, 30), time: T(12, 5)),
            task("undated", date: nil, time: nil),
            task("done", state: .done),
            task("cancelled", state: .cancelled),
        ])
        #expect(out.map(\.title) == ["just ahead"])
        let soon = task("lead pushes into past", date: D(2026, 9, 30), time: T(12, 5))
        #expect(plan([soon], leads: [soon.description: 10]).isEmpty)
    }

    @Test func identicalLinesGetDistinctCodesAndCodesAreStable() {
        let a = plan([task("same", occ: 0), task("same", occ: 1)])
        #expect(Set(a.map(\.code)).count == 2)
        let b = plan([task("same", occ: 1), task("same", occ: 0)]).sorted { $0.id.occurrence < $1.id.occurrence }
        #expect(a == b)
    }

    @Test func leadAppliesPerTask() {
        let out = Dictionary(uniqueKeysWithValues: plan([task("a"), task("b")], leads: ["a": 15]).map { ($0.title, $0.fireAt) })
        #expect(out["a"] == DT(2026, 10, 1, 18, 45))
        #expect(out["b"] == DT(2026, 10, 1, 19, 0))
    }

    @Test func hashCodeIsFirstFourSha256BytesBigEndian() {
        // SHA-256("") = e3b0c442…; 0xe3b0c442 as Int32
        #expect(AlarmPlanner.hashCode("") == Int32(bitPattern: 0xE3B0_C442))
    }

    @Test func windowKeepsSoonestNInDeterministicOrder() {
        let tasks = (0..<10).map { i in task("t\(i)", date: D(2026, 10, 1 + (9 - i) % 5), time: T(8 + i, 0)) }
        let all = plan(tasks)
        let w = AlarmPlanner.window(all, limit: 4)
        #expect(w.count == 4)
        let expected = all.sorted { $0.fireAt != $1.fireAt ? $0.fireAt < $1.fireAt : $0.code < $1.code }.prefix(4)
        #expect(w == Array(expected))
        #expect(AlarmPlanner.window(all.reversed(), limit: 4) == w)
        #expect(AlarmPlanner.window(all, limit: 100).count == 10)
        #expect(AlarmPlanner.window(all, limit: 0).isEmpty)
    }

    @Test func windowTopUpCancelsPushedOutAndBringsItBack() async {
        let late = task("late", date: D(2026, 10, 5))
        let mid = task("mid", date: D(2026, 10, 3))
        _ = await sync(AlarmPlanner.window(plan([late, mid]), limit: 2))
        #expect(await backend.live.count == 2)
        let sooner = task("sooner", date: D(2026, 10, 2))
        let r = await sync(AlarmPlanner.window(plan([late, mid, sooner]), limit: 2))
        #expect(r.scheduled == 1 && r.cancelled == 1)
        #expect(Set(await backend.live.values.map(\.title)) == ["sooner", "mid"])
        // the sooner task goes away (done): the pushed-out one returns on the next top-up
        let r2 = await sync(AlarmPlanner.window(plan([late, mid]), limit: 2))
        #expect(r2.scheduled == 1 && r2.cancelled == 1)
        #expect(Set(await backend.live.values.map(\.title)) == ["late", "mid"])
    }

    @Test func staleDeliveredCodesAreThoseOutsideTheLiveSet() {
        let live = Set(plan([task("a"), task("b")]).map(\.code))
        let delivered: [Int32] = Array(live) + [42, -7]
        #expect(Set(delivered.filter { !live.contains($0) }) == [42, -7])
    }

    // ---- syncer ----

    @Test func syncingTwiceNeverDuplicates() async {
        let d = plan([task("a"), task("b")])
        #expect(await sync(d).scheduled == 2)
        let second = await sync(d)
        #expect(second.scheduled == 0 && second.unchanged == 2)
        #expect(await backend.live.count == 2)
        #expect(await backend.setCalls == 2)
    }

    @Test func changedTimeReplacesInPlaceRemovedTaskIsCancelled() async {
        _ = await sync(plan([task("a"), task("b")]))
        let r = await sync(plan([task("a", time: T(20, 0))]))
        #expect(r.scheduled == 1 && r.cancelled == 1)
        #expect(await backend.live.values.map(\.fireAt) == [DT(2026, 10, 1, 20, 0)])
    }

    @Test func editingTextCancelsOldAlarmAndSetsNew() async {
        _ = await sync(plan([task("old")]))
        let oldCode = await backend.live.keys.first!
        _ = await sync(plan([task("new")]))
        #expect(await backend.live[oldCode] == nil)
        #expect(await backend.live.values.first?.title == "new")
    }

    @Test func alarmsOfUnreadableFilesSurvive() async {
        _ = await sync(plan([task("a", path: "a.md"), task("b", path: "b.md")]))
        let r = await sync(plan([task("a", path: "a.md")]), keep: ["b.md"])
        #expect(r.cancelled == 0)
        #expect(await backend.live.count == 2)
        #expect(store.specs.count == 2)
    }

    @Test func forcedSyncAfterSimulatedResetRestoresEveryAlarm() async {
        let d = plan([task("a"), task("b")])
        _ = await sync(d)
        await backend.clearLive()
        #expect(await sync(d).scheduled == 0)
        #expect(await sync(d, force: true).scheduled == 2)
        #expect(await backend.live.count == 2)
    }

    @Test func failedSetIsNotStoredAndRetried() async {
        let d = plan([task("a")])
        await backend.setFail([d[0].code])
        #expect(await sync(d).failed == 1)
        #expect(store.specs.isEmpty)
        await backend.setFail([])
        #expect(await sync(d).scheduled == 1)
    }

    @Test func firedAlarmsAreDroppedFromStore() async {
        let d = plan([task("a")])
        _ = await sync(d)
        await syncer.markFired(d[0].code)
        #expect(store.specs.isEmpty)
    }

    @Test func concurrentSyncsAreQueued() async {
        let d = plan([task("a"), task("b"), task("c")])
        async let r1 = sync(d), r2 = sync(d), r3 = sync(d)
        let rs = await [r1, r2, r3]
        #expect(rs.map(\.scheduled).reduce(0, +) == 3)
        #expect(await backend.live.count == 3)
    }

    @Test func specJsonRoundTrip() throws {
        let d = plan([task("é ✓")])
        let back = try JSONDecoder().decode([AlarmSpec].self, from: JSONEncoder().encode(d))
        #expect(back == d)
    }
}
