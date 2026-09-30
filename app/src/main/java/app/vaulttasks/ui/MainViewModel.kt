package app.vaulttasks.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaulttasks.data.EditOutcome
import app.vaulttasks.data.RepoState
import app.vaulttasks.data.VaultRepository
import app.vaulttasks.domain.Space
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskFields
import app.vaulttasks.domain.TaskGroups
import app.vaulttasks.domain.TaskGrouping
import app.vaulttasks.domain.TaskState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

sealed interface Screen {
    data object Tasks : Screen
    data object Spaces : Screen
    data class SpaceEdit(val spaceId: String) : Screen
}

/** What the create/edit sheet is doing. [Edit] holds the snapshot the user opened; the write protocol detects staleness. */
sealed interface Editor {
    data object Create : Editor
    data class Edit(val task: Task) : Editor
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
    val vaultFiles: List<String> = emptyList(),
    val screen: Screen = Screen.Tasks,
    val editor: Editor? = null,
    val saving: Boolean = false,
)

class MainViewModel(private val repo: VaultRepository) : ViewModel() {

    private data class Local(val screen: Screen = Screen.Tasks, val editor: Editor? = null, val saving: Boolean = false)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<UiState> = combine(repo.state, local) { s, l ->
        val active = s.spaces.active
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
            groups = TaskGrouping.group(tasks, LocalDate.now(), active?.files.orEmpty()),
            vaultFiles = s.vaultFiles,
            screen = screen,
            editor = l.editor,
            saving = l.saving,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { repo.load() }
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

    fun openSpaceEdit(id: String) = local.update { it.copy(screen = Screen.SpaceEdit(id)) }

    /** System back / up: SpaceEdit → Spaces → Tasks. */
    fun back() = local.update {
        it.copy(screen = if (it.screen is Screen.SpaceEdit) Screen.Spaces else Screen.Tasks)
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

    fun openCreate() {
        if (repo.state.value.spaces.active?.creationFile == null) return
        local.update { it.copy(editor = Editor.Create) }
    }

    fun openEdit(task: Task) = local.update { it.copy(editor = Editor.Edit(task)) }

    fun closeEditor() = local.update { it.copy(editor = null, saving = false) }

    /** Saves the open sheet. Guarded against double taps: a second call while one is running is ignored. */
    fun saveEditor(fields: TaskFields, targetPath: String) {
        val ed = local.value.editor ?: return
        if (local.value.saving) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val outcome = when (ed) {
                Editor.Create -> repo.create(targetPath, fields)
                is Editor.Edit ->
                    if (targetPath == ed.task.id.path) repo.update(ed.task, fields) else repo.move(ed.task, fields, targetPath)
            }
            when (outcome) {
                is EditOutcome.Done, is EditOutcome.Removed -> closeEditor()
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
