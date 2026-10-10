import Foundation

/// java.time.DayOfWeek (Monday = 1 … Sunday = 7). English names are fixed on purpose (§3.1 rule 7).
public enum DayOfWeek: Int, CaseIterable, Comparable, Sendable {
    case monday = 1, tuesday, wednesday, thursday, friday, saturday, sunday
    public var value: Int { rawValue }
    public static func < (a: DayOfWeek, b: DayOfWeek) -> Bool { a.rawValue < b.rawValue }
    /// "monday"…"sunday" (lowercase English).
    public var fullName: String {
        ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"][rawValue - 1]
    }
    /// "Mon"…"Sun" (TextStyle.SHORT, Locale.ENGLISH).
    public var shortName: String { ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][rawValue - 1] }
}

/// A calendar date without time zone (java.time.LocalDate). Due values are never `Date`.
public struct LocalDate: Hashable, Comparable, Codable, Sendable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    public static func isLeap(_ y: Int) -> Bool { (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 }
    public static func daysIn(year y: Int, month m: Int) -> Int {
        switch m {
        case 2: return isLeap(y) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    public init?(year: Int, month: Int, day: Int) {
        guard (1...12).contains(month), day >= 1, day <= Self.daysIn(year: year, month: month) else { return nil }
        self.year = year; self.month = month; self.day = day
    }

    /// Days since 1970-01-01 (java `toEpochDay`).
    public init(epochDay z0: Int) {
        let z = z0 + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let y = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        self.year = m <= 2 ? y + 1 : y; self.month = m; self.day = d
    }

    public var epochDay: Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = month > 2 ? month - 3 : month + 9
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /// Strict ISO `yyyy-MM-dd` (ASCII digits, valid calendar date), like `LocalDate.parse`. nil otherwise.
    public static func parse(_ s: String) -> LocalDate? {
        let b = Array(s.utf8)
        guard b.count == 10, b[4] == 0x2D, b[7] == 0x2D else { return nil }
        func num(_ r: Range<Int>) -> Int? {
            var n = 0
            for i in r { guard b[i] >= 0x30, b[i] <= 0x39 else { return nil }; n = n * 10 + Int(b[i] - 0x30) }
            return n
        }
        guard let y = num(0..<4), let m = num(5..<7), let d = num(8..<10) else { return nil }
        return LocalDate(year: y, month: m, day: d)
    }

    public var dayOfWeek: DayOfWeek {
        let m = ((epochDay + 3) % 7 + 7) % 7
        return DayOfWeek(rawValue: m + 1)!
    }

    public func plusDays(_ n: Int) -> LocalDate { LocalDate(epochDay: epochDay + n) }
    public func plusWeeks(_ n: Int) -> LocalDate { plusDays(7 * n) }

    /// Clamps the day to the target month's length (Jan 31 + 1 month = Feb 28/29), like java.time.
    public func plusMonths(_ n: Int) -> LocalDate {
        let count = year * 12 + (month - 1) + n
        let ny = count >= 0 ? count / 12 : -((-count + 11) / 12)   // floorDiv
        let nm = count - ny * 12 + 1
        return LocalDate(year: ny, month: nm, day: min(day, Self.daysIn(year: ny, month: nm)))!
    }
    public func plusYears(_ n: Int) -> LocalDate {
        let ny = year + n
        return LocalDate(year: ny, month: month, day: min(day, Self.daysIn(year: ny, month: month)))!
    }

    /// ChronoUnit.DAYS.between(self, other)
    public func daysUntil(_ other: LocalDate) -> Int { other.epochDay - epochDay }

    public static func < (a: LocalDate, b: LocalDate) -> Bool { a.epochDay < b.epochDay }

    public var description: String {
        func p(_ n: Int, _ w: Int) -> String { let s = String(n); return String(repeating: "0", count: max(0, w - s.count)) + s }
        return "\(p(year, 4))-\(p(month, 2))-\(p(day, 2))"
    }

    public init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let d = LocalDate.parse(s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad date \(s)"))
        }
        self = d
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer(); try c.encode(description)
    }
}

/// A wall-clock time of day, minute precision (java.time.LocalTime as the domain uses it).
public struct LocalTime: Hashable, Comparable, Codable, Sendable, CustomStringConvertible {
    public let hour: Int
    public let minute: Int

    public init?(hour: Int, minute: Int) {
        guard (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        self.hour = hour; self.minute = minute
    }

    /// `HH:mm` (ASCII digits), like `LocalTime.parse` for the minute-precision strings the domain uses.
    public static func parse(_ s: String) -> LocalTime? {
        let b = Array(s.utf8)
        guard b.count == 5, b[2] == 0x3A else { return nil }
        func d(_ i: Int) -> Int? { (b[i] >= 0x30 && b[i] <= 0x39) ? Int(b[i] - 0x30) : nil }
        guard let h1 = d(0), let h2 = d(1), let m1 = d(3), let m2 = d(4) else { return nil }
        return LocalTime(hour: h1 * 10 + h2, minute: m1 * 10 + m2)
    }

    public var minuteOfDay: Int { hour * 60 + minute }
    public static func < (a: LocalTime, b: LocalTime) -> Bool { a.minuteOfDay < b.minuteOfDay }

    public var description: String {
        (hour < 10 ? "0" : "") + String(hour) + ":" + (minute < 10 ? "0" : "") + String(minute)
    }

    public init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let t = LocalTime.parse(s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad time \(s)"))
        }
        self = t
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer(); try c.encode(description)
    }
}
