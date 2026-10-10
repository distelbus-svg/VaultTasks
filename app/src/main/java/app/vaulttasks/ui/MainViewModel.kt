package app.vaulttasks.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaulttasks.data.EditOutcome
import app.vaulttasks.data.RepoState
import app.vaulttasks.data.VaultRepository
import app.vaulttasks.data.alarms.DiagEntry
import app.vaulttasks.data.alarms.DiagnosticsLog
import app.vaulttasks.data.alarms.HealthReport
import app.vaulttasks.data.alarms.LeadStore
import app.vaulttasks.data.alarms.ReminderHealth
import app.vaulttasks.data.settings.SettingsStore
import app.vaulttasks.domain.Categories
import app.vaulttasks.ui.tasks.TaskKeys
import app.vaulttasks.domain.IcsExport
import app.vaulttasks.domain.Space
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskCalendar
import app.vaulttasks.domain.TaskFields
import app.vaulttasks.domain.TaskGroups
import app.vaulttasks.domain.TaskGrouping
import app.vaulttasks.domain.TaskId
import app.vaulttasks.domain.TaskState
import app.vaulttasks.domain.alarms.ReminderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

sealed interface Screen {
    data object Tasks : Screen
    data object Spaces : Screen
    data class SpaceEdit(val spaceId: String) : Screen
    data object Reminders : Screen
    data object Calendar : Screen
    data object Sync : Screen
}

/** What the create/edit sheet is doing. [Edit] holds the snapshot the user opened; the write protocol detects staleness. */
sealed interface Editor {
    /** [date] pre-fills the due date (calendar day sheet, spec §7.3). */
    data class Create(val date: LocalDate? = null) : Editor
    /** [presetDate]: date to start the sheet with instead of the task's own (notification "Move to tomorrow"). */
    data class Edit(val task: Task, val leadMinutes: Int = 0, val presetDate: LocalDate? = null) : Editor
}

data class UiMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

data class UiState(
    val status: RepoState.Status = RepoState.Status.LOADING,
    val vaultName: String? = null,
    val scanning: Boolean = false,
    val unreadable: List<String> = emptyList(),
    val error: String? = null,
    val spaces: List<Space> = emptyList(),
    val activeSpace: Space? = null,
    val groups: TaskGroups = TaskGroups(),
    /** Active space's tasks by due date for the month view (spec §7.3). */
    val calendar: Map<LocalDate, List<Task>> = emptyMap(),
    val vaultFiles: List<String> = emptyList(),
    /** Categories (trailing #tags) used in the active space, for the editor and the list grouping. */
    val categories: List<String> = emptyList(),
    /** Active space is shown grouped by category (remembered per space). */
    val groupByCategory: Boolean = false,
    /** Manual order of the active space for the current grouping: task list keys, and category names. */
    val taskOrder: List<String> = emptyList(),
    val categoryOrder: List<String> = emptyList(),
    val screen: Screen = Screen.Tasks,
    val editor: Editor? = null,
    val saving: Boolean = false,
    /** Null until the first check; the home banner shows while any detectable check fails (spec §8.5). */
    val health: HealthReport? = null,
    val reminders: ReminderSettings = ReminderSettings(),
    /** Newest first. */
    val diagnostics: List<DiagEntry> = emptyList(),
)

