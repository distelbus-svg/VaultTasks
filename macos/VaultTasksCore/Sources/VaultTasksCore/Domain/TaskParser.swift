import Foundation

/// Declaration order == Tasks' serialization order (spec §4.1 editing rules).
public enum SigKind: Int, CaseIterable, Sendable {
    case id, depends, priority, recurrence, onCompletion, created, start, scheduled, due, cancelled, done
}

/// One trailing signifier exactly as found: `sep` = whitespace before it, `raw` = its bytes.
public struct Token: Equatable, Sendable {
    public var kind: SigKind
    public var sep: String
    public var raw: String
    public var value: String
    public static func == (a: Token, b: Token) -> Bool {
        a.kind == b.kind && sameBytes(a.sep, b.sep) && sameBytes(a.raw, b.raw) && sameBytes(a.value, b.value)
    }
}

/// A task line split into parts that can be re-joined byte-exactly (`render() == rawLine`, modulo trailing whitespace).
public struct ParsedLine: Sendable {
    public var indent: String
    public var bullet: String
    public var gap1: String
    public var mark: Unicode.Scalar
    public var gap2: String
    public var headText: String   // description text, without the time token
    public var timeRaw: String?   // " ⏰ HH:mm" as found inside the description (incl. its leading whitespace)
    public var time: LocalTime?   // due time; only set when a valid due date exists
    public var tokens: [Token]
    public var state: TaskState
    public var rawLine: String

    public var description: String { headText.ktTrimmed }
    public var dueDate: LocalDate? {
        tokens.last(where: { $0.kind == .due }).flatMap { TaskParser.parseDate($0.value) }
    }
    public var doneDate: LocalDate? {
        tokens.last(where: { $0.kind == .done }).flatMap { TaskParser.parseDate($0.value) }
    }
    public var priority: Priority? {
        tokens.last(where: { $0.kind == .priority }).flatMap { t in
            Priority.allCases.first(where: { sameBytes($0.emoji, t.value) })
        }
    }
    public var isRecurring: Bool { tokens.contains(where: { $0.kind == .recurrence }) }
    public var recurrenceRule: String? { tokens.last(where: { $0.kind == .recurrence })?.value.ktTrimmed }

    public func render() -> String {
        indent + bullet + gap1 + "[" + String(mark) + "]" + gap2 + headText + (timeRaw ?? "")
            + tokens.map { $0.sep + $0.raw }.joined()
    }

    public func toTask(id: TaskId, lineIndex: Int, notes: String = "") -> VaultTask {
        VaultTask(id: id, lineIndex: lineIndex, state: state, description: description, dueDate: dueDate, dueTime: time,
                  priority: priority, isRecurring: isRecurring, doneDate: doneDate, rawLine: rawLine, notes: notes,
                  recurrence: recurrenceRule)
    }
}

/// Hand-written scanners instead of `Regex` (§3.1 rule 3): every Kotlin pattern is mirrored on unicode scalars with the
/// same greedy/leftmost semantics, Java's ASCII-only `\s`/`\d`, and Kotlin's Unicode `trim()`.
public enum TaskParser {
    private static let vs = Emoji.variationSelector
    private static func sc(_ s: String) -> [Unicode.Scalar] { s.scalarArray }

    private static let starts: [SigKind: [Unicode.Scalar]] = [
        .priority: [Emoji.highest, Emoji.high, Emoji.medium, Emoji.low, Emoji.lowest].flatMap(sc),
        .start: sc(Emoji.start),
        .created: sc(Emoji.created),
        .scheduled: sc(Emoji.hourglassFlowing) + sc(Emoji.hourglass),
        .due: sc(Emoji.due) + sc(Emoji.dueTearOff) + sc(Emoji.dueSpiral),
        .done: sc(Emoji.done),
        .cancelled: sc(Emoji.cancelled),
        .recurrence: sc(Emoji.recurrence),
        .onCompletion: sc(Emoji.onCompletion),
        .depends: sc(Emoji.depends),
        .id: sc(Emoji.id),
    ]
    /// Order of the Kotlin SIGNIFIERS list.
    private static let order: [SigKind] = [.priority, .start, .created, .scheduled, .due, .done, .cancelled,
                                           .recurrence, .onCompletion, .depends, .id]

    private static func isIdChar(_ u: Unicode.Scalar) -> Bool {
        isAsciiLetter(u) || isAsciiDigit(u) || u == "_" || u == "-"
    }
    private static func isRuleChar(_ u: Unicode.Scalar) -> Bool {
        isAsciiLetter(u) || isAsciiDigit(u) || u == "," || u == " " || u == "!"
    }
    private static func dateShape(_ s: [Unicode.Scalar], _ i: Int) -> Bool {
        guard s.count - i == 10 else { return false }
        for k in 0..<10 {
            if k == 4 || k == 7 { if s[i + k] != "-" { return false } } else if !isAsciiDigit(s[i + k]) { return false }
        }
        return true
    }

