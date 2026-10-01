package app.vaulttasks.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vaulttasks.VaultTasksApp
import app.vaulttasks.data.alarms.AlarmIntents
import app.vaulttasks.data.alarms.Notifications
import app.vaulttasks.domain.TaskId
import app.vaulttasks.ui.reminders.RemindersScreen
import app.vaulttasks.ui.spaces.SpaceEditScreen
import app.vaulttasks.ui.spaces.SpacesScreen
import app.vaulttasks.ui.tasks.TaskEditorSheet
import app.vaulttasks.ui.tasks.TaskListScreen
import app.vaulttasks.ui.theme.VaultTasksTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    /** A task to open from a notification tap; consumed by the composition once the view model exists. */
    private var openRequest by mutableStateOf<TaskId?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openRequest = AlarmIntents.readId(intent)
        val container = (application as VaultTasksApp).container
        setContent {
            VaultTasksTheme {
                val vm: MainViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { MainViewModel(container.vault, container.settings, container.health, container.diagnostics, container.leads) }
                    },
                )
                val state by vm.uiState.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    if (uri != null) vm.onVaultPicked(uri)
                }

                // Explicit "Fix" tap: if the system dialog can't or won't be shown any more (denied twice), open the settings page.
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (!granted) container.health.openNotificationSettings()
                    vm.refreshHealth()
                }
                // The one-time ask at first start: a "no" is respected silently (the home banner stays as the nudge).
                val firstAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshHealth() }

                LaunchedEffect(openRequest) {
                    openRequest?.let {
                        vm.openFromNotification(it)
                        openRequest = null
                    }
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
                // Spec §5: rescan on app foreground. Health is re-read too: the user may have just changed a setting.
                LifecycleEventEffect(Lifecycle.Event.ON_START) {
                    vm.rescan()
                    if (!askedForNotifications && !Notifications.permissionGranted(this@MainActivity)) {
                        askedForNotifications = true
                        firstAsk.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshHealth() }

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
                            onOpenReminders = vm::openReminders,
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
                    Screen.Reminders -> RemindersScreen(
                        health = state.health,
                        settings = state.reminders,
                        diagnostics = state.diagnostics,
                        onBack = vm::back,
                        onFixNotifications = {
                            if (!Notifications.permissionGranted(this@MainActivity)) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                container.health.openNotificationSettings()
                            }
                        },
                        onFixExactAlarms = { container.health.openExactAlarmSettings() },
                        onFixBattery = { container.health.requestBatteryExemption() },
                        onOpenLaunchManager = { container.health.openOemLaunchManager() || container.health.openAppDetails() },
                        onRefresh = vm::refreshHealth,
                        onClearDiagnostics = vm::clearDiagnostics,
                        onDefaultTime = vm::setDefaultReminderTime,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AlarmIntents.readId(intent)?.let { openRequest = it }
    }

    private companion object {
        /** Ask for the notification permission once per process; the Reminders screen handles everything after that. */
        var askedForNotifications = false
    }
}
