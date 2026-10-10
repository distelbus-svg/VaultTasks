import Foundation

/// What an edit does with a task's 🔁 rule. `keep` leaves it exactly as it is (snooze, notification edits, …).
public enum RecurrenceEdit: Equatable, Sendable {
    case keep
    case clear
    case set(String)
}

public enum RepeatUnit: CaseIterable, Sendable {
    case day, week, month, year
    public var word: String {
        switch self { case .day: "day"; case .week: "week"; case .month: "month"; case .year: "year" }
    }
}

/// The subset of the Tasks recurrence syntax the app can advance itself:
///   every [N] day|week|month|year · every weekday · every [week on] monday[, friday…] · … [when done]
/// Anything else (e.g. "every 3rd Friday") stays untouched in the file and is completed in Obsidian.
public struct Recurrence: Hashable, Sendable {
    public let interval: Int
    public let unit: RepeatUnit
    /// Non-nil for weekday rules ("every weekday", "every monday"): the next date is the next listed weekday.
    public let weekdays: Set<DayOfWeek>?
    public let whenDone: Bool
}

public enum Recurrences {
    private static let weekdaySet: Set<DayOfWeek> = [.monday, .tuesday, .wednesday, .thursday, .friday]
    /// Fixed English vocabulary, independent of the Mac's locale (§3.1 rule 7).
    private static let days: [String: DayOfWeek] = Dictionary(uniqueKeysWithValues: DayOfWeek.allCases.map { ($0.fullName, $0) })

    private static func collapseSpaces(_ s: [Unicode.Scalar]) -> [Unicode.Scalar] {
        var out: [Unicode.Scalar] = []
        var inWs = false
        for c in s {
            if javaIsSpace(c) { if !inWs { out.append(" "); inWs = true } } else { out.append(c); inWs = false }
        }
        return out
    }
    private static func has(_ s: [Unicode.Scalar], prefix p: String) -> Bool { scalarsHavePrefix(s, p.scalarArray) }

    /// `^every (?:(\d+) )?(day|week|month|year)s?$`
    private static func wordUnit(_ r: [Unicode.Scalar]) -> ([Unicode.Scalar], RepeatUnit)? {
        guard has(r, prefix: "every ") else { return nil }
        let rest = Array(r.dropFirst(6))
        var i = 0
        while i < rest.count && isAsciiDigit(rest[i]) { i += 1 }
        var digits: [Unicode.Scalar] = []
        var tail = rest
        if i > 0, i < rest.count, rest[i] == " " { digits = Array(rest[..<i]); tail = Array(rest[(i + 1)...]) }
        for u in RepeatUnit.allCases {
            let w = u.word.scalarArray
            if tail == w || tail == w + ["s"] { return (digits, u) }
        }
        return nil
    }

    /// Kotlin `Regex(",\\s*|\\s+and\\s+").split(s)`: empty pieces are kept.
    private static func splitList(_ s: [Unicode.Scalar]) -> [[Unicode.Scalar]] {
        var parts: [[Unicode.Scalar]] = []
        var cur: [Unicode.Scalar] = []
        var i = 0
        let n = s.count
        while i < n {
            if s[i] == "," {
                var j = i + 1
                while j < n && javaIsSpace(s[j]) { j += 1 }
                parts.append(cur); cur = []; i = j; continue
            }
            if javaIsSpace(s[i]) {
                var j = i
                while j < n && javaIsSpace(s[j]) { j += 1 }
                if j + 3 < n, s[j] == "a", s[j + 1] == "n", s[j + 2] == "d", javaIsSpace(s[j + 3]) {
                    var k = j + 3
                    while k < n && javaIsSpace(s[k]) { k += 1 }
                    parts.append(cur); cur = []; i = k; continue
                }
            }
            cur.append(s[i]); i += 1
        }
        parts.append(cur)
        return parts
    }

