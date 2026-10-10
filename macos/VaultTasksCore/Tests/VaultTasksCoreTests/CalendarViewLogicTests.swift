import Testing
import Foundation
@testable import VaultTasksCore

/// Pure parts behind the Phase 6 month view (§9.4).
@Suite struct CalendarViewLogicTests {
    @Test func foundationFirstWeekdayMapsToDomainWeekday() {
        #expect(TaskCalendar.firstDayOfWeek(calendarFirstWeekday: 1) == .sunday)
        #expect(TaskCalendar.firstDayOfWeek(calendarFirstWeekday: 2) == .monday)
        #expect(TaskCalendar.firstDayOfWeek(calendarFirstWeekday: 6) == .friday)
        #expect(TaskCalendar.firstDayOfWeek(calendarFirstWeekday: 7) == .saturday)
        #expect(TaskCalendar.firstDayOfWeek(calendarFirstWeekday: 0) == .monday)
        #expect(TaskCalendar.firstDayOfWeek(calendarFirstWeekday: 8) == .monday)
    }

    @Test func everyFoundationWeekdayGivesAGridStartingOnThatDay() {
        let month = YearMonth(year: 2026, month: 10)!
        for w in 1...7 {
            let first = TaskCalendar.firstDayOfWeek(calendarFirstWeekday: w)
            let grid = TaskCalendar.gridDays(month, firstDayOfWeek: first)
            #expect(grid.first?.dayOfWeek == first)
            #expect(grid.count % 7 == 0)
            #expect(grid.contains(month.atDay(1)) && grid.contains(month.atDay(month.lengthOfMonth)))
        }
    }

    @Test func summaryCountsOpenAndDoneAndIgnoresCancelled() {
        let ts = parseTasks("a.md", "- [ ] a 📅 2026-10-01", "- [x] b 📅 2026-10-01", "- [-] c 📅 2026-10-01", "- [ ] d 📅 2026-10-01")
        let day = D(2026, 10, 1)
        let s = TaskCalendar.summary(ts, on: day, today: D(2026, 10, 9))
        #expect(s.open == 2 && s.done == 1 && s.total == 3)
        #expect(s.overdue)
    }

    @Test func overdueNeedsAPastDayWithOpenTasks() {
        let open = parseTasks("a.md", "- [ ] a 📅 2026-10-09")
        let done = parseTasks("a.md", "- [x] a 📅 2026-10-01")
        let today = D(2026, 10, 9)
        #expect(!TaskCalendar.summary(open, on: today, today: today).overdue)
        #expect(!TaskCalendar.summary(open, on: D(2026, 10, 10), today: today).overdue)
        #expect(!TaskCalendar.summary(done, on: D(2026, 10, 1), today: today).overdue)
        #expect(TaskCalendar.summary(open, on: D(2026, 10, 8), today: today).overdue)
        #expect(TaskCalendar.summary([], on: D(2026, 10, 1), today: today) == .init(open: 0, done: 0, overdue: false))
    }
}
