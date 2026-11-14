package com.droiddeck.launcher.stores.heroic

import android.os.Looper
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.launcher.BundledUpdates
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HeroicAutoUpdateTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private lateinit var state: File
    private val feedAsks = AtomicInteger()
    private val assetAsks = AtomicInteger()

    private class Reply(private val code: Int, private val body: ByteArray) : HttpURLConnection(null) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body)
    }

    private fun appImage(version: String): ByteArray {
        val head = ByteArray(20)
        head[0] = 0x7f
        head[1] = 'E'.code.toByte()
        head[2] = 'L'.code.toByte()
        head[3] = 'F'.code.toByte()
        head[4] = 2
        head[5] = 1
        head[6] = 1
        head[8] = 'A'.code.toByte()
        head[9] = 'I'.code.toByte()
        head[10] = 2
        head[18] = 0xB7.toByte()
        return head + "heroic appimage $version".toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun release(version: String, image: ByteArray): String = JSONObject()
        .put("tag_name", "v$version")
        .put("draft", false)
        .put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "Heroic-$version-linux-arm64.AppImage")
            .put("browser_download_url", "https://github.com/Heroic-Games-Launcher/HeroicGamesLauncher/releases/download/v$version/Heroic-$version-linux-arm64.AppImage")
            .put("digest", "sha256:${sha256(image)}")
            .put("size", image.size)))
        .toString()

    private fun seed(version: String) {
        val dir = File(state, "app/$version").apply { mkdirs() }
        File(dir, "Heroic-$version-linux-arm64.AppImage").writeBytes(appImage(version))
        File(dir, BundledUpdates.READY).writeText("$version\n")
        File(state, "current.json").writeText(JSONObject().put("app", version)
            .put("file", "Heroic-$version-linux-arm64.AppImage").toString())
    }

    @Before fun setUp() {
        root = LinuxRuntime.rootDir(app)
        File(root, "usr/bin/gamescope").apply { parentFile!!.mkdirs(); writeText("") }
        File(root, "usr/local/bin/droiddeck-session").apply { parentFile!!.mkdirs(); writeText("") }
        File(root, "opt/android-host/proot").apply { parentFile!!.mkdirs(); writeText("") }
        File(root, "opt/android-host/loader").writeText("")
        assertTrue(LinuxRuntime.isInstalled(app))
        state = HeroicLauncher.stateDir(app)
        state.mkdirs()
        ShadowStatFs.registerStats(state.path, 4096, 262144, 262144)
        HeroicPrefs.get(app).edit().clear().commit()
        DownloadQueue.dismissFinished()
    }

    @After fun tearDown() {
        HeroicSources.transport = null
        DownloadQueue.dismissFinished()
    }

    private fun await(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        fail("$what did not happen: status=${HeroicLauncher.status} downloads=${DownloadQueue.entry(HeroicLauncher.DOWNLOAD_KEY)}")
    }

    private fun prime(version: String) {
        HeroicLauncher.refresh(app)
        await("status reports $version") { HeroicLauncher.status.version == version && HeroicLauncher.status.installed }
    }

    private fun heroicsOwnData(): Map<String, String> {
        val prefix = File(root, "Games/Heroic/Prefixes/Some Game").apply { mkdirs() }
        val config = File(root, ".config/heroic/config.json").apply { parentFile!!.mkdirs() }
        val library = File(root, "Games/Heroic/Some Game").apply { mkdirs() }
        File(prefix, "system.reg").writeText("prefix")
        config.writeText("{\"wineVersion\":\"droiddeck\"}")
        File(library, "game.exe").writeText("game")
        return mapOf(
            "prefix" to File(prefix, "system.reg").readText(),
            "config" to config.readText(),
            "library" to File(library, "game.exe").readText(),
        )
    }

    @Test fun aNewerBuildArrivesThroughTheLaunchersOwnCheckAndKeepsHeroicsData() {
        seed("2.22.4")
        prime("2.22.4")
        val data = heroicsOwnData()
        val image = appImage("2.23.0")
        HeroicSources.transport = { url ->
            if (url == HeroicSources.RELEASES) { feedAsks.incrementAndGet(); Reply(200, release("2.23.0", image).toByteArray()) }
            else { assetAsks.incrementAndGet(); Reply(200, image) }
        }

        HeroicLauncher.ensure(app)
        await("update to 2.23.0") { HeroicLauncher.status.version == "2.23.0" }

        assertTrue("the release feed was consulted", feedAsks.get() >= 1)
        assertEquals(1, assetAsks.get())
        assertTrue(File(state, "app/2.23.0/${BundledUpdates.READY}").isFile)
        assertTrue(File(state, "app/2.23.0/Heroic-2.23.0-linux-arm64.AppImage").isFile)
        assertFalse("the replaced build must be pruned", File(state, "app/2.22.4").exists())
        val current = JSONObject(File(state, "current.json").readText())
        assertEquals("2.23.0", current.getString("app"))
        assertFalse(File(state, "current.json.tmp").exists())
        assertEquals(data, heroicsOwnData())
        assertEquals(DownloadState.INSTALLED, DownloadQueue.entry(HeroicLauncher.DOWNLOAD_KEY)?.state)
    }

    @Test fun anUpToDateLauncherInstallsNothingAndTheFeedIsNotAskedAgainWithinSixHours() {
        seed("2.23.0")
        prime("2.23.0")
        val image = appImage("2.23.0")
        HeroicSources.transport = { url ->
            if (url == HeroicSources.RELEASES) { feedAsks.incrementAndGet(); Reply(200, release("2.23.0", image).toByteArray()) }
            else { assetAsks.incrementAndGet(); Reply(200, image) }
        }

        HeroicLauncher.ensure(app)
        await("the release check is stamped") { HeroicPrefs.get(app).getLong(BundledUpdates.CHECKED_AT, 0L) > 0L }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("2.23.0", HeroicLauncher.status.version)
        assertEquals(1, feedAsks.get())
        assertEquals("no download for an up-to-date launcher", 0, assetAsks.get())
        assertFalse(DownloadQueue.isActive(HeroicLauncher.DOWNLOAD_KEY))
        assertTrue(File(state, "app/2.23.0/${BundledUpdates.READY}").isFile)

        HeroicLauncher.ensure(app)
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the feed stays unasked inside the six-hour window", 1, feedAsks.get())
        assertEquals("2.23.0", HeroicLauncher.status.version)
    }

    @Test fun aBuildSourceThatFailsLeavesTheOldBuildInstalledAndLaunchable() {
        seed("2.22.4")
        prime("2.22.4")
        val image = appImage("2.23.0")
        HeroicSources.transport = { url ->
            if (url == HeroicSources.RELEASES) { feedAsks.incrementAndGet(); Reply(200, release("2.23.0", image).toByteArray()) }
            else { assetAsks.incrementAndGet(); Reply(503, ByteArray(0)) }
        }

        HeroicLauncher.ensure(app)
        await("the install fails", 120_000) {
            DownloadQueue.entry(HeroicLauncher.DOWNLOAD_KEY)?.state == DownloadState.FAILED
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the download was attempted", assetAsks.get() > 0)
        assertEquals("2.22.4", HeroicLauncher.status.version)
        assertTrue(File(state, "app/2.22.4/${BundledUpdates.READY}").isFile)
        assertTrue(File(state, "app/2.22.4/Heroic-2.22.4-linux-arm64.AppImage").isFile)
        assertFalse(File(state, "app/2.23.0/${BundledUpdates.READY}").isFile)
        assertEquals("2.22.4", JSONObject(File(state, "current.json").readText()).getString("app"))
    }
}
