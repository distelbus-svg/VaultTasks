package app.vaulttasks.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.vaulttasks.data.RepoState.Status
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskState
import app.vaulttasks.ui.UiState
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    state: UiState,
    snackbar: SnackbarHostState,
    onPickVault: () -> Unit,
    onRescan: () -> Unit,
    onToggle: (Task) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.vaultName ?: "VaultTasks") },
                actions = {
                    if (state.status == Status.READY) {
                        TextButton(onClick = onRescan, enabled = !state.scanning) { Text("Rescan") }
                        TextButton(onClick = onPickVault) { Text("Folder") }
                    }
                },
            )
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
                Status.READY -> Ready(state, onToggle)
            }
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

@Composable
private fun Ready(state: UiState, onToggle: (Task) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        state.error?.let { Text("Scan failed: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (state.unreadable.isNotEmpty()) {
            Text(
                "Skipped (unreadable or changing): ${state.unreadable.joinToString()}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.files.isEmpty() && !state.scanning) {
            Centered { Text("No tasks found in this vault.") }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                state.files.forEach { file ->
                    item(key = "h:${file.path}") {
                        Text(
                            file.path,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(file.tasks, key = { "${it.id.path}|${it.id.occurrence}|${it.id.normalizedText}" }) { task ->
                        TaskRow(task, onToggle)
                    }
                    item(key = "d:${file.path}") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, onToggle: (Task) -> Unit) {
    val done = task.state == TaskState.DONE
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
            }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
