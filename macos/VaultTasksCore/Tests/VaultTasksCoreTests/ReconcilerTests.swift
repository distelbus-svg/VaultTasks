import Testing
import Foundation
@testable import VaultTasksCore

private struct SplitMix64: RandomNumberGenerator {
    var s: UInt64
    mutating func next() -> UInt64 {
        s &+= 0x9E37_79B9_7F4A_7C15
        var z = s
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

@Suite struct ReconcilerTests {
    private func parse(_ path: String, _ text: String) -> [VaultTask] {
        TaskParser.parseFile(path: path, text: VaultText.parse(text))
    }

    private func apply(_ cache: [VaultTask], _ c: Changes) -> [VaultTask] {
        var order: [TaskId] = []
        var m: [TaskId: VaultTask] = [:]
        for t in cache { if m.updateValue(t, forKey: t.id) == nil { order.append(t.id) } }
        for d in c.deletes { m[d] = nil; order.removeAll { $0 == d } }
        for u in c.updates { m[u.id] = u }
        for i in c.inserts {
            precondition(m.updateValue(i, forKey: i.id) == nil, "insert of existing id")
            order.append(i.id)
        }
        return order.compactMap { m[$0] }
    }

    @Test func noChangeIsEmpty() throws {
        let t = parse("a.md", "- [ ] A\n- [ ] B\n")
        #expect(try Reconciler.reconcile(cached: t, parsed: t).isEmpty)
    }

    @Test func duplicateLineRemovedDeletesOnlyTheLastOccurrence() throws {
        let before = parse("a.md", "- [ ] Same\n- [ ] Same\n")
        let after = parse("a.md", "- [ ] Same\n")
        let c = try Reconciler.reconcile(cached: before, parsed: after)
        #expect(c.deletes == [before[1].id])
        #expect(c.inserts.isEmpty && c.updates.isEmpty)
    }

    @Test func textEditIsDeletePlusInsert() throws {
        let c = try Reconciler.reconcile(cached: parse("a.md", "- [ ] Old\n"), parsed: parse("a.md", "- [ ] New\n"))
        #expect(c.inserts.count == 1 && c.deletes.count == 1 && c.updates.isEmpty)
    }

    @Test func lineMoveOnlyUpdatesHint() throws {
        let c = try Reconciler.reconcile(cached: parse("a.md", "- [ ] A\n"), parsed: parse("a.md", "\n- [ ] A\n"))
        #expect(c.inserts.isEmpty && c.deletes.isEmpty)
        #expect(c.updates.count == 1)
    }

    @Test func stateChangeChangesIdentityPerSpec() throws {
        // §4.3: identity is the normalized line text, checkbox included → delete + insert, never a duplicate.
        let c = try Reconciler.reconcile(cached: parse("a.md", "- [ ] A\n"), parsed: parse("a.md", "- [x] A\n"))
        #expect(c.inserts.count == 1 && c.deletes.count == 1 && c.updates.isEmpty)
    }

    @Test func moveBetweenFilesIsDeletePlusInsert() throws {
        let c = try Reconciler.reconcile(cached: parse("a.md", "- [ ] A\n"), parsed: parse("b.md", "- [ ] A\n"))
        #expect(c.inserts.count == 1 && c.deletes.count == 1)
    }

    @Test func duplicateParsedIdsAreABug() {
        let t = parse("a.md", "- [ ] A\n")
        #expect(throws: ReconcilerError.self) { try Reconciler.reconcile(cached: [], parsed: t + t) }
    }

    @Test func neverDuplicatesUnderRandomMutations() throws {
        var rnd = SplitMix64(s: 42)
        let pool = ["- [ ] Same", "- [ ] Same", "- [ ] Other \u{1F4C5} 2026-09-28", "- [x] Same", "note", ""]
        var lines = (0..<6).map { _ in pool[Int.random(in: 0..<pool.count, using: &rnd)] }
        var cache: [VaultTask] = []
        for _ in 0..<300 {
            switch Int.random(in: 0..<3, using: &rnd) {
            case 0: lines.insert(pool[Int.random(in: 0..<pool.count, using: &rnd)], at: Int.random(in: 0...lines.count, using: &rnd))
            case 1: if !lines.isEmpty { lines.remove(at: Int.random(in: 0..<lines.count, using: &rnd)) }
            default: if !lines.isEmpty { lines[Int.random(in: 0..<lines.count, using: &rnd)] = pool[Int.random(in: 0..<pool.count, using: &rnd)] }
            }
            let parsed = parse("a.md", lines.joined(separator: "\n"))
            cache = apply(cache, try Reconciler.reconcile(cached: cache, parsed: parsed))
            #expect(Set(parsed.map(\.id)).count == cache.count)
            #expect(Set(parsed) == Set(cache))
        }
    }
}
