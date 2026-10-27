package com.itzsuli.smartkeyboard.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.data.AiTone
import com.itzsuli.smartkeyboard.data.AppTones
import com.itzsuli.smartkeyboard.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ voice typing

@Composable
fun VoiceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.RECORD_AUDIO) }
    SettingsPage("Voice typing", onBack) {
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Talk instead of typing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(6.dp))
                    Text("Tap 🎤 on the suggestion bar and speak. What you say goes through the same chill proofreading as your typing — in the tone of the app you're in — and the fix can be undone with one tap.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Group("Microphone") {
                if (granted) {
                    SettingRow("Microphone allowed ✓", "Only used while the 🎤 panel is open")
                } else {
                    SettingRow("Allow the microphone", "Needed for voice typing. The keyboard only listens while you hold the 🎤 panel open.", trailing = {
                        Button(onClick = { request.launch(Manifest.permission.RECORD_AUDIO) }) { Icon(Icons.Rounded.Mic, null); Text(" Allow") }
                    })
                }
            }
        }
        item {
            Group("Options") {
                SwitchSetting(Settings.VOICE_KEY, "Mic on the suggestion bar") { it.voiceKey }
                SwitchSetting(Settings.VOICE_AI_FIX, "Tidy up with AI", "Fixes what the speech recogniser got wrong, in the app's tone (needs an AI provider)") { it.voiceAiFix }
                SwitchSetting(Settings.VOICE_ON_DEVICE, "Prefer offline recognition", "Uses your phone's on-device speech recogniser when it has one, so audio never leaves the phone") { it.voiceOnDevice }
            }
        }
    }
}

// ------------------------------------------------------------------ your shortcuts

