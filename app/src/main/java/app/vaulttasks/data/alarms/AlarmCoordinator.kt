package app.vaulttasks.data.alarms

import app.vaulttasks.data.RepoState
import app.vaulttasks.data.VaultRepository
import app.vaulttasks.data.settings.SettingsStore
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskState
import app.vaulttasks.domain.alarms.AlarmPlanner
import app.vaulttasks.domain.alarms.AlarmSpec
import app.vaulttasks.domain.alarms.AlarmSyncer
import app.vaulttasks.domain.alarms.ReminderSettings
import app.vaulttasks.domain.alarms.SyncReport
import app.vaulttasks.domain.vault.ScannedFile
import app.vaulttasks.domain.SpacesData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Spec §8.2: the only place alarms are planned and synced. Runs after every change to the parsed vault or to the
 * reminder settings, and on demand for system triggers ([syncNow] with force). Nothing else schedules alarms.
 *
 * Alarms are only touched from a *complete* picture: vault ready and one scan finished. A cold process whose repository
 * is still empty must never cancel the alarms that are already set.
 */
class AlarmCoordinator(
    private val repo: VaultRepository,
    private val settings: SettingsStore,
    private val syncer: AlarmSyncer,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** The first sync of every process is forced: force-stop and some OEM "clean-ups" delete alarms silently. */
    private var firstSync = true

    private data class Snapshot(
        val status: RepoState.Status,
        val scanComplete: Boolean,
        val files: Map<String, ScannedFile>,
        val unreadable: List<String>,
        val spaces: SpacesData,
    )

    fun start() {
        scope.launch {
            combine(
                repo.state.map { Snapshot(it.status, it.scanComplete, it.files, it.unreadable, it.spaces) }.distinctUntilChanged(),
                settings.reminders.distinctUntilChanged(),
            ) { snap, cfg -> snap to cfg }.collect { (snap, cfg) ->
                syncFrom(snap, cfg, forceRequested = false, trigger = null)
            }
        }
    }

    /** For receivers and explicit "reschedule" triggers. Returns null if the vault is not in a syncable state. */
    suspend fun syncNow(force: Boolean, trigger: String? = null): SyncReport? {
        val s = repo.state.value
        val snap = Snapshot(s.status, s.scanComplete, s.files, s.unreadable, s.spaces)
        return syncFrom(snap, settings.reminders.first(), force, trigger)
    }

    private suspend fun syncFrom(snap: Snapshot, cfg: ReminderSettings, forceRequested: Boolean, trigger: String?): SyncReport? =
        mutex.withLock {
            if (snap.status != RepoState.Status.READY || !snap.scanComplete) return@withLock null
            val force = forceRequested || firstSync
            firstSync = false
            withContext(Dispatchers.Default) {
                val now = LocalDateTime.now()
                val tasks = tasksWithSpace(snap)
                val desired = AlarmPlanner.plan(tasks, cfg, now, reserved = syncer.snoozeCodes())
                val open = snap.files.values.flatMap { it.tasks }.filter { it.state == TaskState.OPEN }.mapTo(HashSet()) { it.id }
                val report = syncer.sync(desired, open, snap.unreadable.toSet(), now, force)
                if (trigger != null) {
                    diagnostics.record(
                        DiagEntry(
                            DiagEntry.Kind.EVENT, System.currentTimeMillis(), null,
                            "$trigger → set ${report.scheduled}, cancelled ${report.cancelled}, failed ${report.failed}",
                        ),
                    )
                }
                report
            }
        }

    /** Each task paired with the first space (in list order) that contains its file. */
    private fun tasksWithSpace(snap: Snapshot): List<Pair<Task, String>> {
        val out = ArrayList<Pair<Task, String>>()
        val seen = HashSet<app.vaulttasks.domain.TaskId>()
        for (space in snap.spaces.spaces) {
            for (path in space.files) {
                for (t in snap.files[path]?.tasks.orEmpty()) if (seen.add(t.id)) out += t to space.name
            }
        }
        return out
    }

    /** Notification Snooze action. The file is never touched (spec §8.4). */
    suspend fun snooze(base: AlarmSpec, option: Long): AlarmSpec? = mutex.withLock {
        withContext(Dispatchers.Default) { syncer.snooze(base, LocalDateTime.now().plusMinutes(option)) }
    }

    /** Called by the receiver when an alarm fired. */
    suspend fun markFired(code: Int) = mutex.withLock { syncer.markFired(code) }
}
