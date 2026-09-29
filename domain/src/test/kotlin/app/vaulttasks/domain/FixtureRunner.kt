package app.vaulttasks.domain

import java.time.LocalDate
import java.time.LocalTime

/** Runs parser-fixtures.json (language-neutral; the macOS port reuses the file). Returns failure messages. */
object FixtureRunner {
    @Suppress("UNCHECKED_CAST")
    fun load(): Map<String, Any?> =
        MiniJson.parse(FixtureRunner::class.java.getResourceAsStream("/parser-fixtures.json")!!.readBytes().toString(Charsets.UTF_8)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun caseCount(key: String) = cases(key).size

    @Suppress("UNCHECKED_CAST")
    private fun cases(key: String) = load()[key] as List<Map<String, Any?>>

    private fun fmt(line: Int, occ: Int, state: String, desc: String, due: Any?, time: Any?, prio: Any?, rec: Boolean, done: Any?) =
        "line=$line occ=$occ state=$state desc=[$desc] due=$due time=$time prio=$prio rec=$rec done=$done"

    private fun fmt(t: Task) = fmt(t.lineIndex, t.id.occurrence, t.state.name.lowercase(), t.description, t.dueDate, t.dueTime,
        t.priority?.name?.lowercase(), t.isRecurring, t.doneDate)

    @Suppress("UNCHECKED_CAST")
    fun parseFailures(): List<String> = cases("parseCases").flatMap { c ->
        val name = c["name"] as String
        val filter = c["globalFilter"] as String? ?: ""
        val tasks = TaskParser.parseFile("f.md", VaultText.parse(c["text"] as String), filter)
        val expected = (c["tasks"] as List<Map<String, Any?>>).map {
            fmt((it["line"] as Double).toInt(), ((it["occurrence"] as Double?) ?: 0.0).toInt(), it["state"] as String? ?: "open",
                it["description"] as String, it["due"], it["time"], it["priority"], it["recurring"] as Boolean? ?: false, it["done"])
        }
        val out = ArrayList<String>()
        if (tasks.map(::fmt) != expected) out += "$name: parse mismatch\n  expected=$expected\n  actual  =${tasks.map(::fmt)}"
        // §12.7: independent Tasks-style reader must agree on priority/due/done.
        val blind = c["tasksBlind"] as Boolean? ?: false
        for (t in tasks) {
            val tv = TasksStyleParser.parse(t.rawLine)
            if (tv == null) { out += "$name: Tasks-style reader rejects '${t.rawLine}'"; continue }
            val appDue = if (blind) null else t.dueDate
            if (tv.due != appDue || tv.done != t.doneDate || tv.priority != t.priority?.emoji)
                out += "$name: Tasks view differs for '${t.rawLine}': $tv vs due=$appDue done=${t.doneDate} prio=${t.priority}"
        }
        out.map { it }
    }

    @Suppress("UNCHECKED_CAST")
    fun editFailures(): List<String> = cases("editCases").flatMap { c ->
        val name = c["name"] as String
        val filter = c["globalFilter"] as String? ?: ""
        val vt = VaultText.parse(c["text"] as String)
        val idx = (c["line"] as Double?)?.toInt() ?: -1
        fun fields(): TaskFields = TaskFields(
            c["description"] as String,
            (c["due"] as String?)?.let(LocalDate::parse),
            (c["time"] as String?)?.let(LocalTime::parse),
        )
        val doneDate = (c["doneDate"] as String?)?.let(LocalDate::parse)
        val newText: String
        var editedLine: String? = null
        if (c["op"] == "append") {
            editedLine = TaskSerializer.newLine(fields(), filter)
            newText = vt.appendLine(editedLine).toString()
        } else {
            val p = TaskParser.parseLine(vt.lines[idx].text, filter) ?: return@flatMap listOf("$name: target line is not a task")
            editedLine = when (c["op"]) {
                "setFields" -> TaskSerializer.applyFields(p, fields(), filter)
                "done" -> TaskSerializer.setDone(p, true, doneDate)
                "undone" -> TaskSerializer.setDone(p, false, null)
                else -> error("unknown op ${c["op"]}")
            }
            newText = vt.replaceLine(idx, editedLine).toString()
        }
        val out = ArrayList<String>()
        if (newText != c["expected"]) out += "$name: expected=${show(c["expected"] as String)}\n  actual  =${show(newText)}"
        // §4.2: every other line is byte-identical.
        val after = VaultText.parse(newText)
        val target = if (c["op"] == "append") vt.lines.size else idx
        fun others(v: VaultText, skip: Int) = v.lines.filterIndexed { i, _ -> i != skip }.let { l ->
            // an appended-to file legitimately gains an eol on its former last line
            if (c["op"] == "append") l.map { it.text } else l
        }
        if (c["op"] == "append") { if (others(vt, -1) != others(after, target)) out += "$name: other lines changed" }
        else if (others(vt, target) != others(after, target)) out += "$name: other lines changed"
        // §12.7 on the result: Tasks must read what the app reads.
        val tv = TasksStyleParser.parse(editedLine)
        val av = TaskParser.parseLine(editedLine, filter)
        if (tv == null || av == null) out += "$name: edited line unreadable: '$editedLine'"
        else if (tv.due != av.dueDate || tv.done != av.doneDate || tv.priority != av.priority?.emoji)
            out += "$name: Tasks sees different metadata on '$editedLine': $tv vs ${av.dueDate}/${av.doneDate}/${av.priority}"
        out
    }

    private fun show(s: String) = s.replace("\r", "\\r").replace("\n", "\\n").replace("\uFEFF", "<BOM>").replace("\u00A0", "<NBSP>")
}
