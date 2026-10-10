package app.vaulttasks.domain

/**
 * Task notes live in the vault as nested bullets directly under the task line, one bullet per note line:
 *
 *     - [ ] Call the bank 📅 2026-10-05
 *     \t- ask about the fee
 *     \t- **bring** the contract
 *
 * Obsidian renders them as a normal sub-list with full inline markdown. The Tasks plugin only reads the checkbox line,
 * so the notes never touch its signifiers, queries or the task's identity. A note that would itself look like a task
 * ("[ ] x") gets its first bracket escaped so it can never become one.
 */
object Notes {
    private val NOTE = Regex("^([ \\t>]*)([-*+])[ \\t]+(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val LOOKS_LIKE_BOX = Regex("^\\[.?]")

    private fun indentOf(text: String): String = text.takeWhile { it == ' ' || it == '\t' || it == '>' }

    /** Number of note lines following the task at [taskIndex] (0 if none). */
    fun blockLength(lines: List<VaultText.Line>, taskIndex: Int): Int = block(lines, taskIndex).size

    /** The note lines (bullet content, unescaped) belonging to the task at [taskIndex]. */
    fun block(lines: List<VaultText.Line>, taskIndex: Int): List<String> {
        val taskIndent = indentOf(lines[taskIndex].text)
        val out = ArrayList<String>()
        var j = taskIndex + 1
        while (j < lines.size) {
            val t = lines[j].text
            if (TaskParser.parseLine(t) != null) break // a sub-task ends the notes
            val m = NOTE.matchEntire(t) ?: break
            val indent = m.groupValues[1]
            if (indent.length <= taskIndent.length || !indent.startsWith(taskIndent)) break
            out += unescape(m.groupValues[3])
            j++
        }
        return out
    }

    fun parse(lines: List<VaultText.Line>, taskIndex: Int): String = block(lines, taskIndex).joinToString("\n")

    /** The vault lines for [notes] under a task line whose own indent is that of [taskLine]. Blank lines are dropped. */
    fun render(taskLine: String, notes: String): List<String> {
        val indent = indentOf(taskLine) + "\t"
        return notes.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.map { "$indent- ${escape(it.trimStart())}" }
    }

    /** Normal form used to decide whether the user changed the notes. */
    fun normalize(notes: String): String = notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun escape(s: String) = if (LOOKS_LIKE_BOX.containsMatchIn(s)) "\\$s" else s

    private fun unescape(s: String) = if (s.startsWith("\\[")) s.substring(1) else s
}
