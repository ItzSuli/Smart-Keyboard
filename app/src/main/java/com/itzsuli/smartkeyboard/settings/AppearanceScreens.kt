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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itzsuli.smartkeyboard.data.Languages
import com.itzsuli.smartkeyboard.data.OneHanded
import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.engine.AutoCorrectMode
import com.itzsuli.smartkeyboard.ime.KeyboardTheme
import com.itzsuli.smartkeyboard.ime.SmartKeyboardService
import java.io.File

private val ACCENTS = listOf(
    0, 0xFF3B6FE0, 0xFF5B4BE0, 0xFF9333EA, 0xFFDB2777, 0xFFE5484D, 0xFFF97316, 0xFFF5B800, 0xFF22C55E, 0xFF14B8A6, 0xFF06B6D4, 0xFF64748B,
).map { it.toInt() }

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s = settings()
    val themeId by setting(Settings.THEME) { it.themeId }
    val accent by setting(Settings.ACCENT) { it.customAccent }
    val radius by setting(Settings.KEY_RADIUS) { it.keyRadiusDp }
    val borders by setting(Settings.KEY_BORDERS) { it.keyBorders }
    val oneHanded by setting(Settings.ONE_HANDED) { it.oneHanded }
    val hasImage by setting(Settings.BG_IMAGE) { it.backgroundImage }
    val dark = KeyboardTheme.systemIsDark(context)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null && saveBackground(context, uri)) {
            s.set(Settings.BG_IMAGE, true)
            // Tells a running keyboard to reload the picture even if the flag was already on.
            s.set("bg_version", System.currentTimeMillis())
        }
    }

    SettingsPage("Appearance", onBack) {
        item {
            val current = KeyboardTheme.resolve(context, s).let { if (accent != 0) it.withAccent(accent) else it }
            KeyboardPreview(current, Modifier.fillMaxWidth().aspectRatio(2.2f).clip(RoundedCornerShape(22.dp)), radius.toFloat(), borders)
        }
        item {
            Group("Theme") {
                val options = listOf("auto" to "Auto", "dynamic" to "Material You") + KeyboardTheme.PRESETS.map { it.id to it.name }
                options.chunked(3).forEach { row ->
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (id, name) ->
                            val t = when (id) {
                                "auto" -> if (dark) KeyboardTheme.DARK else KeyboardTheme.LIGHT
                                "dynamic" -> KeyboardTheme.dynamic(context, dark)
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
                            else if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White)
                        }
                    }
                }
                Hint("“A” uses the theme's own colour.")
            }
        }
        item {
            Group("Keys") {
                SwitchSetting(Settings.KEY_BORDERS, "Key backgrounds", "Off gives a flat, minimal look") { it.keyBorders }
                SliderSetting(Settings.KEY_RADIUS, "Corner roundness", 0..20, format = { "$it dp" }) { it.keyRadiusDp }
                SliderSetting(Settings.KEY_GAP, "Space between keys", 2..12, format = { "$it dp" }) { it.keyGapDp }
                SliderSetting(Settings.KEY_HEIGHT, "Keyboard height", 70..140, step = 5, format = { "$it%" }) { it.keyHeightPercent }
                SliderSetting(Settings.LABEL_SCALE, "Letter size", 80..130, step = 5, format = { "$it%" }) { it.labelScalePercent }
                Divider()
                SwitchSetting(Settings.NUMBER_ROW, "Number row") { it.numberRow }
                SwitchSetting(Settings.SHOW_HINTS, "Number hints on top row", "Long-press for numbers either way") { it.showHints }
                SwitchSetting(Settings.KEY_POPUP, "Pop-up on key press") { it.keyPopup }
                SwitchSetting(Settings.EMOJI_KEY, "Emoji key") { it.emojiKey }
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
        item {
            Group("One-handed mode") {
                ChoiceSetting("Keyboard width", "Also toggled from the keyboard's toolbar", listOf(OneHanded.OFF to "Full", OneHanded.LEFT to "Left", OneHanded.RIGHT to "Right"), oneHanded) {
                    s.set(Settings.ONE_HANDED, it)
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
fun TypingScreen(onBack: () -> Unit) {
    val s = settings()
    val mode by setting(Settings.AUTOCORRECT) { it.autoCorrectMode }
    val hitbox by setting(Settings.ADAPTIVE_HITBOX) { it.adaptiveHitbox }
    val swipe by setting(Settings.SWIPE) { it.swipeTyping }
    val haptics by setting(Settings.HAPTICS) { it.haptics }
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
                SwitchSetting(Settings.DOUBLE_SPACE, "Double-space for full stop") { it.doubleSpacePeriod }
            }
        }
        item {
            Group("Suggestions") {
                SwitchSetting(Settings.SUGGESTIONS, "Show suggestions", "Word completions and next-word predictions") { it.showSuggestions }
                SwitchSetting(Settings.EMOJI_SUGGESTIONS, "Emoji suggestions", "e.g. “pizza” suggests 🍕") { it.emojiSuggestions }
                ChoiceSetting(
                    "Adaptive touch targets",
                    "Like on the iPhone: the letters you're most likely to type next get a bigger invisible hit area, so fast typing lands on the right key.",
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
                }
            }
        }
        item {
            Group("Gestures & keys") {
                SwitchSetting(Settings.SPACE_CURSOR, "Slide on space bar to move the cursor") { it.spaceCursor }
                SwitchSetting(Settings.DELETE_SWIPE, "Swipe left from ⌫ to delete words") { it.deleteSwipe }
                SliderSetting(Settings.LONG_PRESS, "Long-press delay", 150..700, step = 25, format = { "$it ms" }) { it.longPressMs }
                SwitchSetting(Settings.CLIPBOARD, "Clipboard history", "Keeps your recent copies (passwords marked sensitive are skipped)") { it.clipboardHistory }
            }
        }
        item {
            Group("Feedback") {
                SwitchSetting(Settings.HAPTICS, "Vibrate on key press") { it.haptics }
                if (haptics) SliderSetting(Settings.HAPTIC_STRENGTH, "Vibration strength", 1..100, format = { "$it%" }) { it.hapticStrength }
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
                SwitchSetting(Settings.ARABIC_DIGITS, "Arabic-Indic digits (١٢٣)") { it.arabicDigits }
            }
        }
    }
}
