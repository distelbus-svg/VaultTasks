package app.vaulttasks.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    /** SAF tree URI of the vault root; permission is persisted separately by the ContentResolver. */
    val vaultUri: Flow<String?> = store.data.map { it[VAULT_URI] }

    /** Tasks global filter (spec §4.1); empty = off. */
    val globalFilter: Flow<String> = store.data.map { it[GLOBAL_FILTER].orEmpty() }

    /** Spaces + active space as JSON (see [SpacesJson]); null until the first space is created. */
    val spacesJson: Flow<String?> = store.data.map { it[SPACES] }

    suspend fun setVaultUri(uri: String) = store.edit { it[VAULT_URI] = uri }

    suspend fun setSpacesJson(json: String) = store.edit { it[SPACES] = json }

    private companion object {
        val VAULT_URI = stringPreferencesKey("vault_uri")
        val GLOBAL_FILTER = stringPreferencesKey("global_filter")
        val SPACES = stringPreferencesKey("spaces_v1")
    }
}
