package com.itzsuli.smartkeyboard.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.itzsuli.smartkeyboard.BuildConfig
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.ai.ProviderPreset
import com.itzsuli.smartkeyboard.data.AiAutoProof
import com.itzsuli.smartkeyboard.data.Languages
import com.itzsuli.smartkeyboard.data.AiTone
import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.engine.TextUtils
import com.itzsuli.smartkeyboard.engine.UserLexicon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(onBack: () -> Unit, open: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = SmartKeyboardApp.of(context)
    val s = app.settings
    val providerId by setting(Settings.AI_PROVIDER) { it.aiProvider }
    val autoProof by setting(Settings.AI_AUTO_PROOF) { it.aiAutoProof }
    val tone by setting(Settings.AI_TONE) { it.aiTone }
    val tonePerApp by setting(Settings.TONE_PER_APP) { it.tonePerApp }
    val preset = ProviderPreset.of(providerId)
    var key by remember(providerId) { mutableStateOf(s.aiApiKey) }
    var model by remember(providerId) { mutableStateOf(s.aiModel.ifBlank { preset.defaultModel }) }
    var baseUrl by remember(providerId) { mutableStateOf(s.aiBaseUrl.ifBlank { preset.baseUrl }) }
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }

    fun save() {
        s.set("${Settings.AI_KEY}_${preset.id}", key.trim())
        s.set(Settings.AI_MODEL, model.trim())
        s.set(Settings.AI_BASE_URL, if (preset == ProviderPreset.CLAUDE) "" else baseUrl.trim())
    }

    // Never lose a pasted key because "Save" wasn't tapped.
    val latestSave by androidx.compose.runtime.rememberUpdatedState({ save() })
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { latestSave() } }

    SettingsPage("AI assistant", onBack) {
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp)) {
                    Text("What the AI does", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(6.dp))
                    Text("• Proofreads your sentences the chill way — fixes typos, keeps your slang, swearing and style.\n" +
                        "• Rewrites, shortens or translates with one tap (✨ on the keyboard).\n" +
                        "• Adds context-aware next-word ideas.\n" +
                        "• Knows your own words: everything the keyboard learned is passed along so the AI never \"fixes\" your slang.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    Text("Privacy: text is only sent when an AI feature runs, straight to the provider you pick below. Never from password fields or in incognito mode. Typing, suggestions and swipe work fully offline without AI.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Group("Provider") {
                SwitchSetting(Settings.AI_ENABLED, "AI features", "Turn everything AI off without losing your setup") { st -> st.prefs.getBoolean(Settings.AI_ENABLED, true) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = preset.label, onValueChange = {}, readOnly = true, label = { Text("Service") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            ProviderPreset.entries.forEach { p ->
                                DropdownMenuItem(text = { Text(p.label) }, onClick = {
                                    expanded = false
                                    save()
                                    s.set(Settings.AI_PROVIDER, p.id)
                                    s.set(Settings.AI_MODEL, "")
                                    s.set(Settings.AI_BASE_URL, "")
                                    models = emptyList(); status = null
                                })
                            }
                        }
                    }
                    if (preset != ProviderPreset.CUSTOM || key.isNotEmpty()) OutlinedTextField(
                        value = key, onValueChange = { key = it }, label = { Text("API key") }, singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
                        supportingText = { if (preset.keyHint.isNotEmpty()) Text("Get one at ${preset.keyHint}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (preset == ProviderPreset.CLAUDE) {
                        ProviderPreset.CLAUDE_MODELS.forEach { (id, label) ->
                            FilterChip(selected = model == id, onClick = { model = id }, label = { Text(label) })
                        }
                    }
                    if (models.isNotEmpty()) {
                        Text("Available models", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            models.take(40).forEach { m -> AssistChip(onClick = { model = m }, label = { Text(m) }) }
                        }
                    }
                    if (preset != ProviderPreset.CLAUDE && preset != ProviderPreset.GEMINI) {
                        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            save()
                            status = "Testing…"
                            app.ai.testConnection { r -> status = r.fold({ it }, { "✗ ${it.message}" }) }
                        }) { Text("Save & test") }
                        OutlinedButton(onClick = {
                            save()
                            status = "Loading models…"
                            app.ai.listModels { r -> r.fold({ models = it; status = "${it.size} models found" }, { status = "✗ ${it.message}" }) }
                        }) { Text("Load models") }
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                    if (preset == ProviderPreset.CLAUDE) Hint("Opus 5.5 gives the best fixes. Haiku 5.5 is much faster and cheaper — ideal if you turn on AI word suggestions.")
                }
            }
        }
        item {
            Group("Proofreading") {
                ChoiceSetting(
                    "After each sentence", when (autoProof) {
                        AiAutoProof.OFF -> "Only when you tap ✨ on the keyboard."
                        AiAutoProof.SUGGEST -> "Checks each finished sentence and offers a fix in the suggestion bar — one tap applies it."
                        AiAutoProof.AUTO -> "Fixes each finished sentence automatically; tap the bar to undo."
                    },
                    listOf(AiAutoProof.OFF to "Manual", AiAutoProof.SUGGEST to "Suggest", AiAutoProof.AUTO to "Auto-fix"), autoProof,
                ) { s.set(Settings.AI_AUTO_PROOF, it) }
                SwitchSetting(Settings.AI_SUGGESTIONS, "AI word suggestions", "Adds context-aware next-word ideas (sends the text before the cursor after each word)") { it.aiSuggestions }
            }
        }
        item {
            Group("Tone") {
                ChoiceSetting(
                    "Default tone", tone.description,
                    AiTone.entries.map { it to it.label }, tone,
                ) { s.set(Settings.AI_TONE, it) }
                SwitchSetting(Settings.TONE_PER_APP, "Tone per app", "Chill in WhatsApp & Snapchat, clean in Slack & Teams, polite in Gmail & Outlook — automatically") { it.tonePerApp }
                if (tonePerApp) NavRow("Choose per app", "Change the tone for any app", Icons.Rounded.Tune) { open("tones") }
            }
        }
    }
}

