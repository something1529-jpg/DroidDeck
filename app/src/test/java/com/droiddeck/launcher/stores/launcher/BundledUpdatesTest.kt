package com.droiddeck.launcher.stores.launcher

import android.content.SharedPreferences
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BundledUpdatesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val now = 10L * BundledUpdates.CHECK_EVERY_MS
    private lateinit var state: File
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        state = File(app.cacheDir, "bundled-state-${System.nanoTime()}")
        prefs = app.getSharedPreferences("bundled-updates-${System.nanoTime()}", 0)
    }

    @After fun tearDown() {
        state.deleteRecursively()
    }

    private fun build(version: String, ready: Boolean = true): File {
        val dir = File(state, "app/$version").apply { mkdirs() }
        File(dir, "Heroic-$version-linux-arm64.AppImage").writeText("fake appimage")
        if (ready) File(dir, BundledUpdates.READY).writeText("$version\n")
        return dir
    }

    private fun install(version: String): Boolean {
        val dir = build(version)
        val recorded = BundledUpdates.record(state, version, "Heroic-$version-linux-arm64.AppImage")
        BundledUpdates.prune(state, dir)
        return recorded
    }

    @Test fun aNewerBuildReplacesTheOldOneAndKeepsHeroicsOwnData() {
        val old = build("2.22.4")
        assertTrue(BundledUpdates.record(state, "2.22.4", "Heroic-2.22.4-linux-arm64.AppImage"))
        val prefix = File(state, "prefixes/Some Game").apply { mkdirs() }
        File(prefix, "system.reg").writeText("prefix")
        val config = File(state, "config.json").apply { writeText("{}") }
        var installs = 0

        val check = BundledUpdates.ensure(state, prefs, latest = { "2.23.0" },
            install = { installs++; install("2.23.0") }, now = now)

        assertTrue(check.checked)
        assertTrue(check.update)
        assertEquals("2.23.0", check.latest)
        assertEquals(1, installs)
        assertEquals("2.23.0", BundledUpdates.installed(state))
        assertEquals("2.23.0", BundledUpdates.version(state))
        assertFalse(old.exists())
        assertTrue(File(state, "app/2.23.0/${BundledUpdates.READY}").isFile)
        assertTrue(File(state, "app/2.23.0/Heroic-2.23.0-linux-arm64.AppImage").isFile)
        assertTrue(prefix.isDirectory)
        assertEquals("prefix", File(prefix, "system.reg").readText())
        assertTrue(config.isFile)
        assertEquals(now, prefs.getLong(BundledUpdates.CHECKED_AT, 0L))
    }

    @Test fun anUpToDateLauncherIsNotDownloadedAgain() {
        build("2.23.0")
        assertTrue(BundledUpdates.record(state, "2.23.0", "Heroic-2.23.0-linux-arm64.AppImage"))
        var installs = 0

        val check = BundledUpdates.ensure(state, prefs, latest = { "2.23.0" }, install = { installs++ }, now = now)

        assertTrue(check.checked)
        assertFalse(check.update)
        assertEquals(0, installs)
        assertEquals(now, prefs.getLong(BundledUpdates.CHECKED_AT, 0L))
    }

    @Test fun theFeedIsNotAskedAgainBeforeSixHours() {
        build("2.22.4")
        assertTrue(BundledUpdates.record(state, "2.22.4", "Heroic-2.22.4-linux-arm64.AppImage"))
        prefs.edit().putLong(BundledUpdates.CHECKED_AT, now).apply()
        var asks = 0
        var installs = 0

        val early = BundledUpdates.ensure(state, prefs, latest = { asks++; "2.23.0" }, install = { installs++ },
            now = now + BundledUpdates.CHECK_EVERY_MS - 1)
        assertFalse(early.checked)
        assertEquals(0, asks)
        assertEquals(0, installs)

        val due = BundledUpdates.ensure(state, prefs, latest = { asks++; "2.23.0" }, install = { installs++; install("2.23.0") },
            now = now + BundledUpdates.CHECK_EVERY_MS)
        assertTrue(due.checked)
        assertTrue(due.update)
        assertEquals(1, asks)
        assertEquals(1, installs)
        assertEquals("2.23.0", BundledUpdates.installed(state))
    }

    @Test fun anUnreachableFeedKeepsTheBuildAndRetriesNextTime() {
        build("2.22.4")
        assertTrue(BundledUpdates.record(state, "2.22.4", "Heroic-2.22.4-linux-arm64.AppImage"))
        var installs = 0

        val check = BundledUpdates.ensure(state, prefs, latest = { null }, install = { installs++ }, now = now)

        assertFalse(check.checked)
        assertFalse(check.update)
        assertEquals(0, installs)
        assertEquals(0L, prefs.getLong(BundledUpdates.CHECKED_AT, 0L))
        assertEquals("2.22.4", BundledUpdates.installed(state))
    }

    @Test fun aFailedInstallLeavesThePreviousBuildLaunchable() {
        val old = build("2.22.4")
        assertTrue(BundledUpdates.record(state, "2.22.4", "Heroic-2.22.4-linux-arm64.AppImage"))

        val check = BundledUpdates.ensure(state, prefs, latest = { "2.23.0" }, install = {
            build("2.23.0", ready = false)
            File(state, "app/2.23.0/Heroic-2.23.0-linux-arm64.AppImage").delete()
        }, now = now)

        assertTrue(check.update)
        assertEquals("2.22.4", BundledUpdates.installed(state))
        assertTrue(File(old, BundledUpdates.READY).isFile)
        assertTrue(File(old, "Heroic-2.22.4-linux-arm64.AppImage").isFile)
    }

    @Test fun aBuildWithoutItsReadyMarkerNeverCounts() {
        build("2.22.4", ready = false)
        assertTrue(BundledUpdates.record(state, "2.22.4", "Heroic-2.22.4-linux-arm64.AppImage"))
        assertNull(BundledUpdates.installed(state))
        assertEquals("2.22.4", BundledUpdates.version(state))
    }

    @Test fun recordingABuildLeavesNoPartialFile() {
        build("2.22.4")
        assertTrue(BundledUpdates.record(state, "2.22.4", "Heroic-2.22.4-linux-arm64.AppImage"))
        assertFalse(File(state, "current.json.tmp").exists())
        assertEquals("2.22.4", BundledUpdates.version(state))
    }
}
