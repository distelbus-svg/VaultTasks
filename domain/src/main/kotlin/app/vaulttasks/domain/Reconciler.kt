package app.vaulttasks.domain

data class Changes(val inserts: List<Task>, val updates: List<Task>, val deletes: List<TaskId>) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty()
}

object Reconciler {
    /**
     * Pure diff by identity. [cached] and [parsed] must cover the same scope (the file(s) just rescanned).
     * Never produces two rows for one identity: duplicate ids in [parsed] are a bug and throw.
     */
    fun reconcile(cached: List<Task>, parsed: List<Task>): Changes {
        val parsedIds = HashSet<TaskId>()
        for (t in parsed) require(parsedIds.add(t.id)) { "duplicate task id in parsed set: ${t.id}" }
        val cachedById = cached.associateBy { it.id }
        return Changes(
            inserts = parsed.filter { it.id !in cachedById },
            updates = parsed.filter { p -> cachedById[p.id]?.let { it != p } ?: false },
            deletes = cached.map { it.id }.distinct().filter { it !in parsedIds },
        )
    }
}
