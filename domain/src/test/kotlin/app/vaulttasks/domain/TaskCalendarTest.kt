package app.vaulttasks.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskCalendarTest {
    private fun tasks(path: String, vararg lines: String) =
        TaskParser.parseFile(path, VaultText.parse(lines.joinToString("\n") + "\n"), "")

    private fun texts(ts: List<Task>?) = ts.orEmpty().map { it.description }

    @Test fun `grid is whole weeks starting on the chosen weekday`() {
        val sep = YearMonth.of(2026, 9) // 1 Sep 2026 is a Tuesday
        val mon = TaskCalendar.gridDays(sep, DayOfWeek.MONDAY)
        assertEquals(35, mon.size)
        assertEquals(LocalDate.of(2026, 8, 31), mon.first())
        assertEquals(LocalDate.of(2026, 10, 4), mon.last())
        val sun = TaskCalendar.gridDays(sep, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 8, 30), sun.first())
        assertEquals(DayOfWeek.SUNDAY, sun.first().dayOfWeek)
    }

    @Test fun `a month that starts on the first weekday needs no leading days and can fit four rows`() {
        val feb = TaskCalendar.gridDays(YearMonth.of(2027, 2), DayOfWeek.MONDAY) // 1 Feb 2027 is a Monday
        assertEquals(28, feb.size)
        assertEquals(LocalDate.of(2027, 2, 1), feb.first())
    }

    @Test fun `a long month starting late in the week needs six rows`() {
        val aug = TaskCalendar.gridDays(YearMonth.of(2026, 8), DayOfWeek.MONDAY) // 1 Aug 2026 is a Saturday
        assertEquals(42, aug.size)
        assertEquals(LocalDate.of(2026, 7, 27), aug.first())
        assertEquals(LocalDate.of(2026, 9, 6), aug.last())
    }

    @Test fun `byDay drops undated and cancelled, keeps done`() {
        val ts = tasks(
            "a.md",
            "- [ ] open 📅 2026-10-01",
            "- [x] finished 📅 2026-10-01",
            "- [-] dropped 📅 2026-10-01",
            "- [ ] whenever",
        )
        val m = TaskCalendar.byDay(ts)
        assertEquals(setOf(LocalDate.of(2026, 10, 1)), m.keys)
        assertEquals(listOf("open", "finished"), texts(m[LocalDate.of(2026, 10, 1)]))
    }

    @Test fun `within a day open comes first, then time with untimed last, then file order`() {
        val a = tasks(
            "a.md",
            "- [x] done early ⏰ 07:00 📅 2026-10-01",
            "- [ ] untimed a 📅 2026-10-01",
            "- [ ] late ⏰ 18:00 📅 2026-10-01",
        )
        val b = tasks("b.md", "- [ ] untimed b 📅 2026-10-01", "- [ ] early ⏰ 08:00 📅 2026-10-01")
        val day = TaskCalendar.byDay(a + b, fileOrder = listOf("b.md", "a.md"))[LocalDate.of(2026, 10, 1)]
        assertEquals(listOf("early", "late", "untimed b", "untimed a", "done early"), texts(day))
    }
}
