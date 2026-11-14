package com.droiddeck.launcher.stores.launcher

import android.content.Context
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.NoSteamLaunch
import com.droiddeck.launcher.stores.epic.EpicClient
import com.droiddeck.launcher.stores.heroic.HeroicLauncher
import java.io.File

/**
 * A launcher DroidDeck bundles and starts in a session instead of handing the game to Steam.
 * Epic and Heroic share this so the rail, the stores page and the launch intent stay in step.
 */
interface BundledLauncher {
    val library: String
    val name: String
    val program: String
    val args: List<String>
    val installed: Boolean
    val version: String?
    var busy: Boolean

    fun install(context: Context)
    fun ensure(context: Context)
    fun refresh(context: Context)
    fun uninstall(context: Context): Boolean
    fun entry(context: Context): Library.SteamGame?
    fun icon(context: Context): File?
    fun session(): Boolean
}

internal object BundledLaunchers {
    val all: List<BundledLauncher> = listOf(EpicBundled, HeroicLauncher)
    private val byLibrary = all.associateBy { it.library }
    private val byProgram = all.associateBy { it.program }

    val epic: BundledLauncher get() = EpicBundled
    val heroic: BundledLauncher get() = HeroicLauncher

    fun forLibrary(library: String?): BundledLauncher? = byLibrary[library]

    fun forProgram(program: String?): BundledLauncher? = byProgram[program]

    fun commandFor(game: Library.SteamGame): NoSteamLaunch.Command? =
        forLibrary(game.library)?.let { NoSteamLaunch.Command(it.program, it.args, game.name) }
}

private object EpicBundled : BundledLauncher {
    override val library = Library.EPIC_LAUNCHER
    override val name = Library.EPIC_LAUNCHER_NAME
    override val program = NoSteamLaunch.EPIC
    override val args = listOf("run")
    override val installed get() = EpicClient.status.installed
    override val version get() = EpicClient.status.version
    override var busy get() = EpicClient.uninstalling
        set(value) { EpicClient.uninstalling = value }

    override fun install(context: Context) = EpicClient.install(context)
    override fun ensure(context: Context) = EpicClient.ensure(context)
    override fun refresh(context: Context) = EpicClient.refresh(context)
    override fun uninstall(context: Context) = EpicClient.uninstall(context)
    override fun entry(context: Context) = EpicClient.launcherEntry(context, name)
    override fun icon(context: Context): File? = EpicClient.icon(context, EpicClient.status.version)
    override fun session() = EpicClient.launcherSession()
}
