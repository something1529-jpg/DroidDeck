package com.droiddeck.launcher.stores.heroic

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.launcher.BundledUpdates
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.formatBytes
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal object HeroicSources {
    private const val TAG = "HeroicSources"
    const val RELEASES = "https://api.github.com/repos/Heroic-Games-Launcher/HeroicGamesLauncher/releases/latest"
    const val USER_AGENT = "DroidDeck"
    private val VERSION = Regex("[A-Za-z0-9._+-]{1,64}")
    private val ASSET = Regex("Heroic-([0-9][A-Za-z0-9._+-]*)-linux-arm64\\.AppImage")
    private val DIGEST = Regex("^sha256:([0-9a-fA-F]{64})$")

    internal var transport: ((String) -> HttpURLConnection)? = null

    internal fun connection(url: String): HttpURLConnection =
        transport?.invoke(url) ?: (URL(url).openConnection() as HttpURLConnection)

    class Release(val version: String, val name: String, val url: String, val sha256: String?, val size: Long)

    fun latest(): Release? {
        val body = text(RELEASES) ?: return null
        val release = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name").removePrefix("v")
        if (!VERSION.matches(tag)) return null
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (ASSET.matchEntire(name)?.groupValues?.get(1) != tag) continue
            val url = asset.optString("browser_download_url")
            val uri = runCatching { URI(url) }.getOrNull() ?: continue
            if (uri.scheme != "https" || uri.host != "github.com") continue
            val digest = DIGEST.matchEntire(asset.optString("digest"))?.groupValues?.get(1)?.lowercase()
            return Release(tag, name, url, digest, asset.optLong("size", -1L))
        }
        return null
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun isArm64AppImage(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(20)
            raf.readFully(head)
            head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() &&
                head[8] == 'A'.code.toByte() && head[9] == 'I'.code.toByte() && head[10].toInt() == 2 &&
                ((head[18].toInt() and 0xff) or ((head[19].toInt() and 0xff) shl 8)) == 0xB7
        }
    }.getOrDefault(false)

    private fun text(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = connection(url).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode != 200) {
                Log.i(TAG, "the release feed answered HTTP ${conn.responseCode}")
                null
            } else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.i(TAG, "the release feed could not be read: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

internal class HeroicInstaller(private val app: Context) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null

    override fun run(handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.MANIFEST, app.getString(R.string.heroic_checking))
        if (!LinuxRuntime.isInstalled(app)) throw IllegalStateException(app.getString(R.string.heroic_no_runtime))
        val release = HeroicSources.latest() ?: throw IllegalStateException(app.getString(R.string.heroic_unreachable))
        val state = HeroicLauncher.stateDir(app)
        val before = HeroicLauncher.read(app)
        if (before.version == release.version && HeroicLauncher.ready(HeroicLauncher.appDir(app, release.version))) {
            handle.log("Heroic ${release.version} is already installed")
            HeroicLauncher.installed(app)
            return state.absolutePath
        }
        if (cancelled.get()) return null
        val dir = HeroicLauncher.downloadsDir(app).apply { mkdirs() }
        val archive = File(dir, release.name)
        val room = (release.size.takeIf { it > 0 } ?: (150L shl 20)) * 2 + (256L shl 20)
        val free = StoreInstallRoot.availableBytes(state)
        if (StoreInstallRoot.insufficientSpace(room, free)) {
            throw IllegalStateException(app.getString(R.string.stores_storage_insufficient, formatBytes(room), formatBytes(free)))
        }
        dir.listFiles()?.forEach { if (it != archive && it.name != archive.name + PARTIAL) it.deleteRecursively() }
        download(archive, release, handle)
        if (cancelled.get()) return null
        handle.stage(DownloadStage.INSTALL, app.getString(R.string.heroic_installing))
        install(app, release, archive)
        archive.delete()
        handle.log("Heroic ${release.version} installed")
        HeroicLauncher.installed(app)
        return state.absolutePath
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        runCatching { connection?.disconnect() }
        if (deleteFiles) HeroicLauncher.downloadsDir(app).deleteRecursively()
    }

    private fun install(app: Context, release: HeroicSources.Release, archive: File) {
        val state = HeroicLauncher.stateDir(app)
        val target = HeroicLauncher.appDir(app, release.version)
        File(state, "app").mkdirs()
        if (!target.isDirectory && !target.mkdirs()) {
            throw IllegalStateException(app.getString(R.string.heroic_install_failed, "no space for ${target.name}"))
        }
        val image = File(target, release.name)
        if (image.isFile) image.delete()
        if (!archive.renameTo(image)) {
            archive.inputStream().use { input -> image.outputStream().use { output -> input.copyTo(output) } }
        }
        if (!image.setExecutable(true, false) || !HeroicSources.isArm64AppImage(image)) {
            image.delete()
            throw IllegalStateException(app.getString(R.string.heroic_install_failed, "not an arm64 AppImage"))
        }
        File(target, BundledUpdates.READY).writeText(release.version + "\n")
        if (!BundledUpdates.record(state, release.version, release.name)) throw IOException("could not record the installed version")
        BundledUpdates.prune(state, target)
    }

    private fun download(file: File, release: HeroicSources.Release, handle: DownloadQueue.JobHandle) {
        val partial = File(file.path + PARTIAL)
        file.delete()
        var attempt = 0
        while (true) {
            if (cancelled.get()) return
            try {
                partial.delete()
                transfer(release, partial, handle)
                if (cancelled.get()) return
                if (release.size > 0 && partial.length() != release.size) throw IOException("the download ended early")
                release.sha256?.let { want ->
                    if (HeroicSources.sha256(partial) != want) throw IOException("checksum mismatch")
                }
                if (!HeroicSources.isArm64AppImage(partial)) throw IOException("not an arm64 AppImage")
                if (!partial.renameTo(file)) throw IOException("could not keep ${file.name}")
                return
            } catch (e: IOException) {
                partial.delete()
                attempt++
                handle.log("Heroic Games Launcher: attempt $attempt: ${e.message ?: e.javaClass.simpleName}")
                if (attempt >= ATTEMPTS) {
                    throw IllegalStateException(app.getString(R.string.heroic_download_failed, e.message ?: e.javaClass.simpleName))
                }
                val until = System.currentTimeMillis() + (2_000L shl (attempt - 1)).coerceAtMost(30_000L)
                while (System.currentTimeMillis() < until && !cancelled.get()) Thread.sleep(100)
            }
        }
    }

    private fun transfer(release: HeroicSources.Release, partial: File, handle: DownloadQueue.JobHandle) {
        val conn = HeroicSources.connection(release.url).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", HeroicSources.USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
        }
        connection = conn
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val total = if (release.size > 0) release.size else conn.contentLengthLong.coerceAtLeast(0L)
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(partial, false).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (!cancelled.get()) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) handle.progress(done, total, release.name)
                    }
                    out.fd.sync()
                }
            }
        } finally {
            connection = null
            runCatching { conn.disconnect() }
        }
    }

    private companion object {
        const val PARTIAL = ".part"
        const val ATTEMPTS = 4
    }
}
