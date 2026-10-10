package app.vaulttasks.domain

/**
 * A category is the LAST word of a task's description when it is an Obsidian tag: `Buy milk #home`.
 * Plain tags keep Obsidian Tasks happy (it filters and groups by them natively) and need no extra file format.
 * Tags in the middle of a sentence are left alone and are not categories.
 */
object Categories {
    private val TRAILING = Regex("^(.*?)(?:^|\\s+)#([\\p{L}\\p{N}_/-]+)$", RegexOption.DOT_MATCHES_ALL)

    /** (description without its trailing tag, tag without '#' or null). A bare "#tag" description is not split. */
    fun split(description: String): Pair<String, String?> {
        val d = description.trim()
        val m = TRAILING.matchEntire(d) ?: return d to null
        val tag = m.groupValues[2]
        val rest = m.groupValues[1].trim()
        if (rest.isEmpty() || tag.all { it.isDigit() }) return d to null // Obsidian: a tag needs a non-digit; a title is required
        return rest to tag
    }

    /** Normalizes user input to a tag name: no '#', whitespace → '-'. Null if nothing usable is left. */
    fun clean(input: String): String? {
        val t = input.trim().trimStart('#').trim().replace(Regex("\\s+"), "-").filter { it.isLetterOrDigit() || it in "_/-" }
        return t.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
    }

    fun join(description: String, category: String?): String {
        val d = description.trim()
        return if (category == null) d else "$d #$category"
    }

    /** Distinct categories of [tasks], alphabetical (case-insensitive). */
    fun of(tasks: Collection<Task>): List<String> =
        tasks.mapNotNull { it.category }.distinct().sortedBy { it.lowercase() }
}
