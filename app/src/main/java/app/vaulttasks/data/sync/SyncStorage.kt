package app.vaulttasks.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.vaulttasks.domain.sync.SyncRecord
import app.vaulttasks.domain.sync.SyncRecords
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a non-exportable Android Keystore key (spec §9: password in Keystore-backed storage). */
internal object SecretBox {
    private const val ALIAS = "vaulttasks_webdav"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** null if the value cannot be decrypted (e.g. restored from a backup onto another device). */
    fun decrypt(stored: String): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES)) }
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
    }.getOrNull()
}

class WebDavSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("webdav", Context.MODE_PRIVATE)

    fun load(): WebDavConfig? {
        val url = prefs.getString("url", null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString("password", null)?.let(SecretBox::decrypt).orEmpty()
        return WebDavConfig(url, prefs.getString("user", "").orEmpty(), password, prefs.getString("folder", "").orEmpty())
    }

    fun save(config: WebDavConfig) {
        prefs.edit()
            .putString("url", config.url.trim())
            .putString("user", config.user.trim())
            .putString("folder", config.baseFolder.trim().trim('/'))
            .putString("password", SecretBox.encrypt(config.password))
            .apply()
    }
}

/** Last agreed state per file (spec §4.4 FileState sync columns). Cleared whenever the server target changes. */
class PrefsSyncRecords(context: Context) : SyncRecords {
    private val prefs = context.applicationContext.getSharedPreferences("sync_records", Context.MODE_PRIVATE)

    override fun get(path: String): SyncRecord? = prefs.getString(path, null)?.let {
        runCatching { JSONObject(it).let { j -> SyncRecord(j.getString("h"), j.getString("t")) } }.getOrNull()
    }

    override fun put(path: String, record: SyncRecord) {
        prefs.edit().putString(path, JSONObject().put("h", record.hash).put("t", record.token).toString()).commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}
