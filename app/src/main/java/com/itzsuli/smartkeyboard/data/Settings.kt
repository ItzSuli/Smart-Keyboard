package com.itzsuli.smartkeyboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.itzsuli.smartkeyboard.engine.AutoCorrectMode

enum class AiAutoProof { OFF, SUGGEST, AUTO }

/** How key presses feel. CRISP and SOFT use the phone's tuned haptic effects where available. */
enum class HapticStyle { CRISP, SOFT, CLASSIC, SYSTEM }

/**
 * All user preferences. Backed by one SharedPreferences file that the keyboard and the
 * settings app share (same process), so changes apply instantly.
 */
class Settings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // One-handed mode was removed in 1.2; drop its old value so nothing can pick it up.
        if (prefs.contains(LEGACY_ONE_HANDED)) prefs.edit { remove(LEGACY_ONE_HANDED) }
    }

    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun str(key: String, def: String) = prefs.getString(key, def) ?: def

    fun set(key: String, value: Any?) = prefs.edit {
        when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Enum<*> -> putString(key, value.name)
            else -> error("Unsupported setting type for $key")
        }
    }

    // ---- appearance ----
    val themeId get() = str(THEME, "auto")
    val customAccent get() = int(ACCENT, 0)
    val keyBorders get() = bool(KEY_BORDERS, true)
    val keyRadiusDp get() = int(KEY_RADIUS, 9)
    val keyGapDp get() = int(KEY_GAP, 5)
    val keyHeightPercent get() = int(KEY_HEIGHT, 100)
    val labelScalePercent get() = int(LABEL_SCALE, 100)
    val numberRow get() = bool(NUMBER_ROW, false)
    val showHints get() = bool(SHOW_HINTS, true)
    val keyPopup get() = bool(KEY_POPUP, true)
    val backgroundImage get() = bool(BG_IMAGE, false)
    val backgroundDim get() = int(BG_DIM, 35)
    /** Button on the suggestion bar that switches to another keyboard app. */
    val switchKey get() = bool(SWITCH_KEY, true)

    // ---- feedback ----
    val haptics get() = bool(HAPTICS, true)
    val hapticStrength get() = int(HAPTIC_STRENGTH, 45)
    val hapticStyle get() = enumValueOrDefault(str(HAPTIC_STYLE, HapticStyle.CRISP.name), HapticStyle.CRISP)
    val sound get() = bool(SOUND, false)
    val soundVolume get() = int(SOUND_VOLUME, 40)
    val longPressMs get() = int(LONG_PRESS, 300)

    // ---- typing ----
    val autoCapitalize get() = bool(AUTO_CAP, true)
    val doubleSpacePeriod get() = bool(DOUBLE_SPACE, true)
    val autoCorrectMode get() = enumValueOrDefault(str(AUTOCORRECT, AutoCorrectMode.CHILL.name), AutoCorrectMode.CHILL)
    val fixCapitalization get() = bool(FIX_CAPS, true)
    val showSuggestions get() = bool(SUGGESTIONS, true)
    val emojiSuggestions get() = bool(EMOJI_SUGGESTIONS, true)
    /** 0 = off, 1 = subtle, 2 = balanced, 3 = strong. */
    val adaptiveHitbox get() = int(ADAPTIVE_HITBOX, 2)
    val swipeTyping get() = bool(SWIPE, true)
    val swipeTrail get() = bool(SWIPE_TRAIL, true)
    val swipePreview get() = bool(SWIPE_PREVIEW, true)
    /** One backspace right after a swipe removes the whole swiped word. */
    val deleteSwipedWord get() = bool(DELETE_SWIPED_WORD, true)
    val spaceCursor get() = bool(SPACE_CURSOR, true)
    val deleteSwipe get() = bool(DELETE_SWIPE, true)
    val emojiKey get() = bool(EMOJI_KEY, true)
    val arabicDigits get() = bool(ARABIC_DIGITS, false)
    val clipboardHistory get() = bool(CLIPBOARD, true)

    // ---- languages ----
    val languages: List<String> get() = str(LANGUAGES, "").split(',').filter { it.isNotBlank() }.ifEmpty { listOf("en") }
    /**
     * Languages typed together on one keyboard (e.g. English + German on QWERTY). When the
     * keyboard's language is one of them, the others are understood too, without switching.
     * Default: English + German if both are enabled.
     */
    val mixedLanguages: List<String> get() = prefs.getString(MIXED_LANGUAGES, null)
        ?.split(',')?.filter { it.isNotBlank() }
        ?: if ("en" in languages && "de" in languages) listOf("en", "de") else emptyList()

    fun mixedLanguagesFor(code: String): List<String> {
        val mix = mixedLanguages
        if (code !in mix || code !in LATIN_SCRIPT) return emptyList()
        return mix.filter { it != code && it in LATIN_SCRIPT }
    }

    val currentLanguage: String get() = str(CURRENT_LANGUAGE, languages.first()).takeIf { it in languages } ?: languages.first()

    // ---- learning & privacy ----
    val learning get() = bool(LEARNING, true)
    val incognito get() = bool(INCOGNITO, false)

    // ---- AI ----
    val aiProvider get() = str(AI_PROVIDER, "gemini")
    /** Keys are stored per provider so switching services never mixes them up. */
    val aiApiKey get() = str("${AI_KEY}_$aiProvider", "")
    val aiModel get() = str(AI_MODEL, "")
    val aiBaseUrl get() = str(AI_BASE_URL, "")
    val aiAutoProof get() = enumValueOrDefault(str(AI_AUTO_PROOF, AiAutoProof.SUGGEST.name), AiAutoProof.SUGGEST)
    val aiSuggestions get() = bool(AI_SUGGESTIONS, true)
    val aiEnabled get() = bool(AI_ENABLED, true) && (aiApiKey.isNotBlank() || (aiProvider == "custom" && aiBaseUrl.isNotBlank()))
    val aiTone get() = str(AI_TONE, "chill")

    val onboardingDone get() = bool(ONBOARDING_DONE, false)

    companion object {
        const val THEME = "theme"
        const val ACCENT = "accent"
        const val KEY_BORDERS = "key_borders"
        const val KEY_RADIUS = "key_radius"
        const val KEY_GAP = "key_gap"
        const val KEY_HEIGHT = "key_height"
        const val LABEL_SCALE = "label_scale"
        const val NUMBER_ROW = "number_row"
        const val SHOW_HINTS = "show_hints"
        const val KEY_POPUP = "key_popup"
        const val BG_IMAGE = "bg_image"
        const val BG_DIM = "bg_dim"
        const val SWITCH_KEY = "switch_key"
        private const val LEGACY_ONE_HANDED = "one_handed"
        const val HAPTICS = "haptics"
        const val HAPTIC_STRENGTH = "haptic_strength"
        const val HAPTIC_STYLE = "haptic_style"
        const val SOUND = "sound"
        const val SOUND_VOLUME = "sound_volume"
        const val LONG_PRESS = "long_press"
        const val AUTO_CAP = "auto_cap"
        const val DOUBLE_SPACE = "double_space"
        const val AUTOCORRECT = "autocorrect"
        const val FIX_CAPS = "fix_caps"
        const val SUGGESTIONS = "suggestions"
        const val EMOJI_SUGGESTIONS = "emoji_suggestions"
        const val ADAPTIVE_HITBOX = "adaptive_hitbox"
        const val SWIPE = "swipe"
        const val SWIPE_TRAIL = "swipe_trail"
        const val SWIPE_PREVIEW = "swipe_preview"
        const val DELETE_SWIPED_WORD = "delete_swiped_word"
        const val SPACE_CURSOR = "space_cursor"
        const val DELETE_SWIPE = "delete_swipe"
        const val EMOJI_KEY = "emoji_key"
        const val ARABIC_DIGITS = "arabic_digits"
        const val CLIPBOARD = "clipboard"
        const val LANGUAGES = "languages"
        const val CURRENT_LANGUAGE = "current_language"
        const val MIXED_LANGUAGES = "mixed_languages"
        /** Languages that share the Latin keyboard letters and can be mixed. */
        val LATIN_SCRIPT = setOf("en", "de", "fr", "es", "it", "pt", "tr", "nl", "pl")
        const val LEARNING = "learning"
        const val INCOGNITO = "incognito"
        const val AI_PROVIDER = "ai_provider"
        const val AI_KEY = "ai_key"
        const val AI_MODEL = "ai_model"
        const val AI_BASE_URL = "ai_base_url"
        const val AI_AUTO_PROOF = "ai_auto_proof"
        const val AI_SUGGESTIONS = "ai_suggestions"
        const val AI_ENABLED = "ai_enabled"
        const val AI_TONE = "ai_tone"
        const val ONBOARDING_DONE = "onboarding_done"
        /** Bumped when a new background picture is saved. */
        const val BG_VERSION = "bg_version"

        /** Settings that change how the keyboard looks. */
        val APPEARANCE = setOf(THEME, ACCENT, KEY_BORDERS, KEY_RADIUS, KEY_GAP, LABEL_SCALE, BG_IMAGE, BG_DIM, BG_VERSION)

        inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, def: T): T =
            runCatching { enumValueOf<T>(name) }.getOrDefault(def)
    }
}
