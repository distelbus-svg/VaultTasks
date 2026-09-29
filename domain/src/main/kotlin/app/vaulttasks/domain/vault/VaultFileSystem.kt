package app.vaulttasks.domain.vault

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class VaultFileInfo(val path: String, val lastModified: Long, val size: Long)

/**
 * Vault I/O seam (spec §5). Blocking; callers run it on an IO dispatcher.
 * Paths are vault-relative and '/'-separated. Android implements it over SAF; tests use an in-memory fake.
 */
interface VaultFileSystem {
    /** Every `.md` file under the vault root, excluding dot-directories (.obsidian, .git, .trash, ...). */
    fun listMarkdown(): List<VaultFileInfo>

    /** null if the file does not exist. */
    fun stat(path: String): VaultFileInfo?

    /** null if the file does not exist. */
    fun read(path: String): ByteArray?

    /** Replaces the whole file content (truncating). Throws on failure. */
    fun write(path: String, bytes: ByteArray)
}

/**
 * Strict UTF-8 decode; null if the bytes are not valid UTF-8. A BOM is kept as U+FEFF (VaultText handles it).
 * Lossy decoding would silently corrupt a file on the next edit, so callers must refuse such files.
 */
internal fun decodeUtf8Strict(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    null
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
