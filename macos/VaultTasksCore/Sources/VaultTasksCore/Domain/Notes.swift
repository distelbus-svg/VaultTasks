import Foundation

/// Task notes live in the vault as nested bullets directly under the task line, one bullet per note line.
/// The Tasks plugin only reads the checkbox line, so notes never touch its signifiers or the task's identity.
/// A note that would itself look like a task ("[ ] x") gets its first bracket escaped so it can never become one.
public enum Notes {
    private static func indentOf(_ s: [Unicode.Scalar]) -> [Unicode.Scalar] {
        Array(s.prefix(while: { $0 == " " || $0 == "\t" || $0 == ">" }))
    }

    /// `^([ \t>]*)([-*+])[ \t]+(.*)$` (DOT_MATCHES_ALL)
    private static func noteMatch(_ s: [Unicode.Scalar]) -> (indent: [Unicode.Scalar], content: [Unicode.Scalar])? {
        let n = s.count
        var i = 0
        while i < n && (s[i] == " " || s[i] == "\t" || s[i] == ">") { i += 1 }
        guard i < n, s[i] == "-" || s[i] == "*" || s[i] == "+" else { return nil }
        let indent = Array(s[..<i])
        i += 1
        let g = i
        while i < n && (s[i] == " " || s[i] == "\t") { i += 1 }
        guard i > g else { return nil }
        return (indent, Array(s[i...]))
    }

    /// `^\[.?]` where `.` excludes line terminators (no DOT_MATCHES_ALL on this one).
    private static func looksLikeBox(_ s: [Unicode.Scalar]) -> Bool {
        guard s.count >= 2, s[0] == "[" else { return false }
        if s[1] == "]" { return true }
        let t = s[1].value
        let terminator = t == 0x0A || t == 0x0D || t == 0x85 || t == 0x2028 || t == 0x2029
        return s.count >= 3 && !terminator && s[2] == "]"
    }

    /// Number of note lines following the task at `taskIndex` (0 if none).
    public static func blockLength(_ lines: [VaultText.Line], taskIndex: Int) -> Int { block(lines, taskIndex: taskIndex).count }

    /// The note lines (bullet content, unescaped) belonging to the task at `taskIndex`.
    public static func block(_ lines: [VaultText.Line], taskIndex: Int) -> [String] {
        let taskIndent = indentOf(lines[taskIndex].text.scalarArray)
        var out: [String] = []
        var j = taskIndex + 1
        while j < lines.count {
            let t = lines[j].text
            if TaskParser.parseLine(t) != nil { break } // a sub-task ends the notes
            guard let m = noteMatch(t.scalarArray) else { break }
            if m.indent.count <= taskIndent.count || !scalarsHavePrefix(m.indent, taskIndent) { break }
            out.append(str(unescape(m.content)))
            j += 1
        }
        return out
    }

    public static func parse(_ lines: [VaultText.Line], taskIndex: Int) -> String {
        block(lines, taskIndex: taskIndex).joined(separator: "\n")
    }

    /// The vault lines for `notes` under a task line whose own indent is that of `taskLine`. Blank lines are dropped.
    public static func render(taskLine: String, notes: String) -> [String] {
        let indent = str(indentOf(taskLine.scalarArray)) + "\t"
        return ktLines(notes).map { $0.ktTrimmedEnd }.filter { !$0.ktIsBlank }
            .map { indent + "- " + str(escape($0.ktTrimmedStart.scalarArray)) }
    }

    /// Normal form used to decide whether the user changed the notes.
    public static func normalize(_ notes: String) -> String {
        ktLines(notes).map { $0.ktTrimmed }.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    private static func escape(_ s: [Unicode.Scalar]) -> [Unicode.Scalar] { looksLikeBox(s) ? ["\\"] + s : s }
    private static func unescape(_ s: [Unicode.Scalar]) -> [Unicode.Scalar] {
        s.count >= 2 && s[0] == "\\" && s[1] == "[" ? Array(s.dropFirst()) : s
    }
}
