package app.vaulttasks.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.vaulttasks.data.settings.SettingsStore
import app.vaulttasks.data.vault.SafVaultFileSystem
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.vault.FileScanner
import app.vaulttasks.domain.vault.ScannedFile
import app.vaulttasks.domain.vault.VaultWriter
import app.vaulttasks.domain.vault.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class RepoState(
    val status: Status = Status.LOADING,
    val vaultName: String? = null,
    val files: Map<String, ScannedFile> = emptyMap(),
    val scanning: Boolean = false,
    /** Files that could not be read on the last scan (mid-write, invalid UTF-8, I/O error). */
    val unreadable: List<String> = emptyList(),
    val error: String? = null,
) {
    enum class Status { LOADING, NO_VAULT, PERMISSION_LOST, READY }
}

sealed interface EditOutcome {
    data object Done : EditOutcome
    data object Stale : EditOutcome
    data class Failed(val message: String) : EditOutcome
}

/**
 * Single source of truth for vault contents. Phase 2 keeps scan results in memory; the Room cache arrives
 * with spaces (Phase 3). Scans and writes are serialized by [opMutex] so a scan can never overwrite a
 * fresher write result.
 */
class VaultRepository(
    context: Context,
    private val settings: SettingsStore,
) {
    private val resolver = context.applicationContext.contentResolver
    private val _state = MutableStateFlow(RepoState())
    val state: StateFlow<RepoState> = _state.asStateFlow()

    private val opMutex = Mutex()
    private var scanner: FileScanner? = null
    private var writer: VaultWriter? = null
    private var globalFilter: String = ""

    suspend fun load() = opMutex.withLock {
        globalFilter = settings.globalFilter.first()
        val stored = settings.vaultUri.first()
        if (stored == null) {
            _state.value = RepoState(status = RepoState.Status.NO_VAULT)
        } else if (attach(Uri.parse(stored))) {
            scanLocked(force = true)
        }
    }

    suspend fun setVault(uri: Uri) = opMutex.withLock {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        settings.setVaultUri(uri.toString())
        if (attach(uri)) scanLocked(force = true)
    }

    suspend fun rescan(force: Boolean = false) = opMutex.withLock {
        if (scanner != null) scanLocked(force)
    }

    suspend fun setDone(task: Task, done: Boolean): EditOutcome = opMutex.withLock {
        val w = writer ?: return@withLock EditOutcome.Failed("No vault selected")
        val doneDate = if (done) LocalDate.now() else null // completion-date setting arrives with Settings (default on)
        when (val r = withContext(Dispatchers.IO) { w.setDone(task, done, doneDate) }) {
            is WriteResult.Ok -> {
                _state.update { it.copy(files = it.files + (task.id.path to r.file)) }
                EditOutcome.Done
            }
            WriteResult.Stale -> {
                scanLocked(force = false)
                EditOutcome.Stale
            }
            WriteResult.Missing -> {
                scanLocked(force = false)
                EditOutcome.Failed("File no longer exists")
            }
            is WriteResult.Failed -> EditOutcome.Failed(r.cause.message ?: r.cause.javaClass.simpleName)
        }
    }

    // ---- internals (call with opMutex held) --------------------------------------------------------------

    /** Builds the file system for [uri] if the persisted permission is intact; otherwise flags PERMISSION_LOST. */
    private suspend fun attach(uri: Uri): Boolean {
        val granted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!granted) {
            scanner = null
            writer = null
            _state.value = RepoState(status = RepoState.Status.PERMISSION_LOST)
            return false
        }
        val fs = SafVaultFileSystem(resolver, uri)
        scanner = FileScanner(fs) { globalFilter }
        writer = VaultWriter(fs) { globalFilter }
        val name = withContext(Dispatchers.IO) { fs.rootName() }
        _state.value = RepoState(status = RepoState.Status.READY, vaultName = name)
        return true
    }

    private suspend fun scanLocked(force: Boolean) {
        val sc = scanner ?: return
        _state.update { it.copy(scanning = true, error = null) }
        try {
            val result = withContext(Dispatchers.IO) { sc.scan(_state.value.files, force = force) }
            _state.update { it.copy(files = result.files, unreadable = result.unreadable, scanning = false) }
        } catch (e: SecurityException) {
            scanner = null
            writer = null
            _state.value = RepoState(status = RepoState.Status.PERMISSION_LOST)
        } catch (e: Exception) {
            _state.update { it.copy(scanning = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }
}
