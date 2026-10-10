package app.vaulttasks.ui.tasks

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.vaulttasks.data.RepoState.Status
import app.vaulttasks.domain.Categories
import app.vaulttasks.ui.components.LocalCategoryColors
import app.vaulttasks.ui.components.onCategoryColor
import app.vaulttasks.domain.Recurrences
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskGroups
import app.vaulttasks.domain.TaskId
import app.vaulttasks.domain.TaskState
import app.vaulttasks.ui.UiState
import app.vaulttasks.ui.components.AppDropdownMenu
import app.vaulttasks.ui.components.AppMenuItem
import app.vaulttasks.ui.components.AppSnackbarHost
import app.vaulttasks.ui.components.Glyph
import app.vaulttasks.ui.components.GlyphIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** How long a just-completed task stays in its section, struck through, before it leaves. */
private const val LINGER_MS = 2_000L

/**
 * List identity that survives completing or reopening a task. TaskId.normalizedText is the whole line, so it changes with
 * the checkbox mark and the done date; for list keys (and the cross-out linger) both are stripped and identical lines are
 * numbered in file order. A toggled task keeps its key and animates in place instead of vanishing and reappearing.
 */
internal class TaskKeys(tasks: Collection<Task>) {
    private val keys = HashMap<TaskId, String>()

    init {
        tasks.groupBy { it.id.path to stable(it) }.forEach { (k, group) ->
            group.sortedBy { it.lineIndex }.forEachIndexed { i, t -> keys[t.id] = "${k.first}|${k.second}|$i" }
        }
    }

    operator fun get(t: Task): String = keys[t.id] ?: "${t.id.path}|${t.id.normalizedText}|${t.id.occurrence}"

    private fun stable(t: Task) = t.id.normalizedText.replaceFirst(BOX, "[ ]").replace(DONE_DATE, "").trim()

    private companion object {
        val BOX = Regex("\\[[^\\]]\\]")
        val DONE_DATE = Regex("\\s*✅\\s*\\d{4}-\\d{2}-\\d{2}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    state: UiState,
    snackbar: SnackbarHostState,
    onPickVault: () -> Unit,
    onRescan: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    onOpenSpaces: () -> Unit,
    onEditSpace: (String) -> Unit,
    onSelectSpace: (String) -> Unit,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
    onCreate: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenSync: () -> Unit,
    onGroupByCategory: (Boolean) -> Unit,
    onReorderTasks: (List<String>) -> Unit,
    onReorderCategories: (List<String>) -> Unit,
    onExportCalendar: () -> Unit,
) {
    var showCategoryOrder by rememberSaveable { mutableStateOf(false) }
    if (showCategoryOrder) {
        val rank = state.categoryOrder.withIndex().associate { it.value to it.index }
        CategoryOrderDialog(
            categories = state.categories.sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { it.lowercase() })),
            onSave = { onReorderCategories(it); showCategoryOrder = false },
            onDismiss = { showCategoryOrder = false },
        )
    }
    val ready = state.status == Status.READY
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            val bg = MaterialTheme.colorScheme.background
            TopAppBar(
                title = {
                    if (ready && state.activeSpace != null) {
                        SpaceSwitcher(state, onSelectSpace, onOpenSpaces)
                    } else {
                        Text(state.vaultName ?: "VaultTasks", style = MaterialTheme.typography.headlineSmall)
                    }
                },
                actions = {
                    if (ready) OverflowMenu(state.scanning, state.groupByCategory, onGroupByCategory, { showCategoryOrder = true }, onExportCalendar, onRescan, onOpenSpaces, onOpenReminders, onOpenSync, onPickVault)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, scrolledContainerColor = bg),
            )
        },
        floatingActionButton = {
            if (ready && state.activeSpace?.creationFile != null) {
                ExtendedFloatingActionButton(
                    onClick = onCreate,
                    icon = { GlyphIcon(Glyph.Plus) },
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
                Status.READY -> Ready(state, onRefresh, refreshing, onOpenSpaces, onEditSpace, onToggle, onDelete, onOpenTask, onOpenReminders, onReorderTasks)
            }
        }
    }
}

/** The space name as the screen title; tapping it opens the space menu (no arrow, the title itself is the control). */
@Composable
internal fun SpaceSwitcher(state: UiState, onSelect: (String) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            state.activeSpace?.name.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { open = true }.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.spaces.forEach { s ->
                AppMenuItem(s.name, selected = s.id == state.activeSpace?.id, onClick = {
                    open = false
                    onSelect(s.id)
                })
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            AppMenuItem("Manage spaces…", onClick = {
                open = false
                onManage()
            })
        }
    }
}

