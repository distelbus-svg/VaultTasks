package app.vaulttasks.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * [notes] == null leaves the task's notes untouched; [recurrence] == Keep leaves its 🔁 rule untouched. Callers that
 * only change date/time (e.g. "move to tomorrow") therefore never lose either.
 */
data class TaskFields(
    val description: String,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val notes: String? = null,
    val recurrence: RecurrenceEdit = RecurrenceEdit.Keep,
)

/**
 * All edits return the new text of ONE line. Untouched parts keep their original bytes.
 * Time is always written inside the description, before every Tasks signifier (spec §4.1).
 */
object TaskSerializer {
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    private fun timeRaw(t: LocalTime) = " ⏰ ${t.format(HHMM)}"

    fun applyFields(line: ParsedLine, f: TaskFields, globalFilter: String = ""): String {
        val desc = f.description.trim()
        require(desc.isNotEmpty()) { "description required" }
        var head = if (desc == line.description) line.headText else desc
        if (globalFilter.isNotEmpty() && !head.contains(globalFilter)) head = "$globalFilter $head"

        val date = f.dueDate
        val time = if (date != null) f.dueTime else null // no date → no time
        val tRaw = when {
            time == null -> null
            time == line.time -> line.timeRaw ?: timeRaw(time) // legacy position → rewritten into the description
            else -> timeRaw(time)
        }
        var tokens = if (date == null) {
            setToken(line.tokens, SigKind.DUE, null, null)
        } else {
            setToken(line.tokens, SigKind.DUE, "📅 $date", date.toString())
        }
        tokens = when (val r = f.recurrence) {
            RecurrenceEdit.Keep -> tokens
            RecurrenceEdit.Clear -> setToken(tokens, SigKind.RECURRENCE, null, null)
            is RecurrenceEdit.Set ->
                if (date == null) setToken(tokens, SigKind.RECURRENCE, null, null) // a repeat needs a date to repeat from
                else setToken(tokens, SigKind.RECURRENCE, "🔁 ${r.rule}", r.rule)
        }
        return line.copy(headText = head, timeRaw = tRaw, tokens = tokens).render()
    }

    /** open⇄done. Cancelled tasks are refused. A recurring task is completed with [complete] instead (needs two lines). */
    fun setDone(line: ParsedLine, done: Boolean, doneDate: LocalDate?): String {
        check(line.state != TaskState.CANCELLED) { "cancelled tasks are not toggled" }
        val mark = if (!done) ' ' else if (line.state == TaskState.DONE) line.mark else 'x'
        val tokens = when {
            !done -> setToken(line.tokens, SigKind.DONE, null, null)
            doneDate != null -> setToken(line.tokens, SigKind.DONE, "✅ $doneDate", doneDate.toString())
            else -> line.tokens
        }
        // keep a legacy-position time: move it into the description
        val tRaw = line.timeRaw ?: line.time?.let { timeRaw(it) }
        return line.copy(mark = mark, timeRaw = tRaw, tokens = tokens).render()
    }

    /**
     * Completing an open recurring task, like Tasks does: the line is marked done and a fresh open copy with the next
     * due date follows it. Returns (done line, next occurrence). Start and scheduled dates move by the same distance.
     */
    fun complete(line: ParsedLine, doneDate: LocalDate?, nextDue: LocalDate): Pair<String, String> {
        val doneLine = setDone(line, true, doneDate)
        val shift = line.dueDate?.let { ChronoUnit.DAYS.between(it, nextDue) } ?: 0L
        var tokens = setToken(line.tokens, SigKind.DONE, null, null)
        tokens = setToken(tokens, SigKind.DUE, "📅 $nextDue", nextDue.toString())
        tokens = tokens.map { t ->
            if (t.kind != SigKind.START && t.kind != SigKind.SCHEDULED) return@map t
            val d = TaskParser.parseDate(t.value) ?: return@map t
            val moved = d.plusDays(shift)
            t.copy(raw = t.raw.replace(t.value, moved.toString()), value = moved.toString())
        }
        val tRaw = line.timeRaw ?: line.time?.let { timeRaw(it) }
        return doneLine to line.copy(mark = ' ', timeRaw = tRaw, tokens = tokens).render()
    }

    fun newLine(f: TaskFields, globalFilter: String = "", bullet: String = "-"): String {
        val desc = f.description.trim()
        require(desc.isNotEmpty()) { "description required" }
        val head = if (globalFilter.isNotEmpty() && !desc.contains(globalFilter)) "$globalFilter $desc" else desc
        val sb = StringBuilder("$bullet [ ] $head")
        val date = f.dueDate
        if (date != null) {
            f.dueTime?.let { sb.append(timeRaw(it)) }
            (f.recurrence as? RecurrenceEdit.Set)?.let { sb.append(" 🔁 ").append(it.rule) }
            sb.append(" 📅 ").append(date)
        }
        return sb.toString()
    }

    /** raw == null removes every token of [kind]; otherwise replaces in place or inserts at Tasks' position. */
    internal fun setToken(tokens: List<Token>, kind: SigKind, raw: String?, value: String?): List<Token> {
        if (raw == null) return tokens.filterNot { it.kind == kind }
        val i = tokens.indexOfLast { it.kind == kind }
        val out = tokens.toMutableList()
        if (i >= 0) {
            if (tokens[i].value != value) out[i] = tokens[i].copy(raw = raw, value = value.orEmpty())
            return out
        }
        val at = tokens.indexOfFirst { it.kind.ordinal > kind.ordinal }.let { if (it < 0) tokens.size else it }
        out.add(at, Token(kind, " ", raw, value.orEmpty()))
        return out
    }
}
