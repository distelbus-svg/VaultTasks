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

    /** Spec §7.4: default reminder time for date-only tasks (09:00) and reminder lead time (0 min). */
    val reminders: Flow<ReminderSettings> = store.data.map {
        val minutes = (it[REMINDER_TIME_MIN] ?: DEFAULT_REMINDER_MIN).coerceIn(0, 24 * 60 - 1)
        ReminderSettings(LocalTime.of(minutes / 60, minutes % 60), (it[REMINDER_LEAD_MIN] ?: 0).coerceAtLeast(0))
    }

    suspend fun setVaultUri(uri: String) = store.edit { it[VAULT_URI] = uri }

    suspend fun setSpacesJson(json: String) = store.edit { it[SPACES] = json }

    suspend fun setDefaultReminderTime(time: LocalTime) = store.edit { it[REMINDER_TIME_MIN] = time.hour * 60 + time.minute }

    suspend fun setReminderLeadMinutes(minutes: Int) = store.edit { it[REMINDER_LEAD_MIN] = minutes.coerceAtLeast(0) }

    private companion object {
        const val DEFAULT_REMINDER_MIN = 9 * 60
        val VAULT_URI = stringPreferencesKey("vault_uri")
        val GLOBAL_FILTER = stringPreferencesKey("global_filter")
        val SPACES = stringPreferencesKey("spaces_v1")
        val REMINDER_TIME_MIN = intPreferencesKey("reminder_time_min")
        val REMINDER_LEAD_MIN = intPreferencesKey("reminder_lead_min")
    }
}
