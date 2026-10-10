import Foundation

/// §9.4 month view, pure parts.
public enum TaskCalendar {
    /// The days shown for `month`: whole weeks (4 to 6 rows of 7), starting on `firstDayOfWeek`, padded with the
    /// neighbouring months' days.
    public static func gridDays(_ month: YearMonth, firstDayOfWeek: DayOfWeek) -> [LocalDate] {
        let first = month.atDay(1)
        let lead = (first.dayOfWeek.rawValue - firstDayOfWeek.rawValue + 7) % 7
        let rows = (lead + month.lengthOfMonth + 6) / 7
        let start = first.plusDays(-lead)
        return (0..<(rows * 7)).map { start.plusDays($0) }
    }

    /// Tasks per due date. Undated and cancelled tasks are left out. Within a day: open before done, then by time
    /// (untimed last), then the space's file order, then line.
    public static func byDay(_ tasks: [VaultTask], fileOrder: [String] = []) -> [LocalDate: [VaultTask]] {
        let rank = TaskOrder.rank(fileOrder)
        let order: (VaultTask, VaultTask) -> Int = { a, b in
            let s = TaskOrder.cmp(a.state == .done ? 1 : 0, b.state == .done ? 1 : 0); if s != 0 { return s }
            let t = TaskOrder.byTime(a, b); if t != 0 { return t }
            return TaskOrder.fileThenLine(rank, a, b)
        }
        var days: [LocalDate: [VaultTask]] = [:]
        for t in tasks where t.state != .cancelled {
            if let d = t.dueDate { days[d, default: []].append(t) }
        }
        return days.mapValues { TaskOrder.sort($0, order) }
    }

    /// Foundation's `Calendar.firstWeekday` counts 1 = Sunday … 7 = Saturday; the domain uses Monday = 1 … Sunday = 7.
    /// Out-of-range values fall back to Monday.
    public static func firstDayOfWeek(calendarFirstWeekday w: Int) -> DayOfWeek {
        switch w {
        case 1: return .sunday
        case 2...7: return DayOfWeek(rawValue: w - 1) ?? .monday
        default: return .monday
        }
    }

    /// What a day cell shows: open and done counts, and whether the day is a past day that still has open tasks.
    public struct DaySummary: Equatable, Sendable {
        public let open: Int
        public let done: Int
        public let overdue: Bool
        public var total: Int { open + done }
    }

    public static func summary(_ tasks: [VaultTask], on day: LocalDate, today: LocalDate) -> DaySummary {
        let open = tasks.filter { $0.state == .open }.count
        let done = tasks.filter { $0.state == .done }.count
        return DaySummary(open: open, done: done, overdue: open > 0 && day < today)
    }
}