    /// `<emoji>\uFE0F? *<tail>\z` anchored at `p`; returns the captured group 1.
    private static func match(_ kind: SigKind, _ s: [Unicode.Scalar], at p: Int) -> [Unicode.Scalar]? {
        guard starts[kind]!.contains(s[p]) else { return nil }
        let n = s.count
        var i = p + 1
        if i < n && s[i] == vs { i += 1 }
        switch kind {
        case .priority:
            return i == n ? [s[p]] : nil
        case .start, .created, .scheduled, .due, .done, .cancelled:
            while i < n && s[i] == " " { i += 1 }
            return dateShape(s, i) ? Array(s[i...]) : nil
        case .recurrence:
            while i < n && s[i] == " " { i += 1 }
            guard i < n, s[i...].allSatisfy(isRuleChar) else { return nil }
            return Array(s[i...])
        case .onCompletion:
            while i < n && s[i] == " " { i += 1 }
            guard i < n, s[i...].allSatisfy(isAsciiLetter) else { return nil }
            return Array(s[i...])
        case .id:
            while i < n && s[i] == " " { i += 1 }
            guard i < n, s[i...].allSatisfy(isIdChar) else { return nil }
            return Array(s[i...])
        case .depends:
            while i < n && s[i] == " " { i += 1 }
            let first = i
            var j = i
            func ids() -> Bool { let st = j; while j < n && isIdChar(s[j]) { j += 1 }; return j > st }
            guard ids() else { return nil }
            while true {
                let save = j
                var k = j
                while k < n && s[k] == " " { k += 1 }
                guard k < n, s[k] == "," else { j = save; break }
                k += 1
                while k < n && s[k] == " " { k += 1 }
                j = k
                guard ids() else { j = save; break }
                while j < n && s[j] == " " { j += 1 }
            }
            return j == n ? Array(s[first...]) : nil
        }
    }

    /// Leftmost match of `kind` anywhere in `s` (Regex.find).
    private static func find(_ kind: SigKind, _ s: [Unicode.Scalar]) -> (start: Int, value: [Unicode.Scalar])? {
        for p in 0..<s.count { if let v = match(kind, s, at: p) { return (p, v) } }
        return nil
    }

    /// `(\s*)⏰\uFE0F?\s*([01]\d|2[0-3]):([0-5]\d)\z`, leftmost; start includes the leading whitespace run.
    private static func timeTail(_ s: [Unicode.Scalar]) -> (start: Int, time: LocalTime)? {
        let n = s.count
        let alarm = Emoji.alarm.unicodeScalars.first!
        for p in 0..<n {
            var i = p
            while i < n && javaIsSpace(s[i]) { i += 1 }
            guard i < n, s[i] == alarm else { continue }
            i += 1
            if i < n && s[i] == vs { i += 1 }
            while i < n && javaIsSpace(s[i]) { i += 1 }
            guard n - i == 5, isAsciiDigit(s[i]), isAsciiDigit(s[i + 1]), s[i + 2] == ":",
                  isAsciiDigit(s[i + 3]), isAsciiDigit(s[i + 4]) else { continue }
            let h = Int(s[i].value - 48) * 10 + Int(s[i + 1].value - 48)
            let m = Int(s[i + 3].value - 48) * 10 + Int(s[i + 4].value - 48)
            guard h <= 23, m <= 59, let t = LocalTime(hour: h, minute: m) else { continue }
            return (p, t)
        }
        return nil
    }

    private struct Scan { var head: [Unicode.Scalar]; var tokens: [Token]; var legacyTime: LocalTime? }

    /// Walk back from the end consuming signifiers, like Tasks does. Optionally skips one interior/legacy ⏰ HH:mm.
    private static func scan(_ body: [Unicode.Scalar], allowLegacy: Bool) -> Scan {
        var rest = ktTrimEnd(body)
        var out: [Token] = []
        var legacy: LocalTime? = nil
        outer: while true {
            for kind in order {
                if let (p, value) = find(kind, rest) {
                    let before = Array(rest[..<p])
                    let head = ktTrimEnd(before)
                    out.insert(Token(kind: kind, sep: str(before[head.count...]), raw: str(rest[p...]), value: str(value)), at: 0)
                    rest = head
                    continue outer
                }
            }
            if allowLegacy && legacy == nil, let t = timeTail(rest) {
                legacy = t.time
                rest = Array(rest[..<t.start])
                continue
            }
            break
        }
        return Scan(head: rest, tokens: out, legacyTime: legacy)
    }

    public static func parseDate(_ s: String) -> LocalDate? { LocalDate.parse(s) }

