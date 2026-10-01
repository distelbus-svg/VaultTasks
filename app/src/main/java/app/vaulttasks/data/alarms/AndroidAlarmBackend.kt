package app.vaulttasks.data.alarms

import android.app.AlarmManager
import android.content.Context
import app.vaulttasks.domain.alarms.AlarmBackend
import app.vaulttasks.domain.alarms.AlarmKind
import app.vaulttasks.domain.alarms.AlarmSpec
import app.vaulttasks.domain.alarms.AlarmStore
import app.vaulttasks.domain.TaskId
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Spec §8.1: setExactAndAllowWhileIdle → BroadcastReceiver. Falls back to an inexact alarm only if exact is not allowed. */
class AndroidAlarmBackend(context: Context) : AlarmBackend {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)

    override fun set(spec: AlarmSpec) {
        val at = spec.fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = AlarmIntents.firePending(context, spec)
        if (alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call: degrade, the health check reports it.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    override fun cancel(code: Int) {
        AlarmIntents.cancelFor(context, code)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }
}

/** What was last scheduled, as JSON in SharedPreferences. commit() on purpose: the process may die right after. */
class PrefsAlarmStore(context: Context) : AlarmStore {
    private val prefs = context.applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    override fun load(): List<AlarmSpec> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { decode(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    override fun save(specs: List<AlarmSpec>) {
        val arr = JSONArray()
        specs.forEach { arr.put(encode(it)) }
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    private fun encode(s: AlarmSpec) = JSONObject()
        .put("code", s.code)
        .put("kind", s.kind.name)
        .put("path", s.id.path)
        .put("text", s.id.normalizedText)
        .put("occ", s.id.occurrence)
        .put("at", s.fireAt.toString())
        .put("title", s.title)
        .put("date", s.dueDate?.toString() ?: JSONObject.NULL)
        .put("time", s.dueTime?.toString() ?: JSONObject.NULL)
        .put("space", s.spaceName)

    private fun decode(o: JSONObject) = AlarmSpec(
        code = o.getInt("code"),
        kind = AlarmKind.valueOf(o.getString("kind")),
        id = TaskId(o.getString("path"), o.getString("text"), o.getInt("occ")),
        fireAt = LocalDateTime.parse(o.getString("at")),
        title = o.getString("title"),
        dueDate = o.optString("date").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalDate::parse),
        dueTime = o.optString("time").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalTime::parse),
        spaceName = o.getString("space"),
    )

    private companion object {
        const val KEY = "scheduled_v1"
    }
}
