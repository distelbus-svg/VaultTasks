package app.vaulttasks.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** What an edit does with a task's 🔁 rule. [Keep] leaves it exactly as it is (snooze, notification edits, …). */
sealed interface RecurrenceEdit {
    data object Keep : RecurrenceEdit
    data object Clear : RecurrenceEdit
    data class Set(val rule: String) : RecurrenceEdit
}

enum class RepeatUnit(val word: String) { DAY("day"), WEEK("week"), MONTH("month"), YEAR("year") }

/**
 * The subset of the Tasks recurrence syntax the app can advance itself:
 *   every [N] day|week|month|year   ·   every weekday   ·   every [week on] monday[, friday…]   ·   … [when done]
 * Anything else (e.g. "every 3rd Friday") is kept untouched in the file and is completed in Obsidian, like before.
 */
data class Recurrence(
    val interval: Int,
    val unit: RepeatUnit,
    /** Non-null for weekday rules ("every weekday", "every monday"): the next date is the next listed weekday. */
    val weekdays: Set<DayOfWeek>?,
    val whenDone: Boolean,
)

object Recurrences {
    private val WORD_UNIT = Regex("^every (?:(\\d+) )?(day|week|month|year)s?$")
    private val DAYS = DayOfWeek.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() }
    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    fun parse(rule: String): Recurrence? {
        var r = rule.trim().lowercase().replace(Regex("\\s+"), " ")
        var whenDone = false
        if (r.endsWith(" when done")) {
            whenDone = true
            r = r.removeSuffix(" when done").trim()
        }
        WORD_UNIT.matchEntire(r)?.let { m ->
            val n = m.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return null
            if (n < 1 || n > 999) return null
            return Recurrence(n, RepeatUnit.valueOf(m.groupValues[2].uppercase()), null, whenDone)
        }
        if (r == "every weekday") return Recurrence(1, RepeatUnit.WEEK, WEEKDAYS, whenDone)
        val list = r.removePrefix("every week on ").takeIf { it != r } ?: r.removePrefix("every ").takeIf { it != r } ?: return null
        val days = list.split(Regex(",\\s*|\\s+and\\s+")).map { DAYS[it.trim()] ?: return null }
        return if (days.isEmpty()) null else Recurrence(1, RepeatUnit.WEEK, days.toSet(), whenDone)
    }

    /** The next due date after completing an occurrence that was due on [due], finished on [completedOn]. */
    fun next(r: Recurrence, due: LocalDate, completedOn: LocalDate): LocalDate {
        val base = if (r.whenDone) completedOn else due
        r.weekdays?.let { days ->
            var d = base.plusDays(1)
            while (d.dayOfWeek !in days) d = d.plusDays(1)
            return d
        }
        val n = r.interval.toLong()
        return when (r.unit) {
            RepeatUnit.DAY -> base.plusDays(n)
            RepeatUnit.WEEK -> base.plusWeeks(n)
            RepeatUnit.MONTH -> base.plusMonths(n)
            RepeatUnit.YEAR -> base.plusYears(n)
        }
    }

    fun rule(interval: Int, unit: RepeatUnit): String =
        if (interval == 1) "every ${unit.word}" else "every $interval ${unit.word}s"

    /** Short label for the UI, e.g. "Every 2 weeks". Unknown rules are shown as they are written. */
    fun label(rule: String): String {
        val r = parse(rule) ?: return rule.replaceFirstChar { it.uppercase() }
        val base = when {
            r.weekdays == WEEKDAYS -> "Every weekday"
            r.weekdays != null -> "Every " + r.weekdays.sortedBy { it.value }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
            else -> rule(r.interval, r.unit).replaceFirstChar { it.uppercase() }
        }
        return if (r.whenDone) "$base (when done)" else base
    }

    /** iCalendar RRULE value for [rule], or null if it cannot be expressed. "When done" has no equivalent and is exported as a plain repeat. */
    fun rrule(rule: String): String? {
        val r = parse(rule) ?: return null
        r.weekdays?.let { days ->
            val by = days.sortedBy { it.value }.joinToString(",") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(2).uppercase() }
            return "FREQ=WEEKLY;BYDAY=$by"
        }
        val freq = when (r.unit) { RepeatUnit.DAY -> "DAILY"; RepeatUnit.WEEK -> "WEEKLY"; RepeatUnit.MONTH -> "MONTHLY"; RepeatUnit.YEAR -> "YEARLY" }
        return "FREQ=$freq" + if (r.interval > 1) ";INTERVAL=${r.interval}" else ""
    }
}
