import Testing
import Foundation
@testable import VaultTasksCore

@Suite struct SpacesDataTests {
    let empty = SpacesData()

    @Test func addMakesNewSpaceActiveAndIgnoresBlankNamesAndDuplicateIds() {
        let a = empty.add(id: "1", name: "  Work ")
        #expect(a.spaces.map(\.name) == ["Work"])
        #expect(a.activeId == "1")
        #expect(a.add(id: "2", name: "   ") == a)
        #expect(a.add(id: "1", name: "Again") == a)
    }

    @Test func activeFallsBackToFirstWhenIdDangles() {
        var d = empty.add(id: "1", name: "A").add(id: "2", name: "B")
        d.activeId = "gone"
        #expect(d.active?.id == "1")
        #expect(empty.active == nil)
    }

    @Test func deleteMovesActiveToFirstRemaining() {
        let d = empty.add(id: "1", name: "A").add(id: "2", name: "B")
        #expect(d.activeId == "2")
        let after = d.delete(id: "2")
        #expect(after.activeId == "1")
        #expect(after.delete(id: "1").activeId == nil)
        #expect(after.delete(id: "nope") == after)
    }

    @Test func deleteOfInactiveKeepsActive() {
        let d = empty.add(id: "1", name: "A").add(id: "2", name: "B").setActive(id: "1")
        #expect(d.delete(id: "2").activeId == "1")
    }

    @Test func moveIsClampedAndReorders() {
        let d = empty.add(id: "1", name: "A").add(id: "2", name: "B").add(id: "3", name: "C")
        #expect(d.move(id: "3", delta: -1).spaces.map(\.id) == ["1", "3", "2"])
        #expect(d.move(id: "3", delta: -10).spaces.map(\.id) == ["3", "1", "2"])
        #expect(d.move(id: "1", delta: -1) == d)
        #expect(d.move(id: "3", delta: 1) == d)
    }

    @Test func setFilesDedupesAndDropsDefaultThatLeft() {
        let d = empty.add(id: "1", name: "A").setFiles(id: "1", files: ["a.md", "b.md", "a.md"]).setDefault(id: "1", file: "b.md")
        #expect(d.spaces[0].files == ["a.md", "b.md"])
        #expect(d.spaces[0].defaultFile == "b.md")
        #expect(d.setFiles(id: "1", files: ["a.md"]).spaces[0].defaultFile == nil)
    }

    @Test func setDefaultOnlyAcceptsFileOfTheSpace() {
        let d = empty.add(id: "1", name: "A").setFiles(id: "1", files: ["a.md"])
        #expect(d.setDefault(id: "1", file: "zzz.md").spaces[0].defaultFile == nil)
        #expect(d.setDefault(id: "1", file: "a.md").spaces[0].defaultFile == "a.md")
        #expect(d.setDefault(id: "1", file: "a.md").setDefault(id: "1", file: nil).spaces[0].defaultFile == nil)
    }

    @Test func creationFileIsDefaultElseFirstElseNil() {
        let s = Space(id: "1", name: "A", files: ["a.md", "b.md"], defaultFile: "b.md")
        #expect(s.creationFile == "b.md")
        var t = s; t.defaultFile = nil
        #expect(t.creationFile == "a.md")
        t.defaultFile = "gone.md"
        #expect(t.creationFile == "a.md")
        #expect(Space(id: "1", name: "A").creationFile == nil)
    }

    @Test func assignedFilesIsTheUnionAcrossSpaces() {
        let d = empty.add(id: "1", name: "A").add(id: "2", name: "B")
            .setFiles(id: "1", files: ["a.md", "shared.md"]).setFiles(id: "2", files: ["shared.md", "b.md"])
        #expect(d.assignedFiles == ["a.md", "shared.md", "b.md"])
    }

    @Test func renameAndUnknownIds() {
        let d = empty.add(id: "1", name: "A")
        #expect(d.rename(id: "1", name: " B ").spaces[0].name == "B")
        #expect(d.rename(id: "1", name: " ") == d)
        #expect(d.rename(id: "nope", name: "X") == d)
        #expect(d.setActive(id: "nope") == d)
    }

    @Test func jsonRoundTrip() throws {
        let d = empty.add(id: "1", name: "A").setFiles(id: "1", files: ["a.md"]).setDefault(id: "1", file: "a.md")
        let back = try JSONDecoder().decode(SpacesData.self, from: JSONEncoder().encode(d))
        #expect(back == d)
    }
}

@Suite struct TaskGroupingTests {
    let now = DT(2026, 9, 30, 12, 0)

    @Test func groupsByDueDateRelativeToTodayAndHidesCancelled() {
        let ts = parseTasks("a.md", "- [ ] late 📅 2026-09-29", "- [ ] now 📅 2026-09-30", "- [ ] soon 📅 2026-10-01",
                            "- [ ] whenever", "- [x] finished 📅 2026-09-01", "- [-] dropped 📅 2026-09-29")
        let g = TaskGrouping.group(ts, now: now)
        #expect(descriptions(g.overdue) == ["late"])
        #expect(descriptions(g.today) == ["now"])
        #expect(descriptions(g.upcoming) == ["soon"])
        #expect(descriptions(g.noDate) == ["whenever"])
        #expect(descriptions(g.done) == ["finished"])
    }

