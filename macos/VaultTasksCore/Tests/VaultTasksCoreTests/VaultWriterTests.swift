import Testing
import Foundation
@testable import VaultTasksCore

extension WriteResult {
    var isOk: Bool { if case .ok = self { true } else { false } }
    var isStale: Bool { if case .stale = self { true } else { false } }
    var isMissing: Bool { if case .missing = self { true } else { false } }
    var isFailed: Bool { if case .failed = self { true } else { false } }
    var file: ScannedFile? { if case .ok(let f, _) = self { f } else { nil } }
    var removed: RemovedLine? { if case .ok(_, let r) = self { r } else { nil } }
}

@Suite struct VaultWriterTests {
    let fs = FakeVaultFileSystem()
    let today = D(2026, 9, 29)
    var scanner: FileScanner { FileScanner(fs: fs) }
    var writer: VaultWriter { VaultWriter(fs: fs) }

    private func scan(_ path: String = "a.md") throws -> ScannedFile { try scanner.scan(previous: [:]).files[path]! }

    /// Only the given line differs; everything else byte-identical.
    private func assertOnlyLineChanged(_ before: String, _ after: String, _ index: Int, _ expected: String?,
                                       sourceLocation: SourceLocation = #_sourceLocation) {
        var b = before.components(separatedBy: "\n")
        if let expected { b[index] = expected } else { b.remove(at: index) }
        #expect(b.joined(separator: "\n") == after, sourceLocation: sourceLocation)
    }

    @Test func setDoneChangesExactlyOneLine() throws {
        let src = "# Title\n\n- [ ] Call Anna ⏰ 19:00 🔼 📅 2026-09-28\n- [ ] other\n"
        fs.put("a.md", src)
        let t = try scan().tasks.first { $0.description.hasPrefix("Call") }!
        #expect(writer.setDone(t, done: true, doneDate: today).isOk)
        assertOnlyLineChanged(src, fs.text("a.md"), 2, "- [x] Call Anna ⏰ 19:00 🔼 📅 2026-09-28 ✅ 2026-09-29")
    }

    @Test func crlfBomAndMissingTrailingNewlineSurviveAnEdit() throws {
        fs.put("a.md", "\u{FEFF}- [ ] one\r\n- [ ] two")
        let t = try scan().tasks[0]
        #expect(writer.setDone(t, done: true, doneDate: nil).isOk)
        #expect(Array(fs.bytes("a.md")) == Array("\u{FEFF}- [x] one\r\n- [ ] two".utf8))
    }

    @Test func createAppendsWithPrecedingNewlineAndKeepsNoTrailingNewlineStyle() {
        fs.put("a.md", "- [ ] one")
        let r = writer.create("a.md", TaskFields(description: "new", dueDate: D(2026, 10, 1), dueTime: T(8, 30)))
        #expect(r.isOk)
        #expect(fs.text("a.md") == "- [ ] one\n- [ ] new ⏰ 08:30 📅 2026-10-01")
        #expect(r.file?.tasks.count == 2)
    }

    @Test func updateRewritesOnlyTargetLine() throws {
        let src = "- [ ] a\n- [ ] b 📅 2026-10-01\n- [ ] c\n"
        fs.put("a.md", src)
        let t = try scan().tasks[1]
        #expect(writer.update(t, TaskFields(description: "b2", dueDate: D(2026, 10, 2), dueTime: nil)).isOk)
        assertOnlyLineChanged(src, fs.text("a.md"), 1, "- [ ] b2 📅 2026-10-02")
    }

    @Test func staleLineAbortsWithoutWriting() throws {
        fs.put("a.md", "- [ ] one\n- [ ] two\n")
        let t = try scan().tasks[0]
        fs.put("a.md", "- [ ] one edited elsewhere\n- [ ] two\n")
        let before = fs.bytes("a.md")
        #expect(writer.setDone(t, done: true, doneDate: nil).isStale)
        #expect(fs.bytes("a.md") == before)
    }

    @Test func staleDetectionSurvivesLineShifting() throws {
        fs.put("a.md", "- [ ] one\n- [ ] two\n")
        let t = try scan().tasks[1]
        fs.put("a.md", "inserted line\n- [ ] one\n- [ ] two\n")
        #expect(writer.setDone(t, done: true, doneDate: nil).isOk)
        #expect(fs.text("a.md") == "inserted line\n- [ ] one\n- [x] two\n")
    }

    @Test func duplicateIdenticalLinesEditTheRightOccurrence() throws {
        fs.put("a.md", "- [ ] same\n- [ ] same\n")
        let second = try scan().tasks[1]
        #expect(writer.setDone(second, done: true, doneDate: nil).isOk)
        #expect(fs.text("a.md") == "- [ ] same\n- [x] same\n")
    }

    @Test func missingFile() throws {
        fs.put("a.md", "- [ ] one\n")
        let t = try scan().tasks[0]
        fs.remove("a.md")
        #expect(writer.setDone(t, done: true, doneDate: nil).isMissing)
    }

    @Test func invalidUtf8FileIsRefused() {
        fs.put("a.md", Data([0xC3, 0x28]))
        #expect(writer.create("a.md", TaskFields(description: "x", dueDate: nil, dueTime: nil)).isFailed)
    }

    @Test func failedReadBackRollsFileBack() throws {
        let src = "- [ ] one\n"
        fs.put("a.md", src)
        let t = try scan().tasks[0]
        fs.corruptReads = true
        #expect(writer.setDone(t, done: true, doneDate: nil).isFailed)
        fs.corruptReads = false
        #expect(fs.text("a.md") == src)
    }