@Composable
private fun OverflowMenu(
    scanning: Boolean,
    byCategory: Boolean,
    onGroupByCategory: (Boolean) -> Unit,
    onReorderCategories: () -> Unit,
    onExport: () -> Unit,
    onRescan: () -> Unit,
    onSpaces: () -> Unit,
    onReminders: () -> Unit,
    onSync: () -> Unit,
    onPickVault: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { GlyphIcon(Glyph.More) }
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppMenuItem(if (byCategory) "Group by date" else "Group by category", onClick = { open = false; onGroupByCategory(!byCategory) })
            if (byCategory) AppMenuItem("Reorder categories", onClick = { open = false; onReorderCategories() })
            AppMenuItem("Export coming tasks (.ics)", onClick = { open = false; onExport() })
            AppMenuItem("Rescan vault", enabled = !scanning, onClick = { open = false; onRescan() })
            AppMenuItem("Manage spaces", onClick = { open = false; onSpaces() })
            AppMenuItem("Reminders", onClick = { open = false; onReminders() })
            AppMenuItem("WebDAV sync", onClick = { open = false; onSync() })
            AppMenuItem("Change vault folder", onClick = { open = false; onPickVault() })
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

/** Friendly empty state: a tonal circle with a glyph, a title and one line of help. */
@Composable
private fun EmptyState(title: String, hint: String) {
    Centered {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Check, tint = MaterialTheme.colorScheme.onSecondaryContainer, size = 40.dp) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Ready(
    state: UiState,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    onOpenSpaces: () -> Unit,
    onEditSpace: (String) -> Unit,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
    onOpenReminders: () -> Unit,
    onReorderTasks: (List<String>) -> Unit,
) {
    val space = state.activeSpace
    // The pull indicator belongs to the user's gesture only; a background scan at start shows a thin bar instead.
    var pulled by remember { mutableStateOf(false) }
    val busyNow by rememberUpdatedState(refreshing || state.scanning)
    LaunchedEffect(pulled) {
        if (pulled) {
            delay(400) // let the refresh actually start
            snapshotFlow { busyNow }.first { !it }
            pulled = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        // Spec §8.5: persistent warning while any detectable reminder precondition fails.
        if (state.health?.allOk == false) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { onOpenReminders() }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Reminders may not fire on time. Tap to fix.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                GlyphIcon(Glyph.Right, tint = MaterialTheme.colorScheme.onErrorContainer)
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
                Text("Create a space to choose which vault files hold your tasks.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Button(onClick = onOpenSpaces, modifier = Modifier.padding(top = 16.dp)) { Text("Create a space") }
            }
            space.files.isEmpty() -> Centered {
                Text("“${space.name}” has no files yet.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onEditSpace(space.id) }, modifier = Modifier.padding(top = 16.dp)) { Text("Choose files") }
            }
            else -> Box(Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = pulled,
                    onRefresh = {
                        pulled = true
                        onRefresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // key(space.id): a space switch starts a fresh list, always scrolled to the top.
                key(space.id) {
                    TaskList(
                        state.groups, state.groupByCategory, state.taskOrder, state.categoryOrder, onReorderTasks,
                        state.scanning, showFile = space.files.size > 1, onToggle, onDelete, onOpenTask,
                    )
                }
                }
                if (state.scanning && !pulled) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(CircleShape).align(Alignment.TopCenter))
                }
            }
        }
    }
}

private data class Linger(val section: String, val index: Int, val stamp: Long)

@Composable
private fun TaskList(
    groups: TaskGroups,
    byCategory: Boolean,
    order: List<String>,
    categoryOrder: List<String>,
    onReorder: (List<String>) -> Unit,
    scanning: Boolean,
    showFile: Boolean,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
) {
    var doneExpanded by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // Just-completed tasks stay in their old section (struck through) for LINGER_MS, then leave with an animation.
    val lingering = remember { mutableStateMapOf<String, Linger>() }

    val keys = remember(groups) { TaskKeys(groups.overdue + groups.today + groups.upcoming + groups.noDate + groups.done) }
    // Positions the user dragged tasks to. `live` is the drag in progress (and the last drop until the stored order arrives).
    val live = remember { mutableStateMapOf<String, List<String>>() }
    val stored = remember(order) { order.withIndex().associate { it.value to it.index } }
    fun arrange(sectionKey: String, list: List<Task>): List<Task> {
        val index = live[sectionKey]?.withIndex()?.associate { it.value to it.index } ?: stored
        return list.sortedBy { index[keys[it]] ?: Int.MAX_VALUE } // stable: tasks without a position keep their normal order, after the placed ones
    }
    val sections = if (byCategory) {
        // Open tasks keep their date order (overdue, today, upcoming, undated) inside each category unless dragged.
        val byCat = (groups.overdue + groups.today + groups.upcoming + groups.noDate).groupBy { it.category }
        val rank = categoryOrder.withIndex().associate { it.value to it.index }
        byCat.keys.filterNotNull().sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { it.lowercase() }))
            .map { Triple("cat:$it", "#$it", arrange("cat:$it", byCat.getValue(it))) } +
            listOfNotNull(byCat[null]?.let { Triple("cat:", "No category", arrange("cat:", it)) })
    } else {
        listOf(
            Triple("overdue", "Overdue", arrange("overdue", groups.overdue)),
            Triple("today", "Today", arrange("today", groups.today)),
            Triple("upcoming", "Upcoming", arrange("upcoming", groups.upcoming)),
            Triple("nodate", "No date", arrange("nodate", groups.noDate)),
        )
    }
    val lateIds = remember(groups) { groups.overdue.mapTo(HashSet()) { it.id } }
    val doneByKey = groups.done.associateBy { keys[it] }

    val toggle: (Task) -> Unit = { t ->
        val key = keys[t]
        if (t.state == TaskState.OPEN && !t.isRecurring) {
            val where = sections.firstNotNullOfOrNull { (k, _, list) ->
                list.indexOfFirst { keys[it] == key }.takeIf { it >= 0 }?.let { k to it }
            }
            if (where != null) {
                val stamp = System.nanoTime()
                lingering[key] = Linger(where.first, where.second, stamp)
                scope.launch {
                    delay(LINGER_MS)
                    if (lingering[key]?.stamp == stamp) lingering.remove(key)
                }
            }
        } else {
            lingering.remove(key)
        }
        onToggle(t)
    }

    val empty = groups.isEmpty
    if (empty && lingering.isEmpty()) {
        if (!scanning) EmptyState("All clear", "No tasks here yet. Tap “New task” to add one.")
        return
    }

    fun withLingering(sectionKey: String, list: List<Task>): List<Task> {
        val have = list.mapTo(HashSet()) { keys[it] }
        val extra = lingering.entries
            .filter { it.value.section == sectionKey && it.key !in have }
            .mapNotNull { e -> doneByKey[e.key]?.let { e.value.index to it } }
            .sortedBy { it.first }
        if (extra.isEmpty()) return list
        val out = list.toMutableList()
        extra.forEach { (idx, t) -> out.add(idx.coerceIn(0, out.size), t) }
        return out
    }

    val doneShown = groups.done.filter { !lingering.containsKey(keys[it]) }

    val shown = sections.map { (key, title, list) -> Triple(key, title, withLingering(key, list)) }
    val lazyState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyState) { from, to ->
        // Tasks only move inside their own section (their date / category decides where they belong).
        val a = from.key as? String ?: return@rememberReorderableLazyListState
        val b = to.key as? String ?: return@rememberReorderableLazyListState
        val section = shown.firstOrNull { (_, _, l) -> l.any { keys[it] == a } && l.any { keys[it] == b } }
            ?: return@rememberReorderableLazyListState
        val ks = section.third.map { keys[it] }.toMutableList()
        ks.add(ks.indexOf(b), ks.removeAt(ks.indexOf(a)))
        live[section.first] = ks
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = lazyState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shown.forEach { (key, title, list) ->
            taskSection(
                key, title, list, if (key == "overdue") colors.error else colors.primary,
                { t -> t.id in lateIds }, showFile, keys, toggle, onDelete, onOpenTask,
                reorderState, onDragStopped = { live[key]?.let(onReorder) },
            )
        }
        if (doneShown.isNotEmpty()) {
            item(key = "h:done") {
                val rotation by animateFloatAsState(if (doneExpanded) 180f else 0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "doneChevron")
                Row(
                    Modifier.animateItem().padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .clickable { doneExpanded = !doneExpanded }.padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Done · ${doneShown.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    GlyphIcon(Glyph.Down, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(rotation), size = 20.dp)
                }
            }
            if (doneExpanded) {
                itemsIndexed(doneShown, key = { _, t -> keys[t] }) { i, t ->
                    SwipeableTaskRow(t, i, doneShown.size, false, showFile, toggle, onDelete, onOpenTask, Modifier.animateItem(placementSpec = itemSpring))
                }
            }
        }
    }
}

