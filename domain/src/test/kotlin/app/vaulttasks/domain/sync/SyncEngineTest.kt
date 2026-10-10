package app.vaulttasks.domain.sync

import app.vaulttasks.domain.vault.VaultFileInfo
import app.vaulttasks.domain.vault.VaultFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemFs : VaultFileSystem, LocalFiles {
    val files = linkedMapOf<String, ByteArray>()
    val mtimes = mutableMapOf<String, Long>()
    override fun listMarkdown() = files.keys.map { VaultFileInfo(it, mtimes[it] ?: 0, files[it]!!.size.toLong()) }
    override fun stat(path: String) = files[path]?.let { VaultFileInfo(path, mtimes[path] ?: 0, it.size.toLong()) }
    override fun read(path: String) = files[path]
    override fun write(path: String, bytes: ByteArray) { check(path in files); files[path] = bytes }
    override fun <T> atomically(block: (VaultFileSystem) -> T) = block(this)
    fun text(path: String) = files[path]?.decodeToString()
    fun set(path: String, text: String, mtime: Long) { files[path] = text.encodeToByteArray(); mtimes[path] = mtime }
}

private class MemRemote : RemoteStore {
    val files = linkedMapOf<String, String>()
    private val versions = mutableMapOf<String, Int>()
    private val mtimes = mutableMapOf<String, Long>()
    var beforePut: (() -> Unit)? = null
    private fun token(path: String) = "\"v${versions[path]}\""
    fun set(path: String, text: String, mtime: Long = 0) {
        files[path] = text; versions[path] = (versions[path] ?: 0) + 1; mtimes[path] = mtime
    }
    override fun stat(path: String) = files[path]?.let { RemoteStat(token(path), mtimes[path]) }
    override fun get(path: String) = files[path]?.let { RemoteData(it.encodeToByteArray(), token(path), mtimes[path]) }
    override fun put(path: String, bytes: ByteArray, expectedToken: String?): PutResult {
        beforePut?.invoke()
        val current = files[path]?.let { token(path) }
        if (current != expectedToken) return PutResult.PreconditionFailed // create-only: expected null, exists -> fail
        set(path, bytes.decodeToString(), mtime = Long.MAX_VALUE)
        return PutResult.Ok(token(path))
    }
}

private class MemRecords : SyncRecords {
    val map = mutableMapOf<String, SyncRecord>()
    override fun get(path: String) = map[path]
    override fun put(path: String, record: SyncRecord) { map[path] = record }
    override fun clear() = map.clear()
}

class SyncEngineTest {
    private val fs = MemFs()
    private val remote = MemRemote()
    private val records = MemRecords()
    private val engine = SyncEngine(fs, remote, records)
    private val p = "Tasks/a.md"

    private fun status() = engine.check(listOf(p)).single().status
    private fun sync() = engine.sync(listOf(p))

    /** Both sides hold [text] and have been synced once. */
    private fun synced(text: String) {
        fs.set(p, text, 100); remote.set(p, text, 100); status()
    }

    @Test fun `identical content on first contact moves nothing`() {
        fs.set(p, "x", 100); remote.set(p, "x", 200)
        assertEquals(FileStatus.IN_SYNC, status())
        val r = sync()
        assertTrue(r.pulled.isEmpty() && r.pushed.isEmpty())
    }

    @Test fun `change on one side only is carried over`() {
        synced("1")
        fs.set(p, "2", 300)
        assertEquals(FileStatus.LOCAL_CHANGED, status())
        assertEquals(listOf(p), sync().pushed)
        assertEquals("2", remote.files[p])
        assertEquals(FileStatus.IN_SYNC, status())

        remote.set(p, "3", 400)
        assertEquals(FileStatus.REMOTE_CHANGED, status())
        assertEquals(listOf(p), sync().pulled)
        assertEquals("3", fs.text(p))
        assertEquals(FileStatus.IN_SYNC, status())
    }

    @Test fun `changed on both sides - the newer local version survives`() {
        synced("1")
        remote.set(p, "theirs", 300); fs.set(p, "mine", 400)
        assertEquals(FileStatus.BOTH_CHANGED, status())
        assertEquals(listOf(p), sync().pushed)
        assertEquals("mine", remote.files[p])
        assertEquals("mine", fs.text(p))
    }

    @Test fun `changed on both sides - the newer server version survives`() {
        synced("1")
        fs.set(p, "mine", 300); remote.set(p, "theirs", 400)
        assertEquals(listOf(p), sync().pulled)
        assertEquals("theirs", fs.text(p))
        assertEquals("theirs", remote.files[p])
    }

    @Test fun `a tie or unknown server time goes to the server`() {
        synced("1")
        fs.set(p, "mine", 300); remote.set(p, "theirs", 300)
        assertEquals(listOf(p), sync().pulled)
        assertEquals("theirs", fs.text(p))
    }

    @Test fun `first contact with different content - the newer one wins`() {
        fs.set(p, "local", 500); remote.set(p, "remote", 400)
        assertEquals(listOf(p), sync().pushed)
        assertEquals("local", remote.files[p])

        val q = "Tasks/b.md"
        fs.set(q, "local", 400); remote.set(q, "remote", 500)
        assertEquals(listOf(q), engine.sync(listOf(q)).pulled)
        assertEquals("remote", fs.text(q))
    }

    @Test fun `the same edit on both sides needs no action`() {
        synced("1")
        fs.set(p, "same", 300); remote.set(p, "same", 400)
        assertEquals(FileStatus.IN_SYNC, status())
    }

    @Test fun `an upload never overwrites a server change that lands after it was read`() {
        synced("1")
        fs.set(p, "2", 300)
        remote.beforePut = { remote.set(p, "sneaky", 350) }
        val r = sync()
        assertTrue(r.pushed.isEmpty())
        assertEquals(1, r.skipped.size)
        assertEquals("sneaky", remote.files[p])
    }

    @Test fun `a new file is created but never replaces one that appeared meanwhile`() {
        fs.set(p, "new", 100)
        assertEquals(FileStatus.NOT_ON_SERVER, status())
        assertEquals(listOf(p), sync().pushed)
        assertEquals("new", remote.files[p])

        val q = "Tasks/b.md"
        fs.set(q, "mine", 100)
        remote.beforePut = { remote.set(q, "theirs", 100) }
        assertTrue(engine.sync(listOf(q)).pushed.isEmpty())
        assertEquals("theirs", remote.files[q])
    }

    @Test fun `deleting on either side is never propagated`() {
        synced("1")
        remote.files.remove(p)
        assertEquals(FileStatus.DELETED_ON_SERVER, status())
        assertTrue(sync().pushed.isEmpty())
        assertNull(remote.files[p])
        assertEquals("1", fs.text(p))

        remote.set(p, "1", 100); fs.files.remove(p)
        assertEquals(FileStatus.MISSING_LOCALLY, status())
        assertTrue(sync().pulled.isEmpty())
    }
}
