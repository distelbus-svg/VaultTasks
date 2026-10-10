package app.vaulttasks.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskCalendar
import app.vaulttasks.domain.TaskState
import app.vaulttasks.ui.components.AppSnackbarHost
import app.vaulttasks.ui.components.Glyph
import app.vaulttasks.ui.components.GlyphIcon
import app.vaulttasks.ui.UiState
import app.vaulttasks.ui.tasks.CheckCircle
import app.vaulttasks.ui.tasks.SpaceSwitcher
import app.vaulttasks.ui.tasks.StrikeText
import app.vaulttasks.ui.tasks.TaskKeys
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayHeaderFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")


private fun monthIndexOf(d: LocalDate) = d.year * 12 + d.monthValue - 1

/** Direction-aware slide + fade, shared by the month title and the grid so they move as one. */
private fun monthTransition(): AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
    val dir = if (targetState > initialState) 1 else -1
    (slideInHorizontally(spring<IntOffset>(stiffness = Spring.StiffnessMediumLow)) { it * dir / 4 } + fadeIn(tween(220)))
        .togetherWith(
            slideOutHorizontally(spring<IntOffset>(stiffness = Spring.StiffnessMediumLow)) { -it * dir / 4 } + fadeOut(tween(120)),
        )
}

/**
 * Calendar as a top-level destination (bottom bar). The grid selects a day; that day's tasks show right below it
 * (no modal sheet), and the FAB creates a task pre-filled with the selected day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    state: UiState,
    snackbar: SnackbarHostState,
    onSelectSpace: (String) -> Unit,
    onOpenSpaces: () -> Unit,
    onToggle: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
    onCreateOn: (LocalDate) -> Unit,
) {
    val locale = Locale.getDefault()
    val firstDow = WeekFields.of(locale).firstDayOfWeek
    val today = LocalDate.now()
    // Plain numbers so rotation / process restart keep the month and the selected day.
    var monthIndex by rememberSaveable { mutableStateOf(monthIndexOf(today)) }
    var selectedEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    val canCreate = state.activeSpace?.creationFile != null

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { if (state.activeSpace != null) SpaceSwitcher(state, onSelectSpace, onOpenSpaces) else Text("Calendar") },
                actions = {
                    if (monthIndex != monthIndexOf(today) || selected != today) {
                        TextButton(onClick = {
                            monthIndex = monthIndexOf(today)
                            selectedEpoch = today.toEpochDay()
                        }) { Text("Today") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (canCreate) {
                ExtendedFloatingActionButton(
                    onClick = { onCreateOn(selected) },
                    icon = { GlyphIcon(Glyph.Plus) },
                    text = { Text("New task") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MonthHeader(monthIndex, locale, onPrev = { monthIndex-- }, onNext = { monthIndex++ })
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).pointerInput(Unit) {
                    // Swipe left = next month, right = previous.
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (dx < -120f) monthIndex++ else if (dx > 120f) monthIndex-- },
                        onHorizontalDrag = { _, delta -> dx += delta },
                    )
                },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                AnimatedContent(targetState = monthIndex, transitionSpec = monthTransition(), label = "month") { idx ->
                    val month = YearMonth.of(idx / 12, idx % 12 + 1)
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            for (i in 0 until 7) {
                                Text(
                                    firstDow.plus(i.toLong()).getDisplayName(TextStyle.SHORT, locale),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                                )
                            }
                        }
                        TaskCalendar.gridDays(month, firstDow).chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth()) {
                                week.forEach { date ->
                                    DayCell(
                                        date = date,
                                        inMonth = date.month == month.month,
                                        today = today,
                                        selected = date == selected,
                                        tasks = state.calendar[date].orEmpty(),
                                        onClick = { selectedEpoch = date.toEpochDay() },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val keys = remember(state.calendar) { TaskKeys(state.calendar.values.flatten()) }
            Agenda(
                keys = keys,
                date = selected,
                today = today,
                // Read from state, so toggling a task here updates it in place.
                tasks = state.calendar[selected].orEmpty(),
                onToggle = onToggle,
                onOpen = onOpenTask,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthHeader(monthIndex: Int, locale: Locale, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(targetState = monthIndex, modifier = Modifier.weight(1f), transitionSpec = monthTransition(), label = "title") { idx ->
            val m = YearMonth.of(idx / 12, idx % 12 + 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(m.month.getDisplayName(TextStyle.FULL, locale), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    " ${m.year}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        FilledTonalIconButton(onClick = onPrev) { GlyphIcon(Glyph.Left) }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = onNext) { GlyphIcon(Glyph.Right) }
    }
}

/** Selection morphs the cell from a rounded square to a circle with a spring; dots show up to three tasks. */
@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    today: LocalDate,
    selected: Boolean,
    tasks: List<Task>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isToday = date == today
    val container by animateColorAsState(if (selected) colors.primary else Color.Transparent, label = "cellColor")
    val radius by animateFloatAsState(
        targetValue = if (selected) 50f else 30f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "cellRadius",
    )
    val shape = RoundedCornerShape(percent = radius.roundToInt().coerceIn(0, 50))
    val textColor = when {
        selected -> colors.onPrimary
        isToday -> colors.primary
        inMonth -> colors.onSurface
        else -> colors.onSurfaceVariant.copy(alpha = 0.45f)
    }
    val open = tasks.count { it.state == TaskState.OPEN }
    val dotColor = when {
        selected -> colors.onPrimary
        open > 0 && date < today -> colors.error
        open > 0 -> colors.primary
        else -> colors.outline
    }
    Box(
        modifier.padding(2.dp).clip(shape).background(container)
            .then(if (isToday && !selected) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isToday || selected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
            )
            Row(Modifier.height(6.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(minOf(tasks.size, 3)) { Box(Modifier.size(4.dp).background(dotColor, CircleShape)) }
            }
        }
    }
}

@Composable
private fun Agenda(
    keys: TaskKeys,
    date: LocalDate,
    today: LocalDate,
    tasks: List<Task>,
    onToggle: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = tasks.count { it.state == TaskState.OPEN }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(date.format(dayHeaderFmt), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (tasks.isEmpty()) "Nothing planned" else "$open open · ${tasks.size - open} done",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tasks, key = { keys[it] }) { t ->
                AgendaCard(t, overdue = date < today && t.state == TaskState.OPEN, onToggle, onOpen, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun AgendaCard(task: Task, overdue: Boolean, onToggle: (Task) -> Unit, onOpen: (Task) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val done = task.state == TaskState.DONE
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (overdue) colors.errorContainer.copy(alpha = 0.5f) else colors.surfaceContainer)
            .clickable { onOpen(task) }
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(checked = done, enabled = task.completable, onToggle = { onToggle(task) })
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            StrikeText(task.description, done, MaterialTheme.typography.bodyLarge, FontWeight.Medium)
            val meta = buildList {
                task.dueTime?.let { add("⏰ " + it.format(timeFmt)) }
                task.priority?.let { add(it.emoji) }
                if (task.isRecurring) add("🔁 " + app.vaulttasks.domain.Recurrences.label(task.recurrence.orEmpty()) + if (task.completable || done) "" else " · complete in Obsidian")
                add(task.id.path.substringAfterLast('/').removeSuffix(".md"))
            }
            Text(meta.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
