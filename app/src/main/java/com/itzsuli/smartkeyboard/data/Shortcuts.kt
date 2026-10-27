package com.itzsuli.smartkeyboard.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shortcuts the user defines themselves ("omw" → "On my way!"). There are no built-in ones:
 * the keyboard never expands anything the user didn't ask for. They show up as a suggestion
 * when the shortcut is typed; with "expand automatically" on, space expands them (backspace
 * undoes it, like any correction).
 */
class Shortcuts(private val store: LearningStore) {
    data class Shortcut(val short: String, val text: String)

    private val map = LinkedHashMap<String, Shortcut>()
    private var loaded = false

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val json = runCatching { store.getValue(KEY) }.getOrNull() ?: return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = Shortcut(o.getString("short"), o.getString("text"))
                if (s.short.isNotBlank() && s.text.isNotBlank()) map[normalize(s.short)] = s
            }
        }
    }

    @Synchronized fun all(): List<Shortcut> { ensureLoaded(); return map.values.toList() }

    /** The shortcut typed as [word], if any (case-insensitive). */
    @Synchronized fun find(word: String): Shortcut? { ensureLoaded(); return if (map.isEmpty()) null else map[normalize(word)] }

    @Synchronized fun put(short: String, text: String) {
        ensureLoaded()
        val s = short.trim()
        val t = text.trim()
        require(s.isNotEmpty() && t.isNotEmpty()) { "Both fields are needed" }
        require(s.none { it.isWhitespace() }) { "A shortcut is one word, without spaces" }
        map[normalize(s)] = Shortcut(s, t)
        save()
    }

    @Synchronized fun remove(short: String) {
        ensureLoaded()
        if (map.remove(normalize(short)) != null) save()
    }

    /** Re-reads from storage (after a backup import or a sync). */
    @Synchronized fun reload() {
        map.clear()
        loaded = false
    }

    private fun save() {
        val arr = JSONArray()
        map.values.forEach { arr.put(JSONObject().put("short", it.short).put("text", it.text)) }
        store.putValue(KEY, arr.toString())
    }

    private fun normalize(s: String) = s.trim().lowercase()

    companion object {
        const val KEY = "shortcuts"
    }
}
