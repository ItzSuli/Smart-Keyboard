package com.itzsuli.smartkeyboard

import android.net.Uri
import com.itzsuli.smartkeyboard.data.SyncCrypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Encrypted sync: nothing readable leaves the phone, and merging never loses anything. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication() as SmartKeyboardApp

    @Test fun encryptsAndNeedsTheRightPassphrase() {
        val secret = """{"format":"smart-keyboard-learning","words":[{"key":"skrrt"}]}"""
        val box = SyncCrypto.encrypt(secret, "correct horse battery")
        assertFalse(String(box, Charsets.ISO_8859_1).contains("skrrt"))
        assertEquals(secret, SyncCrypto.decrypt(box, "correct horse battery"))
        val wrong = runCatching { SyncCrypto.decrypt(box, "wrong passphrase") }
        assertTrue(wrong.exceptionOrNull() is SyncCrypto.WrongPassphrase)
        // Tampering is detected too.
        box[box.size - 3] = (box[box.size - 3] + 1).toByte()
        assertTrue(runCatching { SyncCrypto.decrypt(box, "correct horse battery") }.isFailure)
    }

    private fun backup(words: List<Triple<String, Int, Int>>, shortcuts: List<Pair<String, String>> = emptyList()) = JSONObject()
        .put("format", "smart-keyboard-learning").put("version", 1)
        .put("words", JSONArray().apply {
            words.forEach { (k, count, flags) -> put(JSONObject().put("lang", "en").put("key", k).put("display", k).put("count", count).put("last_used", 1L).put("flags", flags)) }
        })
        .put("kv", JSONArray().apply {
            if (shortcuts.isNotEmpty()) put(JSONObject().put("key", "shortcuts").put("value", JSONArray().apply {
                shortcuts.forEach { (s, t) -> put(JSONObject().put("short", s).put("text", t)) }
            }.toString()))
            put(JSONObject().put("key", "touch.model").put("value", "other phone's screen"))
        })
        .toString()

    private fun wordCount(key: String): Int {
        val json = JSONObject(app.store.exportJson())
        val words = json.getJSONArray("words")
        return (0 until words.length()).map { words.getJSONObject(it) }.firstOrNull { it.getString("key") == key }?.getInt("count") ?: 0
    }

    @Test fun mergingKeepsTheHigherCountsAndEveryShortcut() {
        app.store.importJson(backup(listOf(Triple("bruh", 5, 1), Triple("wyd", 2, 1)), listOf("omw" to "On my way!")))
        app.store.importJson(backup(listOf(Triple("bruh", 3, 1), Triple("wyd", 9, 1), Triple("skrrt", 1, 0)), listOf("brb" to "be right back")))
        assertEquals(5, wordCount("bruh"))
        assertEquals(9, wordCount("wyd"))
        assertEquals(1, wordCount("skrrt"))
        // Merging the same thing again changes nothing.
        app.store.importJson(backup(listOf(Triple("bruh", 3, 1))))
        assertEquals(5, wordCount("bruh"))
        app.shortcuts.reload()
        assertEquals(setOf("omw", "brb"), app.shortcuts.all().map { it.short }.toSet())
        // Where the finger lands is this phone's own business.
        assertFalse(app.store.exportJson(forSync = true).contains("touch.model"))
    }

    @Test fun twoPhonesShareOneFile() {
        val file = File.createTempFile("sync", ".sksync").apply { delete() }
        val uri = Uri.fromFile(file)
        val sync = app.sync
        sync.setPassphrase("our secret phrase")
        sync.setFile(uri)
        app.store.importJson(backup(listOf(Triple("yeet", 4, 1))))
        val first = sync.syncNow().getOrThrow()
        assertTrue(first.createdFile)
        assertTrue(file.length() > 0)
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("yeet"))

        // "The other phone" adds a word to the same file.
        val other = JSONObject(SyncCrypto.decrypt(file.readBytes(), "our secret phrase"))
        other.getJSONArray("words").put(JSONObject().put("lang", "en").put("key", "habibi").put("display", "habibi").put("count", 7).put("last_used", 2L).put("flags", 1))
        file.writeBytes(SyncCrypto.encrypt(other.toString(), "our secret phrase"))

        sync.syncNow().getOrThrow()
        assertEquals(7, wordCount("habibi"))
        assertEquals(4, wordCount("yeet"))
        // A wrong passphrase is reported and the file is left alone.
        val before = file.readBytes()
        sync.setPassphrase("a different phrase")
        val failed = sync.syncNow()
        assertTrue(failed.isFailure)
        assertTrue(file.readBytes().contentEquals(before))
        assertEquals("Wrong passphrase for this sync file", app.settings.syncError)
    }
}
