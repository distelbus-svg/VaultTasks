package app.vaulttasks.domain.alarms

import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskId
import app.vaulttasks.domain.TaskState
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Spec §8.1/§8.4. TASK = the one alarm derived from a task; SNOOZE = an in-app one-shot that never touches the file. */
enum class AlarmKind { TASK, SNOOZE }

/**
 * One scheduled alarm. Carries everything needed to post the notification so the receiver needs no lookup.
 * [fireAt] is a local date-time; the platform layer converts it with the device's current zone (spec §10).
 */
data class AlarmSpec(
    val code: Int,
    val kind: AlarmKind,
    val id: TaskId,
    val fireAt: LocalDateTime,
    val title: String,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val spaceName: String,
)

data class ReminderSettings(
    val defaultTime: LocalTime = LocalTime.of(9, 0),
    val leadMinutes: Int = 0,
)

enum class SnoozeOption(val minutes: Long) { TEN_MINUTES(10), ONE_HOUR(60) }

object AlarmPlanner {
    /** Spec §8.1: open tasks with a due date; date-only tasks use the default time; the lead time is subtracted. */
    fun fireTime(task: Task, settings: ReminderSettings): LocalDateTime? {
        if (task.state != TaskState.OPEN) return null
        val date = task.dueDate ?: return null
        return LocalDateTime.of(date, task.dueTime ?: settings.defaultTime).minusMinutes(settings.leadMinutes.toLong())
    }

    /**
     * The desired TASK alarm set. Past times are excluded (overdue is shown in-app, never re-fired).
     * [tasks] pairs each task with the name of the space it is shown under. [reserved] are codes already used by
     * snoozes; a hash collision is resolved by probing upward in sorted-key order, so the result is deterministic.
     */
    fun plan(
        tasks: List<Pair<Task, String>>,
        settings: ReminderSettings,
        now: LocalDateTime,
        reserved: Set<Int> = emptySet(),
    ): List<AlarmSpec> {
        val due = tasks.mapNotNull { (t, space) ->
            val at = fireTime(t, settings) ?: return@mapNotNull null
            if (!at.isAfter(now)) null else Triple(t, space, at)
        }.sortedBy { keyOf(it.first.id) }
        val used = HashSet(reserved)
        return due.map { (t, space, at) ->
            var code = hashCode(keyOf(t.id))
            while (!used.add(code)) code++
            AlarmSpec(code, AlarmKind.TASK, t.id, at, t.description, t.dueDate, t.dueTime, space)
        }
    }

    fun keyOf(id: TaskId): String = "${id.path}\u0000${id.normalizedText}\u0000${id.occurrence}"

    /** Stable across runs and processes (unlike identity hash codes): first 4 bytes of SHA-256. */
    fun hashCode(key: String): Int {
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return ((d[0].toInt() and 0xFF) shl 24) or ((d[1].toInt() and 0xFF) shl 16) or ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
    }
}

/** Platform seam: AlarmManager on Android, UNUserNotificationCenter on macOS. Both calls must be idempotent per code. */
interface AlarmBackend {
    /** Schedules [spec], replacing any alarm with the same code. May throw; the syncer then retries on the next sync. */
    fun set(spec: AlarmSpec)

    fun cancel(code: Int)
}

/** Persisted copy of what was last scheduled (the OS forgets alarms on reboot/force-stop, we must not). */
interface AlarmStore {
    fun load(): List<AlarmSpec>

    fun save(specs: List<AlarmSpec>)
}

data class SyncReport(val scheduled: Int, val cancelled: Int, val unchanged: Int, val failed: Int)

/**
 * Spec §8.2: the only code that touches the backend. [sync] diffs the desired set against the persisted one,
 * so calling it any number of times never duplicates an alarm. Not thread-safe: callers serialize.
 */
class AlarmSyncer(private val backend: AlarmBackend, private val store: AlarmStore) {

    /**
     * @param desired TASK alarms from [AlarmPlanner.plan]
     * @param openIds every open task currently known; snoozes for anything else are dropped
     * @param preservePaths files that could not be read this scan: their alarms are kept, not cancelled
     * @param force re-set every alarm even if unchanged (boot, time/zone change, app start)
     */
    fun sync(
        desired: List<AlarmSpec>,
        openIds: Set<TaskId>,
        preservePaths: Set<String>,
        now: LocalDateTime,
        force: Boolean,
    ): SyncReport {
        val prev = store.load().associateBy { it.code }
        val wanted = desired.associateBy { it.code }

        val carried = ArrayList<AlarmSpec>()
        val stale = ArrayList<Int>()
        for (p in prev.values) {
            if (p.kind == AlarmKind.TASK && p.code in wanted) continue
            val alive = p.fireAt.isAfter(now) && when (p.kind) {
                AlarmKind.TASK -> p.id.path in preservePaths
                AlarmKind.SNOOZE -> p.id in openIds || p.id.path in preservePaths
            }
            if (alive) carried += p else stale += p.code
        }

        var cancelled = 0
        for (code in stale) {
            runCatching { backend.cancel(code) }
            cancelled++
        }

        val kept = ArrayList<AlarmSpec>()
        var scheduled = 0
        var unchanged = 0
        var failed = 0
        for (spec in desired) {
            if (!force && prev[spec.code] == spec) {
                unchanged++
                kept += spec
                continue
            }
            if (trySet(spec)) {
                scheduled++
                kept += spec
            } else {
                failed++ // not stored, so the next sync retries it
            }
        }
        for (c in carried) {
            if (force && !trySet(c)) {
                failed++
                continue
            }
            kept += c
        }
        store.save(kept)
        return SyncReport(scheduled, cancelled, unchanged, failed)
    }

    /** Spec §8.4 Snooze: a one-shot alarm for [base]'s task; replaces an earlier snooze of the same task. Returns the new alarm. */
    fun snooze(base: AlarmSpec, fireAt: LocalDateTime): AlarmSpec? {
        val all = store.load()
        val old = all.filter { it.kind == AlarmKind.SNOOZE && it.id == base.id }
        val rest = all - old.toSet()
        val used = rest.mapTo(HashSet()) { it.code }
        var code = AlarmPlanner.hashCode("snooze\u0000" + AlarmPlanner.keyOf(base.id))
        while (code in used) code++
        val spec = base.copy(code = code, kind = AlarmKind.SNOOZE, fireAt = fireAt)
        old.filter { it.code != code }.forEach { runCatching { backend.cancel(it.code) } }
        if (!trySet(spec)) return null
        store.save(rest + spec)
        return spec
    }

    /** Called when an alarm has fired so it is not re-set by a later forced sync. */
    fun markFired(code: Int) {
        val all = store.load()
        if (all.any { it.code == code }) store.save(all.filter { it.code != code })
    }

    /** Codes of snoozes, so [AlarmPlanner.plan] can avoid them. */
    fun snoozeCodes(): Set<Int> = store.load().filter { it.kind == AlarmKind.SNOOZE }.mapTo(HashSet()) { it.code }

    fun scheduled(): List<AlarmSpec> = store.load()

    private fun trySet(spec: AlarmSpec): Boolean = try {
        backend.set(spec)
        true
    } catch (e: Exception) {
        false
    }
}
