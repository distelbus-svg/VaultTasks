package app.vaulttasks.domain.sync

import app.vaulttasks.domain.vault.sha256Hex

enum class FileStatus {
    IN_SYNC,

    /** Changed here, unchanged on the server. */
    LOCAL_CHANGED,

    /** Changed on the server, unchanged here. */
    REMOTE_CHANGED,

    /** Changed on both sides, or first contact with different content: the newer version wins. */
    BOTH_CHANGED,

    /** Not on the server yet. */
    NOT_ON_SERVER,

    /** On the server but missing locally. Not recreated in v1. */
    MISSING_LOCALLY,

    /** Was synced before, now gone from the server. Deletions are never propagated (spec §15.6). */
    DELETED_ON_SERVER,

    /** Neither side has it. */
    GONE,
    ERROR,
}

data class FileSyncState(val path: String, val status: FileStatus, val detail: String? = null)
data class Skipped(val path: String, val reason: String)
data class SyncFailure(val path: String, val message: String)

data class SyncReport(
    val pulled: List<String> = emptyList(),
    val pushed: List<String> = emptyList(),
    val skipped: List<Skipped> = emptyList(),
    val failed: List<SyncFailure> = emptyList(),
)

/**
 * Two-way sync, newest version wins. A file changed on one side only is carried over; changed on both sides, the one
 * with the later modification time survives (ties go to the server). Uploads are conditional on the server token
 * that was just read, so a server change that lands in between is never overwritten; it is picked up next run.
 */
class SyncEngine(
    private val local: LocalFiles,
    private val remote: RemoteStore,
    private val records: SyncRecords,
) {
    private class Snap(
        val status: FileStatus,
        val localBytes: ByteArray? = null,
        val localHash: String? = null,
        val remoteData: RemoteData? = null,
    )

    /** Read-only status of every path (the only side effect is adopting identical content as in sync). */
    fun check(paths: Collection<String>): List<FileSyncState> = paths.map { path ->
        try {
            FileSyncState(path, classify(path).status)
        } catch (e: Exception) {
            FileSyncState(path, FileStatus.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    fun sync(paths: Collection<String>): SyncReport {
        val pulled = mutableListOf<String>()
        val pushed = mutableListOf<String>()
        val skipped = mutableListOf<Skipped>()
        val failed = mutableListOf<SyncFailure>()
        for (path in paths) {
            try {
                val snap = classify(path)
                when (snap.status) {
                    FileStatus.LOCAL_CHANGED -> push(path, snap, records.get(path)?.token, pushed, skipped, failed)
                    FileStatus.NOT_ON_SERVER -> push(path, snap, null, pushed, skipped, failed)
                    FileStatus.REMOTE_CHANGED -> pull(path, snap, pulled, skipped)
                    FileStatus.BOTH_CHANGED ->
                        if (localIsNewer(path, snap)) push(path, snap, snap.remoteData?.token, pushed, skipped, failed)
                        else pull(path, snap, pulled, skipped)
                    FileStatus.MISSING_LOCALLY -> skipped += Skipped(path, "Missing locally, not recreated")
                    FileStatus.DELETED_ON_SERVER -> skipped += Skipped(path, "Deleted on the server, kept locally")
                    else -> Unit
                }
            } catch (e: Exception) {
                failed += SyncFailure(path, e.message ?: e.javaClass.simpleName)
            }
        }
        return SyncReport(pulled, pushed, skipped, failed)
    }

    // ---- internals ----------------------------------------------------------------------------------------

    private fun localIsNewer(path: String, snap: Snap): Boolean {
        val localTime = local.atomically { it.stat(path)?.lastModified } ?: return false
        val remoteTime = snap.remoteData?.modified ?: return false
        return localTime > remoteTime
    }

    private fun push(
        path: String,
        snap: Snap,
        expectedToken: String?,
        pushed: MutableList<String>,
        skipped: MutableList<Skipped>,
        failed: MutableList<SyncFailure>,
    ) {
        val bytes = snap.localBytes ?: return
        when (val r = remote.put(path, bytes, expectedToken)) {
            is PutResult.Ok -> {
                records.put(path, SyncRecord(sha256Hex(bytes), r.token))
                pushed += path
            }
            PutResult.PreconditionFailed -> skipped += Skipped(path, "Server changed meanwhile, will sync on the next run")
            is PutResult.Failed -> failed += SyncFailure(path, r.message)
        }
    }

    private fun pull(path: String, snap: Snap, pulled: MutableList<String>, skipped: MutableList<Skipped>) {
        val data = snap.remoteData ?: remote.get(path)
        if (data == null) {
            skipped += Skipped(path, "Disappeared from the server")
            return
        }
        // Replace only if the local file is still exactly what was classified.
        val replaced = local.atomically { fs ->
            if (fs.read(path)?.let(::sha256Hex) != snap.localHash) false
            else { fs.write(path, data.bytes); true }
        }
        if (replaced) {
            records.put(path, SyncRecord(sha256Hex(data.bytes), data.token))
            pulled += path
        } else {
            skipped += Skipped(path, "Changed locally meanwhile, will sync on the next run")
        }
    }

    private fun classify(path: String): Snap {
        val bytes = local.atomically { it.read(path) }
        val hash = bytes?.let(::sha256Hex)
        val stat = remote.stat(path)
        if (bytes == null) return Snap(if (stat == null) FileStatus.GONE else FileStatus.MISSING_LOCALLY)
        val rec = records.get(path)
        if (stat == null) {
            return Snap(if (rec == null) FileStatus.NOT_ON_SERVER else FileStatus.DELETED_ON_SERVER, bytes, hash)
        }
        if (rec == null) {
            // First contact: identical content is adopted silently, anything else goes to the newer version.
            val data = remote.get(path) ?: return Snap(FileStatus.NOT_ON_SERVER, bytes, hash)
            return compare(path, bytes, hash!!, data)
        }
        val localChanged = hash != rec.hash
        val remoteChanged = stat.token != rec.token
        return when {
            !localChanged && !remoteChanged -> Snap(FileStatus.IN_SYNC, bytes, hash)
            localChanged && !remoteChanged -> Snap(FileStatus.LOCAL_CHANGED, bytes, hash)
            !localChanged -> Snap(FileStatus.REMOTE_CHANGED, bytes, hash)
            else -> {
                // Both changed: the same edit on both sides needs no action.
                val data = remote.get(path) ?: return Snap(FileStatus.DELETED_ON_SERVER, bytes, hash)
                compare(path, bytes, hash!!, data)
            }
        }
    }

    private fun compare(path: String, bytes: ByteArray, hash: String, data: RemoteData): Snap =
        if (sha256Hex(data.bytes) == hash) {
            records.put(path, SyncRecord(hash, data.token))
            Snap(FileStatus.IN_SYNC, bytes, hash, data)
        } else {
            Snap(FileStatus.BOTH_CHANGED, bytes, hash, data)
        }
}
