package app.vaulttasks.data.vault

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.vaulttasks.domain.vault.VaultFileInfo
import app.vaulttasks.domain.vault.VaultFileSystem
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * [VaultFileSystem] over a SAF document tree (spec §5). Uses DocumentsContract directly: one provider query
 * per directory instead of DocumentFile's one query per attribute.
 *
 * Document ids are provider-specific, so vault paths are resolved by walking names; resolved ids are cached
 * and dropped whenever the cached id turns out to be gone.
 */
class SafVaultFileSystem(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : VaultFileSystem {

    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val idCache = ConcurrentHashMap<String, String>()

    private class Child(val docId: String, val name: String, val isDir: Boolean, val lastModified: Long, val size: Long)

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** Display name of the vault folder, or null if the provider does not answer. */
    fun rootName(): String? = runCatching {
        resolver.query(docUri(rootDocId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun children(parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
        val out = ArrayList<Child>()
        val cursor = resolver.query(uri, projection, null, null, null)
            ?: throw IOException("Provider returned no cursor for $uri")
        cursor.use { c ->
            while (c.moveToNext()) {
                out += Child(
                    docId = c.getString(0),
                    name = c.getString(1) ?: continue,
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) -1L else c.getLong(4),
                )
            }
        }
        return out
    }

    override fun listMarkdown(): List<VaultFileInfo> {
        val files = ArrayList<VaultFileInfo>()
        val ids = HashMap<String, String>()
        fun walk(parentDocId: String, prefix: String) {
            for (child in children(parentDocId)) {
                if (child.name.startsWith(".")) continue // .obsidian, .git, .trash, ...
                val path = prefix + child.name
                if (child.isDir) {
                    walk(child.docId, "$path/")
                } else if (child.name.endsWith(".md", ignoreCase = true)) {
                    ids[path] = child.docId
                    files += VaultFileInfo(path, child.lastModified, child.size)
                }
            }
        }
        walk(rootDocId, "")
        idCache.clear()
        idCache.putAll(ids)
        return files.sortedBy { it.path }
    }

    /** Resolves a vault-relative path to a document id by walking names from the root. */
    private fun walkTo(path: String): String? {
        var current = rootDocId
        for (segment in path.split('/')) {
            current = children(current).firstOrNull { it.name == segment }?.docId ?: return null
        }
        return current
    }

    private fun statById(docId: String): VaultFileInfo? = try {
        val projection = arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        resolver.query(docUri(docId), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getString(0) == Document.MIME_TYPE_DIR) null
            else VaultFileInfo(
                path = "",
                lastModified = if (c.isNull(1)) 0L else c.getLong(1),
                size = if (c.isNull(2)) -1L else c.getLong(2),
            )
        }
    } catch (e: FileNotFoundException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Cached id if still valid, else re-walk once. Returns the id and its current attributes. */
    private fun locate(path: String): Pair<String, VaultFileInfo>? {
        idCache[path]?.let { id ->
            statById(id)?.let { return id to it.copy(path = path) }
            idCache.remove(path)
        }
        val id = walkTo(path) ?: return null
        val info = statById(id) ?: return null
        idCache[path] = id
        return id to info.copy(path = path)
    }

    override fun stat(path: String): VaultFileInfo? = locate(path)?.second

    override fun read(path: String): ByteArray? {
        val (id, _) = locate(path) ?: return null
        return try {
            resolver.openInputStream(docUri(id))?.use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            idCache.remove(path)
            null
        }
    }

    override fun write(path: String, bytes: ByteArray) {
        val (id, _) = locate(path) ?: throw FileNotFoundException(path)
        // "wt" = write + truncate; plain "w" may leave stale trailing bytes on some providers.
        val out = resolver.openOutputStream(docUri(id), "wt") ?: throw IOException("Cannot open $path for writing")
        out.use {
            it.write(bytes)
            it.flush()
        }
    }
}
