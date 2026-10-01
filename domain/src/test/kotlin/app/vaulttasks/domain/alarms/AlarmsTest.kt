package app.vaulttasks.domain.alarms

import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskId
import app.vaulttasks.domain.TaskState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeBackend : AlarmBackend {
    /** What the "OS" currently holds. */
    val live = HashMap<Int, AlarmSpec>()
    var setCalls = 0
    var failCodes = HashSet<Int>()

    override fun set(spec: AlarmSpec) {
        if (spec.code in failCodes) throw SecurityException("denied")
        setCalls++
        live[spec.code] = spec
    }

    override fun cancel(code: Int) {
        live.remove(code)
    }
}

private class FakeStore : AlarmStore {
    var specs: List<AlarmSpec> = emptyList()
    override fun load() = specs
    override fun save(specs: List<AlarmSpec>) {
        this.specs = specs
    }
}

class AlarmsTest {
    private val now = LocalDateTime.of(2026, 9, 30, 12, 0)
    private val settings = ReminderSettings()
    private val backend = FakeBackend()
    private val store = FakeStore()
    private val syncer = AlarmSyncer(backend, store)

    private fun task(
        text: String,
        date: LocalDate? = LocalDate.of(2026, 10, 1),
        time: LocalTime? = LocalTime.of(19, 0),
        state: TaskState = TaskState.OPEN,
        path: String = "a.md",
        occ: Int = 0,
    ) = Task(
        id = TaskId(path, text, occ), lineIndex = 0, state = state, description = text, dueDate = date, dueTime = time,
        priority = null, isRecurring = false, doneDate = null, rawLine = "- [ ] $text",
    )

    private fun plan(vararg tasks: Task, s: ReminderSettings = settings, reserved: Set<Int> = emptySet()) =
        AlarmPlanner.plan(tasks.map { it to "Space" }, s, now, reserved)

    private fun sync(desired: List<AlarmSpec>, open: Set<TaskId> = emptySet(), keep: Set<String> = emptySet(), force: Boolean = false) =
        syncer.sync(desired, open, keep, now, force)

    // ---- planner ----------------------------------------------------------------------------------------------

