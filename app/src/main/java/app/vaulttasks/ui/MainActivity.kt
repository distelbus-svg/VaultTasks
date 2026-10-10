package app.vaulttasks.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.view.View
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import app.vaulttasks.data.RepoState
import kotlinx.coroutines.withTimeoutOrNull
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
import app.vaulttasks.data.alarms.OpenRequest
import app.vaulttasks.data.sync.SyncScheduler
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import app.vaulttasks.data.alarms.Notifications
import app.vaulttasks.ui.calendar.CalendarScreen
import app.vaulttasks.ui.reminders.RemindersScreen
import app.vaulttasks.ui.spaces.SpaceEditScreen
import app.vaulttasks.ui.spaces.SpacesScreen
import app.vaulttasks.ui.sync.SyncScreen
import app.vaulttasks.ui.tasks.TaskEditorSheet
import app.vaulttasks.ui.tasks.TaskListScreen
import app.vaulttasks.ui.theme.VaultTasksTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    /** A task to open from a notification tap; consumed by the composition once the view model exists. */
    private var openRequest by mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openRequest = AlarmIntents.readOpenRequest(intent)
        val container = (application as VaultTasksApp).container
        requestHighRefreshRate()
        setContent {
            VaultTasksTheme {
                val vm: MainViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { MainViewModel(container.vault, container.settings, container.health, container.diagnostics, container.leads) }
                    },
                )
                val state by vm.uiState.collectAsStateWithLifecycle()
                val syncState by container.sync.state.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                val categoryColors by vm.categoryColors.collectAsStateWithLifecycle()
                androidx.compose.runtime.CompositionLocalProvider(app.vaulttasks.ui.components.LocalCategoryColors provides categoryColors) {

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
                        // An action button does not dismiss its notification by itself.
                        if (it.tomorrow) Notifications.cancel(this@MainActivity, it.code)
                        vm.openFromNotification(it.id, it.tomorrow)
                        openRequest = null
                    }
                }

                // collectLatest: a newer message replaces the one on screen (so a second delete gets its own Undo).
                LaunchedEffect(vm) {
                    vm.messages.collectLatest { m ->
                        // Indefinite + timeout so the toast's lifetime is ours (Undo stays a little longer).
                        val result = withTimeoutOrNull(if (m.actionLabel != null) 6_000L else 4_000L) {
                            snackbar.showSnackbar(message = m.text, actionLabel = m.actionLabel, duration = SnackbarDuration.Indefinite)
                        }
                        if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
                    }
                }
                // Pull-to-refresh sync results ("1 pulled, 0 pushed").
                LaunchedEffect(container) {
                    container.sync.events.collect { text ->
                        snackbar.currentSnackbarData?.dismiss()
                        withTimeoutOrNull(3_500L) {
                            snackbar.showSnackbar(message = text, duration = SnackbarDuration.Indefinite)
                        }
                    }
                }
                // Spec §5: rescan on app foreground. Health is re-read too: the user may have just changed a setting.
                LifecycleEventEffect(Lifecycle.Event.ON_START) {
                    vm.rescan()
                    // Automatic sync trigger #1: every time the app is opened (throttled, silent).
                    container.appScope.launch {
                        container.vault.ensureLoaded()
                        container.sync.autoSync(minGapMs = 60_000)
                    }
                    if (!askedForNotifications && !Notifications.permissionGranted(this@MainActivity)) {
                        askedForNotifications = true
                        firstAsk.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshHealth() }
                // Automatic sync trigger #2: when the app is closed (not on rotation), pushed by WorkManager once online.
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                    if (!isChangingConfigurations) SyncScheduler.syncSoon(applicationContext)
                }

                BackHandler(enabled = state.screen != Screen.Tasks) { vm.back() }

                // The tab bar lives here, outside the animated area, so it stays put while screens slide.
                val showNav = (state.screen == Screen.Tasks || state.screen == Screen.Calendar) &&
                    state.status == RepoState.Status.READY && state.activeSpace != null
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(
                    Modifier.weight(1f).then(if (showNav) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                ) {
                val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
                AnimatedContent(
                    targetState = state.screen,
                    transitionSpec = { screenTransition(slidePx) },
                    label = "screen",
                ) { screen ->
                when (screen) {
                    Screen.Tasks -> {
                        TaskListScreen(
                            state = state,
                            snackbar = snackbar,
                            onPickVault = { picker.launch(null) },
                            onRescan = { vm.rescan(force = true) },
                            onRefresh = container.sync::refresh,
                            refreshing = syncState.busy,
                            onOpenSpaces = vm::openSpaces,
                            onEditSpace = vm::openSpaceEdit,
                            onSelectSpace = vm::selectSpace,
                            onToggle = vm::toggle,
                            onDelete = vm::delete,
                            onOpenTask = vm::openEdit,
                            onCreate = vm::openCreate,
                            onOpenReminders = vm::openReminders,
                            onOpenSync = vm::openSync,
                            onGroupByCategory = vm::setGroupByCategory,
                            onReorderTasks = vm::reorderTasks,
                            onReorderCategories = vm::reorderCategories,
                            onExportCalendar = { exportCalendar(vm) },
                        )
                    }
                    Screen.Sync -> {
                        SyncScreen(
                            state = syncState,
                            onBack = vm::back,
                            onSave = container.sync::save,
                            onTest = container.sync::test,
                            onCheck = container.sync::check,
                            onSync = container.sync::sync,
                        )
                    }
                    Screen.Calendar -> CalendarScreen(
                        state = state,
                        snackbar = snackbar,
                        onSelectSpace = vm::selectSpace,
                        onOpenSpaces = vm::openSpaces,
                        onToggle = vm::toggle,
                        onOpenTask = vm::openEdit,
                        onCreateOn = vm::openCreateOn,
                    )
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
                AnimatedVisibility(visible = showNav, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    AppNavBar(calendarSelected = state.screen == Screen.Calendar, onTasks = vm::back, onCalendar = vm::openCalendar)
                }
                }

                // One editor sheet for every screen that creates/edits tasks (list and calendar).
                val editor = state.editor
                val editorSpace = state.activeSpace
                if (editor != null && editorSpace != null && (state.screen == Screen.Tasks || state.screen == Screen.Calendar)) {
                    TaskEditorSheet(
                        editor = editor,
                        space = editorSpace,
                        categories = state.categories,
                        saving = state.saving,
                        onSave = vm::saveEditor,
                        onCategoryColor = vm::setCategoryColor,
                        onDismiss = vm::closeEditor,
                    )
                }
                } // CompositionLocalProvider
            }
        }
    }

    /**
     * With the system refresh rate on "dynamic" (60–120 Hz) the display driver picks the rate from what the app asks for,
     * and an app that asks for nothing is drawn at 60 Hz, which shows up as lag while scrolling and dragging. Ask for the
     * panel's highest mode at its current resolution; the system still lowers it itself on battery saver or thermal limits.
     */
    private fun requestHighRefreshRate() {
        val current = display?.mode ?: return
        val best = display?.supportedModes.orEmpty()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
            preferredRefreshRate = best.refreshRate
        }
        if (Build.VERSION.SDK_INT >= 35) window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
    }

    /** Coming tasks → one .ics file → share sheet (Apple Calendar opens it from Files/AirDrop/Mail; re-import updates, never duplicates). */
    private fun exportCalendar(vm: MainViewModel) {
        lifecycleScope.launch {
            val export = vm.buildCalendarExport()
            if (export == null) {
                vm.message("No coming tasks to export")
                return@launch
            }
            val uri = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "VaultTasks.ics")
                file.writeText(export.first, Charsets.UTF_8)
                FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            }
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/calendar")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "VaultTasks · ${export.second} coming tasks")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Export ${export.second} coming tasks"))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AlarmIntents.readOpenRequest(intent)?.let { openRequest = it }
    }

    private companion object {
        /** Ask for the notification permission once per process; the Reminders screen handles everything after that. */
        var askedForNotifications = false
    }
}
