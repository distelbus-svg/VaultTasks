package app.vaulttasks.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaulttasks.data.EditOutcome
import app.vaulttasks.data.RepoState
import app.vaulttasks.data.VaultRepository
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FileTasks(val path: String, val tasks: List<Task>)

data class UiState(
    val status: RepoState.Status = RepoState.Status.LOADING,
    val vaultName: String? = null,
    val files: List<FileTasks> = emptyList(),
    val scanning: Boolean = false,
    val unreadable: List<String> = emptyList(),
    val error: String? = null,
)

class MainViewModel(private val repo: VaultRepository) : ViewModel() {

    val uiState: StateFlow<UiState> = repo.state
        .map { s ->
            UiState(
                status = s.status,
                vaultName = s.vaultName,
                // Cancelled tasks are hidden (spec §15.5). Phase 3 replaces per-file grouping with Overdue/Today/…
                files = s.files.values
                    .map { f -> FileTasks(f.state.path, f.tasks.filter { it.state != TaskState.CANCELLED }.sortedBy { it.lineIndex }) }
                    .filter { it.tasks.isNotEmpty() }
                    .sortedBy { it.path },
                scanning = s.scanning,
                unreadable = s.unreadable,
                error = s.error,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { repo.load() }
    }

    fun rescan(force: Boolean = false) {
        viewModelScope.launch { repo.rescan(force) }
    }

    fun onVaultPicked(uri: Uri) {
        viewModelScope.launch { repo.setVault(uri) }
    }

    fun toggle(task: Task) {
        viewModelScope.launch {
            val nowDone = task.state != TaskState.DONE
            when (val r = repo.setDone(task, nowDone)) {
                EditOutcome.Done -> Unit
                EditOutcome.Stale -> _messages.tryEmit("Task changed on disk — please retry")
                is EditOutcome.Failed -> _messages.tryEmit("Could not save: ${r.message}")
            }
        }
    }
}
