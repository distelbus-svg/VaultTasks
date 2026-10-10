import Testing
import Foundation
@testable import VaultTasksCore

/// Ports of the FinalFeaturesTest cases that exercise Phase 1 types directly (the VaultWriter-based ones follow in Phase 2).
@Suite struct Phase1FeatureTests {
    private func date(_ s: String) -> LocalDate { LocalDate.parse(s)! }
    private let today = LocalDate(year: 2026, month: 10, day: 4)!

    @Test func notesAreParsedFromNestedBulletsAndStopAtTheNextTask() {
        let vt = VaultText.parse("- [ ] one\n\t- first\n\t- **second**\n\t- [ ] sub\n- [ ] two\n")
        let t = TaskParser.parseFile(path: "a.md", text: vt)
        #expect(t[0].notes == "first\n**second**")
        #expect(t[1].notes == "") // the sub-task
        #expect(t[2].notes == "")
    }

    @Test func notesRenderEscapeAndRoundTrip() {
        let rendered = Notes.render(taskLine: "- [ ] one \u{1F4C5} 2026-10-05", notes: "line a\n\n[ ] looks like a task")
        #expect(rendered == ["\t- line a", "\t- \\[ ] looks like a task"])
        let file = (["- [ ] one"] + rendered + ["- [ ] two"]).joined(separator: "\n") + "\n"
        let t = TaskParser.parseFile(path: "a.md", text: VaultText.parse(file))
        #expect(t.count == 2) // the escaped note did not become a task
        #expect(t[0].notes == "line a\n[ ] looks like a task")
        #expect(Notes.normalize("  a \n\n b\t") == "a\nb")
        #expect(Notes.render(taskLine: "  > - [ ] x", notes: "n") == ["  > \t- n"]) // indent = every leading space/tab/'>' (Kotlin takeWhile)
    }

    @Test func categoriesAreTrailingTagsOnly() {
        #expect(Categories.split("Buy milk #home") == ("Buy milk", "home"))
        #expect(Categories.split("Buy #milk today") == ("Buy #milk today", nil))
        #expect(Categories.split("#home") == ("#home", nil))
        #expect(Categories.split("Call #1") == ("Call #1", nil))
        #expect(Categories.split("a #x/y-z_1  ") == ("a", "x/y-z_1"))
        #expect(Categories.split("a#b") == ("a#b", nil))
        #expect(Categories.join("Buy milk", "home") == "Buy milk #home")
        #expect(Categories.clean("  #Work stuff ")?.lowercased() == "work-stuff")
        #expect(Categories.clean("###") == nil)
        #expect(Categories.clean("123") == nil)
    }

    @Test func categoryOrderIsCaseInsensitiveAndDistinct() {
        let vt = VaultText.parse("- [ ] a #beta\n- [ ] b #Alpha\n- [ ] c #beta\n- [ ] d\n")
        #expect(Categories.of(TaskParser.parseFile(path: "a.md", text: vt)) == ["Alpha", "beta"])
    }

