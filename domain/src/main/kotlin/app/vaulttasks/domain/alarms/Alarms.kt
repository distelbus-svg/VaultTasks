package app.vaulttasks.domain.alarms

import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskId
import app.vaulttasks.domain.TaskState
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * One scheduled alarm. Carries everything needed to post the notification so the receiver needs no lookup.
 * [fireAt] is a local date-time; the platform layer converts it with the device's current zone (spec §10).
 */
data class AlarmSpec(
    val code: Int,
    val id: TaskId,
    val fireAt: LocalDateTime,
    val title: String,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val spaceName: String,
)

data class ReminderSettings(
    val defaultTime: LocalTime = LocalTime.of(9, 0),
)

object AlarmPlanner {
    /** Spec §8.1: open tasks with a due date; date-only tasks use the default time; the task's lead time is subtracted. */
    fun fireTime(task: Task, defaultTime: LocalTime, leadMinutes: Int): LocalDateTime? {
        if (task.state != TaskState.OPEN) return null
        val date = task.dueDate ?: return null
        return LocalDateTime.of(date, task.dueTime ?: defaultTime).minusMinutes(leadMinutes.toLong())
    }

    /**
     * The desired alarm set. Past times are excluded (overdue is shown in-app, never re-fired).
     * [tasks] pairs each task with the name of the space it is shown under; [leadMinutes] gives each task's
     * "remind me before" (0 = at the due time). A hash collision is resolved by probing upward in sorted-key order,
     * so the result is deterministic.
     */
    fun plan(
        tasks: List<Pair<Task, String>>,
        settings: ReminderSettings,
        now: LocalDateTime,
        leadMinutes: (Task) -> Int = { 0 },
    ): List<AlarmSpec> {
        val due = tasks.mapNotNull { (t, space) ->
            val at = fireTime(t, settings.defaultTime, leadMinutes(t)) ?: return@mapNotNull null
            if (!at.isAfter(now)) null else Triple(t, space, at)
        }.sortedBy { keyOf(it.first.id) }
        val used = HashSet<Int>()
        return due.map { (t, space, at) ->
            var code = hashCode(keyOf(t.id))
            while (!used.add(code)) code++
            AlarmSpec(code, t.id, at, t.description, t.dueDate, t.dueTime, space)
        }
    }

    /**
     * Snooze target: [minutes] from [now], rounded UP to the next whole minute because due times have minute
     * precision (so "10 min" never fires early). Crossing midnight rolls the date.
     */
    fun snoozeTarget(now: LocalDateTime, minutes: Long): LocalDateTime {
        val t = now.plusMinutes(minutes)
        val floor = t.truncatedTo(ChronoUnit.MINUTES)
        return if (floor == t) t else floor.plusMinutes(1)
    }

    /**
     * Key of a task's "remind me before" setting. Deliberately NOT the task identity: identity is the whole line, so
     * it changes on every date, time or state edit. Path + description survives those (and edits made in Obsidian).
     */
    fun leadKey(path: String, description: String): String = "$path\u0000$description"

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
     * @param desired alarms from [AlarmPlanner.plan]
     * @param preservePaths files that could not be read this scan: their alarms are kept, not cancelled
     * @param force re-set every alarm even if unchanged (boot, time/zone change, app start)
     */
    fun sync(desired: List<AlarmSpec>, preservePaths: Set<String>, now: LocalDateTime, force: Boolean): SyncReport {
        val prev = store.load().associateBy { it.code }
        val wanted = desired.associateBy { it.code }

        val carried = ArrayList<AlarmSpec>()
        val stale = ArrayList<Int>()
        for (p in prev.values) {
            if (p.code in wanted) continue
            if (p.fireAt.isAfter(now) && p.id.path in preservePaths) carried += p else stale += p.code
        }

        for (code in stale) runCatching { backend.cancel(code) }

        val kept = ArrayList<AlarmSpec>()
        var scheduled = 0
        var unchanged = 0
        var failed = 0
        for (spec in desired) {
            if (!force && prev[spec.code] == spec) {
                unchanged++
                kept += spec
            } else if (trySet(spec)) {
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
        return SyncReport(scheduled, stale.size, unchanged, failed)
    }

    /** Called when an alarm has fired so it is not re-set by a later forced sync. */
    fun markFired(code: Int) {
        val all = store.load()
        if (all.any { it.code == code }) store.save(all.filter { it.code != code })
    }

    private fun trySet(spec: AlarmSpec): Boolean = try {
        backend.set(spec)
        true
    } catch (e: Exception) {
        false
    }
}