@Composable
fun LearningScreen(onBack: () -> Unit, open: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = SmartKeyboardApp.of(context)
    val s = app.settings
    val scope = rememberCoroutineScope()
    val enabledLangs by setting(Settings.LANGUAGES) { it.languages }
    var lang by remember { mutableStateOf(s.currentLanguage) }
    var lexicon by remember { mutableStateOf<UserLexicon?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var newWord by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val stats = remember(version) {
        listOf("words_typed" to "Words typed", "words_swiped" to "Words swiped", "autocorrections" to "Auto-fixes",
            "corrections_undone" to "Fixes you undid", "ai_fixes_applied" to "AI fixes applied", "ai_words_used" to "AI words used")
    }
    var statValues by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(lang, version) {
        lexicon = withContext(Dispatchers.IO) { app.store.flush(); app.languages.lexicon(lang) }
        statValues = withContext(Dispatchers.IO) { stats.associate { it.first to app.store.stat(it.first) } }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(app.store.exportJson().toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) "Backup saved" else "Couldn't save backup", Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    app.store.importJson(json).also { app.languages.reloadUserData() }
                }.getOrElse { -1 }
            }
            Toast.makeText(context, if (n >= 0) "Restored $n items" else "That file isn't a Smart Keyboard backup", Toast.LENGTH_SHORT).show()
            version++
        }
    }

    SettingsPage("Your words & learning", onBack) {
        item {
            Group {
                SwitchSetting(Settings.LEARNING, "Learn from my typing", "New words, slang, your phrases, which fixes you like or undo, and where you tap") { it.learning }
                Hint("Everything stays on this phone. Nothing is learned in password fields or in incognito mode (toolbar).")
            }
        }
        item {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                enabledLangs.forEach { code -> FilterChip(selected = code == lang, onClick = { lang = code }, label = { Text(Languages.get(code).nativeName) }) }
            }
        }
        item {
            Group("Stats") {
                val words = lexicon?.allWords().orEmpty()
                val personal = words.count { it.isConfirmed && !it.isBlocked }
                val slang = words.count { it.isSlang && it.isConfirmed }
                FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("$personal", "words known")
                    StatChip("$slang", "own slang")
                    StatChip("${lexicon?.allCorrections()?.size ?: 0}", "learned fixes")
                    stats.forEach { (k, label) -> StatChip("${statValues[k] ?: 0}", label) }
                }
            }
        }
        item {
            Group("Your words") {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newWord, { newWord = it }, Modifier.weight(1f), label = { Text("Add a word or slang") }, singleLine = true)
                    IconButton(onClick = {
                        val w = newWord.trim()
                        if (w.isNotEmpty()) scope.launch {
                            withContext(Dispatchers.IO) { app.languages.lexicon(lang).addManualWord(w, System.currentTimeMillis()) }
                            newWord = ""; version++
                        }
                    }) { Icon(Icons.Rounded.Add, "Add") }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text("Search") }, singleLine = true)
                val list = lexicon?.allWords().orEmpty()
                    .filter { it.isConfirmed && !it.isBlocked && (query.isBlank() || it.key.contains(TextUtils.key(query, lang))) }
                    .sortedWith(compareByDescending<UserLexicon.Word> { it.isManual || it.isSlang }.thenByDescending { it.count })
                if (list.isEmpty()) Hint("Words you type that aren't in the dictionary (slang, names, shortcuts) show up here after you use them twice.")
                list.take(150).forEach { w ->
                    WordRow(w.display, buildString {
                        append(if (w.isManual) "added by you" else if (w.isSlang) "your slang" else "dictionary word")
                        append(" · used ${w.count}×")
                    }, onBlock = {
                        scope.launch { withContext(Dispatchers.IO) { lexicon?.block(w.display, System.currentTimeMillis()) }; version++ }
                    }, onDelete = {
                        scope.launch { withContext(Dispatchers.IO) { lexicon?.remove(w.key) }; version++ }
                    })
                }
            }
        }
        item {
            Group("Learned fixes") {
                val fixes = lexicon?.allCorrections().orEmpty().sortedByDescending { it.third }
                if (fixes.isEmpty()) Hint("When you pick a correction or accept an AI fix, the keyboard remembers that typo and fixes it instantly next time.")
                fixes.take(100).forEach { (typed, target, count) ->
                    WordRow("$typed → $target", "learned $count×", onDelete = {
                        scope.launch { withContext(Dispatchers.IO) { lexicon?.learnRejection(typed, target) }; version++ }
                    })
                }
            }
        }
        item {
            Group("Never suggest") {
                val blocked = lexicon?.allWords().orEmpty().filter { it.isBlocked }
                if (blocked.isEmpty()) Hint("Long-press a suggestion on the keyboard to never see it again.")
                blocked.forEach { w ->
                    SettingRow(w.display, "blocked", trailing = {
                        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { lexicon?.unblock(w.key) }; version++ } }) {
                            Icon(Icons.Rounded.Undo, null); Text(" Allow")
                        }
                    })
                }
            }
        }
        item {
            Group("Backup & sync") {
                NavRow("Sync between phones", "End-to-end encrypted, through a file in Google Drive or any folder", Icons.Rounded.Sync) { open("sync") }
                Hint("Moving to a new phone or reinstalling? Save your learned words and restore them later.")
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exporter.launch("smart-keyboard-backup.json") }) { Icon(Icons.Rounded.FileDownload, null); Text(" Export") }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Icon(Icons.Rounded.FileUpload, null); Text(" Import") }
                }
                SettingRow("Reset all learning", "Forget learned words, phrases, fixes and touch habits", trailing = {
                    Icon(Icons.Rounded.RestartAlt, null, tint = MaterialTheme.colorScheme.error)
                }, onClick = { confirmReset = true })
            }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset learning?") },
        text = { Text("The keyboard forgets everything it learned from you in all languages. This can't be undone (unless you exported a backup).") },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                scope.launch {
                    withContext(Dispatchers.IO) { app.store.clearAll(); app.languages.reloadUserData() }
                    version++
                }
            }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
