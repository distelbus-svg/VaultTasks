package app.vaulttasks.domain.vault

import app.vaulttasks.domain.TaskFields
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultWriterTest {
    private val fs = FakeVaultFileSystem()
    private val scanner = FileScanner(fs)
    private val writer = VaultWriter(fs)
    private val today = LocalDate.of(2026, 9, 29)

    private fun scan(path: String = "a.md") = scanner.scan(emptyMap()).files.getValue(path)

    /** Only the given line differs; everything else byte-identical. */
    private fun assertOnlyLineChanged(before: String, after: String, index: Int, expected: String?) {
        val b = before.split("\n").toMutableList()
        if (expected == null) b.removeAt(index) else b[index] = expected
        assertEquals(b.joinToString("\n"), after)
    }

    @Test fun `setDone changes exactly one line`() {
        val src = "# Title\n\n- [ ] Call Anna ⏰ 19:00 🔼 📅 2026-09-28\n- [ ] other\n"
        fs.put("a.md", src)
        val t = scan().tasks.first { it.description.startsWith("Call") }
        val r = writer.setDone(t, true, today)
        assertIs<WriteResult.Ok>(r)
        assertOnlyLineChanged(src, fs.text("a.md"), 2, "- [x] Call Anna ⏰ 19:00 🔼 📅 2026-09-28 ✅ 2026-09-29")
    }

    @Test fun `CRLF BOM and missing trailing newline survive an edit`() {
        val src = "\uFEFF- [ ] one\r\n- [ ] two"
        fs.put("a.md", src)
        val t = scan().tasks[0]
        assertIs<WriteResult.Ok>(writer.setDone(t, true, null))
        assertEquals("\uFEFF- [x] one\r\n- [ ] two", fs.text("a.md"))
    }

    @Test fun `create appends with preceding newline and keeps no-trailing-newline style`() {
        fs.put("a.md", "- [ ] one")
        val r = writer.create("a.md", TaskFields("new", LocalDate.of(2026, 10, 1), LocalTime.of(8, 30)))
        assertIs<WriteResult.Ok>(r)
        assertEquals("- [ ] one\n- [ ] new ⏰ 08:30 📅 2026-10-01", fs.text("a.md"))
        assertEquals(2, r.file.tasks.size)
    }

    @Test fun `update rewrites only the target line`() {
        val src = "- [ ] a\n- [ ] b 📅 2026-10-01\n- [ ] c\n"
        fs.put("a.md", src)
        val t = scan().tasks[1]
        assertIs<WriteResult.Ok>(writer.update(t, TaskFields("b2", LocalDate.of(2026, 10, 2), null)))
        assertOnlyLineChanged(src, fs.text("a.md"), 1, "- [ ] b2 📅 2026-10-02")
    }

    @Test fun `stale line aborts without writing`() {
        fs.put("a.md", "- [ ] one\n- [ ] two\n")
        val t = scan().tasks[0]
        fs.put("a.md", "- [ ] one edited elsewhere\n- [ ] two\n")
        val before = fs.bytes("a.md")
        assertEquals(WriteResult.Stale, writer.setDone(t, true, null))
        assertContentEquals(before, fs.bytes("a.md"))
    }

    @Test fun `stale detection survives line shifting`() {
        fs.put("a.md", "- [ ] one\n- [ ] two\n")
        val t = scan().tasks[1]
        fs.put("a.md", "inserted line\n- [ ] one\n- [ ] two\n") // lineIndex hint is now wrong, identity still valid
        val r = writer.setDone(t, true, null)
        assertIs<WriteResult.Ok>(r)
        assertEquals("inserted line\n- [ ] one\n- [x] two\n", fs.text("a.md"))
    }

    @Test fun `duplicate identical lines edit the right occurrence`() {
        fs.put("a.md", "- [ ] same\n- [ ] same\n")
        val second = scan().tasks[1]
        assertIs<WriteResult.Ok>(writer.setDone(second, true, null))
        assertEquals("- [ ] same\n- [x] same\n", fs.text("a.md"))
    }

    @Test fun `missing file`() {
        fs.put("a.md", "- [ ] one\n")
        val t = scan().tasks[0]
        fs.remove("a.md")
        assertEquals(WriteResult.Missing, writer.setDone(t, true, null))
    }

    @Test fun `invalid utf8 file is refused`() {
        fs.put("a.md", byteArrayOf(0xC3.toByte(), 0x28))
        assertIs<WriteResult.Failed>(writer.create("a.md", TaskFields("x", null, null)))
    }

    @Test fun `failed read-back rolls the file back`() {
        val src = "- [ ] one\n"
        fs.put("a.md", src)
        val t = scan().tasks[0]
        fs.corruptReads = true
        assertIs<WriteResult.Failed>(writer.setDone(t, true, null))
        fs.corruptReads = false
        assertEquals(src, fs.text("a.md"))
    }

    @Test fun `delete then restore round-trips the file`() {
        val src = "- [ ] a\n- [ ] b\n- [ ] c\n"
        fs.put("a.md", src)
        val r = writer.delete(scan().tasks[1])
        assertIs<WriteResult.Ok>(r)
        assertEquals("- [ ] a\n- [ ] c\n", fs.text("a.md"))
        assertIs<WriteResult.Ok>(writer.restore(r.removed!!))
        assertEquals(src, fs.text("a.md"))
    }

    @Test fun `restore appends when the file changed since the delete`() {
        fs.put("a.md", "- [ ] a\n- [ ] b\n- [ ] c\n")
        val r = writer.delete(scan().tasks[1]) as WriteResult.Ok
        fs.put("a.md", fs.text("a.md") + "- [ ] d\n")
        assertIs<WriteResult.Ok>(writer.restore(r.removed!!))
        assertEquals("- [ ] a\n- [ ] c\n- [ ] d\n- [ ] b\n", fs.text("a.md"))
    }

    @Test fun `move removes from source and appends the edited line to target keeping signifiers`() {
        fs.put("a.md", "- [ ] keep\n- [ ] Call Anna ⏰ 19:00 🔼 📅 2026-09-28\n")
        fs.put("b.md", "# B\n- [ ] existing\n")
        val t = scan().tasks.first { it.description.startsWith("Call") }
        val r = writer.move(t, TaskFields("Call Anna", LocalDate.of(2026, 10, 5), LocalTime.of(7, 0)), "b.md")
        assertIs<WriteResult.Ok>(r)
        assertEquals("- [ ] keep\n", fs.text("a.md"))
        assertEquals("# B\n- [ ] existing\n- [ ] Call Anna ⏰ 07:00 🔼 📅 2026-10-05\n", fs.text("b.md"))
        assertEquals("b.md", r.file.state.path)
    }

    @Test fun `move to the same file is a plain update`() {
        fs.put("a.md", "- [ ] one\n- [ ] two\n")
        val t = scan().tasks[0]
        assertIs<WriteResult.Ok>(writer.move(t, TaskFields("one!", null, null), "a.md"))
        assertEquals("- [ ] one!\n- [ ] two\n", fs.text("a.md"))
    }

    @Test fun `move to a missing file is rolled back and loses nothing`() {
        val src = "- [ ] one\n- [ ] two\n- [ ] three\n"
        fs.put("a.md", src)
        val t = scan().tasks[1]
        assertEquals(WriteResult.Missing, writer.move(t, TaskFields("two", null, null), "nope.md"))
        assertEquals(src, fs.text("a.md"))
    }

    @Test fun `move with a stale source writes nothing anywhere`() {
        fs.put("a.md", "- [ ] one\n")
        fs.put("b.md", "- [ ] other\n")
        val t = scan().tasks[0]
        fs.put("a.md", "- [ ] one edited elsewhere\n")
        assertEquals(WriteResult.Stale, writer.move(t, TaskFields("one", null, null), "b.md"))
        assertEquals("- [ ] one edited elsewhere\n", fs.text("a.md"))
        assertEquals("- [ ] other\n", fs.text("b.md"))
    }

    @Test fun `move to a file that is not valid utf8 is refused and rolled back`() {
        val src = "- [ ] one\n- [ ] two\n"
        fs.put("a.md", src)
        fs.put("b.md", byteArrayOf(0xC3.toByte(), 0x28))
        val t = scan().tasks[0]
        assertIs<WriteResult.Failed>(writer.move(t, TaskFields("one", null, null), "b.md"))
        assertEquals(src, fs.text("a.md"))
    }

    @Test fun `opposite concurrent moves do not deadlock`() {
        fs.put("a.md", "- [ ] from a\n")
        fs.put("b.md", "- [ ] from b\n")
        val all = scanner.scan(emptyMap()).files
        val ta = all.getValue("a.md").tasks[0]
        val tb = all.getValue("b.md").tasks[0]
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val f1 = pool.submit<WriteResult> { start.await(); writer.move(ta, TaskFields("from a", null, null), "b.md") }
        val f2 = pool.submit<WriteResult> { start.await(); writer.move(tb, TaskFields("from b", null, null), "a.md") }
        start.countDown()
        val r1 = f1.get(10, java.util.concurrent.TimeUnit.SECONDS)
        val r2 = f2.get(10, java.util.concurrent.TimeUnit.SECONDS)
        pool.shutdown()
        assertIs<WriteResult.Ok>(r1)
        assertIs<WriteResult.Ok>(r2)
        assertEquals("- [ ] from b\n", fs.text("a.md"))
        assertEquals("- [ ] from a\n", fs.text("b.md"))
    }

    @Test fun `scan reports every vault file as available even when scoped`() {
        fs.put("a.md", "- [ ] one\n")
        fs.put("sub/b.md", "- [ ] two\n")
        val r = scanner.scan(emptyMap(), include = { it == "a.md" })
        assertEquals(listOf("a.md"), r.files.keys.toList())
        assertEquals(setOf("a.md", "sub/b.md"), r.available.toSet())
    }

    @Test fun `concurrent writes to one file are serialized and none is lost`() {
        val n = 20
        fs.put("a.md", (0 until n).joinToString("") { "- [ ] task $it\n" })
        val tasks = scan().tasks
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = tasks.map { t ->
            pool.submit<WriteResult> { start.await(); writer.setDone(t, true, null) }
        }
        start.countDown()
        val all = results.map { it.get() }
        pool.shutdown()
        assertTrue(all.all { it is WriteResult.Ok }, "results: $all")
        assertEquals((0 until n).joinToString("") { "- [x] task $it\n" }, fs.text("a.md"))
    }
}
