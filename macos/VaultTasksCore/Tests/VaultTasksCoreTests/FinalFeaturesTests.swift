import Testing
import Foundation
@testable import VaultTasksCore

@Suite struct FinalFeaturesTests {
    let fs = FakeVaultFileSystem()
    let today = D(2026, 10, 4)
    var scanner: FileScanner { FileScanner(fs: fs) }
    var writer: VaultWriter { VaultWriter(fs: fs) }
    private func scan() throws -> ScannedFile { try scanner.scan(previous: [:]).files["a.md"]! }

    @Test func updateRewritesNotesAndLeavesOtherLinesAlone() throws {
        fs.put("a.md", "# h\n- [ ] one 📅 2026-10-05\n\t- old\n- [ ] two\n")
        let t = try scan().tasks[0]
        #expect(writer.update(t, TaskFields(description: "one", dueDate: D(2026, 10, 5), dueTime: nil, notes: "line a\n\n[ ] looks like a task")).isOk)
        #expect(fs.text("a.md") == "# h\n- [ ] one 📅 2026-10-05\n\t- line a\n\t- \\[ ] looks like a task\n- [ ] two\n")
        let back = try scan().tasks
        #expect(back.count == 2) // the escaped note did not become a task
        #expect(back[0].notes == "line a\n[ ] looks like a task")
    }

    @Test func nilNotesKeepExistingNotes() throws {
        fs.put("a.md", "- [ ] one 📅 2026-10-05\n\t- keep me\n")
        let t = try scan().tasks[0]
        #expect(writer.update(t, TaskFields(description: "one", dueDate: D(2026, 10, 6), dueTime: nil)).isOk)
        #expect(fs.text("a.md") == "- [ ] one 📅 2026-10-06\n\t- keep me\n")
    }

    @Test func deleteTakesNotesAlongAndRestoreBringsThemBack() throws {
        let src = "- [ ] a\n- [ ] b\n\t- n1\n\t- n2\n- [ ] c\n"
        fs.put("a.md", src)
        let r = writer.delete(try scan().tasks[1])
        #expect(r.isOk)
        #expect(fs.text("a.md") == "- [ ] a\n- [ ] c\n")
        #expect(writer.restore(r.removed!).isOk)
        #expect(fs.text("a.md") == src)
    }

    @Test func createWritesNotesAndRecurrence() throws {
        fs.put("a.md", "- [ ] one\n")
        _ = writer.create("a.md", TaskFields(description: "new", dueDate: D(2026, 10, 9), dueTime: nil, notes: "n1\nn2", recurrence: .set("every week")))
        #expect(fs.text("a.md") == "- [ ] one\n- [ ] new 🔁 every week 📅 2026-10-09\n\t- n1\n\t- n2\n")
        #expect(try scan().tasks[1].recurrence == "every week")
    }

    @Test func movingKeepsNotesUnlessChanged() throws {
        fs.put("a.md", "- [ ] one\n\t- note\n")
        fs.put("b.md", "")
        let t = try scan().tasks[0]
        #expect(writer.move(t, TaskFields(description: "one", dueDate: nil, dueTime: nil), to: "b.md").isOk)
        #expect(fs.text("a.md") == "")
        #expect(fs.text("b.md") == "- [ ] one\n\t- note\n")
    }