private val itemSpring = spring<IntOffset>(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow, IntOffset.VisibilityThreshold)

private fun LazyListScope.taskSection(
    key: String,
    title: String,
    tasks: List<Task>,
    color: Color,
    isLate: (Task) -> Boolean,
    showFile: Boolean,
    keys: TaskKeys,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
    reorderState: sh.calvin.reorderable.ReorderableLazyListState,
    onDragStopped: () -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "h:$key") {
        Row(
            Modifier.animateItem().fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(
                tasks.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
    itemsIndexed(tasks, key = { _, t -> keys[t] }) { i, t ->
        // The placement animation goes in `animateItemModifier`: ReorderableItem already applies its own animateItem() to every
        // non-dragged row, so a second one passed via `modifier` made each row animate twice (visible as a jump at the drop).
        ReorderableItem(reorderState, key = keys[t], animateItemModifier = Modifier.animateItem(placementSpec = itemSpring)) { dragging ->
            val haptics = LocalHapticFeedback.current
            val lift by animateFloatAsState(if (dragging) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "dragLift")
            SwipeableTaskRow(
                t, i, tasks.size, isLate(t), showFile, onToggle, onDelete, onOpenTask,
                Modifier.graphicsLayer { scaleX = 1f + 0.02f * lift; scaleY = 1f + 0.02f * lift; shadowElevation = 12f * lift }
                    .longPressDraggableHandle(
                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragStopped = onDragStopped,
                    ),
            )
        }
    }
}

/** Expressive "segmented list": outer corners large, inner corners small; corners animate as neighbors come and go. */
@Composable
internal fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val motion = spring<androidx.compose.ui.unit.Dp>(stiffness = Spring.StiffnessMediumLow)
    val top by animateDpAsState(if (index == 0) 24.dp else 6.dp, motion, label = "cornerTop")
    val bottom by animateDpAsState(if (index == count - 1) 24.dp else 6.dp, motion, label = "cornerBottom")
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

/**
 * Swipe right = complete / reopen (recurring tasks advance to their next date), swipe left = delete (with undo).
 *
 * Mail-style visuals: the card keeps its rounded shape and slides over, and the action is its own rounded pill
 * behind it with a small gap, growing with the swipe.
 *
 * The action runs only after the swipe has *settled* and the finger is up, so the user can hesitate and swipe back
 * (a row held past the threshold used to be deleted, and the delete repeated on every list update, via
 * confirmValueChange). Complete springs the card back (the list then updates from state); delete leaves the card
 * swiped away so it simply leaves with the list animation instead of flashing back first.
 */
@Composable
private fun SwipeableTaskRow(
    task: Task,
    index: Int,
    count: Int,
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
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onToggle(current)
                swipe.reset()
            } else {
                onDelete(current)
                // Normally this row is gone (and this effect cancelled) well before; if the delete failed, bring it back.
                delay(1_500)
                swipe.reset()
            }
        }
    }
    val shape = segmentShape(index, count)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.pointerInput(Unit) {
            // Observe only (Initial pass, nothing consumed): is any finger down on this row?
            awaitPointerEventScope {
                while (true) touching = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
            }
        },
        enableDismissFromStartToEnd = task.completable,
        backgroundContent = {
            val offset = runCatching { swipe.requireOffset() }.getOrDefault(0f)
            val gap = with(density) { 6.dp.toPx() }
            val width = (abs(offset) - gap).coerceAtLeast(0f)
            if (width > 0f) {
                val completing = offset > 0f
                val container = if (completing) colors.primaryContainer else colors.errorContainer
                val content = if (completing) colors.onPrimaryContainer else colors.onErrorContainer
                val label = if (!completing) "Delete" else if (task.state == TaskState.DONE) "Reopen" else "Done"
                Box(Modifier.fillMaxSize(), contentAlignment = if (completing) Alignment.CenterStart else Alignment.CenterEnd) {
                    Box(
                        Modifier.width(with(density) { width.toDp() }).fillMaxHeight().clip(shape).background(container),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = content,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true)
                                .alpha((width / with(density) { 64.dp.toPx() }).coerceIn(0f, 1f)),
                        )
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainer)) {
            TaskRow(task, overdue, showFile, onToggle, onOpen)
        }
    }
}

