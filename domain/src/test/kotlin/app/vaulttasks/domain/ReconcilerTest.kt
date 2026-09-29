package app.vaulttasks.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class ReconcilerTest {
    private fun parse(path: String, text: String) = TaskParser.parseFile(path, VaultText.parse(text))

    private fun apply(cache: List<Task>, c: Changes): List<Task> {
        val m = LinkedHashMap<TaskId, Task>()
        cache.forEach { m[it.id] = it }
        c.deletes.forEach { m.remove(it) }
        c.updates.forEach { m[it.id] = it }
        c.inserts.forEach { check(m.put(it.id, it) == null) { "insert of existing id ${it.id}" } }
        return m.values.toList()
    }

    @Test fun noChangeIsEmpty() {
        val t = parse("a.md", "- [ ] A\n- [ ] B\n")
        assertTrue(Reconciler.reconcile(t, t).isEmpty)
    }

    @Test fun duplicateLineRemovedDeletesOnlyTheLastOccurrence() {
        val before = parse("a.md", "- [ ] Same\n- [ ] Same\n")
        val after = parse("a.md", "- [ ] Same\n")
        val c = Reconciler.reconcile(before, after)
        assertEquals(listOf(before[1].id), c.deletes)
        assertTrue(c.inserts.isEmpty() && c.updates.isEmpty())
    }

    @Test fun textEditIsDeletePlusInsert() {
        val c = Reconciler.reconcile(parse("a.md", "- [ ] Old\n"), parse("a.md", "- [ ] New\n"))
        assertEquals(1, c.inserts.size); assertEquals(1, c.deletes.size); assertTrue(c.updates.isEmpty())
    }

    @Test fun lineMoveOnlyUpdatesHint() {
        val c = Reconciler.reconcile(parse("a.md", "- [ ] A\n"), parse("a.md", "\n- [ ] A\n"))
        assertTrue(c.inserts.isEmpty() && c.deletes.isEmpty()); assertEquals(1, c.updates.size)
    }

    @Test fun stateChangeChangesIdentityPerSpec() {
        // §4.3: identity is the normalized line text, checkbox included → delete + insert, never a duplicate.
        val c = Reconciler.reconcile(parse("a.md", "- [ ] A\n"), parse("a.md", "- [x] A\n"))
        assertEquals(1, c.inserts.size); assertEquals(1, c.deletes.size); assertTrue(c.updates.isEmpty())
    }

    @Test fun moveBetweenFilesIsDeletePlusInsert() {
        val c = Reconciler.reconcile(parse("a.md", "- [ ] A\n"), parse("b.md", "- [ ] A\n"))
        assertEquals(1, c.inserts.size); assertEquals(1, c.deletes.size)
    }

    @Test fun duplicateParsedIdsAreABug() {
        val t = parse("a.md", "- [ ] A\n")
        assertThrows<IllegalArgumentException> { Reconciler.reconcile(emptyList(), t + t) }
    }

    @Test fun neverDuplicatesUnderRandomMutations() {
        val rnd = Random(42)
        val pool = listOf("- [ ] Same", "- [ ] Same", "- [ ] Other 📅 2026-09-28", "- [x] Same", "note", "")
        var lines = List(6) { pool[rnd.nextInt(pool.size)] }
        var cache = emptyList<Task>()
        repeat(300) {
            val m = lines.toMutableList()
            when (rnd.nextInt(3)) {
                0 -> m.add(rnd.nextInt(m.size + 1), pool[rnd.nextInt(pool.size)])
                1 -> if (m.isNotEmpty()) m.removeAt(rnd.nextInt(m.size))
                else -> if (m.isNotEmpty()) m[rnd.nextInt(m.size)] = pool[rnd.nextInt(pool.size)]
            }
            lines = m
            val parsed = parse("a.md", lines.joinToString("\n"))
            cache = apply(cache, Reconciler.reconcile(cache, parsed))
            assertEquals(parsed.map { it.id }.toSet().size, cache.size)
            assertEquals(parsed.toSet(), cache.toSet())
        }
    }
}
