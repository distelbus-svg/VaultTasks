import Testing
import Foundation
@testable import VaultTasksCore

@Suite struct FileScannerTests {
    let fs = FakeVaultFileSystem()
    var scanner: FileScanner { FileScanner(fs: fs) }

    @Test func firstScanFindsTasksAndSkipsDotDirsAndNonMd() throws {
        fs.put("a.md", "- [ ] one\n- [x] two\n")
        fs.put("sub/b.md", "text\n- [ ] three 📅 2026-10-01\n")
        fs.put(".obsidian/c.md", "- [ ] hidden\n")
        fs.put("d.txt", "- [ ] nope\n")
        let r = try scanner.scan(previous: [:])
        #expect(Set(r.files.keys) == ["a.md", "sub/b.md"])
        #expect(r.files.values.map(\.tasks.count).reduce(0, +) == 3)
        #expect(r.changes.inserts.count == 3)
        #expect(r.reparsed == 2)
    }

    @Test func unchangedFilesAreNotReRead() throws {
        fs.put("a.md", "- [ ] one\n")
        let first = try scanner.scan(previous: [:])
        fs.readCount = 0
        let second = try scanner.scan(previous: first.files)
        #expect(fs.readCount == 0)
        #expect(second.reparsed == 0)
        #expect(second.changes.isEmpty)
    }

    @Test func touchedButIdenticalIsConfirmedByHash() throws {
        fs.put("a.md", "- [ ] one\n")
        let first = try scanner.scan(previous: [:])
        fs.touch("a.md")
        let second = try scanner.scan(previous: first.files)
        #expect(second.reparsed == 0)
        #expect(second.changes.isEmpty)
        #expect(second.files["a.md"]?.state.lastModified == (try fs.stat("a.md"))?.lastModified)
    }

    @Test func editProducesInsertAndDeleteByIdentity() throws {
        fs.put("a.md", "- [ ] one\n- [ ] two\n")
        let first = try scanner.scan(previous: [:])
        fs.put("a.md", "- [ ] one\n- [ ] three\n")
        let second = try scanner.scan(previous: first.files)
        #expect(second.changes.inserts.map(\.description) == ["three"])
        #expect(second.changes.deletes.map { $0.normalizedText.replacingOccurrences(of: "- [ ] ", with: "") } == ["two"])
    }

    @Test func deletedFileDeletesItsTasks() throws {
        fs.put("a.md", "- [ ] one\n")
        let first = try scanner.scan(previous: [:])
        fs.remove("a.md")
        let second = try scanner.scan(previous: first.files)
        #expect(second.changes.deletes.count == 1)
        #expect(second.files.isEmpty)
    }

    @Test func invalidUtf8IsReportedAndPreviousStateKept() throws {
        fs.put("a.md", "- [ ] one\n")
        let first = try scanner.scan(previous: [:])
        fs.put("a.md", Data([0x2D, 0x20, 0xC3, 0x28]))
        let second = try scanner.scan(previous: first.files)
        #expect(second.unreadable == ["a.md"])
        #expect(first.files["a.md"]?.tasks == second.files["a.md"]?.tasks)
        #expect(second.changes.isEmpty)
    }

    @Test func includeFilterDropsFilesOutsideScope() throws {
        fs.put("a.md", "- [ ] one\n")
        fs.put("b.md", "- [ ] two\n")
        let first = try scanner.scan(previous: [:])
        let second = try scanner.scan(previous: first.files, include: { $0 == "a.md" })
        #expect(Set(second.files.keys) == ["a.md"])
        #expect(second.changes.deletes.count == 1)
    }

    @Test func identicalLinesGetDistinctOccurrenceIndexes() throws {
        fs.put("a.md", "- [ ] same\n- [ ] same\n- [ ] same\n")
        let ids = try scanner.scan(previous: [:]).files["a.md"]!.tasks.map(\.id)
        #expect(Set(ids).count == 3)
    }

    @Test func forceReparsesAndHonoursChangedGlobalFilter() throws {
        fs.put("a.md", "- [ ] #task keep\n- [ ] drop\n")
        let filter = LockedBox("")
        let s = FileScanner(fs: fs) { filter.value }
        let first = try s.scan(previous: [:])
        #expect(first.files["a.md"]?.tasks.count == 2)
        filter.value = "#task"
        let second = try s.scan(previous: first.files, force: true)
        #expect(second.files["a.md"]?.tasks.count == 1)
        #expect(second.changes.deletes.count == 1)
    }

    @Test func scanReportsEveryVaultFileAsAvailableEvenWhenScoped() throws {
        fs.put("a.md", "- [ ] one\n")
        fs.put("sub/b.md", "- [ ] two\n")
        let r = try scanner.scan(previous: [:], include: { $0 == "a.md" })
        #expect(r.order == ["a.md"])
        #expect(Set(r.available) == ["a.md", "sub/b.md"])
    }
}

final class LockedBox<V: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var v: V
    init(_ v: V) { self.v = v }
    func mutate(_ f: (inout V) -> Void) { lock.lock(); f(&v); lock.unlock() }
    var value: V { get { lock.lock(); defer { lock.unlock() }; return v } set { lock.lock(); v = newValue; lock.unlock() } }
}
