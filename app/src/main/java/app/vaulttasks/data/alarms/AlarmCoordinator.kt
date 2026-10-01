package app.vaulttasks.data.alarms

import app.vaulttasks.data.RepoState
import app.vaulttasks.data.VaultRepository
import app.vaulttasks.data.settings.SettingsStore
import app.vaulttasks.domain.SpacesData
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskId
import app.vaulttasks.domain.alarms.AlarmPlanner
import app.vaulttasks.domain.alarms.AlarmSpec
import app.vaulttasks.domain.alarms.AlarmSyncer
import app.vaulttasks.domain.alarms.ReminderSettings
import app.vaulttasks.domain.alarms.SyncReport
import app.vaulttasks.domain.vault.ScannedFile
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
 * Spec §8.2: the only place alarms are planned and synced. Runs after every change to the parsed vault, to the
 * reminder settings or to a task's lead time, and on demand for system triggers ([syncNow] with force). Nothing else
 * schedules alarms.
 *
 * Alarms are only touched from a *complete* picture: vault ready and one scan finished. A cold process whose repository
 * is still empty must never cancel the alarms that are already set.
 */
class AlarmCoordinator(
    private val repo: VaultRepository,
    private val settings: SettingsStore,
    private val leadStore: LeadStore,
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
                leadStore.leads,
            ) { snap, cfg, leads -> Triple(snap, cfg, leads) }.collect { (snap, cfg, leads) ->
                syncFrom(snap, cfg, leads, forceRequested = false, trigger = null)
            }
        }
    }

    /** For receivers and explicit "reschedule" triggers. Returns null if the vault is not in a syncable state. */
    suspend fun syncNow(force: Boolean, trigger: String? = null): SyncReport? {
        val s = repo.state.value
        val snap = Snapshot(s.status, s.scanComplete, s.files, s.unreadable, s.spaces)
        return syncFrom(snap, settings.reminders.first(), leadStore.leads.value, force, trigger)
    }

    private suspend fun syncFrom(
        snap: Snapshot,
        cfg: ReminderSettings,
        leads: Map<String, Int>,
        forceRequested: Boolean,
        trigger: String?,
    ): SyncReport? = mutex.withLock {
        if (snap.status != RepoState.Status.READY || !snap.scanComplete) return@withLock null
        val force = forceRequested || firstSync
        firstSync = false
        withContext(Dispatchers.Default) {
            val now = LocalDateTime.now()
            val desired = AlarmPlanner.plan(tasksWithSpace(snap), cfg, now) { leads[AlarmPlanner.leadKey(it.id.path, it.description)] ?: 0 }
            val report = syncer.sync(desired, snap.unreadable.toSet(), now, force)
            pruneLeads(snap, leads)
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

    /** Lead entries of tasks that no longer exist in any readable file. Only entries seen in [leads] are candidates. */
    private fun pruneLeads(snap: Snapshot, leads: Map<String, Int>) {
        val live = snap.files.values.flatMapTo(HashSet()) { f -> f.tasks.map { AlarmPlanner.leadKey(it.id.path, it.description) } }
        val unreadable = snap.unreadable.toSet()
        leadStore.remove(leads.keys.filter { it !in live && it.substringBefore('\u0000') !in unreadable })
    }

    /** Each task paired with the first space (in list order) that contains its file. */
    private fun tasksWithSpace(snap: Snapshot): List<Pair<Task, String>> {
        val out = ArrayList<Pair<Task, String>>()
        val seen = HashSet<TaskId>()
        for (space in snap.spaces.spaces) {
            for (path in space.files) {
                for (t in snap.files[path]?.tasks.orEmpty()) if (seen.add(t.id)) out += t to space.name
            }
        }
        return out
    }

    /** Called by the receiver when an alarm fired. */
    suspend fun markFired(code: Int) = mutex.withLock { syncer.markFired(code) }
}
