package com.itzsuli.smartkeyboard.data

import com.itzsuli.smartkeyboard.SmartKeyboardApp
import org.json.JSONObject

/**
 * Settings travel with backups and sync, so a fresh install (or a second phone) looks and works
 * exactly like before. What only makes sense on this phone stays out: the sync file and its
 * passphrase, sync status, the background picture (a file) and the setup screens.
 */
object SettingsBackup {
    /** Never leave the phone. */
    private val LOCAL_ONLY = setOf(
        Settings.SYNC_URI, Settings.SYNC_SECRET, Settings.SYNC_LAST, Settings.SYNC_ERROR,
        Settings.BG_IMAGE, Settings.BG_VERSION, Settings.ONBOARDING_DONE, Settings.CHANGED_AT,
    )

    /** API keys: only inside the encrypted sync file, never in a plain backup. */
    private fun isSecret(key: String) = key.startsWith(Settings.AI_KEY + "_")

    /** Adds the settings to a backup made by [LearningStore.exportJson]. */
    fun attach(json: String, settings: Settings, withSecrets: Boolean): String {
        val values = JSONObject()
        for ((key, value) in settings.prefs.all) {
            if (key in LOCAL_ONLY || (isSecret(key) && !withSecrets) || value == null) continue
            values.put(key, JSONObject().put("t", typeOf(value) ?: continue).put("v", if (value is Set<*>) value.joinToString("\n") else value))
        }
        val root = JSONObject(json)
        root.put("settings", JSONObject().put("changedAt", settings.changedAt).put("values", values))
        return root.toString()
    }

    /**
     * Applies the settings in [json]. With [onlyIfNewer] (sync), they're applied only when they
     * were changed after this phone's; a fresh install always takes them. Returns true if
     * anything was applied.
     */
    fun restore(json: String, settings: Settings, onlyIfNewer: Boolean): Boolean {
        val block = JSONObject(json).optJSONObject("settings") ?: return false
        val changedAt = block.optLong("changedAt")
        if (onlyIfNewer && changedAt <= settings.changedAt) return false
        val values = block.optJSONObject("values") ?: return false
        val hasSecrets = values.keys().asSequence().any { isSecret(it) }
        val edit = settings.prefs.edit()
        // A setting missing from the backup was at its default there.
        for (key in settings.prefs.all.keys) {
            if (key in LOCAL_ONLY || (isSecret(key) && !hasSecrets) || values.has(key)) continue
            edit.remove(key)
        }
        for (key in values.keys()) {
            if (key in LOCAL_ONLY) continue
            val entry = values.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "b" -> edit.putBoolean(key, entry.optBoolean("v"))
                "i" -> edit.putInt(key, entry.optInt("v"))
                "l" -> edit.putLong(key, entry.optLong("v"))
                "f" -> edit.putFloat(key, entry.optDouble("v").toFloat())
                "s" -> edit.putString(key, entry.optString("v"))
                "set" -> edit.putStringSet(key, entry.optString("v").split("\n").filter { it.isNotEmpty() }.toSet())
            }
        }
        edit.putLong(Settings.CHANGED_AT, maxOf(changedAt, settings.changedAt))
        edit.apply()
        return true
    }

    /** Everything worth keeping, as one file: learned words, shortcuts and settings (API keys stay out). */
    fun fullBackup(app: SmartKeyboardApp): String = attach(app.store.exportJson(), app.settings, withSecrets = false)

    /** Restores a file made by [fullBackup] (or an older learning-only backup). Returns the rows merged. */
    fun restoreFull(app: SmartKeyboardApp, json: String): Int {
        val n = app.store.importJson(json)
        restore(json, app.settings, onlyIfNewer = false)
        app.languages.reloadUserData()
        app.shortcuts.reload()
        return n
    }

    private fun typeOf(value: Any): String? = when (value) {
        is Boolean -> "b"
        is Int -> "i"
        is Long -> "l"
        is Float -> "f"
        is String -> "s"
        is Set<*> -> "set"
        else -> null
    }
}
