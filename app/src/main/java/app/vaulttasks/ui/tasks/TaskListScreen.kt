package app.vaulttasks.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vaulttasks.data.RepoState.Status
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskGroups
import app.vaulttasks.domain.TaskState
import app.vaulttasks.ui.UiState
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun taskKey(t: Task) = "${t.id.path}|${t.id.occurrence}|${t.id.normalizedText}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    state: UiState,
    snackbar: SnackbarHostState,
    onPickVault: () -> Unit,
    onRescan: () -> Unit,
    onOpenSpaces: () -> Unit,
    onEditSpace: (String) -> Unit,
    onSelectSpace: (String) -> Unit,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
    onCreate: () -> Unit,
    onOpenReminders: () -> Unit,
) {
    val ready = state.status == Status.READY
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (ready && state.activeSpace != null) {
                        SpaceSwitcher(state, onSelectSpace, onOpenSpaces)
                    } else {
                        Text(state.vaultName ?: "VaultTasks")
                    }
                },
                actions = {
                    if (ready) {
                        OverflowMenu(state.scanning, onRescan, onOpenSpaces, onOpenReminders, onPickVault)
                    }
                },
            )
        },
        floatingActionButton = {
            if (ready && state.activeSpace?.creationFile != null) {
                ExtendedFloatingActionButton(
                    onClick = onCreate,
                    icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                    text = { Text("New task") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.status) {
                Status.LOADING -> Centered { CircularProgressIndicator() }
                Status.NO_VAULT -> Centered {
                    Text("Choose the Obsidian vault folder.", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onPickVault, modifier = Modifier.padding(top = 16.dp)) { Text("Choose vault folder") }
                }
                Status.PERMISSION_LOST -> Centered {
                    Text("Access to the vault folder was lost.", style = MaterialTheme.typography.titleMedium)
                    Text("Pick the folder again to continue.", modifier = Modifier.padding(top = 4.dp))
                    Button(onClick = onPickVault, modifier = Modifier.padding(top = 16.dp)) { Text("Choose vault folder") }
                }
                Status.READY -> Ready(state, onRescan, onOpenSpaces, onEditSpace, onToggle, onDelete, onOpenTask, onOpenReminders)
            }
        }
    }
}

@Composable
private fun SpaceSwitcher(state: UiState, onSelect: (String) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clickable { open = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.activeSpace?.name.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(" ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.spaces.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            s.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (s.id == state.activeSpace?.id) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(s.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage spaces…") },
                onClick = {
                    open = false
                    onManage()
                },
            )
        }
    }
}

