package app.vaulttasks.data.alarms

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.vaulttasks.VaultTasksApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Spec §8.3 system triggers: boot, app update, clock/zone change, exact-alarm permission change. Each one loads the
 * vault (once per process) and force-sets every alarm, because after any of these the OS may have dropped them.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            Intent.ACTION_TIMEZONE_CHANGED -> "time zone changed"
            Intent.ACTION_TIME_CHANGED -> "clock changed"
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> "exact-alarm permission changed"
            else -> return
        }
        val container = (context.applicationContext as VaultTasksApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                withTimeout(9_000) {
                    container.vault.ensureLoaded()
                    container.alarms.syncNow(force = true, trigger = label)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
