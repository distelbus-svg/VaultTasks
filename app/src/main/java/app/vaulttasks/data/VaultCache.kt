package app.vaulttasks.data

import android.content.Context
import app.vaulttasks.domain.Priority
import app.vaulttasks.domain.Task
import app.vaulttasks.domain.TaskId
import app.vaulttasks.domain.TaskState
import app.vaulttasks.domain.vault.FileState
import app.vaulttasks.domain.vault.ScannedFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** What a previous run learned about the vault; shown instantly at start, then verified by an incremental scan. */
data class CachedVault(val files: Map<String, ScannedFile>, val available: List<String>, val vaultName: String?)

/**
 * Disk cache of the last scan result (the files stay the source of truth). It is only trusted for the same vault URI
 * and global filter; the scanner re-reads any file whose size or modification time differs from the cached state.
 * Any problem reading it simply means "no cache": a full scan, as before.
 */
class VaultCache(context: Context) {
    private val file = File(context.applicationContext.filesDir, "vault-cache-v2.json")

    fun load(vaultUri: String, filter: String): CachedVault? = try {
        if (!file.exists()) null else {
            val root = JSONObject(file.readText())
            if (root.getString("uri") != vaultUri || root.getString("filter") != filter) null else {
                val files = LinkedHashMap<String, ScannedFile>()
                val arr = root.getJSONArray("files")
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val state = FileState(f.getString("path"), f.getLong("mtime"), f.getLong("size"), f.getString("hash"))
                    val tasks = f.getJSONArray("tasks").let { t -> (0 until t.length()).map { taskFrom(t.getJSONObject(it)) } }
                    files[state.path] = ScannedFile(state, tasks)
                }
                val avail = root.getJSONArray("available").let { a -> (0 until a.length()).map { a.getString(it) } }
                CachedVault(files, avail, root.optString("name").ifEmpty { null })
            }
        }
    } catch (e: Exception) {
        null
    }

    fun save(vaultUri: String, filter: String, files: Map<String, ScannedFile>, available: List<String>, vaultName: String?) {
        try {
            val root = JSONObject()
                .put("uri", vaultUri).put("filter", filter).put("name", vaultName ?: "")
                .put("available", JSONArray(available))
                .put(
                    "files",
                    JSONArray().also { arr ->
                        files.values.forEach { sf ->
                            arr.put(
                                JSONObject().put("path", sf.state.path).put("mtime", sf.state.lastModified)
                                    .put("size", sf.state.size).put("hash", sf.state.contentHash)
                                    .put("tasks", JSONArray().also { t -> sf.tasks.forEach { t.put(taskTo(it)) } }),
                            )
                        }
                    },
                )
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) { // renameTo can fail over an existing file on some filesystems
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // best effort
        }
    }

    private fun taskTo(t: Task) = JSONObject()
        .put("path", t.id.path).put("norm", t.id.normalizedText).put("occ", t.id.occurrence)
        .put("line", t.lineIndex).put("state", t.state.name).put("desc", t.description)
        .put("due", t.dueDate?.toString() ?: JSONObject.NULL).put("time", t.dueTime?.toString() ?: JSONObject.NULL)
        .put("prio", t.priority?.name ?: JSONObject.NULL).put("rec", t.isRecurring)
        .put("done", t.doneDate?.toString() ?: JSONObject.NULL).put("raw", t.rawLine)
        .put("notes", t.notes).put("rule", t.recurrence ?: JSONObject.NULL)

    private fun taskFrom(o: JSONObject) = Task(
        id = TaskId(o.getString("path"), o.getString("norm"), o.getInt("occ")),
        lineIndex = o.getInt("line"),
        state = TaskState.valueOf(o.getString("state")),
        description = o.getString("desc"),
        dueDate = o.optString("due").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalDate::parse),
        dueTime = o.optString("time").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalTime::parse),
        priority = o.optString("prio").takeIf { it.isNotEmpty() && it != "null" }?.let(Priority::valueOf),
        isRecurring = o.getBoolean("rec"),
        doneDate = o.optString("done").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalDate::parse),
        rawLine = o.getString("raw"),
        notes = o.optString("notes"),
        recurrence = o.optString("rule").takeIf { it.isNotEmpty() && it != "null" },
    )
}
