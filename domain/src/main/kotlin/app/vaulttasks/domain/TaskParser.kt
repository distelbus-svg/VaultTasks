package app.vaulttasks.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Declaration order == Tasks' serialization order (spec §4.1 editing rules). */
enum class SigKind { ID, DEPENDS, PRIORITY, RECURRENCE, ON_COMPLETION, CREATED, START, SCHEDULED, DUE, CANCELLED, DONE }

/** One trailing signifier exactly as found: [sep] = whitespace before it, [raw] = its bytes. */
data class Token(val kind: SigKind, val sep: String, val raw: String, val value: String)

/** A task line split into parts that can be re-joined byte-exactly (render() == rawLine, modulo trailing whitespace). */
data class ParsedLine(
    val indent: String,
    val bullet: String,
    val gap1: String,
    val mark: Char,
    val gap2: String,
    val headText: String,   // description text, without the time token
    val timeRaw: String?,   // " ⏰ HH:mm" as found inside the description (incl. its leading whitespace)
    val time: LocalTime?,   // due time; only set when a valid due date exists (head OR legacy position)
    val tokens: List<Token>,
    val state: TaskState,
    val rawLine: String,
) {
    val description: String get() = headText.trim()
    val dueDate: LocalDate? get() = tokens.lastOrNull { it.kind == SigKind.DUE }?.let { TaskParser.parseDate(it.value) }
    val doneDate: LocalDate? get() = tokens.lastOrNull { it.kind == SigKind.DONE }?.let { TaskParser.parseDate(it.value) }
    val priority: Priority? get() =
        tokens.lastOrNull { it.kind == SigKind.PRIORITY }?.let { t -> Priority.entries.firstOrNull { it.emoji == t.value } }
    val isRecurring: Boolean get() = tokens.any { it.kind == SigKind.RECURRENCE }

    fun render(): String =
        indent + bullet + gap1 + "[" + mark + "]" + gap2 + headText + (timeRaw ?: "") +
            tokens.joinToString("") { it.sep + it.raw }

    fun toTask(id: TaskId, lineIndex: Int) =
        Task(id, lineIndex, state, description, dueDate, time, priority, isRecurring, doneDate, rawLine)
}

object TaskParser {
    // Bullets: - * + 1.   Indent may contain '>' (Tasks reads tasks in blockquotes/callouts).
    private val LINE = Regex("^([ \\t>]*)([-*+]|\\d+[.)])([ \\t]+)\\[(.)\\]([ \\t]+)(.*)$", RegexOption.DOT_MATCHES_ALL)

    // Tasks emoji-format trailing signifiers (mirrors DefaultTaskSerializer; verify against current Tasks source).
    private const val VS = "\uFE0F?"
    private const val DATE = "(\\d{4}-\\d{2}-\\d{2})"
    private val SIGNIFIERS: List<Pair<SigKind, Regex>> = listOf(
        SigKind.PRIORITY to Regex("(🔺|⏫|🔼|🔽|⏬)$VS\\z"),
        SigKind.START to Regex("🛫$VS *$DATE\\z"),
        SigKind.CREATED to Regex("➕$VS *$DATE\\z"),
        SigKind.SCHEDULED to Regex("(?:⏳|⌛)$VS *$DATE\\z"),
        SigKind.DUE to Regex("(?:📅|📆|🗓)$VS *$DATE\\z"),
        SigKind.DONE to Regex("✅$VS *$DATE\\z"),
        SigKind.CANCELLED to Regex("❌$VS *$DATE\\z"),
        SigKind.RECURRENCE to Regex("🔁$VS *([a-zA-Z0-9, !]+)\\z"),
        SigKind.ON_COMPLETION to Regex("🏁$VS *([a-zA-Z]+)\\z"),
        SigKind.DEPENDS to Regex("⛔$VS *([a-zA-Z0-9_-]+( *, *[a-zA-Z0-9_-]+ *)*)\\z"),
        SigKind.ID to Regex("🆔$VS *([a-zA-Z0-9_-]+)\\z"),
    )
    private val TIME_TAIL = Regex("(\\s*)⏰$VS\\s*([01]\\d|2[0-3]):([0-5]\\d)\\z")

