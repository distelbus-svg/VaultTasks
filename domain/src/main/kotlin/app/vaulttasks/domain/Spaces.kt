package app.vaulttasks.domain

/**
 * Spec §6. A space is a named group of vault files. [files] are vault-relative paths; order is display order.
 * Deleting a space never touches vault files.
 */
data class Space(
    val id: String,
    val name: String,
    val files: List<String> = emptyList(),
    val defaultFile: String? = null,
) {
    /** File a new task is written to unless the user picks another (spec §7.2): default, else first. */
    val creationFile: String? get() = defaultFile?.takeIf { it in files } ?: files.firstOrNull()
}

/** All spaces plus the active one. Immutable; every operation returns a new value, unknown ids are no-ops. */
data class SpacesData(
    val spaces: List<Space> = emptyList(),
    val activeId: String? = null,
) {
    /** The active space; falls back to the first if [activeId] is unset or dangling. */
    val active: Space? get() = spaces.firstOrNull { it.id == activeId } ?: spaces.firstOrNull()

    /** Union of all files that belong to any space. Only these are scanned (spec §9 scope). */
    val assignedFiles: Set<String> get() = spaces.flatMapTo(LinkedHashSet()) { it.files }

    fun add(id: String, name: String): SpacesData {
        val n = name.trim()
        if (n.isEmpty() || spaces.any { it.id == id }) return this
        return copy(spaces = spaces + Space(id, n), activeId = id)
    }

    fun rename(id: String, name: String): SpacesData {
        val n = name.trim()
        if (n.isEmpty()) return this
        return mapSpace(id) { it.copy(name = n) }
    }

    fun delete(id: String): SpacesData {
        val rest = spaces.filterNot { it.id == id }
        if (rest.size == spaces.size) return this
        return copy(spaces = rest, activeId = if (activeId == id) rest.firstOrNull()?.id else activeId)
    }

    /** Moves a space by [delta] positions (negative = up), clamped to the list bounds. */
    fun move(id: String, delta: Int): SpacesData {
        val from = spaces.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, spaces.lastIndex)
        if (to == from) return this
        val list = spaces.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(spaces = list)
    }

    /** Replaces the file list (deduplicated, order kept). The default file is dropped if it left the list. */
    fun setFiles(id: String, files: List<String>): SpacesData = mapSpace(id) {
        val f = files.distinct()
        it.copy(files = f, defaultFile = it.defaultFile?.takeIf { d -> d in f })
    }

    /** [file] must belong to the space; anything else clears the default. */
    fun setDefault(id: String, file: String?): SpacesData = mapSpace(id) {
        it.copy(defaultFile = file?.takeIf { f -> f in it.files })
    }

    fun setActive(id: String): SpacesData = if (spaces.any { it.id == id }) copy(activeId = id) else this

    private fun mapSpace(id: String, f: (Space) -> Space): SpacesData {
        if (spaces.none { it.id == id }) return this
        return copy(spaces = spaces.map { if (it.id == id) f(it) else it })
    }
}
