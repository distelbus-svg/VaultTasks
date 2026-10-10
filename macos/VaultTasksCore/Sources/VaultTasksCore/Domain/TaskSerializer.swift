import Foundation

/// `notes == nil` leaves the task's notes untouched; `recurrence == .keep` leaves its 🔁 rule untouched. Callers that
/// only change date/time (e.g. "move to tomorrow") therefore never lose either.
public struct TaskFields: Equatable, Sendable {
    public var description: String
    public var dueDate: LocalDate?
    public var dueTime: LocalTime?
    public var notes: String?
    public var recurrence: RecurrenceEdit
    public init(description: String, dueDate: LocalDate?, dueTime: LocalTime?, notes: String? = nil,
                recurrence: RecurrenceEdit = .keep) {
        self.description = description; self.dueDate = dueDate; self.dueTime = dueTime
        self.notes = notes; self.recurrence = recurrence
    }
}

public enum SerializerError: Error, Sendable {
    case descriptionRequired   // Kotlin `require(desc.isNotEmpty())`
    case cancelledTask         // Kotlin `check(state != CANCELLED)`
}

/// All edits return the new text of ONE line. Untouched parts keep their original bytes.
/// Time is always written inside the description, before every Tasks signifier (spec §4.1).
public enum TaskSerializer {
    private static func timeRaw(_ t: LocalTime) -> String { " \(Emoji.alarm) \(t)" }

    public static func applyFields(_ line: ParsedLine, _ f: TaskFields, globalFilter: String = "") throws -> String {
        let desc = f.description.ktTrimmed
        guard !desc.isEmpty else { throw SerializerError.descriptionRequired }
        var head = sameBytes(desc, line.description) ? line.headText : desc
        if !globalFilter.isEmpty && !scalarsContain(head.scalarArray, globalFilter.scalarArray) { head = "\(globalFilter) \(head)" }

        let date = f.dueDate
        let time = date != nil ? f.dueTime : nil // no date → no time
        let tRaw: String?
        if let time {
            tRaw = time == line.time ? (line.timeRaw ?? timeRaw(time)) : timeRaw(time) // legacy position → rewritten into the description
        } else { tRaw = nil }
        var tokens: [Token]
        if let date {
            tokens = setToken(line.tokens, .due, "\(Emoji.due) \(date)", date.description)
        } else {
            tokens = setToken(line.tokens, .due, nil, nil)
        }
        switch f.recurrence {
        case .keep: break
        case .clear: tokens = setToken(tokens, .recurrence, nil, nil)
        case .set(let rule):
            if date == nil { tokens = setToken(tokens, .recurrence, nil, nil) } // a repeat needs a date to repeat from
            else { tokens = setToken(tokens, .recurrence, "\(Emoji.recurrence) \(rule)", rule) }
        }
        var out = line
        out.headText = head; out.timeRaw = tRaw; out.tokens = tokens
        return out.render()
    }

    /// open⇄done. Cancelled tasks are refused. A recurring task is completed with `complete` instead (needs two lines).
    public static func setDone(_ line: ParsedLine, done: Bool, doneDate: LocalDate?) throws -> String {
        guard line.state != .cancelled else { throw SerializerError.cancelledTask }
        let mark: Unicode.Scalar = !done ? " " : (line.state == .done ? line.mark : "x")
        let tokens: [Token]
        if !done { tokens = setToken(line.tokens, .done, nil, nil) }
        else if let doneDate { tokens = setToken(line.tokens, .done, "\(Emoji.done) \(doneDate)", doneDate.description) }
        else { tokens = line.tokens }
        // keep a legacy-position time: move it into the description
        let tRaw = line.timeRaw ?? line.time.map(timeRaw)
        var out = line
        out.mark = mark; out.timeRaw = tRaw; out.tokens = tokens
        return out.render()
    }

    /// Completing an open recurring task, like Tasks does: the line is marked done and a fresh open copy with the next
    /// due date follows it. Returns (done line, next occurrence). Start and scheduled dates move by the same distance.
    public static func complete(_ line: ParsedLine, doneDate: LocalDate?, nextDue: LocalDate) throws -> (done: String, next: String) {
        let doneLine = try setDone(line, done: true, doneDate: doneDate)
        let shift = line.dueDate.map { $0.daysUntil(nextDue) } ?? 0
        var tokens = setToken(line.tokens, .done, nil, nil)
        tokens = setToken(tokens, .due, "\(Emoji.due) \(nextDue)", nextDue.description)
        tokens = tokens.map { t in
            guard t.kind == .start || t.kind == .scheduled, let d = TaskParser.parseDate(t.value) else { return t }
            let moved = d.plusDays(shift)
            var n = t
            n.raw = replacingAll(t.raw, t.value, with: moved.description)
            n.value = moved.description
            return n
        }
        var out = line
        out.mark = " "; out.timeRaw = line.timeRaw ?? line.time.map(timeRaw); out.tokens = tokens
        return (doneLine, out.render())
    }

    public static func newLine(_ f: TaskFields, globalFilter: String = "", bullet: String = "-") throws -> String {
        let desc = f.description.ktTrimmed
        guard !desc.isEmpty else { throw SerializerError.descriptionRequired }
        let head = !globalFilter.isEmpty && !scalarsContain(desc.scalarArray, globalFilter.scalarArray) ? "\(globalFilter) \(desc)" : desc
        var s = "\(bullet) [ ] \(head)"
        if let date = f.dueDate {
            if let t = f.dueTime { s += timeRaw(t) }
            if case .set(let rule) = f.recurrence { s += " \(Emoji.recurrence) \(rule)" }
            s += " \(Emoji.due) \(date)"
        }
        return s
    }

    /// raw == nil removes every token of `kind`; otherwise replaces in place or inserts at Tasks' position.
    static func setToken(_ tokens: [Token], _ kind: SigKind, _ raw: String?, _ value: String?) -> [Token] {
        guard let raw else { return tokens.filter { $0.kind != kind } }
        var out = tokens
        if let i = tokens.lastIndex(where: { $0.kind == kind }) {
            if !sameBytes(tokens[i].value, value) { out[i].raw = raw; out[i].value = value ?? "" }
            return out
        }
        let at = tokens.firstIndex(where: { $0.kind.rawValue > kind.rawValue }) ?? tokens.count
        out.insert(Token(kind: kind, sep: " ", raw: raw, value: value ?? ""), at: at)
        return out
    }
}
