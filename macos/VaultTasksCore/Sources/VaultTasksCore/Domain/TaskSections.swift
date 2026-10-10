import Foundation

public struct TaskSection: Sendable, Identifiable {
    /// `overdue`, `today`, `upcoming`, `nodate`, or `cat:<name>` (`cat:` = no category).
    public let key: String
    public let title: String
    public var tasks: [VaultTask]
    public var id: String { key }
    public var isCategory: Bool { key.hasPrefix("cat:") }
    public init(key: String, title: String, tasks: [VaultTask]) { self.key = key; self.title = title; self.tasks = tasks }
}

/// The open-task sections of the list (§9.2): the four date buckets, or one section per `#category` (open tasks keep
/// their date order inside a category). Manual order is applied per section; tasks without a stored position keep
/// their normal order after the placed ones. Done is not a section, the UI shows it as one collapsed group.
public enum TaskSections {
    public static func build(_ g: TaskGroups, byCategory: Bool, keys: TaskKeys, order: [String], categoryOrder: [String]) -> [TaskSection] {
        var position: [[UInt8]: Int] = [:] // keyed by bytes: `String` hashing is canonical-equivalence (§3.1 rule 2)
        for (i, k) in order.enumerated() { position[Array(k.utf8)] = i } // last duplicate wins, like Kotlin `associate`
        func arrange(_ list: [VaultTask]) -> [VaultTask] {
            list.enumerated().sorted { a, b in
                let pa = position[Array(keys[a.element].utf8)] ?? Int.max, pb = position[Array(keys[b.element].utf8)] ?? Int.max
                return pa != pb ? pa < pb : a.offset < b.offset // explicit tie-break: no reliance on sort stability
            }.map(\.element)
        }

        if !byCategory {
            return [("overdue", "Overdue", g.overdue), ("today", "Today", g.today), ("upcoming", "Upcoming", g.upcoming),
                    ("nodate", "No date", g.noDate)]
                .filter { !$0.2.isEmpty }
                .map { TaskSection(key: $0.0, title: $0.1, tasks: arrange($0.2)) }
        }

        let open = g.overdue + g.today + g.upcoming + g.noDate
        var byCat: [[UInt8]: (name: String, first: Int, tasks: [VaultTask])] = [:]
        var uncategorized: [VaultTask] = []
        for t in open {
            guard let c = t.category else { uncategorized.append(t); continue }
            let k = Array(c.utf8)
            if byCat[k] == nil { byCat[k] = (c, byCat.count, []) }
            byCat[k]!.tasks.append(t)
        }
        var rank: [[UInt8]: Int] = [:]
        for (i, c) in categoryOrder.enumerated() { rank[Array(c.utf8)] = i }
        let cats = byCat.map { (k, v) in (rank: rank[k] ?? Int.max, lower: v.name.lowercased(), first: v.first, name: v.name, tasks: v.tasks) }
            .sorted { a, b in
                if a.rank != b.rank { return a.rank < b.rank }
                if !sameBytes(a.lower, b.lower) { return utf16Less(a.lower, b.lower) }
                return a.first < b.first
            }
        var out = cats.map { TaskSection(key: "cat:\($0.name)", title: "#\($0.name)", tasks: arrange($0.tasks)) }
        if !uncategorized.isEmpty { out.append(TaskSection(key: "cat:", title: "No category", tasks: arrange(uncategorized))) }
        return out
    }

    /// Category names in the order the list shows them (feeds the reorder sheet).
    public static func categoryNames(_ sections: [TaskSection]) -> [String] {
        sections.compactMap { $0.key.hasPrefix("cat:") && $0.key != "cat:" ? String($0.key.dropFirst(4)) : nil }
    }

    /// New stored order after the user dragged tasks inside one section: the moved keys go to the end in their new
    /// order, keys of tasks that no longer exist are dropped so the list cannot grow forever (as `reorderTasks`).
    public static func reordered(existing: [String], sectionKeys: [String], alive: Set<String>) -> [String] {
        let moved = Set(sectionKeys)
        return (existing.filter { !moved.contains($0) } + sectionKeys).filter { alive.contains($0) }
    }

    /// An edit changes a task's key (its text): carry its manual position over to the new key (as `carryOrder`).
    /// `before`/`after` are the key sets around the edit. Returns nil when there is nothing to carry.
    public static func carriedKey(oldKey: String, before: Set<String>, after: Set<String>) -> String? {
        let added = after.subtracting(before)
        if after.contains(oldKey) || added.count != 1 { return nil }
        return added.first
    }
}
