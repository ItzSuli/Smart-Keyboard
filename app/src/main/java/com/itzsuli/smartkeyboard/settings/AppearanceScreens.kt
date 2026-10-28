package com.itzsuli.smartkeyboard.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itzsuli.smartkeyboard.data.Languages
import com.itzsuli.smartkeyboard.data.HapticStyle
import com.itzsuli.smartkeyboard.data.KeyFont
import com.itzsuli.smartkeyboard.data.TrailStyle
import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.engine.AutoCorrectMode
import com.itzsuli.smartkeyboard.ime.KeyboardTheme
import com.itzsuli.smartkeyboard.ime.SmartKeyboardService
import java.io.File

private val ACCENTS = listOf(
    0, 0xFF3B6FE0, 0xFF2563EB, 0xFF5B4BE0, 0xFF7C3AED, 0xFF9333EA, 0xFFDB2777, 0xFFFF5C8A, 0xFFE5484D, 0xFFF97316, 0xFFFF6B00,
    0xFFF5B800, 0xFFFCEE0A, 0xFF84CC16, 0xFF22C55E, 0xFF10B981, 0xFF14B8A6, 0xFF06B6D4, 0xFF5EEAD4, 0xFFB76E79, 0xFF8B5E3C,
    0xFF64748B, 0xFFFFFFFF, 0xFF000000,
).map { it.toInt() }

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s = settings()
    val themeId by setting(Settings.THEME) { it.themeId }
    val accent by setting(Settings.ACCENT) { it.customAccent }
    val radius by setting(Settings.KEY_RADIUS) { it.keyRadiusDp }
    val borders by setting(Settings.KEY_BORDERS) { it.keyBorders }
    val hasImage by setting(Settings.BG_IMAGE) { it.backgroundImage }
    val font by setting(Settings.KEY_FONT) { it.keyFont }
    val trail by setting(Settings.TRAIL_STYLE) { it.trailStyle }
    // Re-read the preview whenever any of the custom colours or the shadow switch change.
    val customBg by setting(Settings.CUSTOM_BG) { it.customBackground }
    val customBg2 by setting(Settings.CUSTOM_BG2) { it.customBackground2 }
    val customKey by setting(Settings.CUSTOM_KEY) { it.customKey }
    val customText by setting(Settings.CUSTOM_TEXT) { it.customText }
    val shadows by setting(Settings.KEY_SHADOWS) { it.keyShadows }
    val dark = KeyboardTheme.systemIsDark(context)
    var accentPicker by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null && saveBackground(context, uri)) {
            s.set(Settings.BG_IMAGE, true)
            // Tells a running keyboard to reload the picture even if the flag was already on.
            s.set(Settings.BG_VERSION, System.currentTimeMillis())
        }
    }

    SettingsPage("Appearance", onBack) {
        item {
            val current = remember(themeId, accent, customBg, customBg2, customKey, customText, shadows) { KeyboardTheme.resolve(context, s) }
            KeyboardPreview(current, Modifier.fillMaxWidth().aspectRatio(2.2f).clip(RoundedCornerShape(22.dp)), radius.toFloat(), borders)
        }
        item {
            Group("Theme") {
                val options = listOf("auto" to "Auto", "dynamic" to "Material You", "custom" to "Your colours") + KeyboardTheme.PRESETS.map { it.id to it.name }
                options.chunked(3).forEach { row ->
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (id, name) ->
                            val t = when (id) {
                                "auto" -> if (dark) KeyboardTheme.DARK else KeyboardTheme.LIGHT
                                "dynamic" -> KeyboardTheme.dynamic(context, dark)
                                "custom" -> KeyboardTheme.custom(s)
                                else -> KeyboardTheme.PRESETS.first { it.id == id }
                            }
                            val selected = id == themeId
                            Column(Modifier.weight(1f).clickable { s.set(Settings.THEME, id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                KeyboardPreview(t, Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(14.dp))
                                    .border(if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp)))
                                Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        if (themeId == "custom") item {
            Group("Your colours") {
                ColorSetting("Background", null, customBg) { s.set(Settings.CUSTOM_BG, it) }
                ColorSetting("Background gradient", "Optional second colour, blended from top-left to bottom-right", customBg2, allowNone = true) { s.set(Settings.CUSTOM_BG2, it) }
                ColorSetting("Keys", null, customKey) { s.set(Settings.CUSTOM_KEY, it) }
                ColorSetting("Letters", null, customText) { s.set(Settings.CUSTOM_TEXT, it) }
                Hint("Pressed keys, function keys and hints are mixed from these so everything matches. The accent colour below colours the enter key and highlights.")
            }
        }
        item {
            Group("Accent colour") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ACCENTS.forEach { c ->
                        val selected = c == accent
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (c == 0) MaterialTheme.colorScheme.surfaceVariant else Color(c))
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { s.set(Settings.ACCENT, c) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c == 0) Text("A", fontWeight = FontWeight.Bold)
                            else if (selected) Icon(Icons.Rounded.Check, null, tint = if (KeyboardTheme.isLight(c)) Color.Black else Color.White)
                        }
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.sweepGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.8f, 1f))) }))
                            .border(if (accent != 0 && accent !in ACCENTS) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { accentPicker = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("+", fontWeight = FontWeight.Bold, color = Color.White) }
                }
                Hint("“A” uses the theme's own colour, “+” picks any colour.")
                if (accentPicker) ColorPickerDialog("Accent colour", accent, onDismiss = { accentPicker = false }) { accentPicker = false; s.set(Settings.ACCENT, it) }
            }
        }
        item {
            Group("Keys") {
                SwitchSetting(Settings.KEY_BORDERS, "Key backgrounds", "Off gives a flat, minimal look") { it.keyBorders }
                SliderSetting(Settings.KEY_RADIUS, "Corner roundness", 0..20, format = { "$it dp" }) { it.keyRadiusDp }
                SliderSetting(Settings.KEY_GAP, "Space between keys", 2..12, format = { "$it dp" }) { it.keyGapDp }
                SliderSetting(Settings.KEY_HEIGHT, "Keyboard height", 70..140, step = 5, format = { "$it%" }) { it.keyHeightPercent }
                SliderSetting(Settings.LABEL_SCALE, "Letter size", 80..130, step = 5, format = { "$it%" }) { it.labelScalePercent }
                SwitchSetting(Settings.KEY_SHADOWS, "Key shadows", "A soft shadow under each key") { it.keyShadows }
                Divider()
                Text("Font", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KeyFont.entries.forEach { f ->
                        androidx.compose.material3.FilterChip(
                            selected = f == font,
                            onClick = { s.set(Settings.KEY_FONT, f) },
                            label = {
                                Text(
                                    "Aa · ${f.label}",
                                    fontFamily = when (f) {
                                        KeyFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
                                        KeyFont.MONO -> androidx.compose.ui.text.font.FontFamily.Monospace
                                        else -> androidx.compose.ui.text.font.FontFamily.SansSerif
                                    },
                                    fontWeight = when (f) {
                                        KeyFont.LIGHT -> FontWeight.Light
                                        KeyFont.MEDIUM -> FontWeight.Medium
                                        KeyFont.BOLD -> FontWeight.Bold
                                        else -> FontWeight.Normal
                                    },
                                )
                            },
                        )
                    }
                }
                SwitchSetting(Settings.CAPITAL_LABELS, "Capital letters on keys", "Like the iPhone; what you type still follows shift") { it.capitalLabels }
                SwitchSetting(Settings.SPACE_LABEL, "Language name on space bar") { it.spaceLabel }
                Divider()
                SwitchSetting(Settings.NUMBER_ROW, "Number row") { it.numberRow }
                SwitchSetting(Settings.SHOW_HINTS, "Number hints on top row", "Long-press for numbers either way") { it.showHints }
                SwitchSetting(Settings.KEY_POPUP, "Pop-up on key press") { it.keyPopup }
                SwitchSetting(Settings.EMOJI_KEY, "Emoji key") { it.emojiKey }
            }
        }
        item {
            Group("Swipe trail") {
                ChoiceSetting(
                    "Colour", null,
                    listOf(TrailStyle.ACCENT to "Accent", TrailStyle.RAINBOW to "Rainbow", TrailStyle.MONO to "Letters"), trail,
                ) { s.set(Settings.TRAIL_STYLE, it) }
                SliderSetting(Settings.TRAIL_WIDTH, "Thickness", 2..14, format = { "$it dp" }) { it.trailWidthDp }
            }
        }
        item {
            Group("Background picture") {
                SettingRow("Your own picture", if (hasImage) "Set — keys stay readable thanks to the dimming" else "Use any photo behind the keys") {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (hasImage) {
                    SliderSetting(Settings.BG_DIM, "Dim picture", 0..80, step = 5, format = { "$it%" }) { it.backgroundDim }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        OutlinedButton(onClick = {
                            File(context.filesDir, SmartKeyboardService.BACKGROUND_FILE).delete()
                            s.set(Settings.BG_IMAGE, false)
                        }) { Icon(Icons.Rounded.Close, null); Text(" Remove picture") }
                    }
                }
            }
        }
    }
}

