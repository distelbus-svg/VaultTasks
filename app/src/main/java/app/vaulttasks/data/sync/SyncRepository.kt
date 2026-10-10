package app.vaulttasks.data.sync

import app.vaulttasks.data.VaultRepository
import app.vaulttasks.domain.sync.FileSyncState
import app.vaulttasks.domain.sync.SyncEngine
import app.vaulttasks.domain.sync.SyncReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncUiState(
    val config: WebDavConfig? = null,
    val busy: Boolean = false,
    /** Result of the last check; empty until the first one. */
    val files: List<FileSyncState> = emptyList(),
    val report: SyncReport? = null,
    /** Last connection-test result or error, shown under the form. */
    val message: String? = null,
)

/**
 * Explicit, user-triggered sync (check / pull / push). Network work runs outside the vault lock; only each local
 * read-check-write holds it (see [VaultRepository.localFiles]).
 */
class SyncRepository(
    private val vault: VaultRepository,
    private val settings: WebDavSettings,
    private val records: PrefsSyncRecords,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SyncUiState(config = settings.load()))
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    /** One-line results of pull-to-refresh syncs ("1 pulled, 0 pushed"), shown as a toast by the home screens. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun save(config: WebDavConfig) {
        val old = _state.value.config
        // Records describe one specific server location; a different one invalidates all of them.
        if (old != null && (old.url != config.url.trim() || old.baseFolder != config.baseFolder.trim().trim('/'))) records.clear()
        settings.save(config)
        _state.update { it.copy(config = settings.load(), files = emptyList(), report = null, message = "Saved.") }
    }

    fun test(config: WebDavConfig) = launchBusy {
        val msg = try {
            withContext(Dispatchers.IO) { WebDavClient(config).test() }
        } catch (e: Exception) {
            "Failed: " + (e.message ?: e.javaClass.simpleName)
        }
        _state.update { it.copy(message = msg) }
    }

    fun check() = launchBusy { engineRun { engine, paths -> _state.update { it.copy(files = engine.check(paths), report = null) } } }

    /** Two-way sync, newest version wins. */
    fun sync() = launchBusy { runSync() }

    private suspend fun runSync() {
        engineRun { engine, paths -> _state.update { it.copy(report = engine.sync(paths)) } }
        vault.rescan(force = true) // pulled files changed on disk: re-parse, which also re-syncs alarms
        engineRun { engine, paths -> _state.update { it.copy(files = engine.check(paths)) } }
    }

    private var lastAuto = 0L

    /**
     * Silent sync for the automatic triggers (app opened, app closed, daily): no toast, no-op without a server, and it
     * yields to a manual sync already running. [minGapMs] keeps quick open/close cycles from hammering the server.
     * Returns false only if it failed with an error worth retrying.
     */
    suspend fun autoSync(minGapMs: Long = 0): Boolean {
        if (_state.value.config == null || _state.value.busy) return true
        val now = System.currentTimeMillis()
        if (now - lastAuto < minGapMs) return true
        lastAuto = now
        _state.update { it.copy(busy = true, message = null) }
        return try {
            runSync()
            true
        } catch (e: Exception) {
            _state.update { it.copy(message = "Failed: " + (e.message ?: e.javaClass.simpleName)) }
            false
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** Pull-to-refresh: sync with the server if one is set up (and announce the result), otherwise just rescan. */
    fun refresh() {
        if (_state.value.config != null) {
            if (!_state.value.busy) launchBusy(announce = true) { runSync() }
        } else {
            scope.launch {
                vault.rescan(force = true)
                _events.tryEmit("Vault refreshed")
            }
        }
    }

    /** One-line toast text for the sync that just ran. */
    private fun summary(): String {
        val s = _state.value
        s.message?.let { return it } // setup problem or failure
        val r = s.report ?: return "Sync finished"
        val base = "${r.pulled.size} pulled, ${r.pushed.size} pushed"
        return if (r.failed.isEmpty()) base else "$base, ${r.failed.size} failed"
    }

    // ---- internals ------------------------------------------------------------------------------------------

    private fun launchBusy(announce: Boolean = false, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, report = if (announce) null else it.report) }
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(message = "Failed: " + (e.message ?: e.javaClass.simpleName)) }
            } finally {
                _state.update { it.copy(busy = false) }
                if (announce) _events.tryEmit(summary())
            }
        }
    }

    private suspend fun engineRun(block: (SyncEngine, List<String>) -> Unit) {
        val config = _state.value.config
        if (config == null) {
            _state.update { it.copy(message = "Set up the server first.") }
            return
        }
        val local = vault.localFiles()
        if (local == null) {
            _state.update { it.copy(message = "No vault selected.") }
            return
        }
        val paths = vault.state.value.spaces.assignedFiles.sorted()
        if (paths.isEmpty()) {
            _state.update { it.copy(message = "No files are assigned to a space yet.") }
            return
        }
        withContext(Dispatchers.IO) { block(SyncEngine(local, WebDavClient(config), records), paths) }
    }
}
