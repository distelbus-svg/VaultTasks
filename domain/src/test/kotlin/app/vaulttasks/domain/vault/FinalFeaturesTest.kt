package app.vaulttasks.domain.vault

import app.vaulttasks.domain.Categories
import app.vaulttasks.domain.IcsExport
import app.vaulttasks.domain.RecurrenceEdit
import app.vaulttasks.domain.Recurrences
import app.vaulttasks.domain.TaskFields
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FinalFeaturesTest {
    private val fs = FakeVaultFileSystem()
    private val scanner = FileScanner(fs)
    private val writer = VaultWriter(fs)
    private val today = LocalDate.of(2026, 10, 4)
    private fun scan() = scanner.scan(emptyMap()).files.getValue("a.md")

    // ---- notes ----------------------------------------------------------------------------------------------

    @Test fun `notes are parsed from nested bullets and stop at the next task`() {
        fs.put("a.md", "- [ ] one\n\t- first\n\t- **second**\n\t- [ ] sub\n- [ ] two\n")
        val t = scan().tasks
        assertEquals("first\n**second**", t[0].notes)
        assertEquals("", t[1].notes) // the sub-task
        assertEquals("", t[2].notes)
    }

    @Test fun `update rewrites notes and leaves other lines alone`() {
        fs.put("a.md", "# h\n- [ ] one 📅 2026-10-05\n\t- old\n- [ ] two\n")
        val t = scan().tasks[0]
        assertIs<WriteResult.Ok>(writer.update(t, TaskFields("one", LocalDate.of(2026, 10, 5), null, notes = "line a\n\n[ ] looks like a task")))
        assertEquals("# h\n- [ ] one 📅 2026-10-05\n\t- line a\n\t- \\[ ] looks like a task\n- [ ] two\n", fs.text("a.md"))
        val back = scan().tasks
        assertEquals(2, back.size) // the escaped note did not become a task
        assertEquals("line a\n[ ] looks like a task", back[0].notes)
    }

    @Test fun `null notes keep existing notes (move to tomorrow)`() {
        fs.put("a.md", "- [ ] one 📅 2026-10-05\n\t- keep me\n")
        val t = scan().tasks[0]
        assertIs<WriteResult.Ok>(writer.update(t, TaskFields("one", LocalDate.of(2026, 10, 6), null)))
        assertEquals("- [ ] one 📅 2026-10-06\n\t- keep me\n", fs.text("a.md"))
    }

    @Test fun `delete takes the notes along and restore brings them back`() {
        val src = "- [ ] a\n- [ ] b\n\t- n1\n\t- n2\n- [ ] c\n"
        fs.put("a.md", src)
        val r = writer.delete(scan().tasks[1])
        assertIs<WriteResult.Ok>(r)
        assertEquals("- [ ] a\n- [ ] c\n", fs.text("a.md"))
        assertIs<WriteResult.Ok>(writer.restore(r.removed!!))
        assertEquals(src, fs.text("a.md"))
    }

    @Test fun `create writes notes and a recurrence`() {
        fs.put("a.md", "- [ ] one\n")
        writer.create("a.md", TaskFields("new", LocalDate.of(2026, 10, 9), null, "n1\nn2", RecurrenceEdit.Set("every week")))
        assertEquals("- [ ] one\n- [ ] new 🔁 every week 📅 2026-10-09\n\t- n1\n\t- n2\n", fs.text("a.md"))
        assertEquals("every week", scan().tasks[1].recurrence)
    }

    // ---- recurrence -----------------------------------------------------------------------------------------

    @Test fun `completing a recurring task adds the next occurrence below with copied notes`() {
        fs.put("a.md", "- [ ] water plants ⏰ 08:00 🔁 every 2 days 📅 2026-10-04\n\t- the ficus\n- [ ] other\n")
        val t = scan().tasks[0]
        assertIs<WriteResult.Ok>(writer.setDone(t, true, today))
        assertEquals(
            "- [x] water plants ⏰ 08:00 🔁 every 2 days 📅 2026-10-04 ✅ 2026-10-04\n\t- the ficus\n" +
                "- [ ] water plants ⏰ 08:00 🔁 every 2 days 📅 2026-10-06\n\t- the ficus\n- [ ] other\n",
            fs.text("a.md"),
        )
    }

    @Test fun `unsupported rules are refused without touching the file`() {
        val src = "- [ ] x 🔁 every 3rd friday 📅 2026-10-04\n"
        fs.put("a.md", src)
        assertIs<WriteResult.Failed>(writer.setDone(scan().tasks[0], true, today))
        assertEquals(src, fs.text("a.md"))
    }

    @Test fun `next dates`() {
        fun next(rule: String, due: String, done: String = due) =
            Recurrences.next(Recurrences.parse(rule)!!, LocalDate.parse(due), LocalDate.parse(done)).toString()
        assertEquals("2026-10-05", next("every day", "2026-10-04"))
        assertEquals("2026-10-18", next("every 2 weeks", "2026-10-04"))
        assertEquals("2026-11-04", next("every month", "2026-10-04"))
        assertEquals("2027-10-04", next("every year", "2026-10-04"))
        assertEquals("2026-10-05", next("every weekday", "2026-10-02")) // Friday → Monday
        assertEquals("2026-10-09", next("every week on monday, friday", "2026-10-05"))
        assertEquals("2026-10-17", next("every week when done", "2026-10-01", "2026-10-10"))
        assertNull(Recurrences.parse("every 3rd friday"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2", Recurrences.rrule("every 2 weeks"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,FR", Recurrences.rrule("every monday, friday"))
    }

    // ---- categories -----------------------------------------------------------------------------------------

    @Test fun `categories are trailing tags only`() {
        assertEquals("Buy milk" to "home", Categories.split("Buy milk #home"))
        assertEquals("Buy #milk today" to null, Categories.split("Buy #milk today"))
        assertEquals("#home" to null, Categories.split("#home"))
        assertEquals("Call #1" to null, Categories.split("Call #1"))
        assertEquals("Buy milk #home", Categories.join("Buy milk", "home"))
        assertEquals("work-stuff", Categories.clean("  #Work stuff ")?.lowercase())
    }

    // ---- calendar export ------------------------------------------------------------------------------------

    @Test fun `ics contains only coming open tasks`() {
        fs.put(
            "a.md",
            "- [ ] overdue 📅 2026-10-01\n- [x] done 📅 2026-10-10 ✅ 2026-10-04\n- [ ] undated\n" +
                "- [ ] today, ok #home ⏰ 18:30 📅 2026-10-04\n\t- bring it\n- [ ] weekly 🔁 every week 📅 2026-10-09\n",
        )
        val ics = IcsExport.build(scan().tasks, LocalDateTime.of(2026, 10, 4, 12, 0), LocalTime.of(9, 0)) { 60 }
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertFalse(ics.contains("overdue") || ics.contains("SUMMARY:done") || ics.contains("undated"))
        assertTrue(ics.contains("SUMMARY:today\\, ok\r\n"))
        assertTrue(ics.contains("DTSTART:20261004T183000\r\n"))
        assertTrue(ics.contains("DESCRIPTION:bring it\r\n"))
        assertTrue(ics.contains("CATEGORIES:home\r\n"))
        assertTrue(ics.contains("TRIGGER:-PT60M\r\n"))
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY\r\n"))
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261009\r\n"))
        assertTrue(ics.contains("TRIGGER:PT480M\r\n")) // all-day: default 09:00 minus 60 min
        assertTrue(ics.lines().all { it.toByteArray().size <= 76 }) // 75 + the \r
    }
}
