import Foundation

/// Stable list keys (port of `TaskKeys` in TaskListScreen.kt, §6): `path | stableText | n`. `stableText` is the identity
/// text with the checkbox mark normalized to `[ ]` and any `✅ yyyy-MM-dd` removed, so a key survives completing or
/// reopening a task. `n` numbers identical lines of one file in line order. Byte-exact like the rest of the domain.
public struct TaskKeys: Sendable {
    private var keys: [TaskId: String] = [:]

    public init(_ tasks: [VaultTask]) {
        struct Group { var path: String; var stable: String; var tasks: [VaultTask] }
        var groups: [[UInt8]: Group] = [:]
        for t in tasks {
            let stable = Self.stable(t.id.normalizedText)
            let k = Array(t.id.path.utf8) + [0] + Array(stable.utf8)
            groups[k, default: Group(path: t.id.path, stable: stable, tasks: [])].tasks.append(t)
        }
        for g in groups.values {
            for (i, t) in g.tasks.sorted(by: { $0.lineIndex < $1.lineIndex }).enumerated() {
                keys[t.id] = "\(g.path)|\(g.stable)|\(i)"
            }
        }
    }

    public subscript(_ t: VaultTask) -> String {
        keys[t.id] ?? "\(t.id.path)|\(t.id.normalizedText)|\(t.id.occurrence)"
    }

    /// `replaceFirst(\[[^\]]\], "[ ]")`, then `replace(\s*✅\s*\d{4}-\d{2}-\d{2}, "")`, then `trim()`.
    static func stable(_ normalizedText: String) -> String {
        var s = normalizedText.scalarArray
        var i = 0
        while i + 2 < s.count {
            if s[i] == "[", s[i + 1] != "]", s[i + 2] == "]" { s[i + 1] = " "; break }
            i += 1
        }
        return str(ktTrim(removingDoneDates(s)))
    }

    private static func removingDoneDates(_ s: Scalars) -> Scalars {
        var out: Scalars = []
        var i = 0
        while i < s.count {
            if let end = doneDateEnd(s, from: i) { i = end } else { out.append(s[i]); i += 1 }
        }
        return out
    }

    /// End index of a `\s*✅\s*\d{4}-\d{2}-\d{2}` match starting at `i`, or nil.
    private static func doneDateEnd(_ s: Scalars, from i: Int) -> Int? {
        var k = i
        while k < s.count, javaIsSpace(s[k]) { k += 1 }
        guard k < s.count, s[k] == "\u{2705}" else { return nil }
        k += 1
        while k < s.count, javaIsSpace(s[k]) { k += 1 }
        let shape: [Bool] = [true, true, true, true, false, true, true, false, true, true] // true = digit, false = '-'
        guard k + shape.count <= s.count else { return nil }
        for (j, isDigit) in shape.enumerated() {
            let c = s[k + j]
            if isDigit ? !isAsciiDigit(c) : c != "-" { return nil }
        }
        return k + shape.count
    }
}
