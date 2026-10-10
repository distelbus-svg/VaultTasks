package app.vaulttasks.ui.tasks

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContent
import app.vaulttasks.ui.components.GlyphIcon
import app.vaulttasks.ui.components.Glyph
import app.vaulttasks.ui.components.AppMenuItem
import app.vaulttasks.ui.components.AppDropdownMenu
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import app.vaulttasks.ui.components.CATEGORY_PALETTE
import app.vaulttasks.ui.components.LocalCategoryColors
import app.vaulttasks.ui.components.onCategoryColor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import app.vaulttasks.domain.Categories
import app.vaulttasks.domain.RecurrenceEdit
import app.vaulttasks.domain.Recurrences
import app.vaulttasks.domain.RepeatUnit
import app.vaulttasks.domain.Space
import app.vaulttasks.domain.TaskFields
import app.vaulttasks.ui.Editor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** "Remind me before" choices in minutes; 0 = at the due time. */
private val LEAD_OPTIONS = listOf(0, 5, 10, 15, 30, 60, 24 * 60, 2 * 24 * 60, 7 * 24 * 60)

private fun leadLabel(m: Int) = when (m) {
    0 -> "At time"
    60 -> "1 h before"
    24 * 60 -> "1 day before"
    2 * 24 * 60 -> "2 days before"
    7 * 24 * 60 -> "1 week before"
    else -> "$m min before"
}

/** Repeat presets: label → Tasks rule. */
private val REPEAT_PRESETS = listOf(
    "Daily" to "every day",
    "Weekdays" to "every weekday",
    "Weekly" to "every week",
    "Monthly" to "every month",
    "Yearly" to "every year",
)

