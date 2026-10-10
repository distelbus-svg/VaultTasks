package app.vaulttasks.ui.sync

import app.vaulttasks.ui.components.BackButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.vaulttasks.data.sync.SyncUiState
import app.vaulttasks.data.sync.WebDavConfig
import app.vaulttasks.domain.sync.FileStatus
import app.vaulttasks.domain.sync.FileSyncState
import app.vaulttasks.domain.sync.SyncReport

/** Spec §9: server setup plus explicit Check / Pull / Push. Nothing here runs unless a button is tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    state: SyncUiState,
    onBack: () -> Unit,
    onSave: (WebDavConfig) -> Unit,
    onTest: (WebDavConfig) -> Unit,
    onCheck: () -> Unit,
    onSync: () -> Unit,
) {
    val saved = state.config
    var url by rememberSaveable { mutableStateOf(saved?.url.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(saved?.user.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(saved?.password.orEmpty()) }
    var folder by rememberSaveable { mutableStateOf(saved?.baseFolder ?: "Vault") }
    val form = WebDavConfig(url, user, password, folder)
    val http = url.startsWith("http://")
    val formOk = (http || url.startsWith("https://")) && user.isNotBlank()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("WebDAV sync") },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Server URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth(), label = { Text("Remote folder") }, singleLine = true,
                supportingText = { Text("Files are stored here, with the same paths as in the vault") })
            if (url.isNotBlank() && !formOkScheme(url)) {
                Text("The URL must start with http:// or https://.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (http) {
                Text("http:// sends the password unencrypted. Only use it on a network you trust.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(form) }, enabled = formOk && !state.busy) { Text("Save") }
                OutlinedButton(onClick = { onTest(form) }, enabled = formOk && !state.busy) { Text("Test connection") }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Files in your spaces", style = MaterialTheme.typography.titleMedium)
            val ready = saved != null && !state.busy
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCheck, enabled = ready) { Text("Check") }
                Button(onClick = onSync, enabled = ready) { Text("Sync now") }
            }
            if (state.busy) Text("Working…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Sync carries changes in both directions. If a file changed on both sides, the newer version replaces the older one. Pull down on the task list to sync. Deletions are never synced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.files.forEach { FileRow(it) }
            state.report?.let { ReportView(it) }
            Text("", Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun FileRow(f: FileSyncState) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(f.path, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            label(f),
            style = MaterialTheme.typography.bodySmall,
            color = when (f.status) {
                FileStatus.IN_SYNC -> MaterialTheme.colorScheme.onSurfaceVariant
                FileStatus.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}

private fun label(f: FileSyncState) = when (f.status) {
    FileStatus.IN_SYNC -> "In sync"
    FileStatus.LOCAL_CHANGED -> "Changed here"
    FileStatus.REMOTE_CHANGED -> "Changed on server"
    FileStatus.BOTH_CHANGED -> "Changed on both sides"
    FileStatus.NOT_ON_SERVER -> "Not on server"
    FileStatus.MISSING_LOCALLY -> "Missing locally"
    FileStatus.DELETED_ON_SERVER -> "Deleted on server"
    FileStatus.GONE -> "Missing everywhere"
    FileStatus.ERROR -> f.detail ?: "Error"
}

@Composable
private fun ReportView(r: SyncReport) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Last run", style = MaterialTheme.typography.titleMedium)
    val none = r.pulled.isEmpty() && r.pushed.isEmpty() && r.skipped.isEmpty() && r.failed.isEmpty()
    if (none) Text("Nothing to do.")
    r.pulled.forEach { Text("↓ Pulled $it") }
    r.pushed.forEach { Text("↑ Pushed $it") }
    r.skipped.forEach { Text("– ${it.path}: ${it.reason}") }
    r.failed.forEach { Text("✗ ${it.path}: ${it.message}", color = MaterialTheme.colorScheme.error) }
}

private fun formOkScheme(url: String) = url.startsWith("http://") || url.startsWith("https://")