    @Test fun `timed task fires at its due time`() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 19, 0), plan(task("x")).single().fireAt)
    }

    @Test fun `date-only task fires at default time and lead is subtracted`() {
        val t = task("x", time = null)
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 0), plan(t).single().fireAt)
        assertEquals(LocalDateTime.of(2026, 10, 1, 8, 30), plan(t, s = ReminderSettings(leadMinutes = 30)).single().fireAt)
    }

    @Test fun `past, undated, done and cancelled tasks are excluded`() {
        val out = plan(
            task("past", date = LocalDate.of(2026, 9, 29)),
            task("now", date = LocalDate.of(2026, 9, 30), time = LocalTime.of(12, 0)), // not strictly after now
            task("lead pushes into past", date = LocalDate.of(2026, 9, 30), time = LocalTime.of(12, 5)),
            task("undated", date = null, time = null),
            task("done", state = TaskState.DONE),
            task("cancelled", state = TaskState.CANCELLED),
        )
        assertEquals(listOf("lead pushes into past"), out.map { it.title })
        assertTrue(plan(task("lead pushes into past", date = LocalDate.of(2026, 9, 30), time = LocalTime.of(12, 5)), s = ReminderSettings(leadMinutes = 10)).isEmpty())
    }

    @Test fun `identical lines get distinct codes and codes are stable`() {
        val a = plan(task("same", occ = 0), task("same", occ = 1))
        assertEquals(2, a.map { it.code }.toSet().size)
        assertEquals(a, plan(task("same", occ = 1), task("same", occ = 0)).sortedBy { it.id.occurrence })
    }

    @Test fun `reserved codes are probed past`() {
        val free = plan(task("x")).single().code
        val probed = plan(task("x"), reserved = setOf(free)).single().code
        assertEquals(free + 1, probed)
    }

    // ---- syncer -----------------------------------------------------------------------------------------------

    @Test fun `syncing twice never duplicates and the second sync does nothing`() {
        val d = plan(task("a"), task("b"))
        assertEquals(2, sync(d).scheduled)
        val second = sync(d)
        assertEquals(0, second.scheduled)
        assertEquals(2, second.unchanged)
        assertEquals(2, backend.live.size)
        assertEquals(2, backend.setCalls)
    }

    @Test fun `changed time replaces in place, removed task is cancelled`() {
        sync(plan(task("a"), task("b")))
        val r = sync(plan(task("a", time = LocalTime.of(20, 0))))
        assertEquals(1, r.scheduled)
        assertEquals(1, r.cancelled)
        assertEquals(listOf(LocalDateTime.of(2026, 10, 1, 20, 0)), backend.live.values.map { it.fireAt })
    }

    @Test fun `editing a task's text cancels the old alarm and sets a new one`() {
        sync(plan(task("old")))
        val oldCode = backend.live.keys.single()
        sync(plan(task("new")))
        assertTrue(oldCode !in backend.live)
        assertEquals("new", backend.live.values.single().title)
    }

    @Test fun `alarms of unreadable files survive a scan that could not see them`() {
        sync(plan(task("a", path = "a.md"), task("b", path = "b.md")))
        val r = sync(plan(task("a", path = "a.md")), keep = setOf("b.md"))
        assertEquals(0, r.cancelled)
        assertEquals(2, backend.live.size)
        assertEquals(2, store.specs.size)
    }

    @Test fun `forced sync after a simulated reboot restores every alarm`() {
        val d = plan(task("a"), task("b"))
        sync(d)
        backend.live.clear() // reboot / force-stop: the OS forgot, our store did not
        assertEquals(0, sync(d).scheduled) // a normal sync trusts the store …
        assertEquals(2, sync(d, force = true).scheduled) // … a forced one does not
        assertEquals(2, backend.live.size)
    }

    @Test fun `a failed set is not stored and is retried next time`() {
        val d = plan(task("a"))
        backend.failCodes += d.single().code
        assertEquals(1, sync(d).failed)
        assertTrue(store.specs.isEmpty())
        backend.failCodes.clear()
        assertEquals(1, sync(d).scheduled)
    }

    @Test fun `fired alarms are dropped from the store and not resurrected`() {
        val d = plan(task("a"))
        sync(d)
        syncer.markFired(d.single().code)
        assertTrue(store.specs.isEmpty())
    }

    // ---- snooze -----------------------------------------------------------------------------------------------

    @Test fun `snooze is a separate alarm that survives syncs while the task is open`() {
        val d = plan(task("a"))
        sync(d)
        val snooze = assertNotNull(syncer.snooze(d.single(), now.plusMinutes(10)))
        assertEquals(AlarmKind.SNOOZE, snooze.kind)
        assertEquals(2, backend.live.size)
        val open = setOf(d.single().id)
        sync(d, open = open)
        sync(d, open = open, force = true)
        assertTrue(snooze.code in backend.live)
    }

    @Test fun `a second snooze replaces the first`() {
        val d = plan(task("a"))
        sync(d)
        syncer.snooze(d.single(), now.plusMinutes(10))
        syncer.snooze(d.single(), now.plusMinutes(60))
        val snoozes = backend.live.values.filter { it.kind == AlarmKind.SNOOZE }
        assertEquals(listOf(now.plusMinutes(60)), snoozes.map { it.fireAt })
    }

    @Test fun `snooze is dropped when its task is no longer open`() {
        val d = plan(task("a"))
        sync(d)
        val snooze = assertNotNull(syncer.snooze(d.single(), now.plusMinutes(10)))
        sync(emptyList(), open = emptySet())
        assertNull(backend.live[snooze.code])
        assertTrue(store.specs.isEmpty())
    }

    @Test fun `snooze does not touch the file-derived plan`() {
        val d = plan(task("a"))
        sync(d)
        syncer.snooze(d.single(), now.plusMinutes(10))
        assertEquals(1, AlarmPlanner.plan(listOf(task("a") to "S"), settings, now, syncer.snoozeCodes()).size)
    }
}