    public static func parse(_ rule: String) -> Recurrence? {
        var r = collapseSpaces(str(ktTrim(rule.scalarArray)).lowercased().scalarArray)
        var whenDone = false
        let wd = " when done".scalarArray
        if r.count >= wd.count, Array(r[(r.count - wd.count)...]) == wd {
            whenDone = true
            r = ktTrim(Array(r[..<(r.count - wd.count)]))
        }
        if let (digits, unit) = wordUnit(r) {
            guard let n = Int(digits.isEmpty ? "1" : str(digits)), n >= 1, n <= 999 else { return nil }
            return Recurrence(interval: n, unit: unit, weekdays: nil, whenDone: whenDone)
        }
        if r == "every weekday".scalarArray { return Recurrence(interval: 1, unit: .week, weekdays: weekdaySet, whenDone: whenDone) }
        let list: [Unicode.Scalar]
        if has(r, prefix: "every week on ") { list = Array(r.dropFirst(14)) }
        else if has(r, prefix: "every ") { list = Array(r.dropFirst(6)) }
        else { return nil }
        var set = Set<DayOfWeek>()
        for part in splitList(list) {
            guard let d = days[str(ktTrim(part))] else { return nil }
            set.insert(d)
        }
        return Recurrence(interval: 1, unit: .week, weekdays: set, whenDone: whenDone)
    }

    /// The next due date after completing an occurrence that was due on `due`, finished on `completedOn`.
    public static func next(_ r: Recurrence, due: LocalDate, completedOn: LocalDate) -> LocalDate {
        let base = r.whenDone ? completedOn : due
        if let days = r.weekdays {
            var d = base.plusDays(1)
            while !days.contains(d.dayOfWeek) { d = d.plusDays(1) }
            return d
        }
        let n = r.interval
        switch r.unit {
        case .day: return base.plusDays(n)
        case .week: return base.plusWeeks(n)
        case .month: return base.plusMonths(n)
        case .year: return base.plusYears(n)
        }
    }

    public static func rule(interval: Int, unit: RepeatUnit) -> String {
        interval == 1 ? "every \(unit.word)" : "every \(interval) \(unit.word)s"
    }

    /// Kotlin `replaceFirstChar { it.uppercase() }`: acts on a UTF-16 char, so a supplementary first scalar is left alone.
    private static func capitalizedFirst(_ s: String) -> String {
        guard let f = s.unicodeScalars.first, f.value <= 0xFFFF else { return s }
        return String(f).uppercased() + str(s.unicodeScalars.dropFirst())
    }

    /// Short label for the UI, e.g. "Every 2 weeks". Unknown rules are shown as they are written.
    public static func label(_ rule: String) -> String {
        guard let r = parse(rule) else { return capitalizedFirst(rule) }
        let base: String
        if let wd = r.weekdays {
            base = wd == weekdaySet ? "Every weekday" : "Every " + wd.sorted().map(\.shortName).joined(separator: ", ")
        } else {
            base = capitalizedFirst(Self.rule(interval: r.interval, unit: r.unit))
        }
        return r.whenDone ? "\(base) (when done)" : base
    }

    /// iCalendar RRULE value for `rule`, or nil if it cannot be expressed. "When done" has no equivalent and exports as a plain repeat.
    public static func rrule(_ rule: String) -> String? {
        guard let r = parse(rule) else { return nil }
        if let wd = r.weekdays {
            let by = wd.sorted().map { String($0.shortName.prefix(2)).uppercased() }.joined(separator: ",")
            return "FREQ=WEEKLY;BYDAY=\(by)"
        }
        let freq: String
        switch r.unit { case .day: freq = "DAILY"; case .week: freq = "WEEKLY"; case .month: freq = "MONTHLY"; case .year: freq = "YEARLY" }
        return "FREQ=\(freq)" + (r.interval > 1 ? ";INTERVAL=\(r.interval)" : "")
    }
}
