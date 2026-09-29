package app.vaulttasks.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vaulttasks.VaultTasksApp
import app.vaulttasks.ui.tasks.TaskListScreen
import app.vaulttasks.ui.theme.VaultTasksTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VaultTasksApp).container
        setContent {
            VaultTasksTheme {
                val vm: MainViewModel = viewModel(factory = viewModelFactory { initializer { MainViewModel(container.vault) } })
                val state by vm.uiState.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    if (uri != null) vm.onVaultPicked(uri)
                }

                LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
                // Spec §5: rescan on app foreground.
                LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.rescan() }

                TaskListScreen(
                    state = state,
                    snackbar = snackbar,
                    onPickVault = { picker.launch(null) },
                    onRescan = { vm.rescan(force = true) },
                    onToggle = vm::toggle,
                )
            }
        }
    }
}
