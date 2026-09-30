package app.vaulttasks.domain.vault

import app.vaulttasks.domain.Changes
import app.vaulttasks.domain.Reconciler
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskParser
import app.vaulttasks.domain.VaultText

/** Spec §4.4 FileState (sync fields are added in the WebDAV phase). */
data class FileState(val path: String, val lastModified: Long, val size: Long, val contentHash: String)

data class ScannedFile(val state: FileState, val tasks: List<Task>)

data class ScanResult(
    val files: Map<String, ScannedFile>,
    val changes: Changes,
    /** Files actually read and parsed this pass. */
    val reparsed: Int,
    /** Files that could not be read this pass (mid-write, invalid UTF-8, I/O error). Previous state is kept. */
    val unreadable: List<String>,
    /** Every `.md` path in the vault this pass, regardless of [FileScanner.scan]'s `include` (feeds the space file picker). */
    val available: List<String> = emptyList(),
)

/**
 * Spec §5 change detection: a file is re-read only if lastModified or size changed; the content hash then
 * confirms a real change. Pure aside from [VaultFileSystem] calls.
 */
class FileScanner(
    private val fs: VaultFileSystem,
    private val globalFilter: () -> String = { "" },
) {
    /**
     * @param previous result of the last scan (empty on first scan)
     * @param include limits scope (Phase 3: files assigned to spaces); files outside it are dropped
     * @param force re-read and re-parse everything (e.g. after the global filter changed)
     */
    fun scan(
        previous: Map<String, ScannedFile>,
        include: (String) -> Boolean = { true },
        force: Boolean = false,
    ): ScanResult {
        val out = LinkedHashMap<String, ScannedFile>()
        val unreadable = ArrayList<String>()
        var reparsed = 0

        val listing = fs.listMarkdown()
        for (info in listing) {
            if (!include(info.path)) continue
            val prev = previous[info.path]
            if (!force && prev != null && prev.state.lastModified == info.lastModified && prev.state.size == info.size) {
                out[info.path] = prev
                continue
            }

            val bytes = runCatching { fs.read(info.path) }.getOrNull()
            // If the file changed while we were reading it (sync tool mid-write), skip it this pass.
            val after = if (bytes != null) runCatching { fs.stat(info.path) }.getOrNull() else null
            val text = if (bytes != null && after != null &&
                after.lastModified == info.lastModified && after.size == info.size
            ) decodeUtf8Strict(bytes) else null
            if (bytes == null || text == null) {
                unreadable += info.path
                if (prev != null) out[info.path] = prev
                continue
            }

            val state = FileState(info.path, info.lastModified, info.size, sha256Hex(bytes))
            if (!force && prev != null && prev.state.contentHash == state.contentHash) {
                out[info.path] = ScannedFile(state, prev.tasks) // touched, not changed
                continue
            }
            out[info.path] = ScannedFile(state, TaskParser.parseFile(info.path, VaultText.parse(text), globalFilter()))
            reparsed++
        }

        val changes = Reconciler.reconcile(
            cached = previous.values.flatMap { it.tasks },
            parsed = out.values.flatMap { it.tasks },
        )
        return ScanResult(out, changes, reparsed, unreadable, listing.map { it.path })
    }
}