class MainViewModel(
    private val repo: VaultRepository,
    private val settings: SettingsStore,
    private val healthCheck: ReminderHealth,
    private val diagnosticsLog: DiagnosticsLog,
    private val leadStore: LeadStore,
) : ViewModel() {

    private data class Local(
        val screen: Screen = Screen.Tasks,
        val editor: Editor? = null,
        val saving: Boolean = false,
        val health: HealthReport? = null,
        val diagnostics: List<DiagEntry> = emptyList(),
    )

    private val local = MutableStateFlow(Local())

    /** Emits at every minute boundary so time-based grouping (overdue by time) updates while the screen is open. */
    private val minuteTicker = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(60_000 - System.currentTimeMillis() % 60_000 + 20)
        }
    }

    private data class Prefs(
        val reminders: ReminderSettings,
        val grouped: Map<String, Boolean>,
        val taskOrders: Map<String, List<String>>,
        val categoryOrders: Map<String, List<String>>,
    )

    private val prefs = combine(settings.reminders, settings.groupByCategory, settings.taskOrders, settings.categoryOrders) { r, g, t, c ->
        Prefs(r, g, t, c)
    }

    val uiState: StateFlow<UiState> = combine(repo.state, local, prefs, minuteTicker) { s, l, p, now ->
        val reminders = p.reminders
        val active = s.spaces.active
        val byCategory = active?.id?.let { p.grouped[it] } == true
        val tasks = active?.files.orEmpty().flatMap { s.files[it]?.tasks.orEmpty() }
        // A space editor for a space that no longer exists falls back to the list.
        val screen = if (l.screen is Screen.SpaceEdit && s.spaces.spaces.none { it.id == l.screen.spaceId }) Screen.Spaces else l.screen
        UiState(
            status = s.status,
            vaultName = s.vaultName,
            scanning = s.scanning,
            unreadable = s.unreadable,
            error = s.error,
            spaces = s.spaces.spaces,
            activeSpace = active,
            groups = TaskGrouping.group(tasks, now, active?.files.orEmpty()),
            calendar = TaskCalendar.byDay(tasks, active?.files.orEmpty()),
            vaultFiles = s.vaultFiles,
            categories = Categories.of(tasks),
            groupByCategory = byCategory,
            taskOrder = p.taskOrders[orderKey(active?.id, byCategory)].orEmpty(),
            categoryOrder = p.categoryOrders[active?.id].orEmpty(),
            screen = screen,
            editor = l.editor,
            saving = l.saving,
            health = l.health,
            reminders = reminders,
            diagnostics = l.diagnostics,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { repo.ensureLoaded() }
        refreshHealth()
    }

    // ---- vault ---------------------------------------------------------------------------------------------

    fun rescan(force: Boolean = false) {
        viewModelScope.launch { repo.rescan(force) }
    }

    fun onVaultPicked(uri: Uri) {
        viewModelScope.launch { repo.setVault(uri) }
    }

    // ---- navigation ----------------------------------------------------------------------------------------

    fun openSpaces() = local.update { it.copy(screen = Screen.Spaces) }

    fun openCalendar() = local.update { it.copy(screen = Screen.Calendar) }

    fun openSync() = local.update { it.copy(screen = Screen.Sync) }
    fun openSpaceEdit(id: String) = local.update { it.copy(screen = Screen.SpaceEdit(id)) }

    fun openReminders() {
        local.update { it.copy(screen = Screen.Reminders) }
        refreshHealth()
    }

    /** System back / up: SpaceEdit → Spaces → Tasks; Reminders → Tasks. */
    fun back() = local.update {
        it.copy(screen = if (it.screen is Screen.SpaceEdit) Screen.Spaces else Screen.Tasks)
    }

    // ---- reminders (spec §7.4, §8.5) -----------------------------------------------------------------------

    /** Re-reads permissions and the diagnostics log. Cheap; called on resume and after returning from system settings. */
    fun refreshHealth() {
        viewModelScope.launch {
            val (health, log) = withContext(Dispatchers.IO) { healthCheck.check() to diagnosticsLog.entries().asReversed() }
            local.update { it.copy(health = health, diagnostics = log) }
        }
    }

    fun clearDiagnostics() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { diagnosticsLog.clear() }
            refreshHealth()
        }
    }

    fun setDefaultReminderTime(time: LocalTime) {
        viewModelScope.launch { settings.setDefaultReminderTime(time) }
    }

    /**
     * Notification tap: waits for the first scan, switches to a space that shows the task's file, and opens its edit
     * sheet. If the task is gone (edited or completed meanwhile) it says so instead of opening nothing.
     */
    fun openFromNotification(id: TaskId, tomorrow: Boolean = false) {
        viewModelScope.launch {
            repo.ensureLoaded()
            repo.state.first { it.status != RepoState.Status.LOADING && !it.scanning }
            val st = repo.state.value
            val task = st.files[id.path]?.tasks?.firstOrNull { it.id == id }
            val space = st.spaces.spaces.let { all -> all.firstOrNull { it.id == st.spaces.active?.id && id.path in it.files } ?: all.firstOrNull { id.path in it.files } }
            if (task == null || space == null) {
                say("That task no longer exists as it was")
                return@launch
            }
            if (space.id != st.spaces.active?.id) repo.updateSpaces { it.setActive(space.id) }
            local.update {
                it.copy(
                    screen = Screen.Tasks,
                    editor = editorFor(task).copy(presetDate = if (tomorrow) LocalDate.now().plusDays(1) else null),
                    saving = false,
                )
            }
        }
    }

    // ---- spaces --------------------------------------------------------------------------------------------

    fun selectSpace(id: String) {
        viewModelScope.launch { repo.updateSpaces { it.setActive(id) } }
    }

    /** Creates the space and opens its editor so files can be chosen right away. */
    fun addSpace(name: String) {
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            repo.updateSpaces { it.add(id, name) }
            if (repo.state.value.spaces.spaces.any { it.id == id }) openSpaceEdit(id)
        }
    }

    fun deleteSpace(id: String) {
        viewModelScope.launch { repo.updateSpaces { it.delete(id) } }
    }

    fun moveSpace(id: String, delta: Int) {
        viewModelScope.launch { repo.updateSpaces { it.move(id, delta) } }
    }

    /** One atomic write of everything the space editor changed. */
    fun saveSpace(id: String, name: String, files: List<String>, defaultFile: String?) {
        viewModelScope.launch {
            repo.updateSpaces { it.rename(id, name).setFiles(id, files).setDefault(id, defaultFile) }
            back()
        }
    }

    // ---- tasks ---------------------------------------------------------------------------------------------

    fun openCreate() = openCreateOn(null)

    /** [date] pre-fills the due date (calendar day sheet). */
    fun openCreateOn(date: LocalDate?) {
        if (repo.state.value.spaces.active?.creationFile == null) return
        local.update { it.copy(editor = Editor.Create(date)) }
    }

    fun openEdit(task: Task) = local.update { it.copy(editor = editorFor(task)) }

    private fun editorFor(task: Task): Editor.Edit = Editor.Edit(task, leadStore.get(task.id.path, task.description))

    fun closeEditor() = local.update { it.copy(editor = null, saving = false) }

    /** Saves the open sheet. Guarded against double taps: a second call while one is running is ignored. */
    val categoryColors: StateFlow<Map<String, Int>> =
        settings.categoryColors.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setCategoryColor(name: String, argb: Int) {
        viewModelScope.launch { settings.setCategoryColor(name, argb) }
    }

    fun saveEditor(fields: TaskFields, targetPath: String, leadMinutes: Int) {
        val ed = local.value.editor ?: return
        if (local.value.saving) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val beforeTasks = activeTasks()
            val beforeKeys = TaskKeys(beforeTasks)
            val beforeSet = beforeTasks.mapTo(HashSet()) { beforeKeys[it] }
            val oldKey = (ed as? Editor.Edit)?.task?.let { beforeKeys[it] }
            val outcome = when (ed) {
                is Editor.Create -> repo.create(targetPath, fields)
                is Editor.Edit ->
                    if (targetPath == ed.task.id.path) repo.update(ed.task, fields) else repo.move(ed.task, fields, targetPath)
            }
            when (outcome) {
                is EditOutcome.Done, is EditOutcome.Removed -> {
                    val afterTasks = activeTasks()
                    carryOrder(beforeSet, oldKey, TaskKeys(afterTasks), afterTasks)
                    // "Remind me before" needs a due date to mean anything.
                    val lead = if (fields.dueDate == null) 0 else leadMinutes
                    val old = (ed as? Editor.Edit)?.task
                    leadStore.replace(old?.id?.path, old?.description, targetPath, fields.description, lead)
                    closeEditor()
                }
                EditOutcome.Stale -> {
                    closeEditor()
                    say("Task changed on disk — please retry")
                }
                is EditOutcome.Failed -> {
                    local.update { it.copy(saving = false) }
                    say("Could not save: ${outcome.message}")
                }
            }
        }
    }

    private fun orderKey(spaceId: String?, byCategory: Boolean) = "$spaceId|${if (byCategory) "cat" else "date"}"

    private fun activeTasks(): List<Task> {
        val s = repo.state.value
        return s.spaces.active?.files.orEmpty().flatMap { s.files[it]?.tasks.orEmpty() }
    }

    /** Remembered per space: switching spaces brings each one back the way it was left. */
    fun setGroupByCategory(on: Boolean) {
        val id = repo.state.value.spaces.active?.id ?: return
        viewModelScope.launch { settings.setGroupByCategory(id, on) }
    }

    /**
     * The user dragged tasks into a new order inside one section. [sectionKeys] are that section's task keys, top to bottom.
     * Keys of tasks that no longer exist are dropped, so the stored list cannot grow forever.
     */
    fun reorderTasks(sectionKeys: List<String>) {
        val s = repo.state.value
        val id = s.spaces.active?.id ?: return
        viewModelScope.launch {
            val byCategory = settings.groupByCategory.first()[id] == true
            val key = orderKey(id, byCategory)
            val existing = settings.taskOrders.first()[key].orEmpty()
            val keys = TaskKeys(activeTasks())
            val alive = activeTasks().mapTo(HashSet()) { keys[it] }
            val moved = sectionKeys.toSet()
            settings.setTaskOrder(key, (existing.filter { it !in moved } + sectionKeys).filter { it in alive })
        }
    }

    fun reorderCategories(order: List<String>) {
        val id = repo.state.value.spaces.active?.id ?: return
        viewModelScope.launch { settings.setCategoryOrder(id, order) }
    }

    /** An edit changes a task's key (its text); carry its manual position over to the new key. */
    private suspend fun carryOrder(before: Set<String>, oldKey: String?, after: TaskKeys, afterTasks: List<Task>) {
        if (oldKey == null) return
        val afterKeys = afterTasks.mapTo(HashSet()) { after[it] }
        val added = afterKeys - before
        if (oldKey in afterKeys || added.size != 1) return
        val id = repo.state.value.spaces.active?.id ?: return
        for (byCategory in listOf(false, true)) {
            val key = orderKey(id, byCategory)
            val order = settings.taskOrders.first()[key] ?: continue
            if (oldKey in order) settings.setTaskOrder(key, order.map { if (it == oldKey) added.first() else it })
        }
    }

    /** All coming tasks of every space (see [IcsExport.coming]) as an .ics document; null if there is nothing to export. */
    suspend fun buildCalendarExport(): Pair<String, Int>? = withContext(Dispatchers.Default) {
        val s = repo.state.value
        val tasks = s.spaces.assignedFiles.flatMap { s.files[it]?.tasks.orEmpty() }
        val now = LocalDateTime.now()
        val count = IcsExport.coming(tasks, now.toLocalDate()).size
        if (count == 0) return@withContext null
        val defaultTime = settings.reminders.first().defaultTime
        IcsExport.build(tasks, now, defaultTime) { leadStore.get(it.id.path, it.description) } to count
    }

    fun message(text: String) = say(text)

    fun toggle(task: Task) {
        viewModelScope.launch {
            val nowDone = task.state != TaskState.DONE
            report(repo.setDone(task, nowDone))
        }
    }

    fun delete(task: Task) {
        viewModelScope.launch {
            when (val r = repo.delete(task)) {
                is EditOutcome.Removed -> _messages.tryEmit(
                    UiMessage("Task deleted", "Undo") {
                        viewModelScope.launch { report(repo.restore(r.removed)) }
                    },
                )
                else -> report(r)
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun report(outcome: EditOutcome) {
        when (outcome) {
            is EditOutcome.Done, is EditOutcome.Removed -> Unit
            EditOutcome.Stale -> say("Task changed on disk — please retry")
            is EditOutcome.Failed -> say("Could not save: ${outcome.message}")
        }
    }

    private fun say(text: String) {
        _messages.tryEmit(UiMessage(text))
    }
}
