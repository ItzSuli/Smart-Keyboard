package com.itzsuli.smartkeyboard.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.HandlerThread
import com.itzsuli.smartkeyboard.engine.UserLexicon
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device storage for everything the keyboard learns. Nothing here ever leaves the phone
 * (except through an export the user starts). Writes are coalesced and flushed on a
 * background thread so typing never waits for the disk.
 */
class LearningStore(context: Context) : UserLexicon.Listener {

    private val helper = object : SQLiteOpenHelper(context, "learning.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE words(lang TEXT, key TEXT, display TEXT, count INTEGER, last_used INTEGER, flags INTEGER, PRIMARY KEY(lang, key))")
            db.execSQL("CREATE TABLE ngrams(lang TEXT, ngram TEXT, count INTEGER, PRIMARY KEY(lang, ngram))")
            db.execSQL("CREATE TABLE corrections(lang TEXT, typed TEXT, target TEXT, count INTEGER, PRIMARY KEY(lang, typed, target))")
            db.execSQL("CREATE TABLE rejections(lang TEXT, typed TEXT, target TEXT, count INTEGER, PRIMARY KEY(lang, typed, target))")
            db.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val thread = HandlerThread("learning-store").apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = LinkedHashMap<String, (SQLiteDatabase) -> Unit>()
    private var flushScheduled = false

    private fun enqueue(id: String, op: (SQLiteDatabase) -> Unit) {
        synchronized(pending) {
            pending.remove(id)
            pending[id] = op
            if (!flushScheduled) {
                flushScheduled = true
                handler.postDelayed(::flush, FLUSH_DELAY_MS)
            }
        }
    }

