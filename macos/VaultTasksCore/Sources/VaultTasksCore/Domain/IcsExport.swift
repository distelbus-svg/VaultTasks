import Foundation

/// iCalendar export of the coming tasks, for Apple Calendar (and anything else that reads .ics).
///
/// "Coming" = open, with a due date of `today` or later. Done, cancelled, undated and overdue tasks are left out.
/// Events carry a stable UID (file + description, not the line, so a changed date or time updates the event on the next
/// import instead of duplicating it), the notes as description, the category, a repeat rule where one can be expressed,
/// and an alarm that matches the task's reminder (lead time; date-only tasks use the default reminder time).
public enum IcsExport {
    private static let timedMinutes = 30

    public static func coming(_ tasks: [VaultTask], today: LocalDate) -> [VaultTask] {
        TaskOrder.sort(tasks.filter { $0.state == .open && ($0.dueDate.map { !($0 < today) } ?? false) }) { a, b in
            let d = TaskOrder.byDate(a, b); if d != 0 { return d }
            let t = TaskOrder.byTime(a, b); if t != 0 { return t }
            let p = TaskOrder.cmp(a.id.path, b.id.path); if p != 0 { return p }
            return TaskOrder.cmp(a.lineIndex, b.lineIndex)
        }
    }

    /// - Parameters:
    ///   - now: the export moment; its local date in `zone` decides what is "coming", its UTC reading is the DTSTAMP
    ///   - leadMinutes: the task's "remind me before"
    public static func build(
        _ tasks: [VaultTask],
        now: Date,
        zone: TimeZone = .current,
        defaultTime: LocalTime,
        leadMinutes: (VaultTask) -> Int = { _ in 0 }
    ) -> String {
        let local = LocalDateTime(now, in: zone)
        let stamp = utcStamp(now)
        var seen: [[UInt8]: Int] = [:]
        var out: [String] = []
        out += ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//VaultTasks//Task export//EN", "CALSCALE:GREGORIAN",
                "X-WR-CALNAME:VaultTasks"]
        for t in coming(tasks, today: local.date) {
            guard let date = t.dueDate else { continue }
            let key = AlarmPlanner.leadKey(path: t.id.path, description: t.description)
            let k = Array(key.utf8)
            let n = seen[k, default: 0]
            seen[k] = n + 1
            let (title, category) = Categories.split(t.description)
            out.append("BEGIN:VEVENT")
            out.append("UID:\(uid(key, n))@vaulttasks")
            out.append("DTSTAMP:\(stamp)")
            out.append("SUMMARY:\(escape(title))")
            if let time = t.dueTime {
                let start = LocalDateTime(date, time)
                out.append("DTSTART:\(compact(start))")
                out.append("DTEND:\(compact(start.plusMinutes(timedMinutes)))")
            } else {
                out.append("DTSTART;VALUE=DATE:\(compact(date))")
                out.append("DTEND;VALUE=DATE:\(compact(date.plusDays(1)))")
            }
            if !t.notes.ktIsBlank { out.append("DESCRIPTION:\(escape(t.notes))") }
            if let category { out.append("CATEGORIES:\(escape(category))") }
            if let r = t.recurrence, let rr = Recurrences.rrule(r) { out.append("RRULE:\(rr)") }
            let lead = leadMinutes(t)
            out += ["BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:\(escape(title))"]
            out.append("TRIGGER:" + (t.dueTime != nil ? trigger(-lead) : trigger(defaultTime.minuteOfDay - lead)))
            out += ["END:VALARM", "END:VEVENT"]
        }
        out.append("END:VCALENDAR")
        return out.map { fold($0) + "\r\n" }.joined()
    }

    /// Offset from the event start, in iCalendar duration syntax.
    private static func trigger(_ minutes: Int) -> String {
        minutes == 0 ? "PT0S" : (minutes < 0 ? "-" : "") + "PT\(abs(minutes))M"
    }

    private static func uid(_ key: String, _ n: Int) -> String {
        hexString(sha256Bytes(Data("\(key)\u{0}\(n)".utf8)).prefix(16))
    }

    private static func escape(_ s: String) -> String {
        var r = replacingAll(s, "\\", with: "\\\\")
        r = replacingAll(r, ";", with: "\\;")
        r = replacingAll(r, ",", with: "\\,")
        r = replacingAll(r, "\r\n", with: "\\n")
        return replacingAll(r, "\n", with: "\\n")
    }

    private static func compact(_ d: LocalDate) -> String { String(d.description.filter { $0 != "-" }) }
    private static func compact(_ dt: LocalDateTime) -> String {
        compact(dt.date) + "T" + String(dt.time.description.filter { $0 != ":" }) + "00"
    }

    private static func utcStamp(_ now: Date) -> String {
        let c = LocalDateTime.gregorian(TimeZone(identifier: "UTC")!)
            .dateComponents([.year, .month, .day, .hour, .minute, .second], from: now)
        func p(_ n: Int?, _ w: Int) -> String { let s = String(n ?? 0); return String(repeating: "0", count: max(0, w - s.count)) + s }
        return "\(p(c.year, 4))\(p(c.month, 2))\(p(c.day, 2))T\(p(c.hour, 2))\(p(c.minute, 2))\(p(c.second, 2))Z"
    }

    /// RFC 5545 §3.1: lines are at most 75 octets; continuation lines start with one space. Never splits a character.
    static func fold(_ line: String) -> String {
        var out = String.UnicodeScalarView()
        var bytes = 0
        var limit = 75
        for u in line.unicodeScalars {
            let len = String(u).utf8.count
            if bytes + len > limit {
                out.append("\r"); out.append("\n"); out.append(" ")
                bytes = 0
                limit = 74 // the leading space counts
            }
            out.append(u)
            bytes += len
        }
        return String(out)
    }
}
