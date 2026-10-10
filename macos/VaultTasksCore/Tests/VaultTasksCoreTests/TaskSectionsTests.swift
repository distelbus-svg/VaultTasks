import Testing
import Foundation
@testable import VaultTasksCore

@Suite struct TaskKeysTests {
    @Test func identicalLinesOfOneFileAreNumberedInLineOrder() {
        let ts = parseTasks("a.md", "- [ ] Same", "- [ ] Other", "- [ ] Same")
        let k = TaskKeys(ts)
        #expect(k[ts[0]] == "a.md|- [ ] Same|0" || k[ts[0]].hasSuffix("|0"))
        #expect(k[ts[0]] != k[ts[2]])
        #expect(k[ts[2]].hasSuffix("|1"))
        #expect(k[ts[1]].hasSuffix("|0"))
    }

    @Test func keySurvivesCompletingAndReopening() {
        let open = parseTasks("a.md", "- [ ] Buy milk 📅 2026-10-08")
        let done = parseTasks("a.md", "- [x] Buy milk 📅 2026-10-08 ✅ 2026-10-09")
        #expect(TaskKeys(open)[open[0]] == TaskKeys(done)[done[0]])
    }

    @Test func keysDifferByPathAndByText() {
        let a = parseTasks("a.md", "- [ ] Same")
        let b = parseTasks("b.md", "- [ ] Same")
        let c = parseTasks("a.md", "- [ ] Different")
        #expect(TaskKeys(a)[a[0]] != TaskKeys(b)[b[0]])
        #expect(TaskKeys(a)[a[0]] != TaskKeys(c)[c[0]])
    }

    @Test func canonicallyEquivalentButByteDifferentLinesAreDifferentKeys() {
        let ts = parseTasks("a.md", "- [ ] caf\u{E9}", "- [ ] cafe\u{301}")
        let k = TaskKeys(ts)
        #expect(!sameBytes(k[ts[0]], k[ts[1]])) // `String !=` would call them equal (canonical equivalence)
        #expect(k[ts[0]].hasSuffix("|0") && k[ts[1]].hasSuffix("|0"))
    }

    @Test func stableRemovesEveryDoneDateAndNormalizesOnlyTheFirstBox() {
        #expect(TaskKeys.stable("- [x] A ✅ 2026-01-02 B  ✅2026-03-04") == "- [ ] A B")
        #expect(TaskKeys.stable("- [-] A [x] b") == "- [ ] A [x] b")
        #expect(TaskKeys.stable("- [ ] A ✅ 2026-1-02") == "- [ ] A ✅ 2026-1-02")
    }
}

@Suite struct TaskSectionsTests {
    let now = DT(2026, 10, 8, 12, 0)

    private func sections(_ tasks: [VaultTask], byCategory: Bool, order: [String] = [], categoryOrder: [String] = []) -> [TaskSection] {
        TaskSections.build(TaskGrouping.group(tasks, now: now), byCategory: byCategory, keys: TaskKeys(tasks), order: order, categoryOrder: categoryOrder)
    }

    @Test func dateModeHasOnlyNonEmptyOpenSectionsInOrder() {
        let ts = parseTasks("a.md", "- [ ] late 📅 2026-10-01", "- [ ] now 📅 2026-10-08", "- [ ] soon 📅 2026-10-20",
                            "- [ ] whenever", "- [x] finished")
        #expect(sections(ts, byCategory: false).map(\.key) == ["overdue", "today", "upcoming", "nodate"])
        #expect(sections(Array(ts[3...]), byCategory: false).map(\.key) == ["nodate"])
    }

    @Test func manualOrderPlacesTasksFirstAndKeepsTheRestInNormalOrder() {
        let ts = parseTasks("a.md", "- [ ] one", "- [ ] two", "- [ ] three", "- [ ] four")
        let k = TaskKeys(ts)
        let s = sections(ts, byCategory: false, order: [k[ts[2]], k[ts[0]]])
        #expect(s[0].tasks.map(\.description) == ["three", "one", "two", "four"])
    }

    @Test func categoryModeOrdersByManualRankThenAlphabeticalThenNoCategoryLast() {
        let ts = parseTasks("a.md", "- [ ] a #zebra", "- [ ] b #Apple", "- [ ] c", "- [ ] d #mango", "- [ ] e #apple2")
        #expect(sections(ts, byCategory: true).map(\.title) == ["#Apple", "#apple2", "#mango", "#zebra", "No category"])
        #expect(sections(ts, byCategory: true, categoryOrder: ["zebra"]).map(\.title) == ["#zebra", "#Apple", "#apple2", "#mango", "No category"])
        #expect(TaskSections.categoryNames(sections(ts, byCategory: true)) == ["Apple", "apple2", "mango", "zebra"])
    }

    @Test func categorySectionKeepsDateOrderInside() {
        let ts = parseTasks("a.md", "- [ ] n #x", "- [ ] late #x 📅 2026-10-01", "- [ ] soon #x 📅 2026-10-20")
        let s = sections(ts, byCategory: true)
        #expect(s.count == 1)
        #expect(s[0].tasks.map(\.description) == ["late #x", "soon #x", "n #x"])
    }

    @Test func doneAndCancelledAreNeverInSections() {
        let ts = parseTasks("a.md", "- [x] done #x", "- [-] cancelled #x", "- [ ] open #x")
        #expect(sections(ts, byCategory: true).flatMap(\.tasks).map(\.description) == ["open #x"])
    }

    @Test func reorderedMovesSectionKeysToTheEndAndDropsDeadKeys() {
        let r = TaskSections.reordered(existing: ["a", "b", "c", "dead"], sectionKeys: ["c", "a"], alive: ["a", "b", "c"])
        #expect(r == ["b", "c", "a"])
    }

    @Test func carriedKeyOnlyWhenExactlyOneNewKeyAppearedAndOldIsGone() {
        #expect(TaskSections.carriedKey(oldKey: "o", before: ["o", "x"], after: ["n", "x"]) == "n")
        #expect(TaskSections.carriedKey(oldKey: "o", before: ["o"], after: ["o"]) == nil)
        #expect(TaskSections.carriedKey(oldKey: "o", before: ["o"], after: ["n", "m"]) == nil)
    }
}
