package app.vaulttasks.ui.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vaulttasks.domain.Space
import app.vaulttasks.ui.components.BackButton
import app.vaulttasks.ui.components.Glyph
import app.vaulttasks.ui.components.GlyphIcon
import app.vaulttasks.ui.tasks.CheckCircle
import app.vaulttasks.ui.tasks.segmentShape

private val FieldShape = RoundedCornerShape(20.dp)

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
    val colors = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Spaces", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, scrolledContainerColor = colors.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newName = ""
                    adding = true
                },
                icon = { GlyphIcon(Glyph.Plus) },
                text = { Text("New space") },
            )
        },
    ) { padding ->
        if (spaces.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("No spaces yet", style = MaterialTheme.typography.titleLarge)
                Text(
                    "A space groups vault files by topic. Create one, then choose which files it reads and writes tasks to.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(spaces, key = { _, s -> s.id }) { i, s ->
                    Row(
                        Modifier.animateItem().fillMaxWidth().clip(segmentShape(i, spaces.size)).background(colors.surfaceContainer)
                            .clickable { onEdit(s.id) }.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val n = s.files.size
                            Text(
                                (if (n == 1) "1 file" else "$n files") + if (s.id == activeId) " · active" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalIconButton(onClick = { onMove(s.id, -1) }, enabled = s.id != spaces.first().id, modifier = Modifier.size(40.dp)) {
                                GlyphIcon(Glyph.Up, size = 20.dp)
                            }
                            FilledTonalIconButton(onClick = { onMove(s.id, 1) }, enabled = s.id != spaces.last().id, modifier = Modifier.size(40.dp)) {
                                GlyphIcon(Glyph.Down, size = 20.dp)
                            }
                        }
                        TextButton(onClick = { deleteId = s.id }, shape = RoundedCornerShape(14.dp)) {
                            Text("Delete", color = colors.error)
                        }
                    }
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
                    shape = FieldShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                FilledTonalButton(
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
                }) { Text("Delete", color = colors.error) }
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
    val colors = MaterialTheme.colorScheme

    // Files of the space that vanished from the vault stay visible (flagged) so they can be unticked.
    val all = (vaultFiles + selected).distinct().sorted()
    val shown = if (query.isBlank()) all else all.filter { it.contains(query.trim(), ignoreCase = true) }
    val effectiveDefault = default?.takeIf { it in selected } ?: selected.firstOrNull()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Edit space", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    FilledTonalButton(enabled = name.isNotBlank(), onClick = { onSave(name, selected, default) }, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, scrolledContainerColor = colors.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter files") },
                singleLine = true,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                "${selected.size} selected. New tasks go to the default file, or the first selected file.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (all.isEmpty()) {
                Text("No markdown files found in the vault.", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(shown, key = { _, p -> p }) { i, path ->
                    val isSelected = path in selected
                    val toggle = { selected = ArrayList(if (isSelected) selected - path else selected + path) }
                    Row(
                        Modifier.animateItem().fillMaxWidth().clip(segmentShape(i, shown.size)).background(colors.surfaceContainer)
                            .clickable { toggle() }.padding(start = 6.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CheckCircle(checked = isSelected, enabled = true, onToggle = toggle)
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(path, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (path !in vaultFiles) {
                                Text("missing from vault", style = MaterialTheme.typography.bodySmall, color = colors.error)
                            }
                        }
                        if (isSelected) {
                            val isDefault = path == effectiveDefault
                            Box(
                                Modifier.clip(CircleShape)
                                    .background(if (isDefault) colors.primaryContainer else colors.surfaceContainerHighest)
                                    .clickable { default = path }.padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    if (isDefault) "Default" else "Make default",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isDefault) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
