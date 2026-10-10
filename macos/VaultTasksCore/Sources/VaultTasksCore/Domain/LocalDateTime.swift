import Foundation

/// A wall-clock moment without time zone, minute precision (java.time.LocalDateTime as the domain uses it).
/// Conversion to an instant happens only at scheduling/ICS time (§3.1 rule 4). Always proleptic Gregorian,
/// whatever calendar the user's system is set to.
public struct LocalDateTime: Hashable, Comparable, Codable, Sendable, CustomStringConvertible {
    public let date: LocalDate
    public let time: LocalTime

    public init(_ date: LocalDate, _ time: LocalTime) { self.date = date; self.time = time }

    /// Minutes since 1970-01-01T00:00 (floor semantics for negatives).
    public var epochMinute: Int { date.epochDay * 1440 + time.minuteOfDay }

    public init(epochMinute m: Int) {
        let days = m >= 0 ? m / 1440 : -((-m + 1439) / 1440)
        let rem = m - days * 1440
        self.date = LocalDate(epochDay: days)
        self.time = LocalTime(hour: rem / 60, minute: rem % 60)!
    }

    public func plusMinutes(_ n: Int) -> LocalDateTime { LocalDateTime(epochMinute: epochMinute + n) }
    public func minusMinutes(_ n: Int) -> LocalDateTime { plusMinutes(-n) }

    public static func < (a: LocalDateTime, b: LocalDateTime) -> Bool { a.epochMinute < b.epochMinute }

    /// `yyyy-MM-ddTHH:mm`
    public var description: String { "\(date)T\(time)" }

    public static func parse(_ s: String) -> LocalDateTime? {
        let b = Array(s.utf8)
        guard b.count == 16, b[10] == 0x54 else { return nil }
        guard let d = LocalDate.parse(String(decoding: b[0..<10], as: UTF8.self)),
              let t = LocalTime.parse(String(decoding: b[11..<16], as: UTF8.self)) else { return nil }
        return LocalDateTime(d, t)
    }

    public init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let v = LocalDateTime.parse(s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad date-time \(s)"))
        }
        self = v
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer(); try c.encode(description)
    }

    // ---- Foundation bridge ---------------------------------------------------------------------------------

    public static func gregorian(_ zone: TimeZone = .current) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        return c
    }

    /// The wall-clock reading of `instant` in `zone` (seconds are dropped).
    public init(_ instant: Date, in zone: TimeZone = .current) {
        let c = Self.gregorian(zone).dateComponents([.year, .month, .day, .hour, .minute], from: instant)
        self.date = LocalDate(year: c.year!, month: c.month!, day: c.day!)!
        self.time = LocalTime(hour: c.hour!, minute: c.minute!)!
    }

    /// The instant this wall-clock reading denotes in `zone` (nil only for readings Foundation cannot resolve).
    public func instant(in zone: TimeZone = .current) -> Date? {
        Self.gregorian(zone).date(from: dateComponents(zone: zone))
    }

    /// Components for a calendar notification trigger. With `zone == nil` no time zone is attached, so the system's
    /// current zone applies when the trigger fires and a zone change needs no reschedule.
    public func dateComponents(zone: TimeZone? = nil) -> DateComponents {
        DateComponents(calendar: Self.gregorian(zone ?? .current), timeZone: zone, year: date.year, month: date.month,
                       day: date.day, hour: time.hour, minute: time.minute)
    }
}

extension LocalDate {
    public static func today(in zone: TimeZone = .current, now: Date = Date()) -> LocalDate {
        LocalDateTime(now, in: zone).date
    }
}

/// java.time.YearMonth.
public struct YearMonth: Hashable, Comparable, Sendable {
    public let year: Int
    public let month: Int

    public init?(year: Int, month: Int) {
        guard (1...12).contains(month) else { return nil }
        self.year = year; self.month = month
    }
    public init(_ d: LocalDate) { self.year = d.year; self.month = d.month }

    public var lengthOfMonth: Int { LocalDate.daysIn(year: year, month: month) }
    public func atDay(_ day: Int) -> LocalDate { LocalDate(year: year, month: month, day: day)! }
    public func plusMonths(_ n: Int) -> YearMonth {
        let idx = year * 12 + (month - 1) + n
        let y = idx >= 0 ? idx / 12 : -((-idx + 11) / 12)
        return YearMonth(year: y, month: idx - y * 12 + 1)!
    }
    public static func < (a: YearMonth, b: YearMonth) -> Bool { (a.year, a.month) < (b.year, b.month) }
}
