package com.itzsuli.smartkeyboard.data

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

class Clip(val text: String, val time: Long, var pinned: Boolean)

/** Recent copied texts. Sensitive clips (passwords copied from password managers) are skipped. */
class ClipboardHistory(context: Context) {
    private val prefs = context.getSharedPreferences("clipboard", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val clips = ArrayList<Clip>()
    private var listening = false
    var enabled = true

    init { load() }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }

    fun startListening() {
        if (listening) return
        listening = true
        manager?.addPrimaryClipChangedListener(listener)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        manager?.removePrimaryClipChangedListener(listener)
    }

    private fun capture() {
        if (!enabled) return
        val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return
        val desc = clip.description
        if (Build.VERSION.SDK_INT >= 33 && desc?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
        if (Build.VERSION.SDK_INT >= 24 && desc?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
        val text = clip.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.length > 5000) return
        add(text)
    }

    @Synchronized fun add(text: String) {
        val existing = clips.indexOfFirst { it.text == text }
        val pinned = existing >= 0 && clips[existing].pinned
        if (existing >= 0) clips.removeAt(existing)
        clips.add(0, Clip(text, System.currentTimeMillis(), pinned))
        trim()
        save()
    }

    @Synchronized fun all(): List<Clip> = clips.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })

    @Synchronized fun togglePin(clip: Clip) { clip.pinned = !clip.pinned; save() }

    @Synchronized fun remove(clip: Clip) { clips.remove(clip); save() }

    @Synchronized fun clearUnpinned() { clips.removeAll { !it.pinned }; save() }

    private fun trim() {
        // Keep pinned clips forever, the 30 newest of the rest, nothing older than a week.
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val unpinned = clips.filter { !it.pinned }
        unpinned.drop(30).forEach { clips.remove(it) }
        clips.removeAll { !it.pinned && it.time < weekAgo }
    }

    private fun save() {
        val arr = JSONArray()
        for (c in clips) arr.put(JSONObject().put("t", c.text).put("time", c.time).put("p", c.pinned))
        prefs.edit().putString("clips", arr.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString("clips", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                clips.add(Clip(o.getString("t"), o.getLong("time"), o.optBoolean("p")))
            }
            trim()
        }
    }
}
