import Foundation

public struct TaskGroups: Sendable {
    public var overdue: [VaultTask] = []
    public var today: [VaultTask] = []
    public var upcoming: [VaultTask] = []
    public var noDate: [VaultTask] = []
    public var done: [VaultTask] = []
    public init() {}
    public var isEmpty: Bool { overdue.isEmpty && today.isEmpty && upcoming.isEmpty && noDate.isEmpty && done.isEmpty }
}

/// Three-way comparators shared by grouping, calendar and export. Every one ends in explicit tie-breakers
/// (path in UTF-16 order, then line) so the result never depends on sort stability (§3.1 rule 6).
enum TaskOrder {
    static func cmp<T: Comparable>(_ a: T, _ b: T) -> Int { a < b ? -1 : (b < a ? 1 : 0) }
    static func cmp(_ a: String, _ b: String) -> Int {
        a.utf16.elementsEqual(b.utf16) ? 0 : (utf16Less(a, b) ? -1 : 1)
    }

    /// Position of a path in the space's file order; unknown paths sort last. Last duplicate wins, like Kotlin `associate`.
    static func rank(_ fileOrder: [String]) -> (String) -> Int {
        var m: [String: Int] = [:]
        for (i, p) in fileOrder.enumerated() { m[p] = i }
        return { m[$0] ?? Int.max }
    }

    static func fileThenLine(_ rank: (String) -> Int, _ a: VaultTask, _ b: VaultTask) -> Int {
        let r = cmp(rank(a.id.path), rank(b.id.path)); if r != 0 { return r }
        let p = cmp(a.id.path, b.id.path); if p != 0 { return p }
        return cmp(a.lineIndex, b.lineIndex)
    }

    /// Untimed after timed, timed by time.
    static func byTime(_ a: VaultTask, _ b: VaultTask) -> Int {
        switch (a.dueTime, b.dueTime) {
        case (nil, nil): return 0
        case (nil, _?): return 1
        case (_?, nil): return -1
        case let (x?, y?): return cmp(x, y)
        }
    }

    static func byDate(_ a: VaultTask, _ b: VaultTask) -> Int {
        switch (a.dueDate, b.dueDate) {
        case (nil, nil): return 0
        case (nil, _?): return -1
        case (_?, nil): return 1
        case let (x?, y?): return cmp(x, y)
        }
    }

    static func sort(_ tasks: [VaultTask], _ c: (VaultTask, VaultTask) -> Int) -> [VaultTask] {
        tasks.sorted { c($0, $1) < 0 }
    }
}

/// §9.2 grouping. Pure: `now` is passed in.
public enum TaskGrouping {
    /// - Parameters:
    ///   - now: the current moment; a timed task becomes overdue once its due minute has passed, an untimed one at midnight
    ///   - fileOrder: space file order, used as tie-break so the list follows the space's file order
    ///
    /// Cancelled tasks are dropped. Dated groups sort by date, then time (untimed last), then file order, then line.
    /// Undated and done tasks keep file order, then line.
    public static func group(_ tasks: [VaultTask], now: LocalDateTime, fileOrder: [String] = []) -> TaskGroups {
        let today = now.date
        let minute = now.time
        let rank = TaskOrder.rank(fileOrder)
        let fileThenLine: (VaultTask, VaultTask) -> Int = { TaskOrder.fileThenLine(rank, $0, $1) }
        let byDue: (VaultTask, VaultTask) -> Int = { a, b in
            let d = TaskOrder.byDate(a, b); if d != 0 { return d }
            let t = TaskOrder.byTime(a, b); if t != 0 { return t }
            return fileThenLine(a, b)
        }

        func isOverdue(_ t: VaultTask) -> Bool {
            guard let d = t.dueDate else { return false }
            if d < today { return true }
            if d == today, let time = t.dueTime, time < minute { return true }
            return false
        }

        let visible = tasks.filter { $0.state != .cancelled }
        let done = visible.filter { $0.state == .done }
        let open = visible.filter { $0.state != .done }
        let dated = open.filter { $0.dueDate != nil }
        var g = TaskGroups()
        g.overdue = TaskOrder.sort(dated.filter(isOverdue), byDue)
        g.today = TaskOrder.sort(dated.filter { $0.dueDate == today && !isOverdue($0) }, byDue)
        g.upcoming = TaskOrder.sort(dated.filter { $0.dueDate! > today }, byDue)
        g.noDate = TaskOrder.sort(open.filter { $0.dueDate == nil }, fileThenLine)
        g.done = TaskOrder.sort(done, fileThenLine)
        return g
    }
}
