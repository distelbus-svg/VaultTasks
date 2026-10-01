package app.vaulttasks.ui.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
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
private val LEAD_OPTIONS = listOf(0, 5, 10, 15, 30, 60)

/**
 * Create/edit sheet (spec §7.2). Drafts live in rememberSaveable, so rotation or a process restart while the
 * sheet is open does not lose what was typed. The time field is only enabled once a date is set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    editor: Editor,
    space: Space,
    saving: Boolean,
    onSave: (TaskFields, String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val task = (editor as? Editor.Edit)?.task
    var description by rememberSaveable(editor) { mutableStateOf(task?.description.orEmpty()) }
    var dateEpoch by rememberSaveable(editor) { mutableStateOf<Long?>(task?.dueDate?.toEpochDay()) }
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
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
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
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }) { Text(date?.format(dateFmt) ?: "Due date") }
                if (date == null) {
                    AssistChip(onClick = { dateEpoch = LocalDate.now().toEpochDay() }, label = { Text("Today") })
                    AssistChip(onClick = { dateEpoch = LocalDate.now().plusDays(1).toEpochDay() }, label = { Text("Tomorrow") })
                } else {
                    TextButton(onClick = {
                        dateEpoch = null
                        minutes = null // no date → no time (spec §4.1)
                    }) { Text("Clear") }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showTime = true }, enabled = date != null) { Text(time?.format(timeFmt) ?: "Due time") }
                if (time != null) TextButton(onClick = { minutes = null }) { Text("Clear") }
            }

            if (date != null) {
                Text("Remind me", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LEAD_OPTIONS.forEach { m ->
                        FilterChip(
                            selected = lead == m,
                            onClick = { lead = m },
                            label = { Text(if (m == 0) "At time" else if (m == 60) "1 h before" else "$m min before") },
                        )
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
                    onClick = { onSave(TaskFields(description.trim(), date, if (date != null) time else null), file, if (date != null) lead else 0) },
                    enabled = canSave,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(if (saving) "Saving…" else "Save") }
            }
        }
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
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("File: $selected ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            files.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f) },
                    onClick = {
                        open = false
                        onSelect(f)
                    },
                )
            }
        }
    }
}