    @Test func deleteThenRestoreRoundTrips() throws {
        let src = "- [ ] a\n- [ ] b\n- [ ] c\n"
        fs.put("a.md", src)
        let r = writer.delete(try scan().tasks[1])
        #expect(r.isOk)
        #expect(fs.text("a.md") == "- [ ] a\n- [ ] c\n")
        #expect(writer.restore(r.removed!).isOk)
        #expect(fs.text("a.md") == src)
    }

    @Test func restoreAppendsWhenFileChangedSinceDelete() throws {
        fs.put("a.md", "- [ ] a\n- [ ] b\n- [ ] c\n")
        let r = writer.delete(try scan().tasks[1])
        fs.put("a.md", fs.text("a.md") + "- [ ] d\n")
        #expect(writer.restore(r.removed!).isOk)
        #expect(fs.text("a.md") == "- [ ] a\n- [ ] c\n- [ ] d\n- [ ] b\n")
    }

    @Test func moveRemovesFromSourceAndAppendsEditedLineKeepingSignifiers() throws {
        fs.put("a.md", "- [ ] keep\n- [ ] Call Anna ⏰ 19:00 🔼 📅 2026-09-28\n")
        fs.put("b.md", "# B\n- [ ] existing\n")
        let t = try scan().tasks.first { $0.description.hasPrefix("Call") }!
        let r = writer.move(t, TaskFields(description: "Call Anna", dueDate: D(2026, 10, 5), dueTime: T(7, 0)), to: "b.md")
        #expect(r.isOk)
        #expect(fs.text("a.md") == "- [ ] keep\n")
        #expect(fs.text("b.md") == "# B\n- [ ] existing\n- [ ] Call Anna ⏰ 07:00 🔼 📅 2026-10-05\n")
        #expect(r.file?.state.path == "b.md")
    }

    @Test func moveToSameFileIsPlainUpdate() throws {
        fs.put("a.md", "- [ ] one\n- [ ] two\n")
        let t = try scan().tasks[0]
        #expect(writer.move(t, TaskFields(description: "one!", dueDate: nil, dueTime: nil), to: "a.md").isOk)
        #expect(fs.text("a.md") == "- [ ] one!\n- [ ] two\n")
    }

    @Test func moveToMissingFileIsRolledBackAndLosesNothing() throws {
        let src = "- [ ] one\n- [ ] two\n- [ ] three\n"
        fs.put("a.md", src)
        let t = try scan().tasks[1]
        #expect(writer.move(t, TaskFields(description: "two", dueDate: nil, dueTime: nil), to: "nope.md").isMissing)
        #expect(fs.text("a.md") == src)
    }

    @Test func moveWithStaleSourceWritesNothingAnywhere() throws {
        fs.put("a.md", "- [ ] one\n")
        fs.put("b.md", "- [ ] other\n")
        let t = try scan().tasks[0]
        fs.put("a.md", "- [ ] one edited elsewhere\n")
        #expect(writer.move(t, TaskFields(description: "one", dueDate: nil, dueTime: nil), to: "b.md").isStale)
        #expect(fs.text("a.md") == "- [ ] one edited elsewhere\n")
        #expect(fs.text("b.md") == "- [ ] other\n")
    }

    @Test func moveToNonUtf8TargetIsRefusedAndRolledBack() throws {
        let src = "- [ ] one\n- [ ] two\n"
        fs.put("a.md", src)
        fs.put("b.md", Data([0xC3, 0x28]))
        let t = try scan().tasks[0]
        #expect(writer.move(t, TaskFields(description: "one", dueDate: nil, dueTime: nil), to: "b.md").isFailed)
        #expect(fs.text("a.md") == src)
    }

    @Test func oppositeConcurrentMovesDoNotDeadlock() throws {
        fs.put("a.md", "- [ ] from a\n")
        fs.put("b.md", "- [ ] from b\n")
        let all = try scanner.scan(previous: [:]).files
        let ta = all["a.md"]!.tasks[0], tb = all["b.md"]!.tasks[0]
        let w = writer
        let results = LockedBox<[WriteResult]>([])
        DispatchQueue.concurrentPerform(iterations: 2) { i in
            let r = i == 0 ? w.move(ta, TaskFields(description: "from a", dueDate: nil, dueTime: nil), to: "b.md")
                           : w.move(tb, TaskFields(description: "from b", dueDate: nil, dueTime: nil), to: "a.md")
            results.mutate { $0.append(r) }
        }
        #expect(results.value.count == 2 && results.value.allSatisfy(\.isOk))
        // both tasks landed somewhere, none lost or duplicated
        #expect((fs.text("a.md") + fs.text("b.md")).components(separatedBy: "\n").filter { $0.hasPrefix("- [ ]") }.sorted() == ["- [ ] from a", "- [ ] from b"])
    }

    @Test func concurrentWritesToOneFileAreSerializedAndNoneIsLost() throws {
        let n = 20
        fs.put("a.md", (0..<n).map { "- [ ] task \($0)\n" }.joined())
        let tasks = try scan().tasks
        let w = writer
        let oks = LockedBox(0)
        DispatchQueue.concurrentPerform(iterations: n) { i in
            if w.setDone(tasks[i], done: true, doneDate: nil).isOk { oks.mutate { $0 += 1 } }
        }
        #expect(fs.text("a.md") == (0..<n).map { "- [x] task \($0)\n" }.joined())
        #expect(oks.value == n)
    }
}
