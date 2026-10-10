package app.vaulttasks.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.vaulttasks.domain.alarms.ReminderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    /** SAF tree URI of the vault root; permission is persisted separately by the ContentResolver. */
    val vaultUri: Flow<String?> = store.data.map { it[VAULT_URI] }

    /** Tasks global filter (spec §4.1); empty = off. */
    val globalFilter: Flow<String> = store.data.map { it[GLOBAL_FILTER].orEmpty() }

    /** Spaces + active space as JSON (see [SpacesJson]); null until the first space is created. */
    val spacesJson: Flow<String?> = store.data.map { it[SPACES] }

    /** Spec §7.4: default reminder time for date-only tasks (09:00). "Remind me before" is per task, see LeadStore. */
    val reminders: Flow<ReminderSettings> = store.data.map {
        val minutes = (it[REMINDER_TIME_MIN] ?: DEFAULT_REMINDER_MIN).coerceIn(0, 24 * 60 - 1)
        ReminderSettings(LocalTime.of(minutes / 60, minutes % 60))
    }

    /** Space id → "this space is grouped by category". Remembered per space, across sessions. */
    val groupByCategory: Flow<Map<String, Boolean>> = store.data.map { prefs ->
        parseObject(prefs[GROUP_BY_CATEGORY_SPACES]) { o, k -> o.getBoolean(k) }
    }

    suspend fun setGroupByCategory(spaceId: String, on: Boolean) = store.edit {
        val all = parseObject(it[GROUP_BY_CATEGORY_SPACES]) { o, k -> o.getBoolean(k) }.toMutableMap()
        all[spaceId] = on
        it[GROUP_BY_CATEGORY_SPACES] = org.json.JSONObject(all as Map<*, *>).toString()
    }

    /** Manual task order per "spaceId|date" / "spaceId|cat": task list keys, earlier = higher. Unknown tasks go last. */
    val taskOrders: Flow<Map<String, List<String>>> = store.data.map { parseLists(it[TASK_ORDERS]) }

    suspend fun setTaskOrder(key: String, order: List<String>) = store.edit { it[TASK_ORDERS] = putList(it[TASK_ORDERS], key, order) }

    /** Manual category order per space id. */
    val categoryOrders: Flow<Map<String, List<String>>> = store.data.map { parseLists(it[CATEGORY_ORDERS]) }

    suspend fun setCategoryOrder(spaceId: String, order: List<String>) =
        store.edit { it[CATEGORY_ORDERS] = putList(it[CATEGORY_ORDERS], spaceId, order) }

    private fun <T> parseObject(json: String?, read: (org.json.JSONObject, String) -> T): Map<String, T> = runCatching {
        val o = org.json.JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { k -> read(o, k) }
    }.getOrDefault(emptyMap())

    private fun parseLists(json: String?): Map<String, List<String>> = parseObject(json) { o, k ->
        val a = o.getJSONArray(k)
        List(a.length()) { a.getString(it) }
    }

    private fun putList(json: String?, key: String, list: List<String>): String {
        val all = parseLists(json).toMutableMap()
        if (list.isEmpty()) all.remove(key) else all[key] = list
        return org.json.JSONObject(all.mapValues { org.json.JSONArray(it.value) } as Map<*, *>).toString()
    }

    /** Category name → ARGB color chosen when the category was created. Device-local (the vault only holds the #tag). */
    val categoryColors: Flow<Map<String, Int>> = store.data.map { parseColors(it[CATEGORY_COLORS]) }

    suspend fun setCategoryColor(name: String, argb: Int) = store.edit {
        val all = parseColors(it[CATEGORY_COLORS]).toMutableMap()
        all[name] = argb
        it[CATEGORY_COLORS] = org.json.JSONObject(all as Map<*, *>).toString()
    }

    private fun parseColors(json: String?): Map<String, Int> = runCatching {
        val o = org.json.JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { k -> o.getInt(k) }
    }.getOrDefault(emptyMap())

    suspend fun setVaultUri(uri: String) = store.edit { it[VAULT_URI] = uri }

    suspend fun setSpacesJson(json: String) = store.edit { it[SPACES] = json }

    suspend fun setDefaultReminderTime(time: LocalTime) = store.edit { it[REMINDER_TIME_MIN] = time.hour * 60 + time.minute }

    private companion object {
        const val DEFAULT_REMINDER_MIN = 9 * 60
        val VAULT_URI = stringPreferencesKey("vault_uri")
        val GLOBAL_FILTER = stringPreferencesKey("global_filter")
        val SPACES = stringPreferencesKey("spaces_v1")
        val CATEGORY_COLORS = stringPreferencesKey("category_colors")
        val GROUP_BY_CATEGORY_SPACES = stringPreferencesKey("group_by_category_spaces")
        val TASK_ORDERS = stringPreferencesKey("task_orders")
        val CATEGORY_ORDERS = stringPreferencesKey("category_orders")
        val REMINDER_TIME_MIN = intPreferencesKey("reminder_time_min")
    }
}
