package app.vaulttasks.domain

import java.time.LocalDate

/**
 * Test helper (spec §12.7): an independent re-implementation of how the Obsidian Tasks plugin reads
 * the emoji format — repeatedly strip a recognised signifier from the END of the line, trim, and stop
 * when nothing matches. It knows nothing about ⏰. Regexes mirror Tasks' DefaultTaskSerializer;
 * re-check them against the current Tasks release when upgrading.
 */
object TasksStyleParser {
    data class View(val due: LocalDate?, val done: LocalDate?, val priority: String?)

    private val LINE = Regex("^([\\s>]*)([-*+]|[0-9]+[.)]) +\\[(.)\\] *(.*)$")
    private const val VS = "\uFE0F?"
    private val PRIORITY = Regex("(🔺|⏫|🔼|🔽|⏬)$VS$")
    private fun date(e: String) = Regex("$e$VS *(\\d{4}-\\d{2}-\\d{2})$")
    private val DUE = date("(?:📅|📆|🗓)")
    private val DONE = date("✅")
    private val OTHER = listOf(
        date("🛫"), date("➕"), date("(?:⏳|⌛)"), date("❌"),
        Regex("🔁$VS *([a-zA-Z0-9, !]+)$"), Regex("🏁$VS *([a-zA-Z]+)$"),
        Regex("⛔$VS *([a-zA-Z0-9-_]+( *, *[a-zA-Z0-9-_]+ *)*)$"), Regex("🆔$VS *([a-zA-Z0-9-_]+)$"),
    )

    fun parse(line: String): View? {
        val m = LINE.matchEntire(line) ?: return null
        var s = m.groupValues[4].trim()
        var due: LocalDate? = null; var done: LocalDate? = null; var prio: String? = null
        var matched = true
        while (matched) {
            matched = false
            PRIORITY.find(s)?.let { prio = it.groupValues[1]; s = s.removeRange(it.range).trim(); matched = true }
            DUE.find(s)?.let { due = runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull(); s = s.removeRange(it.range).trim(); matched = true }
            DONE.find(s)?.let { done = runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull(); s = s.removeRange(it.range).trim(); matched = true }
            for (re in OTHER) re.find(s)?.let { s = s.removeRange(it.range).trim(); matched = true }
        }
        return View(due, done, prio)
    }
}