@Composable
private fun OverflowMenu(scanning: Boolean, onRescan: () -> Unit, onSpaces: () -> Unit, onReminders: () -> Unit, onPickVault: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Rescan vault") },
                enabled = !scanning,
                onClick = {
                    open = false
                    onRescan()
                },
            )
            DropdownMenuItem(
                text = { Text("Manage spaces") },
                onClick = {
                    open = false
                    onSpaces()
                },
            )
            DropdownMenuItem(
                text = { Text("Reminders") },
                onClick = {
                    open = false
                    onReminders()
                },
            )
            DropdownMenuItem(
                text = { Text("Change vault folder") },
                onClick = {
                    open = false
                    onPickVault()
                },
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Ready(
    state: UiState,
    onRescan: () -> Unit,
    onOpenSpaces: () -> Unit,
    onEditSpace: (String) -> Unit,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
    onOpenReminders: () -> Unit,
) {
    val space = state.activeSpace
    Column(Modifier.fillMaxSize()) {
        // Spec §8.5: persistent warning while any detectable reminder precondition fails.
        if (state.health?.allOk == false) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().clickable { onOpenReminders() }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Reminders may not fire on time. Tap to fix.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("›", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        state.error?.let { Text("Scan failed: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (state.unreadable.isNotEmpty()) {
            Text(
                "Skipped (unreadable or changing): ${state.unreadable.joinToString()}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when {
            space == null -> Centered {
                Text("Create a space to choose which vault files hold your tasks.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onOpenSpaces, modifier = Modifier.padding(top = 16.dp)) { Text("Create a space") }
            }
            space.files.isEmpty() -> Centered {
                Text("“${space.name}” has no files yet.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onEditSpace(space.id) }, modifier = Modifier.padding(top = 16.dp)) { Text("Choose files") }
            }
            else -> PullToRefreshBox(isRefreshing = state.scanning, onRefresh = onRescan, modifier = Modifier.fillMaxSize()) {
                TaskList(state.groups, showFile = space.files.size > 1, onToggle, onDelete, onOpenTask)
            }
        }
    }
}

@Composable
private fun TaskList(
    groups: TaskGroups,
    showFile: Boolean,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
) {
    var doneExpanded by rememberSaveable { mutableStateOf(false) }
    if (groups.isEmpty) {
        Centered { Text("No tasks here yet. Tap “New task” to add one.") }
        return
    }
    val error = MaterialTheme.colorScheme.error
    val primary = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        taskSection("overdue", "Overdue", groups.overdue, error, true, showFile, onToggle, onDelete, onOpenTask)
        taskSection("today", "Today", groups.today, primary, false, showFile, onToggle, onDelete, onOpenTask)
        taskSection("upcoming", "Upcoming", groups.upcoming, primary, false, showFile, onToggle, onDelete, onOpenTask)
        taskSection("nodate", "No date", groups.noDate, primary, false, showFile, onToggle, onDelete, onOpenTask)
        if (groups.done.isNotEmpty()) {
            item(key = "h:done") {
                Row(
                    Modifier.fillMaxWidth().clickable { doneExpanded = !doneExpanded }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Done (${groups.done.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (doneExpanded) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (doneExpanded) {
                items(groups.done, key = { taskKey(it) }) { t ->
                    SwipeableTaskRow(t, false, showFile, onToggle, onDelete, onOpenTask, Modifier.animateItem())
                }
            }
        }
    }
}

private fun LazyListScope.taskSection(
    key: String,
    title: String,
    tasks: List<Task>,
    color: Color,
    overdue: Boolean,
    showFile: Boolean,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "h:$key") {
        Text(
            "$title (${tasks.size})",
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(tasks, key = { taskKey(it) }) { t ->
        SwipeableTaskRow(t, overdue, showFile, onToggle, onDelete, onOpenTask, Modifier.animateItem())
    }
}

/**
 * Swipe right = complete (not for recurring tasks), swipe left = delete (with undo).
 *
 * The action runs only after the swipe has *settled* and the finger is up, so the user can hesitate and swipe back
 * (a row held past the threshold used to be deleted, and the delete repeated on every list update, via
 * confirmValueChange). After acting, the row snaps back and the list updates from state.
 */
@Composable
private fun SwipeableTaskRow(
    task: Task,
    overdue: Boolean,
    showFile: Boolean,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(task)
    // Not rememberSwipeToDismissBoxState(): that one is rememberSaveable and can hand a re-added row (Undo of a delete,
    // same list key) its old "swiped away" value, which then deleted the restored task again.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(swipe) {
        snapshotFlow { swipe.settledValue }.collect { value ->
            if (value == SwipeToDismissBoxValue.Settled) return@collect
            snapshotFlow { touching }.first { !it } // never act while the finger is still down
            if (swipe.settledValue != value) return@collect // swiped back in the meantime
            if (value == SwipeToDismissBoxValue.StartToEnd) onToggle(current) else onDelete(current)
            swipe.reset()
        }
    }
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.pointerInput(Unit) {
            // Observe only (Initial pass, nothing consumed): is any finger down on this row?
            awaitPointerEventScope {
                while (true) touching = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
            }
        },
        enableDismissFromStartToEnd = !task.isRecurring && task.state != TaskState.CANCELLED,
        backgroundContent = {
            val completing = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val deleting = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(
                    when {
                        completing -> MaterialTheme.colorScheme.primaryContainer
                        deleting -> MaterialTheme.colorScheme.errorContainer
                        else -> Color.Transparent
                    },
                ).padding(horizontal = 20.dp),
                contentAlignment = if (completing) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (completing) Text(if (task.state == TaskState.DONE) "Reopen" else "Complete", color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (deleting) Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            TaskRow(task, overdue, showFile, onToggle, onOpen)
        }
    }
}

@Composable
private fun TaskRow(task: Task, overdue: Boolean, showFile: Boolean, onToggle: (Task) -> Unit, onOpen: (Task) -> Unit) {
    val done = task.state == TaskState.DONE
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(task) }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = done, onCheckedChange = { onToggle(task) }, enabled = !task.isRecurring)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                task.description,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val meta = buildList {
                task.dueDate?.let { d -> add("📅 " + d.format(dateFmt) + (task.dueTime?.let { " " + it.format(timeFmt) } ?: "")) }
                task.priority?.let { add(it.emoji) }
                if (task.isRecurring) add("🔁 Complete in Obsidian")
                if (showFile) add(task.id.path.substringAfterLast('/').removeSuffix(".md"))
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue && !done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
