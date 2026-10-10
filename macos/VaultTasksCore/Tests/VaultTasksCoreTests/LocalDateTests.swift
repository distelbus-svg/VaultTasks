import Testing
import Foundation
@testable import VaultTasksCore

@Suite struct LocalDateTests {
    private func d(_ s: String) -> LocalDate { LocalDate.parse(s)! }

    @Test func parseIsStrictIso() {
        #expect(LocalDate.parse("2026-10-06")?.description == "2026-10-06")
        #expect(LocalDate.parse("0999-01-02")?.description == "0999-01-02")
        for bad in ["2026-13-45", "2026-02-30", "2025-02-29", "2026-1-06", "26-10-06", "2026/10/06", "2026-10-06 ", "\u{0662}026-10-06", ""] {
            #expect(LocalDate.parse(bad) == nil, "\(bad)")
        }
        #expect(LocalDate.parse("2028-02-29") != nil)
        #expect(LocalDate.parse("1900-02-29") == nil)
        #expect(LocalDate.parse("2000-02-29") != nil)
    }

    @Test func dayOfWeekAndEpoch() {
        #expect(d("1970-01-01").epochDay == 0)
        #expect(d("1970-01-01").dayOfWeek == .thursday)
        #expect(d("2026-10-06").dayOfWeek == .tuesday)
        #expect(d("2026-10-04").dayOfWeek == .sunday)
        #expect(d("2000-02-29").dayOfWeek == .tuesday)
        #expect(d("1969-12-31").epochDay == -1)
    }

    @Test func epochRoundTripOverALongRange() {
        for e in stride(from: -800_000, through: 1_200_000, by: 37) {
            let x = LocalDate(epochDay: e)
            #expect(x.epochDay == e)
            #expect(LocalDate(year: x.year, month: x.month, day: x.day) == x)
        }
    }

    @Test func plusClampsLikeJavaTime() {
        #expect(d("2026-01-31").plusMonths(1).description == "2026-02-28")
        #expect(d("2028-01-31").plusMonths(1).description == "2028-02-29")
        #expect(d("2026-03-31").plusMonths(-1).description == "2026-02-28")
        #expect(d("2026-10-31").plusMonths(4).description == "2027-02-28")
        #expect(d("2026-12-15").plusMonths(1).description == "2027-01-15")
        #expect(d("2026-01-15").plusMonths(-2).description == "2025-11-15")
        #expect(d("2026-01-15").plusMonths(-14).description == "2024-11-15")
        #expect(d("2028-02-29").plusYears(1).description == "2029-02-28")
        #expect(d("2028-02-29").plusYears(4).description == "2032-02-29")
        #expect(d("2026-10-04").plusWeeks(2).description == "2026-10-18")
        #expect(d("2026-12-31").plusDays(1).description == "2027-01-01")
        #expect(d("2026-10-04").daysUntil(d("2026-10-11")) == 7)
        #expect(d("2026-10-11").daysUntil(d("2026-10-04")) == -7)
    }

    @Test func orderingAndCodable() throws {
        #expect(d("2026-10-05") < d("2026-10-06"))
        #expect(d("2025-12-31") < d("2026-01-01"))
        let enc = try JSONEncoder().encode(["a": d("2026-10-06")])
        #expect(String(decoding: enc, as: UTF8.self) == "{\"a\":\"2026-10-06\"}")
        #expect(try JSONDecoder().decode([String: LocalDate].self, from: enc)["a"] == d("2026-10-06"))
        #expect(throws: DecodingError.self) { try JSONDecoder().decode([String: LocalDate].self, from: Data("{\"a\":\"2026-02-30\"}".utf8)) }
    }

    @Test func localTime() throws {
        #expect(LocalTime.parse("09:05")?.description == "09:05")
        for bad in ["9:05", "24:00", "12:60", "12:5", "12-05", "1205", "12:05:00"] { #expect(LocalTime.parse(bad) == nil, "\(bad)") }
        #expect(LocalTime(hour: 8, minute: 0)! < LocalTime(hour: 8, minute: 1)!)
        #expect(LocalTime(hour: 18, minute: 30)!.minuteOfDay == 1110)
        let enc = try JSONEncoder().encode(["t": LocalTime(hour: 7, minute: 30)!])
        #expect(try JSONDecoder().decode([String: LocalTime].self, from: enc)["t"] == LocalTime(hour: 7, minute: 30))
    }
}
