package com.droiddeck.launcher.stores.launcher

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * Update policy and install state shared by the bundled launchers.
 * The guest installers write the same layout: current.json names the build, and only a build
 * carrying [READY] counts as installed, so an interrupted install never replaces a working one.
 */
internal object BundledUpdates {
    const val READY = ".droiddeck-ready"
    const val CHECKED_AT = "client_checked_at"
    const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    fun ready(dir: File): Boolean = File(dir, READY).isFile

    fun version(state: File): String? = runCatching {
        JSONObject(File(state, "current.json").readText()).optString("app").ifEmpty { null }
    }.getOrNull()

    fun installed(state: File): String? = version(state)?.takeIf { ready(File(state, "app/$it")) }

    fun record(state: File, version: String, name: String): Boolean = runCatching {
        state.mkdirs()
        val staged = File(state, "current.json.tmp")
        staged.writeText(JSONObject().put("app", version).put("file", name).toString(2))
        staged.renameTo(File(state, "current.json"))
    }.getOrDefault(false)

    fun prune(state: File, keep: File) {
        File(state, "app").listFiles()?.forEach { if (it != keep) it.deleteRecursively() }
    }

    fun newer(installed: String?, latest: String?): Boolean =
        installed != null && latest != null && latest != installed

    class Check(val latest: String?, val update: Boolean, val checked: Boolean)

    fun check(installed: String?, latest: String?, lastChecked: Long, now: Long): Check {
        if (installed == null || latest == null) return Check(latest, false, false)
        if (now - lastChecked < CHECK_EVERY_MS) return Check(latest, false, false)
        return Check(latest, latest != installed, true)
    }

    fun ensure(
        state: File,
        prefs: SharedPreferences,
        latest: () -> String?,
        install: () -> Unit,
        now: Long = System.currentTimeMillis(),
    ): Check {
        val current = installed(state) ?: return Check(null, false, false)
        if (now - prefs.getLong(CHECKED_AT, 0L) < CHECK_EVERY_MS) return Check(null, false, false)
        val newest = latest() ?: return Check(null, false, false)
        prefs.edit().putLong(CHECKED_AT, now).apply()
        val update = newest != current
        if (update) install()
        return Check(newest, update, true)
    }
}
