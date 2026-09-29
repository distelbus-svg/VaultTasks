package app.vaulttasks.domain.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileScannerTest {
    private val fs = FakeVaultFileSystem()
    private val scanner = FileScanner(fs)

    @Test fun `first scan finds tasks in md files and skips dot dirs and non-md`() {
        fs.put("a.md", "- [ ] one\n- [x] two\n")
        fs.put("sub/b.md", "text\n- [ ] three 📅 2026-10-01\n")
        fs.put(".obsidian/c.md", "- [ ] hidden\n")
        fs.put("d.txt", "- [ ] nope\n")
        val r = scanner.scan(emptyMap())
        assertEquals(setOf("a.md", "sub/b.md"), r.files.keys)
        assertEquals(3, r.files.values.sumOf { it.tasks.size })
        assertEquals(3, r.changes.inserts.size)
        assertEquals(2, r.reparsed)
    }

    @Test fun `unchanged files are not re-read`() {
        fs.put("a.md", "- [ ] one\n")
        val first = scanner.scan(emptyMap())
        fs.readCount = 0
        val second = scanner.scan(first.files)
        assertEquals(0, fs.readCount)
        assertEquals(0, second.reparsed)
        assertTrue(second.changes.isEmpty)
    }

    @Test fun `touched but identical content is confirmed by hash and not re-parsed`() {
        fs.put("a.md", "- [ ] one\n")
        val first = scanner.scan(emptyMap())
        fs.touch("a.md")
        val second = scanner.scan(first.files)
        assertEquals(0, second.reparsed)
        assertTrue(second.changes.isEmpty)
        assertEquals(fs.stat("a.md")!!.lastModified, second.files.getValue("a.md").state.lastModified)
    }

    @Test fun `edit produces update-free insert and delete by identity`() {
        fs.put("a.md", "- [ ] one\n- [ ] two\n")
        val first = scanner.scan(emptyMap())
        fs.put("a.md", "- [ ] one\n- [ ] three\n")
        val second = scanner.scan(first.files)
        assertEquals(listOf("three"), second.changes.inserts.map { it.description })
        assertEquals(listOf("two"), second.changes.deletes.map { it.normalizedText.removePrefix("- [ ] ") })
    }

    @Test fun `deleted file deletes its tasks`() {
        fs.put("a.md", "- [ ] one\n")
        val first = scanner.scan(emptyMap())
        fs.remove("a.md")
        val second = scanner.scan(first.files)
        assertEquals(1, second.changes.deletes.size)
        assertTrue(second.files.isEmpty())
    }

    @Test fun `invalid utf8 is reported and previous state is kept`() {
        fs.put("a.md", "- [ ] one\n")
        val first = scanner.scan(emptyMap())
        fs.put("a.md", byteArrayOf('-'.code.toByte(), ' '.code.toByte(), 0xC3.toByte(), 0x28))
        val second = scanner.scan(first.files)
        assertEquals(listOf("a.md"), second.unreadable)
        assertEquals(first.files.getValue("a.md").tasks, second.files.getValue("a.md").tasks)
        assertTrue(second.changes.isEmpty)
    }

    @Test fun `include filter drops files outside scope`() {
        fs.put("a.md", "- [ ] one\n")
        fs.put("b.md", "- [ ] two\n")
        val first = scanner.scan(emptyMap())
        val second = scanner.scan(first.files, include = { it == "a.md" })
        assertEquals(setOf("a.md"), second.files.keys)
        assertEquals(1, second.changes.deletes.size)
    }

    @Test fun `identical lines get distinct occurrence indexes and no duplicate rows`() {
        fs.put("a.md", "- [ ] same\n- [ ] same\n- [ ] same\n")
        val r = scanner.scan(emptyMap())
        val ids = r.files.getValue("a.md").tasks.map { it.id }
        assertEquals(3, ids.toSet().size)
    }

    @Test fun `force re-parses everything and honours a changed global filter`() {
        fs.put("a.md", "- [ ] #task keep\n- [ ] drop\n")
        var filter = ""
        val s = FileScanner(fs) { filter }
        val first = s.scan(emptyMap())
        assertEquals(2, first.files.getValue("a.md").tasks.size)
        filter = "#task"
        val second = s.scan(first.files, force = true)
        assertEquals(1, second.files.getValue("a.md").tasks.size)
        assertEquals(1, second.changes.deletes.size)
    }
}
