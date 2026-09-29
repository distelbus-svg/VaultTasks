package app.vaulttasks

import android.app.Application
import android.content.Context
import app.vaulttasks.data.VaultRepository
import app.vaulttasks.data.settings.SettingsStore

/** Hand-written DI (spec §15.7). */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val vault = VaultRepository(context, settings)
}

class VaultTasksApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
