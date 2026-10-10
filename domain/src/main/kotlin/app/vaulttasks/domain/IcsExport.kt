package app.vaulttasks.domain

import app.vaulttasks.domain.alarms.AlarmPlanner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * iCalendar export of the coming tasks, for Apple Calendar (and anything else that reads .ics).
 *
 * "Coming" = open, with a due date of [today] or later. Done, cancelled, undated and overdue tasks are left out.
 * Events carry a stable UID (file + description, not the line, so a changed date or time updates the event on the next
 * import instead of duplicating it), the notes as description, the category, a repeat rule where one can be expressed,
 * and an alarm that matches the task's reminder (lead time; date-only tasks use the default reminder time).
 */
object IcsExport {
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private const val TIMED_MINUTES = 30L

    fun coming(tasks: Collection<Task>, today: LocalDate): List<Task> =
        tasks.filter { it.state == TaskState.OPEN && it.dueDate != null && !it.dueDate.isBefore(today) }
            .sortedWith(compareBy({ it.dueDate }, { it.dueTime == null }, { it.dueTime }, { it.id.path }, { it.lineIndex }))

    fun build(
        tasks: Collection<Task>,
        now: LocalDateTime,
        defaultTime: LocalTime,
        leadMinutes: (Task) -> Int = { 0 },
    ): String {
        val stamp = now.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).format(STAMP)
        val seen = HashMap<String, Int>()
        val out = ArrayList<String>()
        out += "BEGIN:VCALENDAR"
        out += "VERSION:2.0"
        out += "PRODID:-//VaultTasks//Task export//EN"
        out += "CALSCALE:GREGORIAN"
        out += "X-WR-CALNAME:VaultTasks"
        for (t in coming(tasks, now.toLocalDate())) {
            val date = t.dueDate ?: continue
            val key = AlarmPlanner.leadKey(t.id.path, t.description)
            val n = seen.merge(key, 1, Int::plus)!! - 1
            val (title, category) = Categories.split(t.description)
            out += "BEGIN:VEVENT"
            out += "UID:${uid(key, n)}@vaulttasks"
            out += "DTSTAMP:$stamp"
            out += "SUMMARY:${escape(title)}"
            val time = t.dueTime
            if (time == null) {
                out += "DTSTART;VALUE=DATE:${date.format(DATE)}"
                out += "DTEND;VALUE=DATE:${date.plusDays(1).format(DATE)}"
            } else {
                val start = LocalDateTime.of(date, time)
                out += "DTSTART:${start.format(DATE_TIME)}"
                out += "DTEND:${start.plusMinutes(TIMED_MINUTES).format(DATE_TIME)}"
            }
            if (t.notes.isNotBlank()) out += "DESCRIPTION:${escape(t.notes)}"
            if (category != null) out += "CATEGORIES:${escape(category)}"
            t.recurrence?.let { r -> Recurrences.rrule(r)?.let { out += "RRULE:$it" } }
            val lead = leadMinutes(t)
            out += "BEGIN:VALARM"
            out += "ACTION:DISPLAY"
            out += "DESCRIPTION:${escape(title)}"
            out += "TRIGGER:" + if (time != null) trigger(-lead.toLong()) else trigger(defaultTime.hour * 60L + defaultTime.minute - lead)
            out += "END:VALARM"
            out += "END:VEVENT"
        }
        out += "END:VCALENDAR"
        return out.joinToString("") { fold(it) + "\r\n" }
    }

    /** Offset from the event start, in iCalendar duration syntax. */
    private fun trigger(minutes: Long): String =
        if (minutes == 0L) "PT0S" else (if (minutes < 0) "-" else "") + "PT${kotlin.math.abs(minutes)}M"

    private fun uid(key: String, n: Int): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest("$key\u0000$n".toByteArray(Charsets.UTF_8))
        return d.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")

    /** RFC 5545 §3.1: lines are at most 75 octets; continuation lines start with one space. Never splits a character. */
    internal fun fold(line: String): String {
        val sb = StringBuilder()
        var bytes = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + len > limit) {
                sb.append("\r\n ")
                bytes = 0
                limit = 74 // the leading space counts
            }
            sb.appendCodePoint(cp)
            bytes += len
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
