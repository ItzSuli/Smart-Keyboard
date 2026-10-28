package com.itzsuli.smartkeyboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.itzsuli.smartkeyboard.engine.AutoCorrectMode

enum class AiAutoProof { OFF, SUGGEST, AUTO }

/** How key presses feel. CRISP and SOFT use the phone's tuned haptic effects where available. */
enum class HapticStyle { CRISP, SOFT, CLASSIC, SYSTEM }

/** Typeface of the key labels (system font families, available on every phone). */
enum class KeyFont(val label: String, val family: String, val bold: Boolean = false) {
    DEFAULT("Default", "sans-serif"),
    LIGHT("Light", "sans-serif-light"),
    MEDIUM("Medium", "sans-serif-medium"),
    BOLD("Bold", "sans-serif", bold = true),
    CONDENSED("Condensed", "sans-serif-condensed"),
    SERIF("Serif", "serif"),
    MONO("Mono", "monospace"),
}

/** Colour of the swipe trail. */
enum class TrailStyle { ACCENT, RAINBOW, MONO }

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
        // When the user last changed a setting: sync keeps the newest settings.
        if (key !in AUTOMATIC) putLong(CHANGED_AT, System.currentTimeMillis())
    }

    /** When the user last changed a setting (0 = never: a fresh install). */
    val changedAt get() = prefs.getLong(CHANGED_AT, 0L)

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
    val keyFont get() = enumValueOrDefault(str(KEY_FONT, KeyFont.DEFAULT.name), KeyFont.DEFAULT)
    val keyShadows get() = bool(KEY_SHADOWS, true)
    /** Letters drawn as capitals (like the iPhone) instead of following shift. */
    val capitalLabels get() = bool(CAPITAL_LABELS, false)
    val spaceLabel get() = bool(SPACE_LABEL, true)
    val trailStyle get() = enumValueOrDefault(str(TRAIL_STYLE, TrailStyle.ACCENT.name), TrailStyle.ACCENT)
    val trailWidthDp get() = int(TRAIL_WIDTH, 6)

    // ---- custom theme (THEME = "custom") ----
    val customBackground get() = int(CUSTOM_BG, 0xFF1B1C20.toInt())
    /** Second background colour for a gradient; 0 = plain. */
    val customBackground2 get() = int(CUSTOM_BG2, 0)
    val customKey get() = int(CUSTOM_KEY, 0xFF3A3B41.toInt())
    val customText get() = int(CUSTOM_TEXT, 0xFFF2F3F5.toInt())
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
    /** A space goes in by itself after . , ! and ? (not inside numbers, links or e-mail addresses). */
    val spaceAfterPunctuation get() = bool(SPACE_AFTER_PUNCT, true)
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
    /** Mic button on the suggestion bar. */
    val voiceKey get() = bool(VOICE_KEY, true)
    /** Let the AI tidy up dictated text in the app's tone. */
    val voiceAiFix get() = bool(VOICE_AI_FIX, true)
    /** Use the phone's offline speech recogniser when it has one. */
    val voiceOnDevice get() = bool(VOICE_ON_DEVICE, true)
    /** Your own shortcuts expand on space (otherwise they're offered as a suggestion). */
    val shortcutsAuto get() = bool(SHORTCUTS_AUTO, false)

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

    /**
     * Arabizi on Latin keyboards: "7abibi" → "حبيبي". On by default when Arabic is one of the
     * keyboard languages.
     */
    val arabizi get() = bool(ARABIZI, "ar" in languages)
    /** Convert clear Arabizi (words with 3, 7, 5... or right after Arabic) on space. */
    val arabiziAuto get() = bool(ARABIZI_AUTO, true)

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
    val aiEnabled get() = bool(AI_ENABLED, true) && (aiApiKey.isNotBlank() || (aiProvider == "custom" && aiBaseUrl.isNotBlank()) ||
        (aiProvider == "ondevice" && offlineEngine.isNotBlank()))
    /** Offline AI: "nano" (Gemini Nano) or the id of a downloaded model. */
    val offlineEngine get() = OfflineModel.current(str(OFFLINE_ENGINE, ""))
    /** Run the offline model on the graphics chip (faster answers, a short stutter when it loads). */
    val offlineGpu get() = bool(OFFLINE_GPU, false)
    /** Use the offline AI when the cloud provider can't be reached. */
    val offlineFallback get() = bool(OFFLINE_FALLBACK, true)
    /** Default proofreading tone (and the tone everywhere when per-app tones are off). */
    val aiTone get() = enumValueOrDefault(str(AI_TONE, AiTone.CHILL.name).uppercase(), AiTone.CHILL)
    val tonePerApp get() = bool(TONE_PER_APP, true)

    /** Tones the user picked for specific apps (package → tone). */
    val toneOverrides: Map<String, AiTone> get() = str(TONE_APPS, "").split(';').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq) to enumValueOrDefault(part.substring(eq + 1), AiTone.CHILL)
    }.toMap()

    fun setToneFor(pkg: String, tone: AiTone?) {
        val map = toneOverrides.toMutableMap()
        if (tone == null) map.remove(pkg) else map[pkg] = tone
        set(TONE_APPS, map.entries.joinToString(";") { "${it.key}=${it.value.name}" })
    }

    /** The tone for text typed in [pkg]: the user's pick for that app, its automatic tone, or the default. */
    fun toneFor(pkg: String?): AiTone {
        if (!tonePerApp || pkg == null) return aiTone
        return toneOverrides[pkg] ?: AppTones.automatic(pkg) ?: aiTone
    }

    /** Apps the keyboard was recently used in (most recent first), for the per-app tone list. */
    val seenApps: List<String> get() = str(SEEN_APPS, "").split(',').filter { it.isNotBlank() }

    fun noteApp(pkg: String) {
        val list = seenApps
        if (list.firstOrNull() == pkg) return
        set(SEEN_APPS, (listOf(pkg) + list.filter { it != pkg }).take(40).joinToString(","))
    }

    val onboardingDone get() = bool(ONBOARDING_DONE, false)

    // ---- sync ----
    val syncAuto get() = bool(SYNC_AUTO, true)
    val syncLast get() = prefs.getLong(SYNC_LAST, 0L)
    val syncError: String? get() = prefs.getString(SYNC_ERROR, null)

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
        const val KEY_FONT = "key_font"
        const val KEY_SHADOWS = "key_shadows"
        const val CAPITAL_LABELS = "capital_labels"
        const val SPACE_LABEL = "space_label"
        const val TRAIL_STYLE = "trail_style"
        const val TRAIL_WIDTH = "trail_width"
        const val CUSTOM_BG = "custom_bg"
        const val CUSTOM_BG2 = "custom_bg2"
        const val CUSTOM_KEY = "custom_key"
        const val CUSTOM_TEXT = "custom_text"
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
        const val SPACE_AFTER_PUNCT = "space_after_punct"
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
        const val ARABIZI = "arabizi"
        const val ARABIZI_AUTO = "arabizi_auto"
        const val SHORTCUTS_AUTO = "shortcuts_auto"
        const val VOICE_KEY = "voice_key"
        const val VOICE_AI_FIX = "voice_ai_fix"
        const val VOICE_ON_DEVICE = "voice_on_device"
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
        const val OFFLINE_ENGINE = "ai_offline_engine"
        const val OFFLINE_FALLBACK = "ai_offline_fallback"
        const val OFFLINE_GPU = "ai_offline_gpu"
        const val TONE_PER_APP = "tone_per_app"
        const val TONE_APPS = "tone_apps"
        const val SEEN_APPS = "seen_apps"
        const val ONBOARDING_DONE = "onboarding_done"
        const val EMOJI_RECENTS = "emoji_recents"
        const val SYNC_URI = "sync_uri"
        const val SYNC_SECRET = "sync_secret"
        const val SYNC_AUTO = "sync_auto"
        const val SYNC_LAST = "sync_last"
        const val SYNC_ERROR = "sync_error"
        /** Bumped when a new background picture is saved. */
        const val BG_VERSION = "bg_version"
        const val CHANGED_AT = "settings_changed_at"
        /** Kept up to date by the app itself, not choices the user makes. */
        val AUTOMATIC = setOf(SEEN_APPS, EMOJI_RECENTS, CURRENT_LANGUAGE, SYNC_URI, SYNC_SECRET, SYNC_LAST, SYNC_ERROR, BG_VERSION, CHANGED_AT)

        /** Settings that change how the keyboard looks. */
        val APPEARANCE = setOf(
            THEME, ACCENT, KEY_BORDERS, KEY_RADIUS, KEY_GAP, LABEL_SCALE, BG_IMAGE, BG_DIM, BG_VERSION, KEY_FONT, KEY_SHADOWS,
            CAPITAL_LABELS, SPACE_LABEL, TRAIL_STYLE, TRAIL_WIDTH, CUSTOM_BG, CUSTOM_BG2, CUSTOM_KEY, CUSTOM_TEXT,
        )

        inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, def: T): T =
            runCatching { enumValueOf<T>(name) }.getOrDefault(def)
    }
}
