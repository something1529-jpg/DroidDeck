package com.droiddeck.launcher.stores.heroic

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.launcher.BundledLauncher
import com.droiddeck.launcher.stores.launcher.BundledUpdates
import java.io.File
import java.util.concurrent.Executors

object HeroicLauncher : BundledLauncher {
    private const val TAG = "HeroicLauncher"
    const val DOWNLOAD_ID = "heroic-games-launcher"
    const val DOWNLOAD_KEY = "epic:$DOWNLOAD_ID"
    const val PROGRAM = "/usr/local/bin/droiddeck-heroic"
    private const val STATE = "root/.local/share/droiddeck-heroic"
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "heroic-launcher") }

    data class Status(val installed: Boolean = false, val version: String? = null)

    override val library: String = Library.HEROIC_LAUNCHER
    override val name: String = Library.HEROIC_LAUNCHER_NAME
    override val program: String = PROGRAM
    override val args: List<String> = emptyList()
    override val installed: Boolean get() = status.installed
    override val version: String? get() = status.version
    override var busy: Boolean
        get() = uninstalling
        set(value) { uninstalling = value }

    var status by mutableStateOf(Status())
        private set

    @Volatile private var uninstalling = false

    fun stateDir(context: Context): File = File(LinuxRuntime.rootDir(context), STATE)

    fun downloadsDir(context: Context): File = File(stateDir(context), "downloads")

    fun appDir(context: Context, version: String): File = File(stateDir(context), "app/$version")

    internal fun ready(dir: File): Boolean = BundledUpdates.ready(dir)

    fun read(context: Context): Status {
        val app = context.applicationContext
        if (!LinuxRuntime.isInstalled(app)) return Status()
        val version = BundledUpdates.installed(stateDir(app)) ?: return Status()
        return Status(true, version)
    }

    override fun refresh(context: Context) {
        val app = context.applicationContext
        work.execute {
            val next = runCatching { read(app) }.getOrNull() ?: return@execute
            StoresState.post { if (status != next) status = next }
        }
    }

    /** Checks GitHub for a newer release the way the bundled Epic launcher does. */
    override fun ensure(context: Context) {
        val app = context.applicationContext
        if (!LinuxRuntime.isInstalled(app)) { refresh(app); return }
        work.execute {
            val before = read(app)
            StoresState.post { if (status != before) status = before }
            if (DownloadQueue.isActive(DOWNLOAD_KEY) || !before.installed) return@execute
            val check = BundledUpdates.ensure(
                stateDir(app), HeroicPrefs.get(app),
                latest = { runCatching { HeroicSources.latest()?.version }.getOrNull() },
                install = { StoresState.post { enqueue(app) } },
            )
            if (check.update) Log.i(TAG, "Heroic ${check.latest} is available, updating from ${before.version}")
        }
    }

    override fun install(context: Context) {
        val app = context.applicationContext
        if (status.installed || uninstalling) return
        enqueue(app)
    }

    private fun enqueue(app: Context) {
        if (DownloadQueue.isActive(DOWNLOAD_KEY)) return
        val entry = DownloadEntry(
            com.droiddeck.launcher.stores.Store.EPIC, DOWNLOAD_ID, app.getString(R.string.heroic_title),
            location = StoreInstallRoot.labelFor(app, stateDir(app)),
        )
        DownloadQueue.enqueue(app, entry) { HeroicInstaller(app) }
    }

    internal fun installed(context: Context) {
        val app = context.applicationContext
        HeroicPrefs.get(app).edit().putLong(BundledUpdates.CHECKED_AT, System.currentTimeMillis()).apply()
        refresh(app)
    }

    override fun uninstall(context: Context): Boolean {
        val app = context.applicationContext
        DownloadQueue.clear(app, DOWNLOAD_KEY)
        val removed = runCatching {
            com.droiddeck.launcher.runtime.RuntimeFileTree.deleteWhatCan(stateDir(app), null)
        }.getOrDefault(false)
        val next = read(app)
        StoresState.post { status = next }
        Log.i(TAG, if (removed) "uninstalled Heroic Games Launcher" else "could not remove every Heroic file")
        return removed
    }

    fun launcherSession(): Boolean =
        SessionState.running && !SessionState.stopRequested && SessionState.mode == SessionService.MODE_RUN &&
            SessionState.program == PROGRAM

    fun launcherRunning(context: Context): Boolean = launcherSession()

    fun launcherEntry(context: Context, name: String): Library.SteamGame? =
        if (read(context).installed) game(name) else null

    private fun game(name: String) = Library.SteamGame(
        appId = 0, name = name, art = null, library = Library.HEROIC_LAUNCHER,
        gameId = 0L, source = Library.SOURCE_HEROIC,
    )

    override fun entry(context: Context): Library.SteamGame? = launcherEntry(context, name)

    override fun icon(context: Context): File? = null

    override fun session(): Boolean = launcherSession()
}

internal object HeroicPrefs {
    private const val FILE = "heroic-launcher"

    fun get(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
