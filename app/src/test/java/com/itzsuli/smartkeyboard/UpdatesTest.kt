package com.itzsuli.smartkeyboard

import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.data.SettingsBackup
import com.itzsuli.smartkeyboard.data.SyncCrypto
import com.itzsuli.smartkeyboard.data.Updater
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Updating keeps everything: settings go into backups and sync, and updates come from inside the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdatesTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication() as SmartKeyboardApp
    private val server = FakeHttpServer()

    @After fun tearDown() {
        server.close()
        Updater.apiBase = "https://api.github.com/repos/ItzSuli/Smart-Keyboard"
    }

    private fun release(tag: String, apk: String = "fake apk bytes") = JSONObject()
        .put("tag_name", tag).put("body", "What's new").put("html_url", "https://github.com/ItzSuli/Smart-Keyboard/releases/$tag")
        .put("assets", JSONArray()
            .put(JSONObject().put("name", "SmartKeyboard-$tag.apk.sha256").put("browser_download_url", "${server.baseUrl}/sha").put("size", 64))
            .put(JSONObject().put("name", "SmartKeyboard-$tag.apk").put("browser_download_url", "${server.baseUrl}/apk").put("size", apk.length)))
        .toString()

    @Test fun findsAndDownloadsANewerVersion() {
        Updater.apiBase = server.baseUrl
        server.reply = release("v99.0.0")
        val updater = Updater(app)
        val r = updater.check()
        assertNotNull(r)
        assertEquals("99.0.0", r!!.version)
        assertEquals("/releases/latest", server.lastPath)
        assertTrue(r.apkUrl.endsWith("/apk"))
        server.reply = "fake apk bytes"
        val apk = updater.download(r) { _, _ -> }
        assertEquals(r.apkSize, apk.length())
        // Not a real APK, so its signer can't be told.
        assertEquals(Updater.Signer.UNKNOWN, updater.signerOf(apk))
        // A cut-off download is never handed to the installer.
        server.reply = "fake apk"
        assertTrue(runCatching { updater.download(r) { _, _ -> } }.isFailure)
        server.reply = release("v99.0.0")
        assertEquals("99.0.0", updater.availableVersion(maxAgeMs = 0))
        // The same or an older version is no update.
        server.reply = release("v${BuildConfig.VERSION_NAME.removeSuffix("-debug")}")
        assertNull(updater.check())
        assertNull(updater.availableVersion(maxAgeMs = 0))
    }

    @Test fun comparesVersions() {
        assertTrue(Updater.isNewer("1.4.0", "1.3.1"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("2.0", "1.99.99"))
        assertFalse(Updater.isNewer("1.4.0", "1.4.0"))
        assertFalse(Updater.isNewer("1.3.9", "1.4.0"))
    }

    @Test fun backupsCarryTheSettings() {
        val s = app.settings
        s.set(Settings.THEME, "midnight")
        s.set(Settings.KEY_HEIGHT, 115)
        s.set(Settings.HAPTICS, false)
        s.set("${Settings.AI_KEY}_gemini", "secret-key")
        s.set(Settings.SYNC_URI, "content://this/phone")
        val backup = SettingsBackup.fullBackup(app)
        // A plain backup file never holds an API key or this phone's sync file.
        assertFalse(backup.contains("secret-key"))
        assertFalse(backup.contains("content://this/phone"))

        // A fresh install: everything back to defaults.
        s.set(Settings.THEME, null); s.set(Settings.KEY_HEIGHT, null); s.set(Settings.HAPTICS, null)
        s.set(Settings.NUMBER_ROW, true)
        SettingsBackup.restoreFull(app, backup)
        assertEquals("midnight", s.themeId)
        assertEquals(115, s.keyHeightPercent)
        assertFalse(s.haptics)
        assertFalse(s.numberRow) // was at its default in the backup
        assertEquals("secret-key", s.prefs.getString("${Settings.AI_KEY}_gemini", null))
        assertEquals("content://this/phone", s.prefs.getString(Settings.SYNC_URI, null))
    }

    @Test fun syncBringsSettingsToANewInstall() {
        val s = app.settings
        val file = File.createTempFile("sync", ".sksync").apply { delete() }
        app.sync.setPassphrase("our secret phrase")
        app.sync.setFile(android.net.Uri.fromFile(file))
        s.set(Settings.THEME, "ocean")
        s.set("${Settings.AI_KEY}_gemini", "k-123")
        app.sync.syncNow().getOrThrow()
        // Inside the encrypted file the API key travels too.
        assertTrue(SyncCrypto.decrypt(file.readBytes(), "our secret phrase").contains("k-123"))

        // Reinstalled: defaults, then the same sync file is picked again.
        s.set(Settings.THEME, null); s.set("${Settings.AI_KEY}_gemini", null)
        s.set(Settings.LANGUAGES, "en") // the setup screen touched something
        app.sync.setFile(android.net.Uri.fromFile(file))
        app.sync.syncNow().getOrThrow()
        assertEquals("ocean", s.themeId)
        assertEquals("k-123", s.prefs.getString("${Settings.AI_KEY}_gemini", null))

        // After that, only newer settings win: a change here survives the next sync.
        Thread.sleep(5)
        s.set(Settings.THEME, "forest")
        app.sync.syncNow().getOrThrow()
        assertEquals("forest", s.themeId)
    }
}