private fun StatChip(value: String, label: String) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun WordRow(title: String, subtitle: String, onBlock: (() -> Unit)? = null, onDelete: () -> Unit) {
    SettingRow(title, subtitle, trailing = {
        Row {
            if (onBlock != null) IconButton(onClick = onBlock) { Icon(Icons.Rounded.Block, "Never suggest") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Forget") }
        }
    })
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsPage("About & privacy", onBack) {
        item {
            Group {
                SettingRow("Smart Keyboard", "Version ${BuildConfig.VERSION_NAME}")
                Divider()
                SettingRow("Source code", "github.com/ItzSuli/Smart-Keyboard", onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ItzSuli/Smart-Keyboard")))
                })
            }
        }
        item {
            Group("Privacy") {
                Hint("• Typing, suggestions, corrections, swipe and all learning run on your phone.\n" +
                    "• Learned data lives in the app's private storage and is excluded from cloud backups; export it yourself if you like.\n" +
                    "• The internet permission is only used by the optional AI features, which talk directly to the provider you choose with your own key.\n" +
                    "• Password fields, incognito mode and apps that ask for no personalised learning are never learned from or sent to AI.")
            }
        }
        item {
            Group("Credits") {
                Hint("Word frequencies: FrequencyWords by Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0.\n" +
                    "Context data and capitalisation: Tatoeba sentences (tatoeba.org), CC BY 2.0 FR.\n" +
                    "Emoji data: Unicode emoji-test.txt, Unicode License v3.\n" +
                    "Claude provider: Anthropic Java SDK (MIT).")
            }
        }
        item { Spacer(Modifier.width(1.dp)) }
    }
}