    @Test func nextDates() throws {
        func next(_ rule: String, _ due: String, _ done: String? = nil) throws -> String {
            Recurrences.next(try #require(Recurrences.parse(rule)), due: date(due), completedOn: date(done ?? due)).description
        }
        #expect(try next("every day", "2026-10-04") == "2026-10-05")
        #expect(try next("every 2 weeks", "2026-10-04") == "2026-10-18")
        #expect(try next("every month", "2026-10-04") == "2026-11-04")
        #expect(try next("every year", "2026-10-04") == "2027-10-04")
        #expect(try next("every weekday", "2026-10-02") == "2026-10-05") // Friday → Monday
        #expect(try next("every week on monday, friday", "2026-10-05") == "2026-10-09")
        #expect(try next("every week when done", "2026-10-01", "2026-10-10") == "2026-10-17")
        #expect(Recurrences.parse("every 3rd friday") == nil)
        #expect(Recurrences.rrule("every 2 weeks") == "FREQ=WEEKLY;INTERVAL=2")
        #expect(Recurrences.rrule("every monday, friday") == "FREQ=WEEKLY;BYDAY=MO,FR")
    }

    @Test func recurrenceParsingEdges() {
        #expect(Recurrences.parse("Every  2   WEEKS") == Recurrence(interval: 2, unit: .week, weekdays: nil, whenDone: false))
        #expect(Recurrences.parse("every days")?.unit == .day) // `s?` is optional for any count, like the Kotlin regex
        #expect(Recurrences.parse("every 0 days") == nil)
        #expect(Recurrences.parse("every 1000 days") == nil)
        #expect(Recurrences.parse("every 99999999999999999999 days") == nil) // toIntOrNull overflow
        #expect(Recurrences.parse("every monday and friday")?.weekdays == [.monday, .friday])
        #expect(Recurrences.parse("every monday,") == nil) // Kotlin split keeps the trailing empty piece
        #expect(Recurrences.parse("every week on tuesday when done")?.whenDone == true)
        #expect(Recurrences.parse("every") == nil)
        #expect(Recurrences.label("every 2 weeks") == "Every 2 weeks")
        #expect(Recurrences.label("every weekday") == "Every weekday")
        #expect(Recurrences.label("every monday, friday when done") == "Every Mon, Fri (when done)")
        #expect(Recurrences.label("every 3rd friday") == "Every 3rd friday")
        #expect(Recurrences.rule(interval: 1, unit: .month) == "every month")
        #expect(Recurrences.rule(interval: 3, unit: .day) == "every 3 days")
    }

    @Test func completableMatchesKotlin() {
        func task(_ line: String) -> VaultTask { TaskParser.parseFile(path: "a.md", text: VaultText.parse(line + "\n"))[0] }
        #expect(task("- [ ] plain").completable)
        #expect(task("- [ ] r \u{1F501} every week \u{1F4C5} 2026-10-04").completable)
        #expect(!task("- [ ] r \u{1F501} every 3rd friday \u{1F4C5} 2026-10-04").completable)
        #expect(!task("- [ ] r \u{1F501} every week").completable) // recurring but undated
        #expect(task("- [x] r \u{1F501} every 3rd friday \u{1F4C5} 2026-10-04").completable) // done may always be reopened
        #expect(!task("- [-] gone").completable)
    }

    @Test func completingARecurringTaskProducesDoneLineAndNextOccurrence() throws {
        let line = "- [ ] water plants \u{23F0} 08:00 \u{1F501} every 2 days \u{1F4C5} 2026-10-04"
        let p = try #require(TaskParser.parseLine(line))
        let next = Recurrences.next(try #require(Recurrences.parse(p.recurrenceRule ?? "")), due: try #require(p.dueDate), completedOn: today)
        let (done, fresh) = try TaskSerializer.complete(p, doneDate: today, nextDue: next)
        #expect(done == "- [x] water plants \u{23F0} 08:00 \u{1F501} every 2 days \u{1F4C5} 2026-10-04 \u{2705} 2026-10-04")
        #expect(fresh == "- [ ] water plants \u{23F0} 08:00 \u{1F501} every 2 days \u{1F4C5} 2026-10-06")
    }

    @Test func completeShiftsStartAndScheduledByTheSameDistance() throws {
        let line = "- [ ] x \u{1F501} every week \u{1F6EB} 2026-10-01 \u{23F3} 2026-10-02 \u{1F4C5} 2026-10-04"
        let p = try #require(TaskParser.parseLine(line))
        let (_, fresh) = try TaskSerializer.complete(p, doneDate: nil, nextDue: date("2026-10-11"))
        #expect(fresh == "- [ ] x \u{1F501} every week \u{1F6EB} 2026-10-08 \u{23F3} 2026-10-09 \u{1F4C5} 2026-10-11")
    }

    @Test func serializerGuards() throws {
        let p = try #require(TaskParser.parseLine("- [-] gone \u{274C} 2026-09-02"))
        #expect(throws: SerializerError.self) { try TaskSerializer.setDone(p, done: true, doneDate: nil) }
        let q = try #require(TaskParser.parseLine("- [ ] x"))
        #expect(throws: SerializerError.self) { try TaskSerializer.applyFields(q, TaskFields(description: "  ", dueDate: nil, dueTime: nil)) }
        #expect(throws: SerializerError.self) { try TaskSerializer.newLine(TaskFields(description: "", dueDate: nil, dueTime: nil)) }
        // a repeat needs a date; clearing the date drops it
        let r = try TaskSerializer.applyFields(q, TaskFields(description: "x", dueDate: nil, dueTime: nil, recurrence: .set("every week")))
        #expect(r == "- [ ] x")
        let s = try TaskSerializer.newLine(TaskFields(description: "new", dueDate: date("2026-10-09"), dueTime: nil, recurrence: .set("every week")))
        #expect(s == "- [ ] new \u{1F501} every week \u{1F4C5} 2026-10-09")
    }
}
