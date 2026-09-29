package app.vaulttasks.domain.vault

/** In-memory vault. Every write bumps a logical clock so lastModified always changes. */
class FakeVaultFileSystem : VaultFileSystem {
    private class Entry(var bytes: ByteArray, var mtime: Long, var writtenViaWrite: Boolean = false)

    private val files = LinkedHashMap<String, Entry>()
    private var clock = 1_000L

    /** When set, reads of a file last changed through write() return corrupted bytes (simulates a failed write). */
    var corruptReads = false
    var readCount = 0

    @Synchronized fun put(path: String, text: String) = put(path, text.toByteArray(Charsets.UTF_8))

    @Synchronized fun put(path: String, bytes: ByteArray) {
        files[path] = Entry(bytes, ++clock)
    }

    @Synchronized fun remove(path: String) { files.remove(path) }

    /** Changes mtime only (content identical). */
    @Synchronized fun touch(path: String) { files.getValue(path).mtime = ++clock }

    @Synchronized fun text(path: String): String = String(files.getValue(path).bytes, Charsets.UTF_8)

    @Synchronized fun bytes(path: String): ByteArray = files.getValue(path).bytes.copyOf()

    @Synchronized override fun listMarkdown() = files
        .filterKeys { it.endsWith(".md") && it.split('/').none { seg -> seg.startsWith(".") } }
        .map { (p, e) -> VaultFileInfo(p, e.mtime, e.bytes.size.toLong()) }

    @Synchronized override fun stat(path: String) = files[path]?.let { VaultFileInfo(path, it.mtime, it.bytes.size.toLong()) }

    @Synchronized override fun read(path: String): ByteArray? {
        readCount++
        val e = files[path] ?: return null
        return if (corruptReads && e.writtenViaWrite) e.bytes + byteArrayOf(0x21) else e.bytes.copyOf()
    }

    @Synchronized override fun write(path: String, bytes: ByteArray) {
        val e = files[path] ?: error("no such file: $path")
        e.bytes = bytes.copyOf()
        e.mtime = ++clock
        e.writtenViaWrite = true
    }
}
