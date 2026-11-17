package de.lumen.launcher

import android.app.Application
import android.content.Context
import de.lumen.launcher.core.JsonStore
import de.lumen.launcher.data.AppSync
import de.lumen.launcher.data.apps.AppRepository
import de.lumen.launcher.data.apps.IconLoader
import de.lumen.launcher.data.apps.IconPackManager
import de.lumen.launcher.data.apps.LibraryState
import de.lumen.launcher.data.layout.HomeLayout
import de.lumen.launcher.data.settings.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class LumenApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Manual dependency graph: process-wide singletons shared by UI, services and widgets. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dir = File(context.filesDir, "state").apply { mkdirs() }

    val settings = JsonStore(File(dir, "settings.json"), LauncherSettings.serializer(), { LauncherSettings() }, scope)
    val layout = JsonStore(File(dir, "layout.json"), HomeLayout.serializer(), { HomeLayout() }, scope)
    val library = JsonStore(File(dir, "library.json"), LibraryState.serializer(), { LibraryState() }, scope)

    val apps = AppRepository(context, scope)
    val iconPacks = IconPackManager(context)
    val icons = IconLoader(context, apps, iconPacks)
    val appSync = AppSync(context, apps, layout, library, settings, scope)
    val system by lazy { de.lumen.launcher.system.SystemControls(context) }

    fun flushAll() {
        settings.flush()
        layout.flush()
        library.flush()
    }
}

val Context.graph: AppGraph get() = (applicationContext as LumenApp).graph
