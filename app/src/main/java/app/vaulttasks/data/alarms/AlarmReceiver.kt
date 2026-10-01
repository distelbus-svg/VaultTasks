package app.vaulttasks.data.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.vaulttasks.AppContainer
import app.vaulttasks.VaultTasksApp
import app.vaulttasks.data.EditOutcome
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskFields
import app.vaulttasks.domain.TaskState
import app.vaulttasks.domain.alarms.AlarmPlanner
import app.vaulttasks.domain.alarms.AlarmSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Spec §8.1/§8.4. The notification is posted straight from the spec carried in the intent: no vault access, no UI
 * process. Done and Snooze run under goAsync() (the system allows roughly 10 s; we cap at 8-9 s and report failure
 * on the notification itself instead of failing silently).
 *
 * Snooze moves the task's due time in the vault file to now + N minutes. The alarm then follows from the file like
 * any other, so there is no second, hidden kind of reminder to keep in sync.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val spec = AlarmIntents.read(intent) ?: return
        val container = (context.applicationContext as VaultTasksApp).container
        when (intent.action) {
            AlarmIntents.ACTION_FIRE -> {
                fire(context, container.diagnostics, spec)
                async(container) { container.alarms.markFired(spec.code) }
            }
            AlarmIntents.ACTION_DONE -> async(container) {
                Notifications.cancel(context, spec.code)
                val result = mutateTask(container, spec) { task ->
                    when {
                        task.state == TaskState.DONE -> EditOutcome.Done
                        task.isRecurring -> EditOutcome.Failed("recurring task, complete it in Obsidian")
                        else -> container.vault.setDone(task, true)
                    }
                }
                finish(context, container, spec, result, "complete")
            }
            AlarmIntents.ACTION_SNOOZE -> async(container) {
                Notifications.cancel(context, spec.code)
                val minutes = intent.getLongExtra(AlarmIntents.EXTRA_SNOOZE_MINUTES, 10)
                val target = AlarmPlanner.snoozeTarget(LocalDateTime.now(), minutes)
                val result = mutateTask(container, spec) { task ->
                    container.vault.update(task, TaskFields(task.description, target.toLocalDate(), target.toLocalTime()))
                }
                if (result is EditOutcome.Done) {
                    // The reminder is a "remind me before" offset from the old due time; after a snooze the new due time IS the reminder.
                    container.leads.clear(spec.id.path, spec.title)
                }
                finish(context, container, spec, result, "snooze")
            }
        }
    }

    private fun fire(context: Context, diag: DiagnosticsLog, spec: AlarmSpec) {
        val posted = Notifications.showReminder(context, spec)
        val scheduled = spec.fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        diag.record(
            DiagEntry(
                DiagEntry.Kind.FIRE,
                System.currentTimeMillis(),
                scheduled,
                spec.title + if (posted) "" else " (NOT posted: notification permission missing)",
            ),
        )
    }

    /**
     * Cold-starts the vault if needed, finds the task by identity and runs [op] on it through the normal write
     * protocol. Two attempts: on Stale the repository has already rescanned, so the retry sees fresh state.
     */
    private suspend fun mutateTask(container: AppContainer, spec: AlarmSpec, op: suspend (Task) -> EditOutcome): EditOutcome {
        val repo = container.vault
        return runCatching {
            withTimeout(8_000) {
                repo.ensureLoaded()
                var outcome: EditOutcome = EditOutcome.Failed("task changed on disk")
                for (attempt in 0..1) {
                    val task = repo.state.value.files[spec.id.path]?.tasks?.firstOrNull { it.id == spec.id }
                    outcome = if (task == null) EditOutcome.Failed("task changed on disk") else op(task)
                    if (outcome !is EditOutcome.Stale) break
                }
                outcome
            }
        }.getOrElse { EditOutcome.Failed(it.message ?: "timed out") }
    }

    private suspend fun finish(context: Context, container: AppContainer, spec: AlarmSpec, result: EditOutcome, verb: String) {
        when (result) {
            is EditOutcome.Done, is EditOutcome.Removed -> container.alarms.syncNow(force = false)
            EditOutcome.Stale -> Notifications.showProblem(context, spec, "Task changed on disk. Open the app to retry.")
            is EditOutcome.Failed -> Notifications.showProblem(context, spec, "Could not $verb: ${result.message}")
        }
    }

    private fun async(container: AppContainer, block: suspend () -> Unit) {
        val pending = goAsync()
        container.appScope.launch {
            try {
                withTimeout(9_000) { block() }
            } finally {
                pending.finish()
            }
        }
    }
}
