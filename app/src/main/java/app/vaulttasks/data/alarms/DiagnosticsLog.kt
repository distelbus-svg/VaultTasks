package app.vaulttasks.data.alarms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spec §8.5: the last [MAX] events, so lateness can be measured instead of guessed. FIRE entries record the time the
 * alarm was scheduled for next to the time the receiver actually ran. EVENT entries mark system-triggered reschedules.
 */
data class DiagEntry(
    val kind: Kind,
    /** Epoch millis when the entry was written (receiver ran). */
    val atMillis: Long,
    /** Epoch millis the alarm was meant to fire; null for events. */
    val scheduledMillis: Long?,
    val label: String,
) {
    enum class Kind { FIRE, SNOOZE_FIRE, EVENT }

    val latenessMillis: Long? get() = scheduledMillis?.let { atMillis - it }
}

class DiagnosticsLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    @Synchronized
    fun record(entry: DiagEntry) {
        val all = (entries() + entry).takeLast(MAX)
        val arr = JSONArray()
        all.forEach {
            arr.put(
                JSONObject().put("k", it.kind.name).put("at", it.atMillis).put("sch", it.scheduledMillis ?: JSONObject.NULL).put("l", it.label),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    /** Oldest first. */
    @Synchronized
    fun entries(): List<DiagEntry> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            DiagEntry(
                DiagEntry.Kind.valueOf(o.getString("k")),
                o.getLong("at"),
                if (o.isNull("sch")) null else o.getLong("sch"),
                o.getString("l"),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val MAX = 100
        const val KEY = "log_v1"
    }
}