@Composable
fun ShortcutsScreen(onBack: () -> Unit) {
    val app = SmartKeyboardApp.of(LocalContext.current)
    var version by remember { mutableIntStateOf(0) }
    val list = remember(version) { app.shortcuts.all().sortedBy { it.short.lowercase() } }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    SettingsPage("Your shortcuts", onBack) {
        item {
            Group {
                Hint("Only shortcuts you add here are ever expanded — nothing built in. Type the shortcut and its text shows up first in the suggestion bar.")
                SwitchSetting(Settings.SHORTCUTS_AUTO, "Expand on space", "Off: offered as a suggestion. On: space expands it, backspace undoes it.") { it.shortcutsAuto }
            }
        }
        item {
            Group("Shortcuts") {
                if (list.isEmpty()) Hint("None yet. Try “omw” → “On my way!” or your email address.")
                list.forEach { sc ->
                    SettingRow(sc.short, sc.text, trailing = {
                        IconButton(onClick = { app.shortcuts.remove(sc.short); version++ }) { Icon(Icons.Rounded.Delete, "Delete") }
                    }) { editing = sc.short to sc.text }
                }
                Row(Modifier.padding(12.dp)) {
                    Button(onClick = { editing = "" to "" }) { Icon(Icons.Rounded.Add, null); Text(" Add shortcut") }
                }
            }
        }
    }
    editing?.let { (s0, t0) ->
        var short by remember(s0) { mutableStateOf(s0) }
        var text by remember(t0) { mutableStateOf(t0) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (s0.isEmpty()) "New shortcut" else "Edit shortcut") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(short, { short = it; error = null }, label = { Text("You type") }, singleLine = true)
                    OutlinedTextField(text, { text = it; error = null }, label = { Text("It becomes") }, minLines = 2)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        if (s0.isNotEmpty() && !s0.equals(short.trim(), ignoreCase = true)) app.shortcuts.remove(s0)
                        app.shortcuts.put(short, text)
                    }.onSuccess { editing = null; version++ }.onFailure { error = it.message }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ tone per app

@Composable
fun AppTonesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s = settings()
    val overrides by setting(Settings.TONE_APPS) { it.toneOverrides }
    val defaultTone by setting(Settings.AI_TONE) { it.aiTone }
    val pm = context.packageManager
    // Apps you've typed in first, then well-known chat/email/work apps that are installed.
    val apps = remember {
        (s.seenApps + AppTones.KNOWN).distinct().mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                Triple(pkg, pm.getApplicationLabel(info).toString(), runCatching { pm.getApplicationIcon(info) }.getOrNull())
            }.getOrNull()
        }
    }
    SettingsPage("Tone per app", onBack) {
        item {
            Group {
                Hint("“Auto” means: chill in chats, clean in work apps, polite in email, and your default (${defaultTone.label}) everywhere else.")
            }
        }
        item {
            Group("Apps") {
                if (apps.isEmpty()) Hint("Apps show up here once you've typed in them.")
                apps.forEach { (pkg, label, icon) ->
                    val auto = AppTones.automatic(pkg) ?: defaultTone
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) Image(icon.toBitmap(64, 64).asImageBitmap(), null, Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.size(6.dp))
                        val options = listOf<AiTone?>(null) + AiTone.entries
                        ChoiceSetting(
                            "", null,
                            options.map { it to (it?.label ?: "Auto · ${auto.label}") }, overrides[pkg],
                        ) { s.setToneFor(pkg, it) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ encrypted sync

@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = SmartKeyboardApp.of(context)
    val sync = app.sync
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val uri = remember(version) { sync.fileUri }
    val hasPass = remember(version) { sync.hasPassphrase }
    val last by setting(Settings.SYNC_LAST) { it.syncLast }
    val error by setting(Settings.SYNC_ERROR) { it.syncError }
    var pass by remember { mutableStateOf("") }
    var passMessage by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun runSync() {
        busy = true
        status = "Syncing…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { sync.syncNow() }
            busy = false
            status = r.fold(
                onSuccess = { if (it.createdFile) "Sync file created ✓ Now pick the same file on your other phone." else "Synced ✓ ${it.merged} items merged" },
                onFailure = { "Couldn't sync: ${it.message}" },
            )
        }
    }

    fun useFile(u: Uri?) {
        if (u == null) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        sync.setFile(u)
        version++
        if (sync.isReady) runSync()
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { useFile(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { useFile(it) }

    SettingsPage("Sync between phones", onBack) {
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Your words on all your phones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(6.dp))
                    Text("Everything the keyboard learned (words, slang, phrases, fixes, your shortcuts) is kept in one file that only your passphrase can open — encrypted on the phone with AES-256 before it's saved. Put the file in Google Drive (or any folder) and pick the same file on your other phone.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Group("1 · Passphrase") {
                if (hasPass) SettingRow("Passphrase set ✓", "Use the same one on your other phone. It never leaves this phone.")
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pass, { pass = it; passMessage = null }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(if (hasPass) "Change passphrase" else "Passphrase (8+ characters)") },
                        visualTransformation = PasswordVisualTransformation())
                    Button(onClick = {
                        passMessage = runCatching { sync.setPassphrase(pass) }.fold({ pass = ""; version++; "Saved ✓" }, { it.message })
                    }, enabled = pass.isNotEmpty()) { Text("Save passphrase") }
                    passMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Hint("Forget it and the file can't be opened by anyone — not even you. Your phone keeps working normally.")
                }
            }
        }
        item {
            Group("2 · Sync file") {
                SettingRow(if (uri == null) "No file chosen yet" else displayName(context, uri), if (uri == null) null else "Tap “Sync now” any time")
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { create.launch("smart-keyboard.sksync") }) { Text("New file…") }
                    OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Use existing…") }
                }
                Hint("First phone: “New file…” and save it in Google Drive. Other phone: “Use existing…” and pick that file in Drive.")
            }
        }
        item {
            Group("3 · Sync") {
                SwitchSetting(Settings.SYNC_AUTO, "Sync automatically", "In the background now and then while you type") { it.syncAuto }
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { runSync() }, enabled = !busy && uri != null && hasPass) { Icon(Icons.Rounded.Sync, null); Text(" Sync now") }
                }
                val lastText = if (last > 0) "Last synced: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)) else "Not synced yet"
                Hint(status ?: error?.let { "Last sync failed: $it" } ?: lastText)
            }
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "Sync file"