private data class ChipSpec(val text: String, val container: Color, val content: Color)

@Composable
private fun TaskRow(task: Task, overdue: Boolean, showFile: Boolean, onToggle: (Task) -> Unit, onOpen: (Task) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val categoryColors = LocalCategoryColors.current
    val done = task.state == TaskState.DONE
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(task) }.padding(start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(checked = done, enabled = task.completable, onToggle = { onToggle(task) })
        Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StrikeText(Categories.split(task.description).first, done, MaterialTheme.typography.bodyLarge, FontWeight.Medium)
            if (task.notes.isNotBlank()) {
                Text(
                    task.notes.lines().joinToString("\n") { "· $it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val late = overdue && !done
            val chips = buildList {
                task.dueDate?.let { d ->
                    add(
                        ChipSpec(
                            d.format(dateFmt) + (task.dueTime?.let { " " + it.format(timeFmt) } ?: ""),
                            if (late) colors.errorContainer else colors.secondaryContainer,
                            if (late) colors.onErrorContainer else colors.onSecondaryContainer,
                        ),
                    )
                }
                task.priority?.let { add(ChipSpec(it.emoji, colors.surfaceContainerHighest, colors.onSurfaceVariant)) }
                if (task.isRecurring) {
                    val label = "🔁 " + Recurrences.label(task.recurrence.orEmpty()) + if (task.completable || done) "" else " · complete in Obsidian"
                    add(ChipSpec(label, colors.surfaceContainerHighest, colors.onSurfaceVariant))
                }
                task.category?.let { c ->
                    val picked = categoryColors[c]
                    add(
                        if (picked != null) ChipSpec("#$c", Color(picked), onCategoryColor(picked))
                        else ChipSpec("#$c", colors.tertiaryContainer, colors.onTertiaryContainer),
                    )
                }
                if (showFile) add(ChipSpec(task.id.path.substringAfterLast('/').removeSuffix(".md"), colors.surfaceContainerHighest, colors.onSurfaceVariant))
            }
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { c ->
                        Text(
                            c.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = c.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).background(c.container)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Title whose cross-out line is drawn left to right (line by line) when [done] turns true, and retracts on reopen. */
@Composable
internal fun StrikeText(text: String, done: Boolean, style: TextStyle, weight: FontWeight? = null) {
    val colors = MaterialTheme.colorScheme
    val color by animateColorAsState(if (done) colors.onSurfaceVariant else colors.onSurface, tween(300), label = "titleColor")
    val progress by animateFloatAsState(if (done) 1f else 0f, tween(380, easing = FastOutSlowInEasing), label = "strike")
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        fontWeight = weight,
        color = color,
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            if (progress <= 0.001f) return@drawWithContent
            var total = 0f
            for (i in 0 until l.lineCount) total += l.getLineRight(i) - l.getLineLeft(i)
            var remaining = total * progress
            for (i in 0 until l.lineCount) {
                val left = l.getLineLeft(i)
                val seg = minOf(l.getLineRight(i) - left, remaining)
                if (seg <= 0f) break
                val y = (l.getLineTop(i) + l.getLineBottom(i)) / 2f
                drawLine(color, Offset(left, y), Offset(left + seg, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                remaining -= seg
            }
        },
    )
}

/** Round check: the ring fills with a spring and the tick is drawn stroke by stroke, with a small pop in between. */
@Composable
internal fun CheckCircle(checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (checked) colors.primary else Color.Transparent, label = "checkFill")
    val ring by animateColorAsState(if (checked) colors.primary else colors.outline, label = "checkRing")
    val tick by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "checkTick",
    )
    val tickColor = colors.onPrimary
    Box(
        Modifier.size(48.dp).clip(CircleShape).alpha(if (enabled) 1f else 0.38f)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier.size(26.dp).graphicsLayer {
                val pop = 1f + 0.15f * (4f * tick * (1f - tick)).coerceAtLeast(0f)
                scaleX = pop
                scaleY = pop
            },
        ) {
            val s = size.minDimension
            val stroke = 2.dp.toPx()
            drawCircle(fill)
            drawCircle(ring, radius = s / 2f - stroke / 2f, style = Stroke(stroke))
            val p = tick.coerceIn(0f, 1f)
            if (p > 0.02f) {
                val path = Path().apply {
                    moveTo(s * 0.28f, s * 0.52f)
                    lineTo(s * 0.44f, s * 0.68f)
                    lineTo(s * 0.74f, s * 0.34f)
                }
                val seg = Path()
                val measure = PathMeasure().apply { setPath(path, false) }
                measure.getSegment(0f, measure.length * p, seg, true)
                drawPath(seg, tickColor, style = Stroke(s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
