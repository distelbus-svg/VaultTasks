package app.vaulttasks.data.alarms

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** One detectable precondition for reliable reminders. */
data class HealthCheck(val ok: Boolean, val detail: String)

data class HealthReport(
    val notifications: HealthCheck,
    val exactAlarms: HealthCheck,
    val battery: HealthCheck,
) {
    val allOk: Boolean get() = notifications.ok && exactAlarms.ok && battery.ok
}

/** Spec §8.5. MagicOS auto-launch / lock-in-recents cannot be detected, so they are guidance only (never a failing check). */
class ReminderHealth(context: Context) {
    private val context = context.applicationContext

    fun check(): HealthReport {
        Notifications.ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val channelOff = nm.getNotificationChannel(Notifications.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        val permission = Notifications.permissionGranted(context)
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val notifications = when {
            !permission -> HealthCheck(false, "Notification permission not granted")
            !enabled -> HealthCheck(false, "Notifications are turned off for this app")
            channelOff -> HealthCheck(false, "The “Task reminders” channel is turned off")
            else -> HealthCheck(true, "Allowed")
        }

        val alarms = context.getSystemService(AlarmManager::class.java)
        val exact = if (alarms.canScheduleExactAlarms()) HealthCheck(true, "Allowed")
        else HealthCheck(false, "Exact alarms not allowed; reminders may be late")

        val power = context.getSystemService(PowerManager::class.java)
        val battery = if (power.isIgnoringBatteryOptimizations(context.packageName)) HealthCheck(true, "Unrestricted")
        else HealthCheck(false, "Battery optimization may delay or kill reminders")

        return HealthReport(notifications, exact, battery)
    }

    // ---- deep links (every launch is guarded: OEM builds drop or rename these screens) ----------------------

    fun openNotificationSettings(): Boolean = launch(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openExactAlarmSettings(): Boolean =
        launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) || openAppDetails()

    fun requestBatteryExemption(): Boolean =
        launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) ||
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    fun openAppDetails(): Boolean =
        launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    /**
     * Best effort only. Candidate component names recalled from Huawei/Honor naming conventions, NOT verified on the
     * Honor 400 (no public API, may not exist or be exported), so each is tried under try/catch and the caller
     * falls back to [openAppDetails].
     */
    fun openOemLaunchManager(): Boolean = OEM_LAUNCH_MANAGERS.any { (pkg, cls) ->
        launch(Intent().setClassName(pkg, cls))
    }

    /** No resolve check: package visibility hides other packages' activities, so just try and catch (ActivityNotFound/Security). */
    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        val OEM_LAUNCH_MANAGERS = listOf(
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
    }
}