/** Copies the picked picture into app storage, scaled down to keep memory use small. */
private fun saveBackground(context: android.content.Context, uri: Uri): Boolean = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return false
    File(context.filesDir, SmartKeyboardService.BACKGROUND_FILE).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    true
}.getOrDefault(false)

@Composable
fun TypingScreen(onBack: () -> Unit, open: (String) -> Unit = {}) {
    val s = settings()
    val mode by setting(Settings.AUTOCORRECT) { it.autoCorrectMode }
    val hitbox by setting(Settings.ADAPTIVE_HITBOX) { it.adaptiveHitbox }
    val swipe by setting(Settings.SWIPE) { it.swipeTyping }
    val haptics by setting(Settings.HAPTICS) { it.haptics }
    val hapticStyle by setting(Settings.HAPTIC_STYLE) { it.hapticStyle }
    val sound by setting(Settings.SOUND) { it.sound }
    SettingsPage("Typing & swipe", onBack) {
        item {
            Group("Corrections") {
                ChoiceSetting(
                    "Autocorrect", when (mode) {
                        AutoCorrectMode.OFF -> "Never changes your words; suggestions only."
                        AutoCorrectMode.CHILL -> "Only fixes clear typos. Slang, swearing and your own words are left alone. Backspace undoes any fix — and it remembers."
                        AutoCorrectMode.NORMAL -> "Also fixes likely real-word slips. Still learns from every undo."
                    },
                    listOf(AutoCorrectMode.OFF to "Off", AutoCorrectMode.CHILL to "Chill", AutoCorrectMode.NORMAL to "Strict"), mode,
                ) { s.set(Settings.AUTOCORRECT, it) }
                SwitchSetting(Settings.FIX_CAPS, "Fix capitalisation", "“i” → “I”, names, and German nouns") { it.fixCapitalization }
                SwitchSetting(Settings.AUTO_CAP, "Capitalise sentences") { it.autoCapitalize }
                SwitchSetting(Settings.SPACE_AFTER_PUNCT, "Space after . , ! ?", "Adds the space for you; not inside numbers like 3.5 or 1,000") { it.spaceAfterPunctuation }
                SwitchSetting(Settings.DOUBLE_SPACE, "Double-space for full stop") { it.doubleSpacePeriod }
            }
        }
        item {
            Group("Suggestions") {
                SwitchSetting(Settings.SUGGESTIONS, "Show suggestions", "Word completions and next-word predictions") { it.showSuggestions }
                SwitchSetting(Settings.EMOJI_SUGGESTIONS, "Emoji suggestions", "e.g. “pizza” suggests 🍕") { it.emojiSuggestions }
                ChoiceSetting(
                    "Adaptive touch targets",
                    "Like on the iPhone: the letters you're most likely to type next get a slightly bigger invisible hit area at the edges of the keys. The middle of every key always types that key. When you delete a letter it picked, it learns to back off for that pair of keys.",
                    listOf(0 to "Off", 1 to "Subtle", 2 to "Balanced", 3 to "Strong"), hitbox,
                ) { s.set(Settings.ADAPTIVE_HITBOX, it) }
            }
        }
        item {
            Group("Swipe typing") {
                SwitchSetting(Settings.SWIPE, "Swipe to type", "Glide over the letters without lifting your finger") { it.swipeTyping }
                if (swipe) {
                    SwitchSetting(Settings.SWIPE_TRAIL, "Show swipe trail") { it.swipeTrail }
                    SwitchSetting(Settings.SWIPE_PREVIEW, "Live word preview while swiping") { it.swipePreview }
                    SwitchSetting(Settings.DELETE_SWIPED_WORD, "Delete a swiped word with one backspace", "Right after swiping, ⌫ removes the whole word") { it.deleteSwipedWord }
                }
            }
        }
        item {
            Group("Gestures & keys") {
                NavRow("Your shortcuts", "Only the ones you add — offered or expanded as you type", Icons.AutoMirrored.Rounded.ShortText) { open("shortcuts") }
                NavRow("Voice typing", "Mic on the suggestion bar, AI tidy-up", Icons.Rounded.Mic) { open("voice") }
                SwitchSetting(Settings.SWITCH_KEY, "Switch-keyboard button", "Button on the right of the suggestion bar: tap to pick another keyboard app, hold to jump to the next one") { it.switchKey }
                SwitchSetting(Settings.SPACE_CURSOR, "Slide on space bar to move the cursor") { it.spaceCursor }
                SwitchSetting(Settings.DELETE_SWIPE, "Swipe left from ⌫ to delete words") { it.deleteSwipe }
                SliderSetting(Settings.LONG_PRESS, "Long-press delay", 150..700, step = 25, format = { "$it ms" }) { it.longPressMs }
                SwitchSetting(Settings.CLIPBOARD, "Clipboard history", "Keeps your recent copies (passwords marked sensitive are skipped)") { it.clipboardHistory }
            }
        }
        item {
            Group("Feedback") {
                SwitchSetting(Settings.HAPTICS, "Vibrate on key press") { it.haptics }
                if (haptics) {
                    ChoiceSetting(
                        "Vibration feel", when (hapticStyle) {
                            HapticStyle.CRISP -> "Short, sharp clicks using your phone's precise haptics. Held delete and cursor moves tick lightly."
                            HapticStyle.SOFT -> "Gentler, lighter ticks."
                            HapticStyle.CLASSIC -> "Plain buzz, like older keyboards."
                            HapticStyle.SYSTEM -> "Exactly like your phone's own keyboard (follows the system touch-feedback setting)."
                        },
                        listOf(HapticStyle.CRISP to "Crisp", HapticStyle.SOFT to "Soft", HapticStyle.CLASSIC to "Classic", HapticStyle.SYSTEM to "System"), hapticStyle,
                    ) { s.set(Settings.HAPTIC_STYLE, it) }
                    if (hapticStyle != HapticStyle.SYSTEM) SliderSetting(Settings.HAPTIC_STRENGTH, "Vibration strength", 1..100, format = { "$it%" }) { it.hapticStrength }
                }
                SwitchSetting(Settings.SOUND, "Sound on key press") { it.sound }
                if (sound) SliderSetting(Settings.SOUND_VOLUME, "Volume", 5..100, step = 5, format = { "$it%" }) { it.soundVolume }
            }
        }
    }
}