    @Test func doneTasksNeverCountAsOverdue() {
        let g = TaskGrouping.group(parseTasks("a.md", "- [x] old 📅 2020-01-01"), now: now)
        #expect(g.overdue.isEmpty)
        #expect(g.done.count == 1)
    }

    @Test func datedGroupsSortByDateThenTimeWithUntimedLast() {
        let ts = parseTasks("a.md", "- [ ] untimed 📅 2026-10-01", "- [ ] late ⏰ 18:00 📅 2026-10-01", "- [ ] early ⏰ 08:00 📅 2026-10-01",
                            "- [ ] also untimed 📅 2026-10-01", "- [ ] next day 📅 2026-10-02")
        #expect(descriptions(TaskGrouping.group(ts, now: now).upcoming) == ["early", "late", "untimed", "also untimed", "next day"])
    }

    @Test func tiesFollowSpaceFileOrderThenLineOrder() {
        let a = parseTasks("a.md", "- [ ] a1", "- [ ] a2")
        let b = parseTasks("b.md", "- [ ] b1")
        let g = TaskGrouping.group(a + b, now: now, fileOrder: ["b.md", "a.md"])
        #expect(descriptions(g.noDate) == ["b1", "a1", "a2"])
    }

    @Test func emptyInputGivesEmptyGroups() { #expect(TaskGrouping.group([], now: now).isEmpty) }

    @Test func timedTaskBecomesOverdueOnceItsMinutePassed() {
        let ts = parseTasks("a.md", "- [ ] earlier today ⏰ 11:59 📅 2026-09-30", "- [ ] due this minute ⏰ 12:00 📅 2026-09-30",
                            "- [ ] later today ⏰ 12:01 📅 2026-09-30", "- [ ] untimed today 📅 2026-09-30")
        let g = TaskGrouping.group(ts, now: now) // 12:00:30 truncates to the same minute
        #expect(descriptions(g.overdue) == ["earlier today"])
        #expect(descriptions(g.today) == ["due this minute", "later today", "untimed today"])
        #expect(descriptions(TaskGrouping.group(ts, now: now.plusMinutes(1)).overdue) == ["earlier today", "due this minute"])
    }
}

@Suite struct TaskCalendarTests {
    @Test func gridIsWholeWeeksStartingOnChosenWeekday() {
        let sep = YearMonth(year: 2026, month: 9)! // 1 Sep 2026 is a Tuesday
        let mon = TaskCalendar.gridDays(sep, firstDayOfWeek: .monday)
        #expect(mon.count == 35)
        #expect(mon.first == D(2026, 8, 31))
        #expect(mon.last == D(2026, 10, 4))
        let sun = TaskCalendar.gridDays(sep, firstDayOfWeek: .sunday)
        #expect(sun.first == D(2026, 8, 30))
        #expect(sun.first?.dayOfWeek == .sunday)
    }

    @Test func monthStartingOnFirstWeekdayCanFitFourRows() {
        let feb = TaskCalendar.gridDays(YearMonth(year: 2027, month: 2)!, firstDayOfWeek: .monday)
        #expect(feb.count == 28)
        #expect(feb.first == D(2027, 2, 1))
    }

    @Test func longMonthStartingLateNeedsSixRows() {
        let aug = TaskCalendar.gridDays(YearMonth(year: 2026, month: 8)!, firstDayOfWeek: .monday)
        #expect(aug.count == 42)
        #expect(aug.first == D(2026, 7, 27))
        #expect(aug.last == D(2026, 9, 6))
    }

    @Test func byDayDropsUndatedAndCancelledKeepsDone() {
        let ts = parseTasks("a.md", "- [ ] open 📅 2026-10-01", "- [x] finished 📅 2026-10-01", "- [-] dropped 📅 2026-10-01", "- [ ] whenever")
        let m = TaskCalendar.byDay(ts)
        #expect(Set(m.keys) == [D(2026, 10, 1)])
        #expect(descriptions(m[D(2026, 10, 1)]) == ["open", "finished"])
    }

    @Test func withinADayOpenFirstThenTimeUntimedLastThenFileOrder() {
        let a = parseTasks("a.md", "- [x] done early ⏰ 07:00 📅 2026-10-01", "- [ ] untimed a 📅 2026-10-01", "- [ ] late ⏰ 18:00 📅 2026-10-01")
        let b = parseTasks("b.md", "- [ ] untimed b 📅 2026-10-01", "- [ ] early ⏰ 08:00 📅 2026-10-01")
        let day = TaskCalendar.byDay(a + b, fileOrder: ["b.md", "a.md"])[D(2026, 10, 1)]
        #expect(descriptions(day) == ["early", "late", "untimed b", "untimed a", "done early"])
    }

    @Test func localDateTimeArithmetic() {
        #expect(DT(2026, 9, 30, 23, 30).plusMinutes(61) == DT(2026, 10, 1, 0, 31))
        #expect(DT(2026, 10, 1, 0, 5).minusMinutes(10) == DT(2026, 9, 30, 23, 55))
        #expect(LocalDateTime.parse("2026-10-01T08:05") == DT(2026, 10, 1, 8, 5))
        #expect(YearMonth(year: 2026, month: 12)!.plusMonths(1) == YearMonth(year: 2027, month: 1))
        #expect(YearMonth(year: 2026, month: 1)!.plusMonths(-1) == YearMonth(year: 2025, month: 12))
    }
}
