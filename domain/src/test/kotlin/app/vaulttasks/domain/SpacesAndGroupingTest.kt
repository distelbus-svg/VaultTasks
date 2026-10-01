package app.vaulttasks.domain

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SpacesDataTest {
    private val empty = SpacesData()

    @Test fun `add makes the new space active and ignores blank names and duplicate ids`() {
        val a = empty.add("1", "  Work ")
        assertEquals(listOf("Work"), a.spaces.map { it.name })
        assertEquals("1", a.activeId)
        assertSame(a, a.add("2", "   "))
        assertSame(a, a.add("1", "Again"))
    }

    @Test fun `active falls back to the first space when the id dangles`() {
        val d = empty.add("1", "A").add("2", "B").copy(activeId = "gone")
        assertEquals("1", d.active?.id)
        assertNull(empty.active)
    }

    @Test fun `delete moves active to the first remaining space`() {
        val d = empty.add("1", "A").add("2", "B")
        assertEquals("2", d.activeId)
        val after = d.delete("2")
        assertEquals("1", after.activeId)
        assertNull(after.delete("1").activeId)
        assertSame(after, after.delete("nope"))
    }

    @Test fun `delete of an inactive space keeps the active one`() {
        val d = empty.add("1", "A").add("2", "B").setActive("1")
        assertEquals("1", d.delete("2").activeId)
    }

    @Test fun `move is clamped and reorders`() {
        val d = empty.add("1", "A").add("2", "B").add("3", "C")
        assertEquals(listOf("1", "3", "2"), d.move("3", -1).spaces.map { it.id })
        assertEquals(listOf("3", "1", "2"), d.move("3", -10).spaces.map { it.id })
        assertSame(d, d.move("1", -1))
        assertSame(d, d.move("3", 1))
    }

    @Test fun `setFiles dedupes and drops a default that left`() {
        val d = empty.add("1", "A").setFiles("1", listOf("a.md", "b.md", "a.md")).setDefault("1", "b.md")
        assertEquals(listOf("a.md", "b.md"), d.spaces[0].files)
        assertEquals("b.md", d.spaces[0].defaultFile)
        assertNull(d.setFiles("1", listOf("a.md")).spaces[0].defaultFile)
    }

    @Test fun `setDefault only accepts a file of the space`() {
        val d = empty.add("1", "A").setFiles("1", listOf("a.md"))
        assertNull(d.setDefault("1", "zzz.md").spaces[0].defaultFile)
        assertEquals("a.md", d.setDefault("1", "a.md").spaces[0].defaultFile)
        assertNull(d.setDefault("1", "a.md").setDefault("1", null).spaces[0].defaultFile)
    }

    @Test fun `creationFile is default else first else null`() {
        val s = Space("1", "A", listOf("a.md", "b.md"), defaultFile = "b.md")
        assertEquals("b.md", s.creationFile)
        assertEquals("a.md", s.copy(defaultFile = null).creationFile)
        assertEquals("a.md", s.copy(defaultFile = "gone.md").creationFile)
        assertNull(Space("1", "A").creationFile)
    }

    @Test fun `assignedFiles is the union across spaces`() {
        val d = empty.add("1", "A").add("2", "B")
            .setFiles("1", listOf("a.md", "shared.md")).setFiles("2", listOf("shared.md", "b.md"))
        assertEquals(setOf("a.md", "shared.md", "b.md"), d.assignedFiles)
    }

    @Test fun `rename and unknown ids`() {
        val d = empty.add("1", "A")
        assertEquals("B", d.rename("1", " B ").spaces[0].name)
        assertSame(d, d.rename("1", " "))
        assertSame(d, d.rename("nope", "X"))
        assertSame(d, d.setActive("nope"))
    }
}

class TaskGroupingTest {
    private val now = LocalDateTime.of(2026, 9, 30, 12, 0)

    private fun tasks(path: String, vararg lines: String) =
        TaskParser.parseFile(path, VaultText.parse(lines.joinToString("\n") + "\n"), "")

    private fun texts(ts: List<Task>) = ts.map { it.description }

    @Test fun `groups by due date relative to today and hides cancelled`() {
        val ts = tasks(
            "a.md",
            "- [ ] late 📅 2026-09-29",
            "- [ ] now 📅 2026-09-30",
            "- [ ] soon 📅 2026-10-01",
            "- [ ] whenever",
            "- [x] finished 📅 2026-09-01",
            "- [-] dropped 📅 2026-09-29",
        )
        val g = TaskGrouping.group(ts, now)
        assertEquals(listOf("late"), texts(g.overdue))
        assertEquals(listOf("now"), texts(g.today))
        assertEquals(listOf("soon"), texts(g.upcoming))
        assertEquals(listOf("whenever"), texts(g.noDate))
        assertEquals(listOf("finished"), texts(g.done))
    }

    @Test fun `done tasks never count as overdue`() {
        val g = TaskGrouping.group(tasks("a.md", "- [x] old 📅 2020-01-01"), now)
        assertEquals(emptyList(), g.overdue)
        assertEquals(1, g.done.size)
    }

    @Test fun `dated groups sort by date then time with untimed last`() {
        val ts = tasks(
            "a.md",
            "- [ ] untimed 📅 2026-10-01",
            "- [ ] late ⏰ 18:00 📅 2026-10-01",
            "- [ ] early ⏰ 08:00 📅 2026-10-01",
            "- [ ] also untimed 📅 2026-10-01",
            "- [ ] next day 📅 2026-10-02",
        )
        val g = TaskGrouping.group(ts, now)
        assertEquals(listOf("early", "late", "untimed", "also untimed", "next day"), texts(g.upcoming))
    }

    @Test fun `ties follow the space file order then line order`() {
        val a = tasks("a.md", "- [ ] a1", "- [ ] a2")
        val b = tasks("b.md", "- [ ] b1")
        val g = TaskGrouping.group(a + b, now, fileOrder = listOf("b.md", "a.md"))
        assertEquals(listOf("b1", "a1", "a2"), texts(g.noDate))
    }

    @Test fun `empty input gives empty groups`() {
        assertEquals(true, TaskGrouping.group(emptyList(), now).isEmpty)
    }

    @Test fun `a timed task becomes overdue once its minute has passed, an untimed one stays in today`() {
        val ts = tasks(
            "a.md",
            "- [ ] earlier today ⏰ 11:59 📅 2026-09-30",
            "- [ ] due this minute ⏰ 12:00 📅 2026-09-30",
            "- [ ] later today ⏰ 12:01 📅 2026-09-30",
            "- [ ] untimed today 📅 2026-09-30",
        )
        val g = TaskGrouping.group(ts, now.plusSeconds(30))
        assertEquals(listOf("earlier today"), texts(g.overdue))
        assertEquals(listOf("due this minute", "later today", "untimed today"), texts(g.today))
        assertEquals(listOf("earlier today", "due this minute"), texts(TaskGrouping.group(ts, now.plusMinutes(1)).overdue))
    }
}
