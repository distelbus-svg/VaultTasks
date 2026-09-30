package app.vaulttasks.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vaulttasks.VaultTasksApp
import app.vaulttasks.ui.spaces.SpaceEditScreen
import app.vaulttasks.ui.spaces.SpacesScreen
import app.vaulttasks.ui.tasks.TaskEditorSheet
import app.vaulttasks.ui.tasks.TaskListScreen
import app.vaulttasks.ui.theme.VaultTasksTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VaultTasksApp).container
        setContent {
            VaultTasksTheme {
                val vm: MainViewModel = viewModel(factory = viewModelFactory { initializer { MainViewModel(container.vault) } })
                val state by vm.uiState.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    if (uri != null) vm.onVaultPicked(uri)
                }

                // collectLatest: a newer message replaces the one on screen (so a second delete gets its own Undo).
                LaunchedEffect(vm) {
                    vm.messages.collectLatest { m ->
                        val result = snackbar.showSnackbar(
                            message = m.text,
                            actionLabel = m.actionLabel,
                            duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
                    }
                }
                // Spec §5: rescan on app foreground.
                LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.rescan() }

                BackHandler(enabled = state.screen != Screen.Tasks) { vm.back() }

                when (val screen = state.screen) {
                    Screen.Tasks -> {
                        TaskListScreen(
                            state = state,
                            snackbar = snackbar,
                            onPickVault = { picker.launch(null) },
                            onRescan = { vm.rescan(force = true) },
                            onOpenSpaces = vm::openSpaces,
                            onEditSpace = vm::openSpaceEdit,
                            onSelectSpace = vm::selectSpace,
                            onToggle = vm::toggle,
                            onDelete = vm::delete,
                            onOpenTask = vm::openEdit,
                            onCreate = vm::openCreate,
                        )
                        val editor = state.editor
                        val space = state.activeSpace
                        if (editor != null && space != null) {
                            TaskEditorSheet(
                                editor = editor,
                                space = space,
                                saving = state.saving,
                                onSave = vm::saveEditor,
                                onDismiss = vm::closeEditor,
                            )
                        }
                    }
                    Screen.Spaces -> SpacesScreen(
                        spaces = state.spaces,
                        activeId = state.activeSpace?.id,
                        onBack = vm::back,
                        onAdd = vm::addSpace,
                        onEdit = vm::openSpaceEdit,
                        onMove = vm::moveSpace,
                        onDelete = vm::deleteSpace,
                    )
                    is Screen.SpaceEdit -> state.spaces.firstOrNull { it.id == screen.spaceId }?.let { space ->
                        SpaceEditScreen(
                            space = space,
                            vaultFiles = state.vaultFiles,
                            onBack = vm::back,
                            onSave = { name, files, default -> vm.saveSpace(space.id, name, files, default) },
                        )
                    }
                }
            }
        }
    }
}
