package app.vaulttasks.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Spec §7.3 month view, pure parts. */
object TaskCalendar {
    /**
     * The days shown for [month]: whole weeks (5 or 6 rows of 7), starting on [firstDayOfWeek], padded with the
     * neighbouring months' days.
     */
    fun gridDays(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val rows = (lead + month.lengthOfMonth() + 6) / 7
        val start = first.minusDays(lead.toLong())
        return List(rows * 7) { start.plusDays(it.toLong()) }
    }

    /**
     * Tasks per due date. Undated and cancelled tasks are left out (spec §7.3, §15.5). Within a day: open before
     * done, then by time (untimed last), then the space's file order, then line.
     */
    fun byDay(tasks: List<Task>, fileOrder: List<String> = emptyList()): Map<LocalDate, List<Task>> {
        val rank = fileOrder.withIndex().associate { it.value to it.index }
        val order = compareBy<Task>(
            { it.state == TaskState.DONE },
            { it.dueTime == null },
            { it.dueTime },
            { rank[it.id.path] ?: Int.MAX_VALUE },
            { it.id.path },
            { it.lineIndex },
        )
        return tasks
            .filter { it.state != TaskState.CANCELLED && it.dueDate != null }
            .groupBy { it.dueDate!! }
            .mapValues { (_, day) -> day.sortedWith(order) }
    }
}
