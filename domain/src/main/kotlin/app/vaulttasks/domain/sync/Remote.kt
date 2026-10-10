package app.vaulttasks.domain.sync

import app.vaulttasks.domain.vault.VaultFileSystem

/**
 * Identity of a remote file's content: the strong ETag (quoted, as sent by the server) when there is one, otherwise a
 * synthetic `lm|<last-modified>|<size>` string. Only compared for equality, never parsed.
 */
/** [modified]: server's last-modified time in epoch millis, null if unknown. */
data class RemoteStat(val token: String, val modified: Long? = null)

class RemoteData(val bytes: ByteArray, val token: String, val modified: Long? = null)

sealed interface PutResult {
    data class Ok(val token: String) : PutResult

    /** The remote file is not in the state the caller last saw (HTTP 412). Nothing was written. */
    data object PreconditionFailed : PutResult
    data class Failed(val message: String) : PutResult
}

/** WebDAV seam (spec §9). Blocking. Paths are vault-relative; the implementation maps them below its base folder. */
interface RemoteStore {
    /** null if the file does not exist. Throws IOException for network/auth errors. */
    fun stat(path: String): RemoteStat?

    /** null if the file does not exist. */
    fun get(path: String): RemoteData?

    /**
     * Writes [bytes]. [expectedToken] null = create only (fails with [PutResult.PreconditionFailed] if the file
     * exists); otherwise replaces only if the remote file still has that token.
     */
    fun put(path: String, bytes: ByteArray, expectedToken: String?): PutResult
}

/** Last state both sides agreed on, per file: content hash of the local file and the remote token at that time. */
data class SyncRecord(val hash: String, val token: String)

interface SyncRecords {
    fun get(path: String): SyncRecord?
    fun put(path: String, record: SyncRecord)
    fun clear()
}

/** Local file access where each block runs with the vault's write lock held, so read-check-write is atomic. */
interface LocalFiles {
    fun <T> atomically(block: (VaultFileSystem) -> T): T
}
