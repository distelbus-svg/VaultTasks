package app.vaulttasks.domain.vault

import app.vaulttasks.domain.ParsedLine
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskFields
import app.vaulttasks.domain.TaskParser
import app.vaulttasks.domain.TaskSerializer
import app.vaulttasks.domain.VaultText
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What a delete removed, enough to undo it (spec §7.1). */
data class RemovedLine(val path: String, val index: Int, val text: String, val fileHashAfter: String)

sealed interface WriteResult {
    /** [file] is the verified, re-parsed state after the write. */
    data class Ok(val file: ScannedFile, val removed: RemovedLine? = null) : WriteResult

    /** Target line no longer matches (or the file changed underneath us). Nothing was written. Rescan and retry. */
    data object Stale : WriteResult

    data object Missing : WriteResult

    /** Nothing was written, or a failed write was rolled back where possible. */
    data class Failed(val cause: Throwable) : WriteResult
}

/**
 * Spec §5 write protocol: read fresh → locate target by identity → replace only that line → write →
 * read back and verify → re-parse. Writes are serialized per file.
 *
 * Known residual race: an external writer landing between our final stat and our write is not detectable
 * through SAF (no atomic compare-and-swap). The window is a few milliseconds.
 */
class VaultWriter(
    private val fs: VaultFileSystem,
    private val globalFilter: () -> String = { "" },
) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun setDone(task: Task, done: Boolean, doneDate: LocalDate?): WriteResult =
        mutate(task.id.path) { text, fresh ->
            val (index, line) = locate(text, fresh, task) ?: return@mutate null
            Edit(text.replaceLine(index, TaskSerializer.setDone(line, done, doneDate)))
        }

    fun update(task: Task, fields: TaskFields): WriteResult =
        mutate(task.id.path) { text, fresh ->
            val (index, line) = locate(text, fresh, task) ?: return@mutate null
            Edit(text.replaceLine(index, TaskSerializer.applyFields(line, fields, globalFilter())))
        }

    fun delete(task: Task): WriteResult =
        mutate(task.id.path) { text, fresh ->
            val (index, _) = locate(text, fresh, task) ?: return@mutate null
            Edit(text.removeLine(index), removedIndex = index, removedText = text.lines[index].text)
        }

    /** Appends at the end of an existing file, ensuring a preceding newline. */
    fun create(path: String, fields: TaskFields, bullet: String = "-"): WriteResult =
        mutate(path) { text, _ ->
            Edit(text.appendLine(TaskSerializer.newLine(fields, globalFilter(), bullet)))
        }

    /** Undo of [delete]: original index if the file is unchanged since, otherwise appended. */
    fun restore(removed: RemovedLine): WriteResult =
        mutate(removed.path) { text, _ ->
            val unchanged = sha256Hex(text.toString().toByteArray(Charsets.UTF_8)) == removed.fileHashAfter
            Edit(if (unchanged) text.insertLine(removed.index, removed.text) else text.appendLine(removed.text))
        }

    // ---- internals -------------------------------------------------------------------------------------------

    private class Edit(val text: VaultText, val removedIndex: Int? = null, val removedText: String? = null)

    /** The target's current line index and parse, or null if its identity no longer matches anything in the file. */
    private fun locate(text: VaultText, fresh: List<Task>, target: Task): Pair<Int, ParsedLine>? {
        val hit = fresh.firstOrNull { it.id == target.id } ?: return null
        val line = TaskParser.parseLine(text.lines[hit.lineIndex].text, globalFilter()) ?: return null
        return hit.lineIndex to line
    }

    private fun mutate(path: String, edit: (VaultText, List<Task>) -> Edit?): WriteResult {
        val lock = locks.computeIfAbsent(path) { ReentrantLock() }
        return lock.withLock {
            try {
                mutateLocked(path, edit)
            } catch (e: Exception) {
                WriteResult.Failed(e)
            }
        }
    }

    private fun mutateLocked(path: String, edit: (VaultText, List<Task>) -> Edit?): WriteResult {
        val statBefore = fs.stat(path) ?: return WriteResult.Missing
        val original = fs.read(path) ?: return WriteResult.Missing
        val decoded = decodeUtf8Strict(original)
            ?: return WriteResult.Failed(IOException("$path is not valid UTF-8; refusing to edit"))

        val text = VaultText.parse(decoded)
        val filter = globalFilter()
        val fresh = TaskParser.parseFile(path, text, filter)
        val result = edit(text, fresh) ?: return WriteResult.Stale
        val newBytes = result.text.toString().toByteArray(Charsets.UTF_8)

        // Last-chance check: someone else wrote while we were working.
        val statNow = fs.stat(path) ?: return WriteResult.Missing
        if (statNow.lastModified != statBefore.lastModified || statNow.size != statBefore.size) return WriteResult.Stale

        fs.write(path, newBytes)

        val back = fs.read(path)
        if (back == null || !back.contentEquals(newBytes)) {
            runCatching { fs.write(path, original) } // best-effort rollback
            return WriteResult.Failed(IOException("read-back verification failed for $path"))
        }

        val info = fs.stat(path) ?: return WriteResult.Missing
        val hash = sha256Hex(newBytes)
        val parsed = TaskParser.parseFile(path, result.text, filter)
        val removed = if (result.removedIndex != null && result.removedText != null) {
            RemovedLine(path, result.removedIndex, result.removedText, hash)
        } else null
        return WriteResult.Ok(ScannedFile(FileState(path, info.lastModified, info.size, hash), parsed), removed)
    }
}
