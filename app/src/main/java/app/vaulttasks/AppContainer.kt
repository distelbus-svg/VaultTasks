package app.vaulttasks

import android.app.Application
import android.content.Context
import app.vaulttasks.data.VaultRepository
import app.vaulttasks.data.alarms.AlarmCoordinator
import app.vaulttasks.data.alarms.AndroidAlarmBackend
import app.vaulttasks.data.alarms.DiagnosticsLog
import app.vaulttasks.data.alarms.LeadStore
import app.vaulttasks.data.alarms.Notifications
import app.vaulttasks.data.alarms.PrefsAlarmStore
import app.vaulttasks.data.alarms.ReminderHealth
import app.vaulttasks.data.settings.SettingsStore
import app.vaulttasks.domain.alarms.AlarmSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-written DI (spec §15.7). */
class AppContainer(context: Context) {
    /** Process-lifetime scope for work that must outlive any screen (alarm sync, receivers). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)
    val vault = VaultRepository(context, settings)
    val diagnostics = DiagnosticsLog(context)
    val health = ReminderHealth(context)
    val leads = LeadStore(context)
    val alarms = AlarmCoordinator(
        repo = vault,
        settings = settings,
        leadStore = leads,
        syncer = AlarmSyncer(AndroidAlarmBackend(context), PrefsAlarmStore(context)),
        diagnostics = diagnostics,
        scope = appScope,
    )

    init {
        Notifications.ensureChannel(context)
        alarms.start()
    }
}

class VaultTasksApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