    private struct LineMatch {
        let indent, bullet, gap1: [Unicode.Scalar]
        let mark: Unicode.Scalar
        let gap2, body: [Unicode.Scalar]
    }

    /// `^([ \t>]*)([-*+]|\d+[.)])([ \t]+)\[(.)\]([ \t]+)(.*)$` (DOT_MATCHES_ALL, matchEntire)
    private static func matchLine(_ s: [Unicode.Scalar]) -> LineMatch? {
        let n = s.count
        func ws(_ u: Unicode.Scalar) -> Bool { u == " " || u == "\t" }
        var i = 0
        while i < n && (ws(s[i]) || s[i] == ">") { i += 1 }
        let indent = Array(s[..<i])
        guard i < n else { return nil }
        let b0 = i
        if s[i] == "-" || s[i] == "*" || s[i] == "+" { i += 1 } else {
            let d0 = i
            while i < n && isAsciiDigit(s[i]) { i += 1 }
            guard i > d0, i < n, s[i] == "." || s[i] == ")" else { return nil }
            i += 1
        }
        let bullet = Array(s[b0..<i])
        let g1 = i
        while i < n && ws(s[i]) { i += 1 }
        guard i > g1 else { return nil }
        let gap1 = Array(s[g1..<i])
        guard i + 2 < n, s[i] == "[", s[i + 2] == "]" else { return nil }
        let mark = s[i + 1]
        i += 3
        let g2 = i
        while i < n && ws(s[i]) { i += 1 }
        guard i > g2 else { return nil }
        return LineMatch(indent: indent, bullet: bullet, gap1: gap1, mark: mark, gap2: Array(s[g2..<i]), body: Array(s[i...]))
    }

    public static func parseLine(_ line: String, globalFilter: String = "") -> ParsedLine? {
        guard let m = matchLine(line.scalarArray) else { return nil }
        let state: TaskState
        switch m.mark {
        case " ": state = .open
        case "x", "X": state = .done
        case "-": state = .cancelled
        default: return nil // custom statuses are not tasks in v1
        }
        if ktIsBlank(m.body) { return nil }
        if !globalFilter.isEmpty && !scalarsContain(m.body, globalFilter.scalarArray) { return nil }

        var sc = scan(m.body, allowLegacy: true)
        func hasDue(_ s: Scan) -> Bool {
            s.tokens.last(where: { $0.kind == .due }).flatMap { parseDate($0.value) } != nil
        }
        // A skipped ⏰ only counts as legacy time if a due date sits before it; otherwise it is plain text.
        if sc.legacyTime != nil && !hasDue(sc) { sc = scan(m.body, allowLegacy: false) }

        var head = sc.head
        var timeRaw: String? = nil
        var time: LocalTime? = nil
        if hasDue(sc) {
            time = sc.legacyTime
            if time == nil, let t = timeTail(head) {
                time = t.time
                timeRaw = str(head[t.start...])
                head = Array(head[..<t.start])
            }
        }
        return ParsedLine(indent: str(m.indent), bullet: str(m.bullet), gap1: str(m.gap1), mark: m.mark, gap2: str(m.gap2),
                          headText: str(head), timeRaw: timeRaw, time: time, tokens: sc.tokens, state: state, rawLine: line)
    }

    /// Identity text: trimmed, variation selectors removed, NBSP → space.
    public static func normalize(_ line: String) -> String {
        str(ktTrim(line.scalarArray.filter { $0 != vs }.map { $0 == "\u{00A0}" ? " " : $0 }))
    }

    /// Parses every task of one file; skips fenced code blocks. Identity = (path, normalized text, nth identical line).
    public static func parseFile(path: String, text: VaultText, globalFilter: String = "") -> [VaultTask] {
        var seen: [[UInt8]: Int] = [:]
        var out: [VaultTask] = []
        var fence: String? = nil
        for (i, l) in text.lines.enumerated() {
            let t = ktTrimStart(l.text.scalarArray)
            let f: String? = scalarsHavePrefix(t, Array("```".unicodeScalars)) ? "```"
                : scalarsHavePrefix(t, Array("~~~".unicodeScalars)) ? "~~~" : nil
            if let f {
                fence = fence == nil ? f : (fence == f ? nil : fence)
                continue
            }
            if fence != nil { continue }
            guard let p = parseLine(l.text, globalFilter: globalFilter) else { continue }
            let norm = normalize(l.text)
            let key = Array(norm.utf8)
            let occurrence = seen[key, default: 0]
            seen[key] = occurrence + 1
            out.append(p.toTask(id: TaskId(path: path, normalizedText: norm, occurrence: occurrence), lineIndex: i,
                                notes: Notes.parse(text.lines, taskIndex: i)))
        }
        return out
    }
}
