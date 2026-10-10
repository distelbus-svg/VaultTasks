package app.vaulttasks.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.vaulttasks.VaultTasksApp
import java.util.concurrent.TimeUnit

/**
 * Silent two-way WebDAV sync (same engine as the manual one). Does nothing if no server is set up. Runs from three
 * triggers, all through [SyncScheduler]: once a day, shortly after the app is closed, and (directly, not via work)
 * whenever it is opened.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as VaultTasksApp).container
        return try {
            container.vault.ensureLoaded()
            if (container.sync.autoSync()) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object SyncScheduler {
    private const val DAILY = "vaulttasks-sync-daily"
    private const val ON_CLOSE = "vaulttasks-sync-on-close"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Once per day when the device is online; the system picks the exact moment (battery friendly). Idempotent. */
    fun scheduleDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** After the app went to the background: push what was just edited. Repeated calls while one is queued are ignored. */
    fun syncSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).setInitialDelay(5, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ON_CLOSE, ExistingWorkPolicy.KEEP, request)
    }
}