    @Test func completingARecurringTaskAddsNextOccurrenceWithCopiedNotes() throws {
        fs.put("a.md", "- [ ] water plants ⏰ 08:00 🔁 every 2 days 📅 2026-10-04\n\t- the ficus\n- [ ] other\n")
        let t = try scan().tasks[0]
        #expect(writer.setDone(t, done: true, doneDate: today).isOk)
        #expect(fs.text("a.md") ==
            "- [x] water plants ⏰ 08:00 🔁 every 2 days 📅 2026-10-04 ✅ 2026-10-04\n\t- the ficus\n" +
            "- [ ] water plants ⏰ 08:00 🔁 every 2 days 📅 2026-10-06\n\t- the ficus\n- [ ] other\n")
    }

    @Test func unsupportedRulesAreRefusedWithoutTouchingTheFile() throws {
        let src = "- [ ] x 🔁 every 3rd friday 📅 2026-10-04\n"
        fs.put("a.md", src)
        #expect(writer.setDone(try scan().tasks[0], done: true, doneDate: today).isFailed)
        #expect(fs.text("a.md") == src)
    }

    // ---- calendar export ----

    @Test func icsContainsOnlyComingOpenTasks() throws {
        fs.put("a.md",
            "- [ ] overdue 📅 2026-10-01\n- [x] done 📅 2026-10-10 ✅ 2026-10-04\n- [ ] undated\n" +
            "- [ ] today, ok #home ⏰ 18:30 📅 2026-10-04\n\t- bring it\n- [ ] weekly 🔁 every week 📅 2026-10-09\n")
        let utcZone = TimeZone(identifier: "UTC")!
        let now = DT(2026, 10, 4, 12, 0).instant(in: utcZone)!
        let ics = IcsExport.build(try scan().tasks, now: now, zone: utcZone, defaultTime: T(9, 0)) { _ in 60 }
        func has(_ s: String) -> Bool { ics.contains(s) }
        #expect(ics.components(separatedBy: "BEGIN:VEVENT").count - 1 == 2)
        #expect(!ics.contains("overdue") && !ics.contains("SUMMARY:done") && !ics.contains("undated"))
        #expect(has("SUMMARY:today\\, ok\r\n"))
        #expect(has("DTSTART:20261004T183000\r\n"))
        #expect(has("DESCRIPTION:bring it\r\n"))
        #expect(has("CATEGORIES:home\r\n"))
        #expect(has("TRIGGER:-PT60M\r\n"))
        #expect(has("RRULE:FREQ=WEEKLY\r\n"))
        #expect(has("DTSTART;VALUE=DATE:20261009\r\n"))
        #expect(has("TRIGGER:PT480M\r\n")) // all-day: default 09:00 minus 60 min
        #expect(has("DTSTAMP:20261004T120000Z\r\n"))
        #expect(ktLines(ics).allSatisfy { $0.utf8.count <= 75 })
        #expect(ics.hasPrefix("BEGIN:VCALENDAR\r\nVERSION:2.0"))
    }

    @Test func icsUidsAreStableAcrossDateChangesAndDistinctForIdenticalLines() throws {
        fs.put("a.md", "- [ ] same 📅 2026-10-05\n- [ ] same 📅 2026-10-05\n")
        let z = TimeZone(identifier: "UTC")!
        let now = DT(2026, 10, 4, 12, 0).instant(in: z)!
        func uids(_ tasks: [VaultTask]) -> [String] {
            IcsExport.build(tasks, now: now, zone: z, defaultTime: T(9, 0)).components(separatedBy: "\r\n").filter { $0.hasPrefix("UID:") }
        }
        let a = uids(try scan().tasks)
        #expect(a.count == 2 && a[0] != a[1])
        fs.put("a.md", "- [ ] same 📅 2026-10-09\n- [ ] same 📅 2026-10-09\n")
        #expect(uids(try scan().tasks) == a)
    }

    @Test func icsFoldingNeverSplitsACharacterAndRoundTrips() {
        let line = "SUMMARY:" + String(repeating: "ä😀", count: 40)
        let folded = IcsExport.fold(line)
        let parts = folded.components(separatedBy: "\r\n")
        #expect(parts.allSatisfy { $0.utf8.count <= 75 })
        #expect(parts.dropFirst().allSatisfy { $0.hasPrefix(" ") })
        #expect(parts.enumerated().map { $0.offset == 0 ? $0.element : String($0.element.dropFirst()) }.joined() == line)
    }
}