@Composable
fun LanguagesScreen(onBack: () -> Unit) {
    val s = settings()
    val enabled by setting(Settings.LANGUAGES) { it.languages }
    val current by setting(Settings.CURRENT_LANGUAGE) { it.currentLanguage }
    val mixed by setting(Settings.MIXED_LANGUAGES) { it.mixedLanguages }
    SettingsPage("Languages", onBack) {
        item {
            Group("Mixed typing") {
                Hint("Pick the languages you mix. On any of their keyboards you can write all of them without switching — suggestions, corrections and swipe follow whichever language you're writing at that moment. Umlauts work without umlaut keys: “fur” becomes “für”, “strasse” becomes “Straße” when you're writing German.")
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Languages.ALL.filter { it.code in Settings.LATIN_SCRIPT }.forEach { lang ->
                        val on = lang.code in mixed
                        androidx.compose.material3.FilterChip(
                            selected = on,
                            onClick = {
                                var list = if (on) mixed - lang.code else mixed + lang.code
                                // Adding German while typing on English means "German on my English keyboard".
                                if (!on && current in Settings.LATIN_SCRIPT && current !in list) list = listOf(current) + list
                                s.set(Settings.MIXED_LANGUAGES, list.distinct().joinToString(","))
                            },
                            label = { Text(lang.nativeName) },
                            leadingIcon = if (on) { { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } } else null,
                        )
                    }
                }
                if (mixed.size >= 2) {
                    val names = mixed.joinToString(" + ") { Languages.get(it).nativeName }
                    Hint("Now: $names together. Tip: you only need one of them ticked as a keyboard below — e.g. keep the English layout and write German on it.")
                } else {
                    Hint("Pick at least two to mix them.")
                }
            }
        }
        item {
            Group {
                Hint("Tick every language you type in. Switch on the keyboard with the 🌐 key. Each language has its own layout, dictionary, slang list and its own learned words.")
                Languages.ALL.forEach { lang ->
                    val on = lang.code in enabled
                    SettingRow(
                        lang.nativeName,
                        if (lang.code == current) "${lang.name} · typing in this now" else lang.name,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (on) RadioButton(selected = lang.code == current, onClick = { s.set(Settings.CURRENT_LANGUAGE, lang.code) })
                                Checkbox(checked = on, onCheckedChange = { checked ->
                                    val list = if (checked) enabled + lang.code else enabled - lang.code
                                    if (list.isNotEmpty()) s.set(Settings.LANGUAGES, list.joinToString(","))
                                })
                            }
                        },
                        onClick = {
                            val list = if (on) enabled - lang.code else enabled + lang.code
                            if (list.isNotEmpty()) s.set(Settings.LANGUAGES, list.joinToString(","))
                        },
                    )
                }
            }
        }
        if ("ar" in enabled) item {
            Group("Arabic") {
                SwitchSetting(Settings.ARABIZI, "Arabizi", "On the English (or any Latin) keyboard, “7abibi”, “3arabi” or “2albi” become حبيبي, عربي, قلبي") { it.arabizi }
                SwitchSetting(Settings.ARABIZI_AUTO, "Convert on space",
                    "Words with Arabizi numbers (3, 7, 5, 2…) and words right after Arabic are converted automatically — backspace undoes it. Other words get an Arabic suggestion.") { it.arabiziAuto }
                Hint("Tip: turn on the number row (Appearance → Keys) for quick 3, 5, 7 and 2.")
                SwitchSetting(Settings.ARABIC_DIGITS, "Arabic-Indic digits (١٢٣)") { it.arabicDigits }
            }
        }
    }
}
