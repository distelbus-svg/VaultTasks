import Foundation
@testable import VaultTasksCore

/// Test helper (spec §12.7): an independent re-implementation of how the Obsidian Tasks plugin reads the emoji format —
/// repeatedly strip a recognised signifier from the END of the line, trim, and stop when nothing matches. It knows
/// nothing about ⏰. Deliberately built on NSRegularExpression (not the Core scanners) so the two cannot share bugs.
/// Patterns mirror the Kotlin helper with Java's ASCII-only \s and \d spelled out.
enum TasksStyleParser {
    struct View: Equatable, CustomStringConvertible {
        let due: LocalDate?
        let done: LocalDate?
        let priority: String?
        var description: String { "View(due=\(due.map(\.description) ?? "nil"), done=\(done.map(\.description) ?? "nil"), priority=\(priority ?? "nil"))" }
    }

    private static func re(_ p: String) -> NSRegularExpression { try! NSRegularExpression(pattern: p) }
    private static let vs = "\\x{FE0F}?"
    private static func date(_ e: String) -> NSRegularExpression { re("\(e)\(vs) *([0-9]{4}-[0-9]{2}-[0-9]{2})$") }

    private static let line = re("^([ \\t\\n\\x{0B}\\f\\r>]*)([-*+]|[0-9]+[.)]) +\\[(.)\\] *(.*)$")
    private static let priority = re("(\(Emoji.highest)|\(Emoji.high)|\(Emoji.medium)|\(Emoji.low)|\(Emoji.lowest))\(vs)$")
    private static let due = date("(?:\(Emoji.due)|\(Emoji.dueTearOff)|\(Emoji.dueSpiral))")
    private static let done = date(Emoji.done)
    private static let other: [NSRegularExpression] = [
        date(Emoji.start), date(Emoji.created), date("(?:\(Emoji.hourglassFlowing)|\(Emoji.hourglass))"), date(Emoji.cancelled),
        re("\(Emoji.recurrence)\(vs) *([a-zA-Z0-9, !]+)$"), re("\(Emoji.onCompletion)\(vs) *([a-zA-Z]+)$"),
        re("\(Emoji.depends)\(vs) *([a-zA-Z0-9_\\-]+( *, *[a-zA-Z0-9_\\-]+ *)*)$"), re("\(Emoji.id)\(vs) *([a-zA-Z0-9_\\-]+)$"),
    ]

    private static func find(_ r: NSRegularExpression, _ s: String) -> (range: NSRange, groups: [String])? {
        let ns = s as NSString
        guard let m = r.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        var g: [String] = []
        for i in 1..<m.numberOfRanges {
            let rg = m.range(at: i)
            g.append(rg.location == NSNotFound ? "" : ns.substring(with: rg))
        }
        return (m.range, g)
    }
    private static func remove(_ s: String, _ r: NSRange) -> String { ((s as NSString).replacingCharacters(in: r, with: "")).ktTrimmed }

    static func parse(_ text: String) -> View? {
        guard let m = find(line, text) else { return nil }
        var s = m.groups[3].ktTrimmed
        var d: LocalDate? = nil, dn: LocalDate? = nil
        var p: String? = nil
        var matched = true
        while matched {
            matched = false
            if let x = find(priority, s) { p = x.groups[0]; s = remove(s, x.range); matched = true }
            if let x = find(due, s) { d = LocalDate.parse(x.groups[0]); s = remove(s, x.range); matched = true }
            if let x = find(done, s) { dn = LocalDate.parse(x.groups[0]); s = remove(s, x.range); matched = true }
            for r in other { if let x = find(r, s) { s = remove(s, x.range); matched = true } }
        }
        return View(due: d, done: dn, priority: p)
    }
}
