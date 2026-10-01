package app.vaulttasks.domain

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class TaskGroups(
    val overdue: List<Task> = emptyList(),
    val today: List<Task> = emptyList(),
    val upcoming: List<Task> = emptyList(),
    val noDate: List<Task> = emptyList(),
    val done: List<Task> = emptyList(),
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && today.isEmpty() && upcoming.isEmpty() && noDate.isEmpty() && done.isEmpty()
}

/** Spec §7.1 grouping. Pure: [now] is passed in. */
object TaskGrouping {
    /**
     * @param tasks tasks of the active space's files
     * @param now the current moment; a timed task becomes overdue once its due minute has passed, an untimed one at midnight
     * @param fileOrder space file order, used as tie-break so the list follows the space's file order
     *
     * Cancelled tasks are dropped (spec §15.5). Dated groups sort by date, then time (untimed last), then
     * file order, then line. Undated and done tasks keep file order, then line.
     */
    fun group(tasks: List<Task>, now: LocalDateTime, fileOrder: List<String> = emptyList()): TaskGroups {
        val today = now.toLocalDate()
        val minute = now.truncatedTo(ChronoUnit.MINUTES).toLocalTime()
        val rank = fileOrder.withIndex().associate { it.value to it.index }
        val fileThenLine = compareBy<Task>({ rank[it.id.path] ?: Int.MAX_VALUE }, { it.id.path }, { it.lineIndex })
        val byDue = compareBy<Task>({ it.dueDate }, { it.dueTime == null }, { it.dueTime }).then(fileThenLine)

        fun isOverdue(t: Task): Boolean {
            val d = t.dueDate ?: return false
            return d < today || (d == today && t.dueTime != null && t.dueTime < minute)
        }

        val visible = tasks.filter { it.state != TaskState.CANCELLED }
        val (done, open) = visible.partition { it.state == TaskState.DONE }
        val dated = open.filter { it.dueDate != null }
        return TaskGroups(
            overdue = dated.filter(::isOverdue).sortedWith(byDue),
            today = dated.filter { it.dueDate == today && !isOverdue(it) }.sortedWith(byDue),
            upcoming = dated.filter { it.dueDate!! > today }.sortedWith(byDue),
            noDate = open.filter { it.dueDate == null }.sortedWith(fileThenLine),
            done = done.sortedWith(fileThenLine),
        )
    }
}
