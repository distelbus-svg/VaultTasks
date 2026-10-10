import Foundation
@testable import VaultTasksCore

func D(_ y: Int, _ m: Int, _ d: Int) -> LocalDate { LocalDate(year: y, month: m, day: d)! }
func T(_ h: Int, _ m: Int) -> LocalTime { LocalTime(hour: h, minute: m)! }
func DT(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> LocalDateTime { LocalDateTime(D(y, mo, d), T(h, mi)) }

func parseTasks(_ path: String, _ lines: String...) -> [VaultTask] {
    TaskParser.parseFile(path: path, text: VaultText.parse(lines.joined(separator: "\n") + "\n"))
}
func descriptions(_ ts: [VaultTask]?) -> [String] { (ts ?? []).map(\.description) }

/// In-memory vault. Every write bumps a logical clock so lastModified always changes.
final class FakeVaultFileSystem: VaultFileSystem, @unchecked Sendable {
    private struct Entry { var bytes: Data; var mtime: Int64; var writtenViaWrite = false }
    private let lock = NSLock()
    private var files: [String: Entry] = [:]
    private var order: [String] = []
    private var clock: Int64 = 1_000
    private var _corruptReads = false
    private var _readCount = 0

    struct NoSuchFile: Error {}

    var corruptReads: Bool { get { sync { _corruptReads } } set { sync { _corruptReads = newValue } } }
    var readCount: Int { get { sync { _readCount } } set { sync { _readCount = newValue } } }

    private func sync<R>(_ f: () throws -> R) rethrows -> R { lock.lock(); defer { lock.unlock() }; return try f() }

    func put(_ path: String, _ text: String) { put(path, Data(text.utf8)) }
    func put(_ path: String, _ bytes: Data) {
        sync {
            clock += 1
            if files[path] == nil { order.append(path) }
            files[path] = Entry(bytes: bytes, mtime: clock)
        }
    }
    func remove(_ path: String) { sync { files[path] = nil; order.removeAll { $0 == path } } }
    func touch(_ path: String) { sync { clock += 1; files[path]!.mtime = clock } }
    func text(_ path: String) -> String { sync { String(decoding: files[path]!.bytes, as: UTF8.self) } }
    func bytes(_ path: String) -> Data { sync { files[path]!.bytes } }

    func listMarkdown() throws -> [VaultFileInfo] {
        sync {
            order.filter { $0.hasSuffix(".md") && !$0.split(separator: "/").contains { $0.hasPrefix(".") } }
                .map { VaultFileInfo(path: $0, lastModified: files[$0]!.mtime, size: Int64(files[$0]!.bytes.count)) }
        }
    }
    func stat(_ path: String) throws -> VaultFileInfo? {
        sync { files[path].map { VaultFileInfo(path: path, lastModified: $0.mtime, size: Int64($0.bytes.count)) } }
    }
    func read(_ path: String) throws -> Data? {
        sync {
            _readCount += 1
            guard let e = files[path] else { return nil }
            return (_corruptReads && e.writtenViaWrite) ? e.bytes + Data([0x21]) : e.bytes
        }
    }
    func write(_ path: String, _ bytes: Data) throws {
        try sync {
            guard files[path] != nil else { throw NoSuchFile() }
            clock += 1
            files[path]!.bytes = bytes; files[path]!.mtime = clock; files[path]!.writtenViaWrite = true
        }
    }
}
