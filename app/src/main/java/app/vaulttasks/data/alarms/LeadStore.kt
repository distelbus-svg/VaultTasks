package app.vaulttasks.data.alarms

import android.content.Context
import app.vaulttasks.domain.alarms.AlarmPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Per-task "remind me before" in minutes (0 = at the due time = no entry). Kept on the device, not in the vault line,
 * because the line format has no field for it and must stay Obsidian Tasks-compatible (spec §4.1). Keyed by
 * [AlarmPlanner.leadKey] (path + description) so it survives date/time/state edits. A rename in Obsidian drops it.
 */
class LeadStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reminder_leads", Context.MODE_PRIVATE)
    private val _leads = MutableStateFlow(load())
    val leads: StateFlow<Map<String, Int>> = _leads.asStateFlow()

    fun get(path: String, description: String): Int = _leads.value[AlarmPlanner.leadKey(path, description)] ?: 0

    /** After the editor saved: the task may have moved file or changed description, so the old entry goes. */
    @Synchronized
    fun replace(oldPath: String?, oldDescription: String?, path: String, description: String, minutes: Int) = update { m ->
        val removed = if (oldPath != null && oldDescription != null) m - AlarmPlanner.leadKey(oldPath, oldDescription) else m
        val key = AlarmPlanner.leadKey(path, description)
        if (minutes > 0) removed + (key to minutes) else removed - key
    }

    fun clear(path: String, description: String) = replace(null, null, path, description, 0)

    /** Drops entries whose task is gone. Only touches [keys], so entries added meanwhile are never pruned by an older scan. */
    @Synchronized
    fun remove(keys: Collection<String>) {
        if (keys.isNotEmpty()) update { it - keys.toSet() }
    }

    private fun update(f: (Map<String, Int>) -> Map<String, Int>) {
        _leads.update(f)
        val o = JSONObject()
        _leads.value.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(KEY, o.toString()).commit()
    }

    private fun load(): Map<String, Int> = runCatching {
        val o = JSONObject(prefs.getString(KEY, "{}"))
        o.keys().asSequence().associateWith { o.getInt(it) }
    }.getOrDefault(emptyMap())

    private companion object {
        const val KEY = "leads_v1"
    }
}
