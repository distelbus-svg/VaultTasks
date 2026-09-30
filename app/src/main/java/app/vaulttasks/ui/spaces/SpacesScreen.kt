package app.vaulttasks.ui.spaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vaulttasks.domain.Space

/** Spec §6: create / reorder / delete spaces. Tapping one opens [SpaceEditScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesScreen(
    spaces: List<Space>,
    activeId: String?,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onEdit: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Spaces") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newName = ""
                    adding = true
                },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                text = { Text("New space") },
            )
        },
    ) { padding ->
        if (spaces.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("No spaces yet.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A space groups vault files by topic. Create one, then choose which files it reads and writes tasks to.",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(spaces, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(s.id) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val n = s.files.size
                            Text(
                                (if (n == 1) "1 file" else "$n files") + if (s.id == activeId) " · active" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onMove(s.id, -1) }, enabled = s.id != spaces.first().id) { Text("▲") }
                        TextButton(onClick = { onMove(s.id, 1) }, enabled = s.id != spaces.last().id) { Text("▼") }
                        TextButton(onClick = { deleteId = s.id }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New space") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        adding = false
                        onAdd(newName)
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }

    deleteId?.let { id ->
        val name = spaces.firstOrNull { it.id == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete “$name”?") },
            text = { Text("Only the space is removed. Your vault files are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteId = null
                    onDelete(id)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Name, the vault files this space reads and writes (spec §6), and the default file for new tasks.
 * Edits are a draft until Save, so one DataStore write and one rescan cover the whole change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceEditScreen(
    space: Space,
    vaultFiles: List<String>,
    onBack: () -> Unit,
    onSave: (name: String, files: List<String>, defaultFile: String?) -> Unit,
) {
    var name by rememberSaveable(space.id) { mutableStateOf(space.name) }
    var selected by rememberSaveable(space.id) { mutableStateOf(ArrayList(space.files)) }
    var default by rememberSaveable(space.id) { mutableStateOf(space.defaultFile) }
    var query by rememberSaveable(space.id) { mutableStateOf("") }

    // Files of the space that vanished from the vault stay visible (flagged) so they can be unticked.
    val all = (vaultFiles + selected).distinct().sorted()
    val shown = if (query.isBlank()) all else all.filter { it.contains(query.trim(), ignoreCase = true) }
    val effectiveDefault = default?.takeIf { it in selected } ?: selected.firstOrNull()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Edit space") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
                actions = {
                    TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, selected, default) }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter files") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                "${selected.size} selected. New tasks go to the default file (●), or the first selected file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            if (all.isEmpty()) {
                Text("No markdown files found in the vault.", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it }) { path ->
                    val isSelected = path in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = ArrayList(if (isSelected) selected - path else selected + path)
                        }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                selected = ArrayList(if (isSelected) selected - path else selected + path)
                            },
                        )
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(path, style = MaterialTheme.typography.bodyMedium)
                            if (path !in vaultFiles) {
                                Text("missing from vault", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (isSelected) {
                            RadioButton(selected = path == effectiveDefault, onClick = { default = path })
                        }
                    }
                }
            }
        }
    }
}