    fun flush() {
        val ops = synchronized(pending) {
            flushScheduled = false
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        if (ops.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ops.forEach { it(db) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun flushAsync() = handler.post(::flush)

    // ---- UserLexicon.Listener ----

    override fun onWord(lang: String, word: UserLexicon.Word) {
        val display = word.display
        val count = word.count
        val last = word.lastUsed
        val flags = word.flags
        enqueue("w|$lang|${word.key}") { db ->
            db.insertWithOnConflict("words", null, ContentValues().apply {
                put("lang", lang); put("key", word.key); put("display", display)
                put("count", count); put("last_used", last); put("flags", flags)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun onWordRemoved(lang: String, key: String) {
        enqueue("w|$lang|$key") { db ->
            db.delete("words", "lang=? AND key=?", arrayOf(lang, key))
            db.delete("corrections", "lang=? AND typed=?", arrayOf(lang, key))
        }
    }

    override fun onNgram(lang: String, ngram: String, count: Int) {
        enqueue("n|$lang|$ngram") { db ->
            db.insertWithOnConflict("ngrams", null, ContentValues().apply {
                put("lang", lang); put("ngram", ngram); put("count", count)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun onCorrection(lang: String, typed: String, target: String, count: Int) {
        enqueue("c|$lang|$typed|$target") { db ->
            db.insertWithOnConflict("corrections", null, ContentValues().apply {
                put("lang", lang); put("typed", typed); put("target", target); put("count", count)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun onRejection(lang: String, typed: String, target: String, count: Int) {
        enqueue("r|$lang|$typed|$target") { db ->
            db.insertWithOnConflict("rejections", null, ContentValues().apply {
                put("lang", lang); put("typed", typed); put("target", target); put("count", count)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("corrections", "lang=? AND typed=? AND target=?", arrayOf(lang, typed, target))
        }
    }

    override fun onCleared(lang: String) {
        synchronized(pending) { pending.keys.removeAll { it.contains("|$lang|") } }
        handler.post {
            val db = helper.writableDatabase
            for (t in listOf("words", "ngrams", "corrections", "rejections")) db.delete(t, "lang=?", arrayOf(lang))
        }
    }

    // ---- loading ----

    /** Fills [lexicon] from disk. Call from a background thread. */
    fun load(lexicon: UserLexicon) {
        flush()
        val db = helper.readableDatabase
        val lang = lexicon.lang
        db.rawQuery("SELECT key, display, count, last_used, flags FROM words WHERE lang=?", arrayOf(lang)).use { c ->
            while (c.moveToNext()) lexicon.restoreWord(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), c.getInt(4))
        }
        db.rawQuery("SELECT ngram, count FROM ngrams WHERE lang=? ORDER BY count DESC LIMIT $MAX_NGRAMS", arrayOf(lang)).use { c ->
            while (c.moveToNext()) lexicon.restoreNgram(c.getString(0), c.getInt(1))
        }
        db.rawQuery("SELECT typed, target, count FROM corrections WHERE lang=?", arrayOf(lang)).use { c ->
            while (c.moveToNext()) lexicon.restoreCorrection(c.getString(0), c.getString(1), c.getInt(2))
        }
        db.rawQuery("SELECT typed, target, count FROM rejections WHERE lang=?", arrayOf(lang)).use { c ->
            while (c.moveToNext()) lexicon.restoreRejection(c.getString(0), c.getString(1), c.getInt(2))
        }
    }

    fun getValue(key: String): String? =
        helper.readableDatabase.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun putValue(key: String, value: String) = enqueue("kv|$key") { db ->
        db.insertWithOnConflict("kv", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun incrementStat(name: String, by: Long = 1) {
        handler.post {
            val db = helper.writableDatabase
            val current = db.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf("stat.$name")).use { c ->
                if (c.moveToFirst()) c.getString(0).toLongOrNull() ?: 0 else 0
            }
            db.insertWithOnConflict("kv", null, ContentValues().apply {
                put("key", "stat.$name"); put("value", (current + by).toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun stat(name: String): Long = getValue("stat.$name")?.toLongOrNull() ?: 0

    // ---- backup & sync ----

    /**
     * Everything learned as JSON. [forSync] leaves out what belongs to this phone only (where
     * the finger lands on this screen, adaptive-key tuning).
     */
    fun exportJson(forSync: Boolean = false): String {
        flush()
        val db = helper.readableDatabase
        val root = JSONObject().put("format", "smart-keyboard-learning").put("version", 1)
        for (table in TABLES) {
            val rows = JSONArray()
            db.rawQuery("SELECT * FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in 0 until c.columnCount) {
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(c.getColumnName(i), c.getLong(i))
                            else -> row.put(c.getColumnName(i), c.getString(i))
                        }
                    }
                    if (forSync && table == "kv" && isDeviceOnly(row.optString("key"))) continue
                    rows.put(row)
                }
            }
            root.put(table, rows)
        }
        return root.toString()
    }

    /**
     * Merges a backup (or another phone's data) into the database without losing anything:
     * counts take the higher value, flags (confirmed, blocked...) are combined, your own
     * shortcuts are joined, and this phone's touch data is kept. Merging the same data twice
     * changes nothing. Returns the number of rows merged.
     */
    fun importJson(json: String): Int {
        val root = JSONObject(json)
        require(root.optString("format") == "smart-keyboard-learning") { "Not a Smart Keyboard backup" }
        flush()
        val db = helper.writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            root.optJSONArray("words")?.forEachRow { r ->
                val lang = r.getString("lang"); val key = r.getString("key")
                val local = db.rawQuery("SELECT display, count, last_used, flags FROM words WHERE lang=? AND key=?", arrayOf(lang, key)).use { c ->
                    if (c.moveToFirst()) arrayOf<Any>(c.getString(0), c.getInt(1), c.getLong(2), c.getInt(3)) else null
                }
                val count = r.optInt("count"); val last = r.optLong("last_used"); val flags = r.optInt("flags")
                db.insertWithOnConflict("words", null, ContentValues().apply {
                    put("lang", lang); put("key", key)
                    if (local == null) {
                        put("display", r.optString("display", key)); put("count", count); put("last_used", last); put("flags", flags)
                    } else {
                        val newer = last > (local[2] as Long)
                        put("display", if (newer) r.optString("display", key) else local[0] as String)
                        put("count", maxOf(count, local[1] as Int)); put("last_used", maxOf(last, local[2] as Long))
                        put("flags", flags or (local[3] as Int))
                    }
                }, SQLiteDatabase.CONFLICT_REPLACE)
                n++
            }
            root.optJSONArray("ngrams")?.forEachRow { r ->
                mergeCount(db, "ngrams", arrayOf("lang", "ngram"), arrayOf(r.getString("lang"), r.getString("ngram")), r.optInt("count")); n++
            }
            for (table in listOf("corrections", "rejections")) root.optJSONArray(table)?.forEachRow { r ->
                mergeCount(db, table, arrayOf("lang", "typed", "target"), arrayOf(r.getString("lang"), r.getString("typed"), r.getString("target")), r.optInt("count")); n++
            }
            root.optJSONArray("kv")?.forEachRow { r ->
                val key = r.getString("key")
                val value = r.optString("value")
                if (isDeviceOnly(key)) return@forEachRow
                val local = db.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val merged = when {
                    local == null -> value
                    key.startsWith("stat.") -> maxOf(local.toLongOrNull() ?: 0, value.toLongOrNull() ?: 0).toString()
                    key == Shortcuts.KEY -> mergeShortcuts(local, value)
                    else -> local
                }
                if (merged != local) db.insertWithOnConflict("kv", null, ContentValues().apply { put("key", key); put("value", merged) }, SQLiteDatabase.CONFLICT_REPLACE)
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    private inline fun JSONArray.forEachRow(action: (JSONObject) -> Unit) { for (i in 0 until length()) action(getJSONObject(i)) }

    private fun mergeCount(db: SQLiteDatabase, table: String, keys: Array<String>, values: Array<String>, count: Int) {
        val where = keys.joinToString(" AND ") { "$it=?" }
        val local = db.rawQuery("SELECT count FROM $table WHERE $where", values).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        if (local >= count) return
        db.insertWithOnConflict(table, null, ContentValues().apply {
            keys.forEachIndexed { i, k -> put(k, values[i]) }
            put("count", count)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Your shortcuts from both sides; for the same shortcut, this phone's text wins. */
    private fun mergeShortcuts(local: String, remote: String): String = runCatching {
        val out = JSONArray(local)
        val have = (0 until out.length()).map { out.getJSONObject(it).optString("short").lowercase() }.toHashSet()
        val other = JSONArray(remote)
        for (i in 0 until other.length()) {
            val o = other.getJSONObject(i)
            if (have.add(o.optString("short").lowercase())) out.put(o)
        }
        out.toString()
    }.getOrDefault(local)

    private fun isDeviceOnly(key: String) = key.startsWith("touch.") || key.startsWith("hitbox.")

    fun clearAll() {
        synchronized(pending) { pending.clear() }
        handler.post {
            val db = helper.writableDatabase
            for (t in listOf("words", "ngrams", "corrections", "rejections")) db.delete(t, null, null)
            db.delete("kv", "key LIKE 'touch.%'", null)
        }
    }

    companion object {
        private val TABLES = listOf("words", "ngrams", "corrections", "rejections", "kv")
        private const val FLUSH_DELAY_MS = 2500L
        private const val MAX_NGRAMS = 80_000
    }
}
