package app.vaulttasks.domain

import java.time.LocalDate
import java.time.LocalTime

enum class TaskState(val mark: Char) { OPEN(' '), DONE('x'), CANCELLED('-') }

enum class Priority(val emoji: String) {
    HIGHEST("🔺"), HIGH("⏫"), MEDIUM("🔼"), LOW("🔽"), LOWEST("⏬")
}

/** Identity per spec §4.3. lineIndex is deliberately NOT part of it. */
data class TaskId(val path: String, val normalizedText: String, val occurrence: Int)

data class Task(
    val id: TaskId,
    val lineIndex: Int, // hint only
    val state: TaskState,
    val description: String, // without ⏰ HH:mm and without Tasks signifiers
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val priority: Priority?,
    val isRecurring: Boolean,
    val doneDate: LocalDate?,
    val rawLine: String,
    /** Sub-bullet lines under the task, joined with '\n' (see [Notes]); "" if none. Not part of the identity. */
    val notes: String = "",
    /** Rule text after 🔁 (e.g. "every week"), null if not recurring. */
    val recurrence: String? = null,
) {
    /** Trailing `#tag` of the description, if any; the app's "category" (a plain Obsidian tag, so Tasks queries can use it). */
    val category: String? get() = Categories.split(description).second

    /** Whether the checkbox can be toggled in the app: recurring tasks only if their rule is one we can advance. */
    val completable: Boolean
        get() = state != TaskState.CANCELLED &&
            (!isRecurring || state == TaskState.DONE || (dueDate != null && Recurrences.parse(recurrence.orEmpty()) != null))
}