/**
 * Create/edit sheet (spec §7.2). Drafts live in rememberSaveable, so rotation or a process restart while the
 * sheet is open does not lose what was typed. The time and reminder rows only unfold once a date is set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    editor: Editor,
    space: Space,
    categories: List<String>,
    saving: Boolean,
    onSave: (TaskFields, String, Int) -> Unit,
    onCategoryColor: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val task = (editor as? Editor.Edit)?.task
    // The category is the description's trailing #tag; it has its own control, so the text field shows the title only.
    val split = remember(editor) { Categories.split(task?.description.orEmpty()) }
    var description by rememberSaveable(editor) { mutableStateOf(split.first) }
    var notes by rememberSaveable(editor) { mutableStateOf(task?.notes.orEmpty()) }
    var category by rememberSaveable(editor) { mutableStateOf(split.second) }
    val categoryColors = LocalCategoryColors.current
    var repeat by rememberSaveable(editor) { mutableStateOf(task?.recurrence) }
    var repeatTouched by rememberSaveable(editor) { mutableStateOf(false) }
    var showCategoryDialog by rememberSaveable(editor) { mutableStateOf(false) }
    var showRepeatDialog by rememberSaveable(editor) { mutableStateOf(false) }
    var dateEpoch by rememberSaveable(editor) {
        mutableStateOf<Long?>(
            (editor as? Editor.Edit)?.presetDate?.toEpochDay() ?: task?.dueDate?.toEpochDay() ?: (editor as? Editor.Create)?.date?.toEpochDay(),
        )
    }
    var minutes by rememberSaveable(editor) { mutableStateOf<Int?>(task?.dueTime?.let { it.hour * 60 + it.minute }) }
    var file by rememberSaveable(editor) { mutableStateOf(task?.id?.path ?: space.creationFile.orEmpty()) }
    var lead by rememberSaveable(editor) { mutableStateOf((editor as? Editor.Edit)?.leadMinutes ?: 0) }
    var showDate by rememberSaveable(editor) { mutableStateOf(false) }
    var showTime by rememberSaveable(editor) { mutableStateOf(false) }

    val date = dateEpoch?.let { LocalDate.ofEpochDay(it) }
    val time = minutes?.let { LocalTime.of(it / 60, it % 60) }
    val files = (space.files + file).filter { it.isNotEmpty() }.distinct()
    val canSave = description.isNotBlank() && file.isNotEmpty() && !saving
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // New task: type immediately. (Editing an existing task does not pop the keyboard.)
            if (task == null) {
                LaunchedEffect(Unit) {
                    withFrameNanos { } // let the sheet attach the field first
                    runCatching { focus.requestFocus() }
                    keyboard?.show()
                }
            }
            Text(if (task == null) "New task" else "Edit task", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = description,
                onValueChange = { description = it.replace('\n', ' ') },
                label = { Text("Description") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            // Notes: any number of lines; stored as nested bullets under the task (see domain Notes), so Tasks ignores them.
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Category", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRow {
                PopChip(selected = category == null, label = "None", onClick = { category = null })
                (categories + listOfNotNull(category)).distinct().sortedBy { it.lowercase() }.forEach { c ->
                    PopChip(selected = category == c, label = "#$c", accent = categoryColors[c]?.let { Color(it) }, onClick = { category = c })
                }
                PopChip(selected = false, label = "+ New", onClick = { showCategoryDialog = true })
            }

            // Date: the value swaps with a vertical slide; Today/Tomorrow and Clear trade places with a small pop.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, shape = PillShape) {
                    AnimatedContent(date?.format(dateFmt) ?: "Due date", transitionSpec = valueSwap(), label = "dateValue") { Text(it) }
                }
                AnimatedContent(
                    targetState = date == null,
                    transitionSpec = { (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.85f)).togetherWith(fadeOut(tween(100))) },
                    label = "dateActions",
                ) { noDate ->
                    if (noDate) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { dateEpoch = LocalDate.now().toEpochDay() }, label = { Text("Today") }, shape = PillShape)
                            AssistChip(onClick = { dateEpoch = LocalDate.now().plusDays(1).toEpochDay() }, label = { Text("Tomorrow") }, shape = PillShape)
                        }
                    } else {
                        TextButton(onClick = {
                            dateEpoch = null
                            minutes = null // no date → no time (spec §4.1)
                        }) { Text("Clear") }
                    }
                }
            }

            // Time and reminder only make sense with a date, so they unfold once one is set (and fold away when it is cleared).
            AnimatedVisibility(
                visible = date != null,
                enter = fadeIn(tween(200)) + expandVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(220)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showTime = true }, shape = PillShape) {
                            AnimatedContent(time?.format(timeFmt) ?: "Due time", transitionSpec = valueSwap(), label = "timeValue") { Text(it) }
                        }
                        AnimatedVisibility(
                            visible = time != null,
                            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.85f),
                            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.85f),
                        ) { TextButton(onClick = { minutes = null }) { Text("Clear") } }
                    }
                    Text("Repeat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChipRow {
                        PopChip(selected = repeat == null, label = "Never", onClick = { repeat = null; repeatTouched = true })
                        REPEAT_PRESETS.forEach { (label, rule) ->
                            PopChip(selected = repeat == rule, label = label, onClick = { repeat = rule; repeatTouched = true })
                        }
                        val custom = repeat?.takeIf { r -> REPEAT_PRESETS.none { it.second == r } }
                        PopChip(selected = custom != null, label = custom?.let { Recurrences.label(it) } ?: "Custom…", onClick = { showRepeatDialog = true })
                    }
                    Text("Remind me", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChipRow {
                        LEAD_OPTIONS.forEach { m ->
                            PopChip(
                                selected = lead == m,
                                label = leadLabel(m),
                                onClick = { lead = m },
                            )
                        }
                    }
                }
            }

            if (files.size > 1) {
                FilePicker(files, file) { file = it }
            } else {
                Text(file, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        val recurrence = when {
                            date == null -> RecurrenceEdit.Clear // a repeat needs a date to repeat from
                            !repeatTouched -> RecurrenceEdit.Keep
                            repeat == null -> RecurrenceEdit.Clear
                            else -> RecurrenceEdit.Set(repeat!!)
                        }
                        onSave(
                            TaskFields(Categories.join(description, category), date, if (date != null) time else null, notes, recurrence),
                            file,
                            if (date != null) lead else 0,
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(if (saving) "Saving…" else "Save") }
            }
        }
    }

    if (showCategoryDialog) {
        var input by remember { mutableStateOf("") }
        var picked by remember { mutableStateOf(CATEGORY_PALETTE[5]) }
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("New category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, singleLine = true, label = { Text("Name") },
                        prefix = { Text("#") }, shape = RoundedCornerShape(20.dp),
                    )
                    Text("Color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Two rows of five swatches; the picked one carries a ring.
                    CATEGORY_PALETTE.chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { c ->
                                val chosen = picked == c
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                                        .then(if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                        .clickable { picked = c },
                                    contentAlignment = Alignment.Center,
                                ) { if (chosen) Text("✓", color = onCategoryColor(c), style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                    Categories.clean(input)?.let { name ->
                        Text("#$name", color = onCategoryColor(picked), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(CircleShape).background(Color(picked)).padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = Categories.clean(input) != null, onClick = {
                    category = Categories.clean(input)
                    category?.let { onCategoryColor(it, picked) }
                    showCategoryDialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showCategoryDialog = false }) { Text("Cancel") } },
        )
    }

    if (showRepeatDialog) {
        var count by remember { mutableStateOf("2") }
        var unit by remember { mutableStateOf(RepeatUnit.WEEK) }
        AlertDialog(
            onDismissRequest = { showRepeatDialog = false },
            title = { Text("Repeat every…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = count, onValueChange = { count = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(20.dp),
                    )
                    ChipRow {
                        RepeatUnit.entries.forEach { u -> PopChip(selected = unit == u, label = u.word + "s", onClick = { unit = u }) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = (count.toIntOrNull() ?: 0) >= 1, onClick = {
                    repeat = Recurrences.rule(count.toInt(), unit)
                    repeatTouched = true
                    showRepeatDialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showRepeatDialog = false }) { Text("Cancel") } },
        )
    }

    if (showDate) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        dateEpoch = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = picker) }
    }

    if (showTime) {
        val start = minutes ?: (9 * 60)
        val picker = rememberTimePickerState(initialHour = start / 60, initialMinute = start % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    minutes = picker.hour * 60 + picker.minute
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = picker) },
        )
    }
}

@Composable
private fun FilePicker(files: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Text("File: $selected", maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            GlyphIcon(Glyph.Down, size = 18.dp, modifier = Modifier.padding(start = 6.dp))
        }
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            files.forEach { f ->
                AppMenuItem(f, selected = f == selected, onClick = {
                    open = false
                    onSelect(f)
                })
            }
        }
    }
}

private val PillShape = RoundedCornerShape(16.dp)

/** Value swap: the old text slides up and out, the new one slides up into place. */
private fun <S> valueSwap(): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    (slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeIn(tween(150)))
        .togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(100)))
        .using(SizeTransform(clip = false))
}

/** Option chip whose color cross-fades and which pops slightly when it becomes selected. */
@Composable
private fun PopChip(selected: Boolean, label: String, accent: Color? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when {
            selected -> accent ?: colors.primaryContainer
            accent != null -> accent.copy(alpha = 0.28f).compositeOver(colors.surfaceContainerHigh)
            else -> colors.surfaceContainerHigh
        },
        label = "chipBg",
    )
    val content by animateColorAsState(
        when {
            selected -> accent?.let { onCategoryColor(it.toArgb()) } ?: colors.onPrimaryContainer
            else -> colors.onSurfaceVariant
        },
        label = "chipFg",
    )
    val scale by animateFloatAsState(if (selected) 1.05f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "chipScale")
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.clip(CircleShape).background(container).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * Horizontally scrolling row of chips. The viewport is 8 dp wider than the sheet's content on both sides, so a chip that
 * pops (scales up) at the edge is not clipped by the scroll container, and the first chip still lines up with the text above.
 */
@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .layout { measurable, constraints ->
                val extra = 8.dp.roundToPx()
                val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth + 2 * extra))
                layout(constraints.maxWidth, p.height) { p.place(-extra, 0) }
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
