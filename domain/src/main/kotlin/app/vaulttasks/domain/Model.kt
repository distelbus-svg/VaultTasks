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
)