    private class Scan(val head: String, val tokens: List<Token>, val legacyTime: LocalTime?)

    /** Walk back from the end consuming signifiers, like Tasks does. Optionally skips one interior/legacy ⏰ HH:mm. */
    private fun scan(body: String, allowLegacy: Boolean): Scan {
        var rest = body.trimEnd()
        val out = ArrayDeque<Token>()
        var legacy: LocalTime? = null
        while (true) {
            val hit = SIGNIFIERS.firstNotNullOfOrNull { (kind, re) -> re.find(rest)?.let { kind to it } }
            if (hit != null) {
                val (kind, m) = hit
                val before = rest.substring(0, m.range.first)
                val head = before.trimEnd()
                out.addFirst(Token(kind, before.substring(head.length), rest.substring(m.range.first), m.groupValues[1]))
                rest = head
                continue
            }
            if (allowLegacy && legacy == null) {
                val t = TIME_TAIL.find(rest)
                if (t != null) {
                    legacy = LocalTime.of(t.groupValues[2].toInt(), t.groupValues[3].toInt())
                    rest = rest.substring(0, t.range.first)
                    continue
                }
            }
            break
        }
        return Scan(rest, out.toList(), legacy)
    }

    fun parseDate(s: String): LocalDate? = try { LocalDate.parse(s) } catch (e: DateTimeParseException) { null }

    fun parseLine(line: String, globalFilter: String = ""): ParsedLine? {
        val m = LINE.matchEntire(line) ?: return null
        val (indent, bullet, gap1, markStr, gap2, body) = m.destructured
        val state = when (markStr[0]) {
            ' ' -> TaskState.OPEN
            'x', 'X' -> TaskState.DONE
            '-' -> TaskState.CANCELLED
            else -> return null // custom statuses are not tasks in v1
        }
        if (body.isBlank()) return null
        if (globalFilter.isNotEmpty() && !body.contains(globalFilter)) return null

        var sc = scan(body, allowLegacy = true)
        fun hasDue(s: Scan) = s.tokens.lastOrNull { it.kind == SigKind.DUE }?.let { parseDate(it.value) } != null
        // A skipped ⏰ only counts as legacy time if a due date sits before it; otherwise it is plain text.
        if (sc.legacyTime != null && !hasDue(sc)) sc = scan(body, allowLegacy = false)

        var head = sc.head
        var timeRaw: String? = null
        var time: LocalTime? = null
        if (hasDue(sc)) {
            time = sc.legacyTime
            if (time == null) {
                TIME_TAIL.find(head)?.let { t ->
                    time = LocalTime.of(t.groupValues[2].toInt(), t.groupValues[3].toInt())
                    timeRaw = t.value
                    head = head.substring(0, t.range.first)
                }
            }
        }
        return ParsedLine(indent, bullet, gap1, markStr[0], gap2, head, timeRaw, time, sc.tokens, state, line)
    }

    /** Identity text: trimmed, variation selectors removed, NBSP → space. */
    fun normalize(line: String): String = line.replace("\uFE0F", "").replace('\u00A0', ' ').trim()

    /** Parses every task of one file; skips fenced code blocks. Identity = (path, normalized text, nth identical line). */
    fun parseFile(path: String, text: VaultText, globalFilter: String = ""): List<Task> {
        val seen = HashMap<String, Int>()
        val out = ArrayList<Task>()
        var fence: String? = null
        text.lines.forEachIndexed { i, l ->
            val t = l.text.trimStart()
            val f = when {
                t.startsWith("```") -> "```"
                t.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (f != null) {
                fence = if (fence == null) f else if (fence == f) null else fence
                return@forEachIndexed
            }
            if (fence != null) return@forEachIndexed
            val p = parseLine(l.text, globalFilter) ?: return@forEachIndexed
            val norm = normalize(l.text)
            val occurrence = seen.merge(norm, 1, Int::plus)!! - 1
            out += p.toTask(TaskId(path, norm, occurrence), i)
        }
        return out
    }
}
