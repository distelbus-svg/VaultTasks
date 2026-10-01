package app.vaulttasks.data.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.vaulttasks.AppContainer
import app.vaulttasks.VaultTasksApp
import app.vaulttasks.data.EditOutcome
import app.vaulttasks.domain.TaskState
import app.vaulttasks.domain.alarms.AlarmKind
import app.vaulttasks.domain.alarms.AlarmSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.ZoneId

/**
 * Spec §8.1/§8.4. The notification is posted straight from the spec carried in the intent: no vault access, no UI
 * process. Done and Snooze run under goAsync() (the system allows roughly 10 s; we cap at 8-9 s and report failure
 * on the notification itself instead of failing silently).
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
                done(context, container, spec)
            }
            AlarmIntents.ACTION_SNOOZE -> async(container) {
                Notifications.cancel(context, spec.code)
                val minutes = intent.getLongExtra(AlarmIntents.EXTRA_SNOOZE_MINUTES, 10)
                if (container.alarms.snooze(spec, minutes) == null) {
                    Notifications.showProblem(context, spec, "Could not snooze. Open the app to check reminder settings.")
                }
            }
        }
    }

    private fun fire(context: Context, diag: DiagnosticsLog, spec: AlarmSpec) {
        val posted = Notifications.showReminder(context, spec)
        val scheduled = spec.fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        diag.record(
            DiagEntry(
                if (spec.kind == AlarmKind.SNOOZE) DiagEntry.Kind.SNOOZE_FIRE else DiagEntry.Kind.FIRE,
                System.currentTimeMillis(),
                scheduled,
                spec.title + if (posted) "" else " (NOT posted: notification permission missing)",
            ),
        )
    }

    /** Completes the task through the normal write protocol; a second attempt covers a benign mid-write race. */
    private suspend fun done(context: Context, container: AppContainer, spec: AlarmSpec) {
        val repo = container.vault
        val result = runCatching {
            withTimeout(8_000) {
                repo.ensureLoaded()
                var outcome: EditOutcome = EditOutcome.Failed("Task not found")
                for (attempt in 0..1) {
                    val task = repo.state.value.files[spec.id.path]?.tasks?.firstOrNull { it.id == spec.id }
                    outcome = when {
                        task == null -> EditOutcome.Failed("task changed on disk")
                        task.state == TaskState.DONE -> EditOutcome.Done
                        task.isRecurring -> EditOutcome.Failed("recurring task, complete it in Obsidian")
                        else -> repo.setDone(task, true) // on Stale the repository rescans, so the retry sees fresh state
                    }
                    if (outcome !is EditOutcome.Stale) break
                }
                outcome
            }
        }.getOrElse { EditOutcome.Failed(it.message ?: "timed out") }

        when (result) {
            is EditOutcome.Done, is EditOutcome.Removed -> container.alarms.syncNow(force = false)
            EditOutcome.Stale -> Notifications.showProblem(context, spec, "Task changed on disk. Open the app to retry.")
            is EditOutcome.Failed -> Notifications.showProblem(context, spec, "Could not complete: ${result.message}")
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
