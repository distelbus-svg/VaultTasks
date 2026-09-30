package app.vaulttasks.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.vaulttasks.data.settings.SettingsStore
import app.vaulttasks.data.settings.SpacesJson
import app.vaulttasks.data.vault.SafVaultFileSystem
import app.vaulttasks.domain.SpacesData
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskFields
import app.vaulttasks.domain.vault.FileScanner
import app.vaulttasks.domain.vault.RemovedLine
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
    /** Parsed files, limited to files assigned to at least one space (spec §9 scope). */
    val files: Map<String, ScannedFile> = emptyMap(),
    val scanning: Boolean = false,
    /** Files that could not be read on the last scan (mid-write, invalid UTF-8, I/O error). */
    val unreadable: List<String> = emptyList(),
    val error: String? = null,
    val spaces: SpacesData = SpacesData(),
    /** Every `.md` path in the vault as of the last scan (feeds the space file picker). */
    val vaultFiles: List<String> = emptyList(),
) {
    enum class Status { LOADING, NO_VAULT, PERMISSION_LOST, READY }
}

sealed interface EditOutcome {
    data object Done : EditOutcome

    /** A delete succeeded; pass [removed] to [VaultRepository.restore] to undo it. */
    data class Removed(val removed: RemovedLine) : EditOutcome
    data object Stale : EditOutcome
    data class Failed(val message: String) : EditOutcome
}

/**
 * Single source of truth for vault contents and spaces. Scan results live in memory (the files are the source of
 * truth and a rescan of several hundred tasks takes ~1 s); spaces persist in DataStore. Scans and writes are
 * serialized by [opMutex] so a scan can never overwrite a fresher write result.
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
        val spaces = SpacesJson.decode(settings.spacesJson.first())
        _state.update { it.copy(spaces = spaces) }
        val stored = settings.vaultUri.first()
        if (stored == null) {
            reset(RepoState.Status.NO_VAULT)
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

    /** Applies [transform] to the spaces, persists, and rescans if the set of assigned files changed. */
    suspend fun updateSpaces(transform: (SpacesData) -> SpacesData) = opMutex.withLock {
        val before = _state.value.spaces
        val after = transform(before)
        if (after == before) return@withLock
        settings.setSpacesJson(SpacesJson.encode(after))
        _state.update { it.copy(spaces = after) }
        if (after.assignedFiles != before.assignedFiles && scanner != null) scanLocked(force = false)
    }

    suspend fun setDone(task: Task, done: Boolean): EditOutcome {
        val doneDate = if (done) LocalDate.now() else null // completion-date setting arrives with Settings (default on)
        return write { it.setDone(task, done, doneDate) }
    }

    suspend fun create(path: String, fields: TaskFields): EditOutcome = write { it.create(path, fields) }

    suspend fun update(task: Task, fields: TaskFields): EditOutcome = write { it.update(task, fields) }

    /** Edit and move to [toPath] (another file of the space). Rolled back by the writer if the append fails. */
    suspend fun move(task: Task, fields: TaskFields, toPath: String): EditOutcome =
        write(rescanAfter = true) { it.move(task, fields, toPath) }

    suspend fun delete(task: Task): EditOutcome = write { it.delete(task) }

    suspend fun restore(removed: RemovedLine): EditOutcome = write { it.restore(removed) }

    // ---- internals (call with opMutex held unless noted) --------------------------------------------------

    /** Runs one write-protocol operation and folds its result into state. Takes [opMutex] itself. */
    private suspend fun write(rescanAfter: Boolean = false, block: (VaultWriter) -> WriteResult): EditOutcome =
        opMutex.withLock {
            val w = writer ?: return@withLock EditOutcome.Failed("No vault selected")
            when (val r = withContext(Dispatchers.IO) { block(w) }) {
                is WriteResult.Ok -> {
                    _state.update { it.copy(files = it.files + (r.file.state.path to r.file)) }
                    if (rescanAfter) scanLocked(force = false)
                    r.removed?.let { EditOutcome.Removed(it) } ?: EditOutcome.Done
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

    /** Resets everything vault-related but keeps the spaces (they are independent of the permission state). */
    private fun reset(status: RepoState.Status, vaultName: String? = null) {
        _state.update { RepoState(status = status, vaultName = vaultName, spaces = it.spaces) }
    }

    /** Builds the file system for [uri] if the persisted permission is intact; otherwise flags PERMISSION_LOST. */
    private suspend fun attach(uri: Uri): Boolean {
        val granted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!granted) {
            scanner = null
            writer = null
            reset(RepoState.Status.PERMISSION_LOST)
            return false
        }
        val fs = SafVaultFileSystem(resolver, uri)
        scanner = FileScanner(fs) { globalFilter }
        writer = VaultWriter(fs) { globalFilter }
        val name = withContext(Dispatchers.IO) { fs.rootName() }
        reset(RepoState.Status.READY, name)
        return true
    }

    private suspend fun scanLocked(force: Boolean) {
        val sc = scanner ?: return
        _state.update { it.copy(scanning = true, error = null) }
        try {
            val scope = _state.value.spaces.assignedFiles
            val result = withContext(Dispatchers.IO) {
                sc.scan(_state.value.files, include = { it in scope }, force = force)
            }
            _state.update {
                it.copy(files = result.files, unreadable = result.unreadable, vaultFiles = result.available, scanning = false)
            }
        } catch (e: SecurityException) {
            scanner = null
            writer = null
            reset(RepoState.Status.PERMISSION_LOST)
        } catch (e: Exception) {
            _state.update { it.copy(scanning = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }
}
