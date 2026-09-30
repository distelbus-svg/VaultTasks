package app.vaulttasks.domain

import java.time.LocalDate

data class TaskGroups(
    val overdue: List<Task> = emptyList(),
    val today: List<Task> = emptyList(),
    val upcoming: List<Task> = emptyList(),
    val noDate: List<Task> = emptyList(),
    val done: List<Task> = emptyList(),
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && today.isEmpty() && upcoming.isEmpty() && noDate.isEmpty() && done.isEmpty()
}

/** Spec §7.1 grouping. Pure: [today] is passed in. */
object TaskGrouping {
    /**
     * @param tasks tasks of the active space's files
     * @param fileOrder space file order, used as tie-break so the list follows the space's file order
     *
     * Cancelled tasks are dropped (spec §15.5). Dated groups sort by date, then time (untimed last), then
     * file order, then line. Undated and done tasks keep file order, then line.
     */
    fun group(tasks: List<Task>, today: LocalDate, fileOrder: List<String> = emptyList()): TaskGroups {
        val rank = fileOrder.withIndex().associate { it.value to it.index }
        val fileThenLine = compareBy<Task>({ rank[it.id.path] ?: Int.MAX_VALUE }, { it.id.path }, { it.lineIndex })
        val byDue = compareBy<Task>({ it.dueDate }, { it.dueTime == null }, { it.dueTime }).then(fileThenLine)

        val visible = tasks.filter { it.state != TaskState.CANCELLED }
        val (done, open) = visible.partition { it.state == TaskState.DONE }
        val dated = open.filter { it.dueDate != null }
        return TaskGroups(
            overdue = dated.filter { it.dueDate!! < today }.sortedWith(byDue),
            today = dated.filter { it.dueDate == today }.sortedWith(byDue),
            upcoming = dated.filter { it.dueDate!! > today }.sortedWith(byDue),
            noDate = open.filter { it.dueDate == null }.sortedWith(fileThenLine),
            done = done.sortedWith(fileThenLine),
        )
    }
}
